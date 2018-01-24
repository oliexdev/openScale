/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
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

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;

public class ScaleDatabase extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 6;
    private static final String DATABASE_NAME = "openScaleDatabase.db";

    private static final String TABLE_NAME = "scaledata";
    private static final String COLUMN_NAME_ID = "id";
    private static final String COLUMN_NAME_USER_ID = "user_id";
    private static final String COLUMN_NAME_DATE_TIME = "date_time";
    private static final String COLUMN_NAME_WEIGHT = "weight";
    private static final String COLUMN_NAME_FAT = "fat";
    private static final String COLUMN_NAME_WATER = "water";
    private static final String COLUMN_NAME_MUSCLE = "muscle";
    private static final String COLUMN_NAME_LBW = "lbw";
    private static final String COLUMN_NAME_BONE = "bone";
    private static final String COLUMN_NAME_WAIST = "waist";
    private static final String COLUMN_NAME_HIP = "hip";
    private static final String COLUMN_NAME_COMMENT = "comment";
    private static final String COLUMN_NAME_ENABLE = "enable";

    private static final String SQL_CREATE_ENTRIES =
            "CREATE TABLE " + TABLE_NAME + " (" +
                    COLUMN_NAME_ID + " INTEGER PRIMARY KEY," +
                    COLUMN_NAME_USER_ID + " INTEGER," +
                    COLUMN_NAME_DATE_TIME + " TEXT," +
                    COLUMN_NAME_WEIGHT + " REAL," +
                    COLUMN_NAME_FAT + " REAL," +
                    COLUMN_NAME_WATER + " REAL," +
                    COLUMN_NAME_MUSCLE + " REAL," +
                    COLUMN_NAME_LBW + " REAL," +
                    COLUMN_NAME_BONE + " REAL," +
                    COLUMN_NAME_WAIST + " REAL," +
                    COLUMN_NAME_HIP + " REAL," +
                    COLUMN_NAME_COMMENT + " TEXT," +
                    COLUMN_NAME_ENABLE + " INTEGER" +
                    ")";

    private static String[] projection = {
            COLUMN_NAME_ID,
            COLUMN_NAME_USER_ID,
            COLUMN_NAME_DATE_TIME,
            COLUMN_NAME_WEIGHT,
            COLUMN_NAME_FAT,
            COLUMN_NAME_WATER,
            COLUMN_NAME_MUSCLE,
            COLUMN_NAME_LBW,
            COLUMN_NAME_BONE,
            COLUMN_NAME_WAIST,
            COLUMN_NAME_HIP,
            COLUMN_NAME_COMMENT,
            COLUMN_NAME_ENABLE
    };

    private SimpleDateFormat formatDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    public ScaleDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_ENTRIES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_COMMENT + " TEXT DEFAULT ''");
        }

        if (oldVersion == 2 && newVersion == 3) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_WAIST + " REAL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_HIP + " REAL DEFAULT 0");
        }

        if (oldVersion == 3 && newVersion == 4) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_ENABLE + " INTEGER DEFAULT 1");
        }

        if (oldVersion == 4 && newVersion == 5) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_BONE + " REAL DEFAULT 0");
        }

        if (oldVersion == 5 && newVersion == 6) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_LBW + " REAL DEFAULT 0");
        }
    }

    public ArrayList<ScaleMeasurement> getScaleDataList(int userId) {
        ArrayList<ScaleMeasurement> scaleMeasurementList = new ArrayList<ScaleMeasurement>();

        try {
            String sortOrder = COLUMN_NAME_DATE_TIME + " DESC";

            Cursor cursorScaleDB = getReadableDatabase().query(
                    TABLE_NAME,    // The table to query
                    projection,    // The columns to return
                    COLUMN_NAME_USER_ID + "=? AND " + COLUMN_NAME_ENABLE + "=1", // The columns for the WHERE clause
                    new String[]{Integer.toString(userId)},            // The values for the WHERE clause
                    null,            // don't group the rows
                    null,            // don't filter by row groups
                    sortOrder        // The sort order
            );

            cursorScaleDB.moveToFirst();

            while (!cursorScaleDB.isAfterLast()) {
                scaleMeasurementList.add(readAtCursor(cursorScaleDB));

                cursorScaleDB.moveToNext();
            }

            cursorScaleDB.close();
        } catch (SQLException ex) {
            Log.e("ScaleDatabase", "SQL exception occured while getting scale data list: " + ex.getMessage());
        }

        return scaleMeasurementList;
    }


    private ScaleMeasurement readAtCursor (Cursor cur) {
        ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

        try {
            scaleMeasurement.setId(cur.getInt(cur.getColumnIndexOrThrow(COLUMN_NAME_ID)));
            scaleMeasurement.setUserId(cur.getInt(cur.getColumnIndexOrThrow(COLUMN_NAME_USER_ID)));
            String date_time = cur.getString(cur.getColumnIndexOrThrow(COLUMN_NAME_DATE_TIME));
            scaleMeasurement.setWeight(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_WEIGHT)));
            scaleMeasurement.setFat(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_FAT)));
            scaleMeasurement.setWater(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_WATER)));
            scaleMeasurement.setMuscle(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_MUSCLE)));
            scaleMeasurement.setLbw(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_LBW)));
            scaleMeasurement.setBone(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_BONE)));
            scaleMeasurement.setWaist(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_WAIST)));
            scaleMeasurement.setHip(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_HIP)));
            scaleMeasurement.setComment(cur.getString(cur.getColumnIndexOrThrow(COLUMN_NAME_COMMENT)));

            scaleMeasurement.setDateTime(formatDateTime.parse(date_time));
        } catch (ParseException ex) {
            Log.e("ScaleDatabase", "Can't parse the date time string: " + ex.getMessage());
        }
        catch (IllegalArgumentException ex) {
            Log.e("ScaleDatabase", "Illegal argument while reading from scale database: " + ex.getMessage());
        }

        return scaleMeasurement;
    }
}
