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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.health.openscale.core.datatypes.ScaleData;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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

    private static final String SQL_DELETE_ENTRIES =
            "DROP TABLE IF EXISTS " + TABLE_NAME;


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

    private final SQLiteDatabase dbWrite = getWritableDatabase();
    private final SQLiteDatabase dbRead = getReadableDatabase();

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

    public void clearScaleData(int userId) {
        dbWrite.delete(TABLE_NAME, COLUMN_NAME_USER_ID + "=" + Integer.toString(userId), null);
    }

    public boolean insertEntry(ScaleData scaleData) {
        SQLiteDatabase db = getWritableDatabase();

        Cursor cursorScaleDB = db.query(TABLE_NAME, new String[] {COLUMN_NAME_DATE_TIME}, COLUMN_NAME_DATE_TIME + "=? AND " + COLUMN_NAME_USER_ID + "=?",
                new String[] {formatDateTime.format(scaleData.getDateTime()), Integer.toString(scaleData.getUserId())}, null, null, null);

        // we don't want double entries
        if (cursorScaleDB.getCount() > 0) {
            cursorScaleDB.close();
            return false;
        } else {
            ContentValues values = new ContentValues();
            values.put(COLUMN_NAME_USER_ID, scaleData.getUserId());
            values.put(COLUMN_NAME_DATE_TIME, formatDateTime.format(scaleData.getDateTime()));
            values.put(COLUMN_NAME_WEIGHT, scaleData.getWeight());
            values.put(COLUMN_NAME_FAT, scaleData.getFat());
            values.put(COLUMN_NAME_WATER, scaleData.getWater());
            values.put(COLUMN_NAME_MUSCLE, scaleData.getMuscle());
            values.put(COLUMN_NAME_LBW, scaleData.getLBW());
            values.put(COLUMN_NAME_BONE, scaleData.getBone());
            values.put(COLUMN_NAME_WAIST, scaleData.getWaist());
            values.put(COLUMN_NAME_HIP, scaleData.getHip());
            values.put(COLUMN_NAME_COMMENT, scaleData.getComment());
            values.put(COLUMN_NAME_ENABLE, 1);

            try
            {
                db.insertOrThrow(TABLE_NAME, null, values);
            }
            catch (SQLException e)
            {
                Log.e("ScaleDatabase", "An error occured while inserting a new entry into the scale database: " + e.toString());
                cursorScaleDB.close();
                return false;
            }
        }

        cursorScaleDB.close();

        return true;
    }

    public void updateEntry(long id, ScaleData scaleData) {
        ContentValues values = new ContentValues();

        values.put(COLUMN_NAME_DATE_TIME, formatDateTime.format(scaleData.getDateTime()));
        values.put(COLUMN_NAME_WEIGHT, scaleData.getWeight());
        values.put(COLUMN_NAME_FAT, scaleData.getFat());
        values.put(COLUMN_NAME_WATER, scaleData.getWater());
        values.put(COLUMN_NAME_MUSCLE, scaleData.getMuscle());
        values.put(COLUMN_NAME_LBW, scaleData.getLBW());
        values.put(COLUMN_NAME_BONE, scaleData.getBone());
        values.put(COLUMN_NAME_WAIST, scaleData.getWaist());
        values.put(COLUMN_NAME_HIP, scaleData.getHip());
        values.put(COLUMN_NAME_COMMENT, scaleData.getComment());
        values.put(COLUMN_NAME_ENABLE, 1);

        dbWrite.update(TABLE_NAME, values, COLUMN_NAME_ID + "=" + id, null);
    }

    public ScaleData[] getTupleDataEntry(int userId, long id)
    {
        Cursor cursorScaleDB;

        ScaleData[] tupleScaleData = new ScaleData[3];

        // selected scale data entry
        cursorScaleDB = dbRead.query(
                TABLE_NAME,     // The table to query
                projection,     // The columns to return
                COLUMN_NAME_USER_ID + "=? AND " + COLUMN_NAME_ID + "=?",             // The columns for the WHERE clause
                new String[] {Integer.toString(userId), Long.toString(id)},             // The values for the WHERE clause
                null,             // don't group the rows
                null,            // don't filter by row groups
                null,          // The sort order
                "1"         // Limit
        );

        if (cursorScaleDB.getCount() == 1) {
            cursorScaleDB.moveToFirst();
            tupleScaleData[1] = readAtCursor(cursorScaleDB);
        } else {
            tupleScaleData[1] = new ScaleData();
        }

        // previous scale entry
        cursorScaleDB = dbRead.query(
                TABLE_NAME,     // The table to query
                projection,     // The columns to return
                COLUMN_NAME_USER_ID + "=? AND " + COLUMN_NAME_DATE_TIME + "<? AND " + COLUMN_NAME_ENABLE + "=1",             // The columns for the WHERE clause
                new String[] {Integer.toString(userId), formatDateTime.format(tupleScaleData[1].getDateTime())},             // The values for the WHERE clause
                null,             // don't group the rows
                null,            // don't filter by row groups
                COLUMN_NAME_DATE_TIME + " DESC",      // The sort order
                "1"             // Limit
        );

        if (cursorScaleDB.getCount() == 1) {
            cursorScaleDB.moveToFirst();
            tupleScaleData[0] = readAtCursor(cursorScaleDB);
        } else {
            tupleScaleData[0] = null;
        }

        cursorScaleDB.close();

        // next scale data entry
        cursorScaleDB = dbRead.query(
                TABLE_NAME,     // The table to query
                projection,     // The columns to return
                COLUMN_NAME_USER_ID + "=? AND " + COLUMN_NAME_DATE_TIME + ">? AND " + COLUMN_NAME_ENABLE + "=1",             // The columns for the WHERE clause
                new String[] {Integer.toString(userId), formatDateTime.format(tupleScaleData[1].getDateTime())},             // The values for the WHERE clause
                null,             // don't group the rows
                null,            // don't filter by row groups
                COLUMN_NAME_DATE_TIME, // The sort order
                "1"             // Limit
        );

        if (cursorScaleDB.getCount() == 1) {
            cursorScaleDB.moveToFirst();
            tupleScaleData[2] = readAtCursor(cursorScaleDB);
        } else {
            tupleScaleData[2] = null;
        }

        return tupleScaleData;
    }

    public void deleteEntry(long id) {
        //dbWrite.delete(TABLE_NAME, COLUMN_NAME_ID + "= ?", new String[] {String.valueOf(id)});

        ContentValues values = new ContentValues();
        values.put(COLUMN_NAME_ENABLE, 0);

        dbWrite.update(TABLE_NAME, values, COLUMN_NAME_ID + "=" + id, null);
    }

    public int[] getCountsOfAllMonth(int userId, int year) {
        int [] numOfMonth = new int[12];

        Calendar start_cal = Calendar.getInstance();
        Calendar end_cal = Calendar.getInstance();

        for (int i=0; i<12; i++) {
            start_cal.set(year, i, 1, 0, 0, 0);
            end_cal.set(year, i, 1, 0, 0, 0);
            end_cal.add(Calendar.MONTH, 1);

            Cursor cursorScaleDB = dbRead.query(
                    TABLE_NAME,    // The table to query
                    new String[]{"count(*)"},    // The columns to return
                    COLUMN_NAME_DATE_TIME + " >= ? AND " + COLUMN_NAME_DATE_TIME + " < ? AND " + COLUMN_NAME_USER_ID + "=? AND "+ COLUMN_NAME_ENABLE + "=1", // The columns for the WHERE clause
                    new String[]{formatDateTime.format(start_cal.getTime()), formatDateTime.format(end_cal.getTime()), Integer.toString(userId)},            // The values for the WHERE clause
                    null,            // don't group the rows
                    null,            // don't filter by row groups
                    null        // The sort order
            );

            cursorScaleDB.moveToFirst();

            numOfMonth[i] = cursorScaleDB.getInt(0);

            cursorScaleDB.close();
        }

        return numOfMonth;
    }

    public ArrayList<ScaleData> getScaleDataOfMonth(int userId, int year, int month) {
        ArrayList<ScaleData> scaleDataList = new ArrayList<ScaleData>();

        String sortOrder = COLUMN_NAME_DATE_TIME + " DESC";

        Calendar start_cal = Calendar.getInstance();
        Calendar end_cal = Calendar.getInstance();

        start_cal.set(year, month, 1, 0, 0, 0);
        end_cal.set(year, month, 1, 0, 0, 0);
        end_cal.add(Calendar.MONTH, 1);

        Cursor cursorScaleDB = dbRead.query(
                TABLE_NAME,     // The table to query
                projection,     // The columns to return
                COLUMN_NAME_DATE_TIME + " >= ? AND " + COLUMN_NAME_DATE_TIME + " < ? AND " + COLUMN_NAME_USER_ID + "=? AND " + COLUMN_NAME_ENABLE + "=1", // The columns for the WHERE clause
                new String[]{formatDateTime.format(start_cal.getTime()), formatDateTime.format(end_cal.getTime()), Integer.toString(userId)},            // The values for the WHERE clause
                null,             // don't group the rows
                null,            // don't filter by row groups
                sortOrder          // The sort order
        );

        cursorScaleDB.moveToFirst();

        while (!cursorScaleDB.isAfterLast()) {
            scaleDataList.add(readAtCursor(cursorScaleDB));

            cursorScaleDB.moveToNext();
        }

        cursorScaleDB.close();

        return scaleDataList;
    }

    public ArrayList<ScaleData> getScaleDataOfYear(int userId, int year) {
        ArrayList<ScaleData> scaleDataList = new ArrayList<ScaleData>();

        String sortOrder = COLUMN_NAME_DATE_TIME + " DESC";

        Calendar start_cal = Calendar.getInstance();
        Calendar end_cal = Calendar.getInstance();

        start_cal.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        end_cal.set(year+1, Calendar.JANUARY, 1, 0, 0, 0);

        Cursor cursorScaleDB = dbRead.query(
                TABLE_NAME,     // The table to query
                projection,     // The columns to return
                COLUMN_NAME_DATE_TIME + " >= ? AND " + COLUMN_NAME_DATE_TIME + " < ? AND " + COLUMN_NAME_USER_ID + "=? AND " + COLUMN_NAME_ENABLE + "=1", // The columns for the WHERE clause
                new String[]{formatDateTime.format(start_cal.getTime()), formatDateTime.format(end_cal.getTime()), Integer.toString(userId)},            // The values for the WHERE clause
                null,             // don't group the rows
                null,            // don't filter by row groups
                sortOrder          // The sort order
        );

        cursorScaleDB.moveToFirst();

        while (!cursorScaleDB.isAfterLast()) {
            scaleDataList.add(readAtCursor(cursorScaleDB));

            cursorScaleDB.moveToNext();
        }

        cursorScaleDB.close();

        return scaleDataList;
    }

    public ArrayList<ScaleData> getScaleDataList(int userId) {
        ArrayList<ScaleData> scaleDataList = new ArrayList<ScaleData>();

        try {
            String sortOrder = COLUMN_NAME_DATE_TIME + " DESC";

            Cursor cursorScaleDB = dbRead.query(
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
                scaleDataList.add(readAtCursor(cursorScaleDB));

                cursorScaleDB.moveToNext();
            }

            cursorScaleDB.close();
        } catch (SQLException ex) {
            Log.e("ScaleDatabase", "SQL exception occured while getting scale data list: " + ex.getMessage());
        }

        return scaleDataList;
    }


    private ScaleData readAtCursor (Cursor cur) {
        ScaleData scaleData = new ScaleData();

        try {
            scaleData.setId(cur.getLong(cur.getColumnIndexOrThrow(COLUMN_NAME_ID)));
            scaleData.setUserId(cur.getInt(cur.getColumnIndexOrThrow(COLUMN_NAME_USER_ID)));
            String date_time = cur.getString(cur.getColumnIndexOrThrow(COLUMN_NAME_DATE_TIME));
            scaleData.setWeight(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_WEIGHT)));
            scaleData.setFat(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_FAT)));
            scaleData.setWater(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_WATER)));
            scaleData.setMuscle(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_MUSCLE)));
            scaleData.setLBW(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_LBW)));
            scaleData.setBone(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_BONE)));
            scaleData.setWaist(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_WAIST)));
            scaleData.setHip(cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_HIP)));
            scaleData.setComment(cur.getString(cur.getColumnIndexOrThrow(COLUMN_NAME_COMMENT)));

            scaleData.setDateTime(formatDateTime.parse(date_time));
        } catch (ParseException ex) {
            Log.e("ScaleDatabase", "Can't parse the date time string: " + ex.getMessage());
        }
        catch (IllegalArgumentException ex) {
            Log.e("ScaleDatabase", "Illegal argument while reading from scale database: " + ex.getMessage());
        }

        return scaleData;
    }
}
