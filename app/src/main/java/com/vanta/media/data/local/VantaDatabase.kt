package com.vanta.media.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.MediaSourceType
import com.vanta.media.data.local.entity.MediaType
import com.vanta.media.data.local.entity.TmdbLookupCacheEntity
import com.vanta.media.data.local.entity.UnifiedMediaEntity

class VantaTypeConverters {
    @TypeConverter fun fromMediaType(value: MediaType): String = value.name
    @TypeConverter fun toMediaType(value: String): MediaType = MediaType.valueOf(value)
    @TypeConverter fun fromSourceType(value: MediaSourceType): String = value.name
    @TypeConverter fun toSourceType(value: String): MediaSourceType = MediaSourceType.valueOf(value)
}

@Database(
    entities = [
        UnifiedMediaEntity::class,
        MediaSourceLinkEntity::class,
        TmdbLookupCacheEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(VantaTypeConverters::class)
abstract class VantaDatabase : RoomDatabase() {
    abstract fun mediaDao(): VantaMediaDao
}
