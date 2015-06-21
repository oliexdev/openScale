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

package com.health.openscale.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

public class ScaleDatabase extends SQLiteOpenHelper {	
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "openScaleDatabase.db";	
	
    private static final String TABLE_NAME = "scaledata";
    private static final String COLUMN_NAME_ID = "id";
    private static final String COLUMN_NAME_USER_ID = "user_id";
    private static final String COLUMN_NAME_DATE_TIME = "date_time";
    private static final String COLUMN_NAME_WEIGHT = "weight";
    private static final String COLUMN_NAME_FAT = "fat";
    private static final String COLUMN_NAME_WATER = "water";
    private static final String COLUMN_NAME_MUSCLE = "muscle";
    
    private static final String SQL_CREATE_ENTRIES = 
    		"CREATE TABLE " + TABLE_NAME + " (" + 
    				COLUMN_NAME_ID + " INTEGER PRIMARY KEY," +
                    COLUMN_NAME_USER_ID + " INTEGER," +
    				COLUMN_NAME_DATE_TIME + " TEXT," +
    				COLUMN_NAME_WEIGHT + " REAL," +
    				COLUMN_NAME_FAT + " REAL," +
    				COLUMN_NAME_WATER + " REAL," + 
    				COLUMN_NAME_MUSCLE + " REAL" +
    				")";
    
