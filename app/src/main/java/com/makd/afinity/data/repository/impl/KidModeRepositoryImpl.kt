package com.makd.afinity.data.repository.impl

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.makd.afinity.data.repository.AppCapabilityPolicy
import com.makd.afinity.data.repository.KidModeRepository
import com.makd.afinity.di.AppPreferences
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

@Singleton
class KidModeRepositoryImpl
@Inject
constructor(@AppPreferences private val dataStore: DataStore<Preferences>) : KidModeRepository {

    private object Keys {
        val ENABLED = booleanPreferencesKey("kid_mode_enabled")
        val PIN_SALT = stringPreferencesKey("kid_mode_pin_salt")
        val PIN_HASH = stringPreferencesKey("kid_mode_pin_hash")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val _isParentUnlocked = MutableStateFlow(false)

    override val isKidModeEnabled: StateFlow<Boolean> =
        dataStore.data
            .map { preferences -> preferences[Keys.ENABLED] ?: false }
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val isParentUnlocked: StateFlow<Boolean> = _isParentUnlocked

    override val policy: StateFlow<AppCapabilityPolicy> =
        combine(isKidModeEnabled, isParentUnlocked) { enabled, parentUnlocked ->
                AppCapabilityPolicy(
                    isKidModeEnabled = enabled,
                    isParentUnlocked = parentUnlocked,
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, AppCapabilityPolicy())

    override suspend fun enableKidMode(pin: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                validatePin(pin)
                val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
                val hash = hashPin(pin, salt)
                dataStore.edit { preferences ->
                    preferences[Keys.PIN_SALT] = Base64.getEncoder().encodeToString(salt)
                    preferences[Keys.PIN_HASH] = Base64.getEncoder().encodeToString(hash)
                    preferences[Keys.ENABLED] = true
                }
                _isParentUnlocked.value = false
            }
        }

    override suspend fun disableKidMode(pin: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!verifyPinInternal(pin)) {
                    throw IllegalArgumentException("Incorrect PIN")
                }
                dataStore.edit { preferences ->
                    preferences[Keys.ENABLED] = false
                    preferences.remove(Keys.PIN_SALT)
                    preferences.remove(Keys.PIN_HASH)
                }
                _isParentUnlocked.value = true
            }
        }

    override suspend fun verifyParentPin(pin: String): Boolean =
        withContext(Dispatchers.IO) {
            val matches = verifyPinInternal(pin)
            if (matches) {
                _isParentUnlocked.value = true
            }
            matches
        }

    override fun lockParent() {
        _isParentUnlocked.update { false }
    }

    private suspend fun verifyPinInternal(pin: String): Boolean {
        val preferences = dataStore.data.first()
        val salt = preferences[Keys.PIN_SALT]?.let { Base64.getDecoder().decode(it) } ?: return false
        val expectedHash =
            preferences[Keys.PIN_HASH]?.let { Base64.getDecoder().decode(it) } ?: return false
        val actualHash = hashPin(pin, salt)
        return actualHash.contentEquals(expectedHash)
    }

    private fun validatePin(pin: String) {
        require(pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all(Char::isDigit)) {
            "PIN must contain $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits"
        }
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec: KeySpec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private companion object {
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        const val SALT_BYTES = 16
        const val PBKDF2_ITERATIONS = 120_000
        const val HASH_BITS = 256
    }
}
