package com.haloged.watchmail.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.haloged.watchmail.data.local.dao.AccountDao
import com.haloged.watchmail.data.local.dao.DraftDao
import com.haloged.watchmail.data.local.dao.EmailBodyDao
import com.haloged.watchmail.data.local.dao.EmailDao
import com.haloged.watchmail.data.local.entity.AccountEntity
import com.haloged.watchmail.data.local.entity.DraftEntity
import com.haloged.watchmail.data.local.entity.EmailBodyEntity
import com.haloged.watchmail.data.local.entity.EmailEntity

/**
 * WatchMail Room数据库
 * 管理所有本地数据存储
 */
@Database(
    entities = [
        AccountEntity::class,
        EmailEntity::class,
        EmailBodyEntity::class,
        DraftEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class WatchMailDatabase : RoomDatabase() {
    
    abstract fun accountDao(): AccountDao
    abstract fun emailDao(): EmailDao
    abstract fun emailBodyDao(): EmailBodyDao
    abstract fun draftDao(): DraftDao
    
    companion object {
        @Volatile
        private var INSTANCE: WatchMailDatabase? = null
        
        private const val DATABASE_NAME = "watchmail.db"
        
        /**
         * 获取数据库单例
         */
        fun getInstance(context: Context): WatchMailDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WatchMailDatabase::class.java,
                    DATABASE_NAME
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
