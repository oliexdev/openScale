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

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class ScaleDatabase extends SQLiteOpenHelper {	
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "openScaleDatabase.db";	
	
    private static final String TABLE_NAME = "scaledata";
    private static final String COLUMN_NAME_ID = "id";
    private static final String COLUMN_NAME_DATE_TIME = "date_time";
    private static final String COLUMN_NAME_WEIGHT = "weight";
    private static final String COLUMN_NAME_FAT = "fat";
    private static final String COLUMN_NAME_WATER = "water";
    private static final String COLUMN_NAME_MUSCLE = "muscle";
    
    private static final String SQL_CREATE_ENTRIES = 
    		"CREATE TABLE " + TABLE_NAME + " (" + 
    				COLUMN_NAME_ID + " INTEGER PRIMARY KEY," +
    				COLUMN_NAME_DATE_TIME + " TEXT UNIQUE," +
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
	
	public void deleteAllEntries() {
		SQLiteDatabase db = getWritableDatabase();
		
		db.delete(TABLE_NAME, null, null);
	}

	public void insertEntry(ScaleData scaleData) {
		SQLiteDatabase db = getWritableDatabase();
		
		// Create a new map of values, where column names are the keys
		ContentValues values = new ContentValues();
		values.put(COLUMN_NAME_DATE_TIME, formatDateTime.format(scaleData.date_time));
		values.put(COLUMN_NAME_WEIGHT, scaleData.weight);
		values.put(COLUMN_NAME_FAT, scaleData.fat);
		values.put(COLUMN_NAME_WATER, scaleData.water);
		values.put(COLUMN_NAME_MUSCLE, scaleData.muscle);

		// Insert the new row, returning the primary key value of the new row
		long newRowId = db.insert(TABLE_NAME, null, values);
		
		if (newRowId == -1) {
			Log.e("ScaleDatabase", "An error occured while inserting a new entry into the scale database");
		}
	}
	
	public ArrayList<ScaleData> getAllDBEntries() {
		SQLiteDatabase db = getReadableDatabase();
		ArrayList<ScaleData> scaleDBEntries = new ArrayList<ScaleData>();
		
		// Define a projection that specifies which columns from the database
		// you will actually use after this query.
		String[] projection = {
				COLUMN_NAME_ID,
				COLUMN_NAME_DATE_TIME,
				COLUMN_NAME_WEIGHT,
				COLUMN_NAME_FAT,
				COLUMN_NAME_WATER,
				COLUMN_NAME_MUSCLE
				};

		// How you want the results sorted in the resulting Cursor
		String sortOrder = COLUMN_NAME_DATE_TIME + " DESC";

		Cursor cursorScaleDB = db.query(
		    TABLE_NAME, 	// The table to query
		    projection, 	// The columns to return
		    null, 			// The columns for the WHERE clause
		    null, 			// The values for the WHERE clause
		    null, 			// don't group the rows
		    null,			// don't filter by row groups
		    sortOrder  		// The sort order
		    );
		
		try {
			cursorScaleDB.moveToFirst();
			
			while (!cursorScaleDB.isAfterLast()) {
				ScaleData dataEntry = new ScaleData();
				
				dataEntry.id = cursorScaleDB.getLong(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_ID));
				String date_time = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_DATE_TIME));
				dataEntry.weight = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_WEIGHT));
				dataEntry.fat = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_FAT));
				dataEntry.water = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_WATER));
				dataEntry.muscle = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_MUSCLE));
				
				Date date = formatDateTime.parse(date_time);
				
				dataEntry.date_time = date;
				
				scaleDBEntries.add(dataEntry);
				//Log.d("ScaleDatabase", dataEntry.toString());
				
				cursorScaleDB.moveToNext();
			}
		} catch (ParseException ex) {
			Log.e("ScaleDatabase", "Can't parse the date time string: " + ex.getMessage());
		} 
		  catch ( IllegalArgumentException ex) {
			Log.e("ScaleDatabase", "Illegal argument while reading from scale database: " + ex.getMessage());
		}  
		
		
		return scaleDBEntries;
	}
}
