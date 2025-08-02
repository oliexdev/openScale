/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.User
import com.health.openscale.core.utils.LogManager

/**
 * Main Room database for the application.
 * It holds references to all DAOs and manages the database instance.
 */
@Database(
    entities = [
        User::class,
        Measurement::class,
        MeasurementValue::class,
        MeasurementType::class
    ],
    version = 1, // TODO Increment this on schema changes
    exportSchema = false // TODO Consider setting to true for production apps to keep schema history
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun measurementValueDao(): MeasurementValueDao
    abstract fun measurementTypeDao(): MeasurementTypeDao

    /**
     * Closes the database connection and resets the singleton instance.
     * This is typically not needed in normal app operation as Room handles lifecycle.
     * Could be useful in specific scenarios like testing or explicit resource cleanup.
     */
    fun closeConnection() {
        if (isOpen) {
            try {
                super.close() // Call RoomDatabase's close method
                INSTANCE = null
                LogManager.i(TAG, "Database connection closed and INSTANCE reset.")
            } catch (e: Exception) {
                LogManager.e(TAG, "Error closing database connection.", e)
            }
        } else {
            LogManager.w(TAG, "Attempted to close database connection, but it was already closed or not initialized.")
        }
    }

    companion object {
        private const val TAG = "AppDatabase"
        const val DATABASE_NAME = "openScale.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Gets the singleton instance of the [AppDatabase].
         * Uses double-checked locking to ensure thread safety.
         *
         * @param context The application context.
         * @return The singleton [AppDatabase] instance.
         */
        fun getInstance(context: Context): AppDatabase {
            // Double-checked locking pattern
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also {
                    LogManager.i(TAG, "Database instance created or retrieved.")
                    INSTANCE = it
                }
            }
        }

        /**
         * Builds the Room database instance.
         *
         * @param appContext The application context.
         * @return A new [AppDatabase] instance.
         */
        private fun buildDatabase(appContext: Context): AppDatabase {
            LogManager.d(TAG, "Building new database instance: $DATABASE_NAME")
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                // TODO Destroys and re-creates the database if a migration is needed and not provided. For production, define proper migrations instead.
                .fallbackToDestructiveMigration()
                // TODO Add any other configurations like .addCallback(), .setQueryExecutor(), etc. here if needed.
                .build()
        }
    }
}
