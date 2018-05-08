/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
*
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>
*/

package com.health.openscale.core.database;

import android.arch.persistence.db.SupportSQLiteDatabase;
import android.arch.persistence.room.Database;
import android.arch.persistence.room.RoomDatabase;
import android.arch.persistence.room.TypeConverters;
import android.arch.persistence.room.migration.Migration;

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

@Database(entities = {ScaleMeasurement.class, ScaleUser.class}, version = 2)
@TypeConverters({Converters.class})
public abstract class AppDatabase extends RoomDatabase {
    public abstract ScaleMeasurementDAO measurementDAO();
    public abstract ScaleUserDAO userDAO();

    // For the next database version:
    // - rename lbw column to lbm
    // - remove initial weight

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.beginTransaction();
            try {
                // Drop old index on datetime only
                database.execSQL("DROP INDEX index_scaleMeasurements_datetime");

                // Rename old table
                database.execSQL("ALTER TABLE scaleMeasurements RENAME TO scaleMeasurementsOld");

                // Create new table with foreign key
                database.execSQL("CREATE TABLE scaleMeasurements"
                        + " (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                        + " userId INTEGER NOT NULL, enabled INTEGER NOT NULL,"
                        + " datetime INTEGER, weight REAL NOT NULL, fat REAL NOT NULL,"
                        + " water REAL NOT NULL, muscle REAL NOT NULL, lbw REAL NOT NULL,"
                        + " waist REAL NOT NULL, hip REAL NOT NULL, bone REAL NOT NULL,"
                        + " comment TEXT, FOREIGN KEY(userId) REFERENCES scaleUsers(id)"
                        + " ON UPDATE NO ACTION ON DELETE CASCADE)");

                // Create new index on datetime + userId
                database.execSQL("CREATE UNIQUE INDEX index_scaleMeasurements_userId_datetime"
                        + " ON scaleMeasurements (userId, datetime)");

                // Copy data from the old table, ignoring those with invalid userId (if any)
                database.execSQL("INSERT INTO scaleMeasurements"
                        + " SELECT * FROM scaleMeasurementsOld"
                        + " WHERE userId IN (SELECT id from scaleUsers)");

                // Delete old table
                database.execSQL("DROP TABLE scaleMeasurementsOld");

                database.setTransactionSuccessful();
            }
            finally {
                database.endTransaction();
            }
        }
    };
}