    private static final String SQL_DELETE_ENTRIES =
    		"DROP TABLE IF EXISTS " + TABLE_NAME;
    
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
		db.execSQL(SQL_DELETE_ENTRIES);
        onCreate(db);
	}
	
	public void clearScaleData(int userId) {
		SQLiteDatabase db = getWritableDatabase();
		
		db.delete(TABLE_NAME, COLUMN_NAME_USER_ID + "=" + Integer.toString(userId), null);
	}

	public boolean insertEntry(ScaleData scaleData) {
		SQLiteDatabase db = getWritableDatabase();

        Cursor cursorScaleDB = db.query(TABLE_NAME, new String[] {COLUMN_NAME_DATE_TIME}, COLUMN_NAME_DATE_TIME + "=? AND " + COLUMN_NAME_USER_ID + "=?",
                new String[] {formatDateTime.format(scaleData.date_time), Integer.toString(scaleData.user_id)}, null, null, null);

        if (cursorScaleDB.getCount() > 0) {
            // we don't want double entries
            return false;
        } else {
            ContentValues values = new ContentValues();
            values.put(COLUMN_NAME_USER_ID, scaleData.user_id);
            values.put(COLUMN_NAME_DATE_TIME, formatDateTime.format(scaleData.date_time));
            values.put(COLUMN_NAME_WEIGHT, scaleData.weight);
            values.put(COLUMN_NAME_FAT, scaleData.fat);
            values.put(COLUMN_NAME_WATER, scaleData.water);
            values.put(COLUMN_NAME_MUSCLE, scaleData.muscle);

            try
            {
                db.insertOrThrow(TABLE_NAME, null, values);
            }
            catch (SQLException e)
            {
                Log.e("ScaleDatabase", "An error occured while inserting a new entry into the scale database: " + e.toString());
                return false;
            }
        }

        return true;
	}

    public void updateEntry(long id, ScaleData scaleData) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(COLUMN_NAME_WEIGHT, scaleData.weight);
        values.put(COLUMN_NAME_FAT, scaleData.fat);
        values.put(COLUMN_NAME_WATER, scaleData.water);
        values.put(COLUMN_NAME_MUSCLE, scaleData.muscle);

        db.update(TABLE_NAME, values, COLUMN_NAME_ID + "=" + id, null);
    }

    public ScaleData getDataEntry(long id)
    {
        SQLiteDatabase db = getReadableDatabase();
        ScaleData scaleData = new ScaleData();

        String[] projection = {
                COLUMN_NAME_ID,
                COLUMN_NAME_USER_ID,
                COLUMN_NAME_DATE_TIME,
                COLUMN_NAME_WEIGHT,
                COLUMN_NAME_FAT,
                COLUMN_NAME_WATER,
                COLUMN_NAME_MUSCLE
        };

        Cursor cursorScaleDB = db.query(
                TABLE_NAME, 	// The table to query
                projection, 	// The columns to return
                COLUMN_NAME_ID + "=?", 			// The columns for the WHERE clause
                new String[] {Long.toString(id)}, 			// The values for the WHERE clause
                null, 			// don't group the rows
                null,			// don't filter by row groups
                null  		// The sort order
        );

        try {
            cursorScaleDB.moveToFirst();

            scaleData.id = cursorScaleDB.getLong(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_ID));
            scaleData.user_id = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_USER_ID));
            String date_time = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_DATE_TIME));
            scaleData.weight = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_WEIGHT));
            scaleData.fat = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_FAT));
            scaleData.water = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_WATER));
            scaleData.muscle = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_MUSCLE));

            scaleData.date_time = formatDateTime.parse(date_time);

            cursorScaleDB.moveToNext();

        } catch (ParseException ex) {
            Log.e("ScaleDatabase", "Can't parse the date time string: " + ex.getMessage());
        }
        catch ( IllegalArgumentException ex) {
            Log.e("ScaleDatabase", "Illegal argument while reading from scale database: " + ex.getMessage());
        }

        return scaleData;
    }

    public void deleteEntry(long id) {
        SQLiteDatabase db = getWritableDatabase();

        db.delete(TABLE_NAME, COLUMN_NAME_ID + "= ?", new String[] {String.valueOf(id)});
    }

    public int[] getCountsOfAllMonth(int userId, int year) {
        int [] numOfMonth = new int[12];

        SQLiteDatabase db = getReadableDatabase();

        Calendar start_cal = Calendar.getInstance();
        Calendar end_cal = Calendar.getInstance();

        for (int i=0; i<12; i++) {
            start_cal.set(year, i, 1, 0, 0, 0);
            end_cal.set(year, i, 1, 0, 0, 0);
            end_cal.add(Calendar.MONTH, 1);

            Cursor cursorScaleDB = db.query(
                    TABLE_NAME,    // The table to query
                    new String[]{"count(*)"},    // The columns to return
                    COLUMN_NAME_DATE_TIME + " >= ? AND " + COLUMN_NAME_DATE_TIME + " < ? AND " + COLUMN_NAME_USER_ID + "=?",            // The columns for the WHERE clause
                    new String[]{formatDateTime.format(start_cal.getTime()), formatDateTime.format(end_cal.getTime()), Integer.toString(userId)},            // The values for the WHERE clause
                    null,            // don't group the rows
                    null,            // don't filter by row groups
                    null        // The sort order
            );

            cursorScaleDB.moveToFirst();

            numOfMonth[i] = cursorScaleDB.getInt(0);
        }

        return numOfMonth;
    }

    public float getMaxValueOfScaleData(int userId, int year, int month) {
        SQLiteDatabase db = getReadableDatabase();

        Calendar start_cal = Calendar.getInstance();
        Calendar end_cal = Calendar.getInstance();

        start_cal.set(year, month, 1, 0, 0, 0);
        end_cal.set(year, month, 1, 0, 0, 0);
        end_cal.add(Calendar.MONTH, 1);

        String[] projection = {
                "MAX(" + COLUMN_NAME_WEIGHT + ")",
                "MAX(" + COLUMN_NAME_FAT + ")",
                "MAX(" + COLUMN_NAME_WATER + ")",
                "MAX(" + COLUMN_NAME_MUSCLE + ")"
        };

        Cursor cursorScaleDB = db.query(
                TABLE_NAME, 	// The table to query
                projection, 	// The columns to return
                COLUMN_NAME_DATE_TIME + " >= ? AND " + COLUMN_NAME_DATE_TIME + " < ? AND " + COLUMN_NAME_USER_ID + "=?",            // The columns for the WHERE clause
                new String[]{formatDateTime.format(start_cal.getTime()), formatDateTime.format(end_cal.getTime()), Integer.toString(userId)},            // The values for the WHERE clause
                null, 			// don't group the rows
                null,			// don't filter by row groups
                null  		// The sort order
        );

        cursorScaleDB.moveToFirst();

        float maxValue = -1;

        for (int i=0; i<4; i++)
        {
            if (maxValue < cursorScaleDB.getFloat(i))
            {
                maxValue =  cursorScaleDB.getFloat(i);
            }
        }

        return maxValue;
    }


    public ArrayList<ScaleData> getScaleDataOfMonth(int userId, int year, int month) {
        SQLiteDatabase db = getReadableDatabase();
        ArrayList<ScaleData> scaleDataList = new ArrayList<ScaleData>();

        String[] projection = {
                COLUMN_NAME_ID,
                COLUMN_NAME_USER_ID,
                COLUMN_NAME_DATE_TIME,
                COLUMN_NAME_WEIGHT,
                COLUMN_NAME_FAT,
                COLUMN_NAME_WATER,
                COLUMN_NAME_MUSCLE
        };

        String sortOrder = COLUMN_NAME_DATE_TIME + " DESC";

        Calendar start_cal = Calendar.getInstance();
        Calendar end_cal = Calendar.getInstance();

        start_cal.set(year, month, 1, 0, 0, 0);
        end_cal.set(year, month, 1, 0, 0, 0);
        end_cal.add(Calendar.MONTH, 1);

        Cursor cursorScaleDB = db.query(
                TABLE_NAME, 	// The table to query
                projection, 	// The columns to return
                COLUMN_NAME_DATE_TIME + " >= ? AND " + COLUMN_NAME_DATE_TIME + " < ? AND " + COLUMN_NAME_USER_ID + "=?",            // The columns for the WHERE clause
                new String[]{formatDateTime.format(start_cal.getTime()), formatDateTime.format(end_cal.getTime()), Integer.toString(userId)},            // The values for the WHERE clause
                null, 			// don't group the rows
                null,			// don't filter by row groups
                sortOrder  		// The sort order
        );

        try {
            cursorScaleDB.moveToFirst();

            while (!cursorScaleDB.isAfterLast()) {
                ScaleData scaleData = new ScaleData();

                scaleData.id = cursorScaleDB.getLong(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_ID));
                scaleData.user_id = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_USER_ID));
                String date_time = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_DATE_TIME));
                scaleData.weight = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_WEIGHT));
                scaleData.fat = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_FAT));
                scaleData.water = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_WATER));
                scaleData.muscle = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_MUSCLE));

                scaleData.date_time = formatDateTime.parse(date_time);

                scaleDataList.add(scaleData);

                cursorScaleDB.moveToNext();
            }
        } catch (ParseException ex) {
            Log.e("ScaleDatabase", "Can't parse the date time string: " + ex.getMessage());
        }
        catch ( IllegalArgumentException ex) {
            Log.e("ScaleDatabase", "Illegal argument while reading from scale database: " + ex.getMessage());
        }

        return scaleDataList;
    }

	public ArrayList<ScaleData> getScaleDataList(int userId) {
		SQLiteDatabase db = getReadableDatabase();
		ArrayList<ScaleData> scaleDataList = new ArrayList<ScaleData>();

		String[] projection = {
				COLUMN_NAME_ID,
                COLUMN_NAME_USER_ID,
				COLUMN_NAME_DATE_TIME,
				COLUMN_NAME_WEIGHT,
				COLUMN_NAME_FAT,
				COLUMN_NAME_WATER,
				COLUMN_NAME_MUSCLE
				};

		String sortOrder = COLUMN_NAME_DATE_TIME + " DESC";

		Cursor cursorScaleDB = db.query(
		    TABLE_NAME, 	// The table to query
		    projection, 	// The columns to return
            COLUMN_NAME_USER_ID + "=?", 			// The columns for the WHERE clause
		    new String[]{Integer.toString(userId)}, 			// The values for the WHERE clause
		    null, 			// don't group the rows
		    null,			// don't filter by row groups
		    sortOrder  		// The sort order
		    );
		
		try {
			cursorScaleDB.moveToFirst();
			
			while (!cursorScaleDB.isAfterLast()) {
				ScaleData scaleData = new ScaleData();
				
				scaleData.id = cursorScaleDB.getLong(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_ID));
                scaleData.user_id = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_USER_ID));
				String date_time = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_DATE_TIME));
				scaleData.weight = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_WEIGHT));
				scaleData.fat = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_FAT));
				scaleData.water = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_WATER));
				scaleData.muscle = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_MUSCLE));
				
				scaleData.date_time = formatDateTime.parse(date_time);
				
				scaleDataList.add(scaleData);
				
				cursorScaleDB.moveToNext();
			}
		} catch (ParseException ex) {
			Log.e("ScaleDatabase", "Can't parse the date time string: " + ex.getMessage());
		} 
		  catch ( IllegalArgumentException ex) {
			Log.e("ScaleDatabase", "Illegal argument while reading from scale database: " + ex.getMessage());
		}  
		
		
		return scaleDataList;
	}
}
