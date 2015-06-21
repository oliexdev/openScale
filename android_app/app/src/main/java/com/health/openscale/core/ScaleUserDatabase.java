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
import java.util.Locale;

public class ScaleUserDatabase extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 2;
    private static final String DATABASE_NAME = "openScaleUserDatabase.db";

    private static final String TABLE_NAME = "scaleuserdata";
    private static final String COLUMN_NAME_ID = "id";
    private static final String COLUMN_NAME_USER_NAME = "user_name";
    private static final String COLUMN_NAME_BIRTHDAY = "birthday";
    private static final String COLUMN_NAME_BODY_HEIGHT = "body_height";
    private static final String COLUMN_NAME_SCALE_UNIT = "scale_unit";
    private static final String COLUMN_NAME_GENDER = "gender";
    private static final String COLUMN_NAME_GOAL_WEIGHT = "goal_weight";
    private static final String COLUMN_NAME_GOAL_DATE = "goal_date";

    private static final String SQL_CREATE_ENTRIES =
    		"CREATE TABLE " + TABLE_NAME + " (" +
    				COLUMN_NAME_ID + " INTEGER PRIMARY KEY," +
                    COLUMN_NAME_USER_NAME + " TEXT," +
                    COLUMN_NAME_BIRTHDAY + " TEXT," +
                    COLUMN_NAME_BODY_HEIGHT + " INTEGER," +
                    COLUMN_NAME_SCALE_UNIT + " INTEGER," +
                    COLUMN_NAME_GENDER + " INTEGER," +
                    COLUMN_NAME_GOAL_WEIGHT + " REAL," +
                    COLUMN_NAME_GOAL_DATE + " TEXT" +
    				")";

    private static final String SQL_DELETE_ENTRIES =
    		"DROP TABLE IF EXISTS " + TABLE_NAME;

    private SimpleDateFormat formatDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

	public ScaleUserDatabase(Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(SQL_CREATE_ENTRIES);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_GENDER + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_GOAL_WEIGHT + " REAL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_GOAL_DATE + " TEXT DEFAULT '2014-01-01 00:00'");
        }
	}
	
	public void clearDatabase() {
		SQLiteDatabase db = getWritableDatabase();
		
		db.delete(TABLE_NAME, null, null);
	}

	public boolean insertEntry(ScaleUser scaleUser) {
		SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(COLUMN_NAME_USER_NAME, scaleUser.user_name);
        values.put(COLUMN_NAME_BIRTHDAY, formatDateTime.format(scaleUser.birthday));
        values.put(COLUMN_NAME_BODY_HEIGHT, scaleUser.body_height);
        values.put(COLUMN_NAME_SCALE_UNIT, scaleUser.scale_unit);
        values.put(COLUMN_NAME_GENDER, scaleUser.gender);
        values.put(COLUMN_NAME_GOAL_WEIGHT, scaleUser.goal_weight);
        values.put(COLUMN_NAME_GOAL_DATE, formatDateTime.format(scaleUser.goal_date));

        try
        {
            db.insertOrThrow(TABLE_NAME, null, values);
        }
        catch (SQLException e)
        {
            Log.e("ScaleUserDatabase", "An error occured while inserting a new entry into the scale user database: " + e.toString());
            return false;
        }

        return true;
	}

    public void deleteEntry(int id) {
        SQLiteDatabase db = getWritableDatabase();

        db.delete(TABLE_NAME, COLUMN_NAME_ID + "= ?", new String[] {String.valueOf(id)});
    }

    public void updateScaleUser(ScaleUser scaleUser)
    {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put(COLUMN_NAME_USER_NAME, scaleUser.user_name);
        values.put(COLUMN_NAME_BIRTHDAY, formatDateTime.format(scaleUser.birthday));
        values.put(COLUMN_NAME_BODY_HEIGHT, scaleUser.body_height);
        values.put(COLUMN_NAME_SCALE_UNIT, scaleUser.scale_unit);
        values.put(COLUMN_NAME_GENDER, scaleUser.gender);
        values.put(COLUMN_NAME_GOAL_WEIGHT, scaleUser.goal_weight);
        values.put(COLUMN_NAME_GOAL_DATE, formatDateTime.format(scaleUser.goal_date));

        db.update(TABLE_NAME, values, COLUMN_NAME_ID + "=" + scaleUser.id, null);
    }

    public ScaleUser getScaleUser(int id)
    {
        SQLiteDatabase db = getReadableDatabase();
        ScaleUser scaleUser = new ScaleUser();

        String[] projection = {
                COLUMN_NAME_ID,
                COLUMN_NAME_USER_NAME,
                COLUMN_NAME_BIRTHDAY,
                COLUMN_NAME_BODY_HEIGHT,
                COLUMN_NAME_SCALE_UNIT,
                COLUMN_NAME_GENDER,
                COLUMN_NAME_GOAL_WEIGHT,
                COLUMN_NAME_GOAL_DATE
        };

        Cursor cursorScaleDB = db.query(
                TABLE_NAME, 	// The table to query
                projection, 	// The columns to return
                COLUMN_NAME_ID + "=?", 			// The columns for the WHERE clause
                new String[] {Integer.toString(id)}, 			// The values for the WHERE clause
                null, 			// don't group the rows
                null,			// don't filter by row groups
                null  		// The sort order
        );

        try {
            cursorScaleDB.moveToFirst();

            scaleUser.id = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_ID));
            scaleUser.user_name = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_USER_NAME));
            String birthday = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_BIRTHDAY));
            scaleUser.body_height = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_BODY_HEIGHT));
            scaleUser.scale_unit = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_SCALE_UNIT));
            scaleUser.gender = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_GENDER));
            scaleUser.goal_weight = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_GOAL_WEIGHT));
            String goal_date = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_GOAL_DATE));

            scaleUser.birthday = formatDateTime.parse(birthday);
            scaleUser.goal_date = formatDateTime.parse(goal_date);

            cursorScaleDB.moveToNext();

        } catch (ParseException ex) {
            Log.e("ScaleDatabase", "Can't parse the date time string: " + ex.getMessage());
        }
        catch ( IllegalArgumentException ex) {
            Log.e("ScaleDatabase", "Illegal argument while reading from scale database: " + ex.getMessage());
        }

        return scaleUser;
    }

	public ArrayList<ScaleUser> getScaleUserList() {
		SQLiteDatabase db = getReadableDatabase();
		ArrayList<ScaleUser> scaleUserDBEntries = new ArrayList<ScaleUser>();

		String[] projection = {
				COLUMN_NAME_ID,
                COLUMN_NAME_USER_NAME,
				COLUMN_NAME_BIRTHDAY,
				COLUMN_NAME_BODY_HEIGHT,
				COLUMN_NAME_SCALE_UNIT,
                COLUMN_NAME_GENDER,
                COLUMN_NAME_GOAL_WEIGHT,
                COLUMN_NAME_GOAL_DATE
				};

		String sortOrder = COLUMN_NAME_ID + " DESC";

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
                ScaleUser scaleUser = new ScaleUser();
				
				scaleUser.id = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_ID));
                scaleUser.user_name = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_USER_NAME));
				String birthday = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_BIRTHDAY));
				scaleUser.body_height = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_BODY_HEIGHT));
				scaleUser.scale_unit = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_SCALE_UNIT));
                scaleUser.gender = cursorScaleDB.getInt(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_GENDER));
                scaleUser.goal_weight = cursorScaleDB.getFloat(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_GOAL_WEIGHT));
                String goal_date = cursorScaleDB.getString(cursorScaleDB.getColumnIndexOrThrow(COLUMN_NAME_GOAL_DATE));

                scaleUser.birthday = formatDateTime.parse(birthday);
                scaleUser.goal_date = formatDateTime.parse(goal_date);

				scaleUserDBEntries.add(scaleUser);
				
				cursorScaleDB.moveToNext();
			}
		} catch (ParseException ex) {
			Log.e("ScaleDatabase", "Can't parse the date time string: " + ex.getMessage());
		} 
		  catch ( IllegalArgumentException ex) {
			Log.e("ScaleDatabase", "Illegal argument while reading from scale database: " + ex.getMessage());
		}  
		
		
		return scaleUserDBEntries;
	}
}
