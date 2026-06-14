package one.only.player.core.database

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import one.only.player.core.database.dao.DirectoryDao
import one.only.player.core.database.dao.FavoriteItemDao
import one.only.player.core.database.dao.MediumDao
import one.only.player.core.database.dao.PlaybackMarkDao
import one.only.player.core.database.dao.RemoteServerDao

@Module
@InstallIn(SingletonComponent::class)
object DaoModule {

    @Provides
    fun provideMediumDao(db: MediaDatabase): MediumDao = db.mediumDao()

    @Provides
    fun provideMediumStateDao(db: MediaDatabase) = db.mediumStateDao()

    @Provides
    fun provideDirectoryDao(db: MediaDatabase): DirectoryDao = db.directoryDao()

    @Provides
    fun provideRemoteServerDao(db: MediaDatabase): RemoteServerDao = db.remoteServerDao()

    @Provides
    fun provideFavoriteItemDao(db: MediaDatabase): FavoriteItemDao = db.favoriteItemDao()

    @Provides
    fun providePlaybackMarkDao(db: MediaDatabase): PlaybackMarkDao = db.playbackMarkDao()
}
