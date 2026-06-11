package com.makd.afinity.di

import android.content.Context
import com.makd.afinity.data.local.AndroidLocalLibraryFileSystem
import com.makd.afinity.data.local.DataStoreLocalLibraryRootStore
import com.makd.afinity.data.local.LocalLibraryFileSystem
import com.makd.afinity.data.local.LocalLibraryIndexRepository
import com.makd.afinity.data.local.LocalLibraryPathPolicy
import com.makd.afinity.data.local.LocalLibraryRootStore
import com.makd.afinity.data.local.LocalLibraryScanner
import com.makd.afinity.data.local.LocalLibrarySidecarReader
import com.makd.afinity.data.local.LocalLibraryVisibilityPolicy
import com.makd.afinity.data.local.LocalMediaUserStateRepository
import com.makd.afinity.data.local.LocalMediaVisibilityRepository
import com.makd.afinity.data.local.RoomLocalLibraryIndexRepository
import com.makd.afinity.data.local.RoomLocalMediaUserStateRepository
import com.makd.afinity.data.local.RoomLocalMediaVisibilityRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalLibraryModule {
    @Binds
    @Singleton
    abstract fun bindRootStore(impl: DataStoreLocalLibraryRootStore): LocalLibraryRootStore

    @Binds
    @Singleton
    abstract fun bindIndexRepository(
        impl: RoomLocalLibraryIndexRepository
    ): LocalLibraryIndexRepository

    @Binds
    @Singleton
    abstract fun bindUserStateRepository(
        impl: RoomLocalMediaUserStateRepository
    ): LocalMediaUserStateRepository

    @Binds
    @Singleton
    abstract fun bindVisibilityRepository(
        impl: RoomLocalMediaVisibilityRepository
    ): LocalMediaVisibilityRepository

    companion object {
        @Provides
        @Singleton
        fun provideFileSystem(@ApplicationContext context: Context): LocalLibraryFileSystem =
            AndroidLocalLibraryFileSystem(context)

        @Provides @Singleton fun providePathPolicy(): LocalLibraryPathPolicy = LocalLibraryPathPolicy()

        @Provides
        @Singleton
        fun provideSidecarReader(): LocalLibrarySidecarReader = LocalLibrarySidecarReader()

        @Provides
        @Singleton
        fun provideVisibilityPolicy(): LocalLibraryVisibilityPolicy = LocalLibraryVisibilityPolicy()

        @Provides
        @Singleton
        fun provideScanner(
            fileSystem: LocalLibraryFileSystem,
            sidecarReader: LocalLibrarySidecarReader,
            indexRepository: LocalLibraryIndexRepository,
            pathPolicy: LocalLibraryPathPolicy,
        ): LocalLibraryScanner =
            LocalLibraryScanner(
                fileSystem = fileSystem,
                sidecarReader = sidecarReader,
                indexRepository = indexRepository,
                pathPolicy = pathPolicy,
            )
    }
}
