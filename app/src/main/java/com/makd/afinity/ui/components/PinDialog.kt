package com.makd.afinity.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun PinDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    errorText: String? = null,
) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(message)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        pin = value.filter(Char::isDigit).take(MAX_PIN_LENGTH)
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = errorText != null,
                )
                if (errorText != null) {
                    Text(errorText)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = pin.length >= MIN_PIN_LENGTH) {
                Text(confirmText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8
