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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;

public class ScaleUserDatabase extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 3;
    private static final String DATABASE_NAME = "openScaleUserDatabase.db";

    private static final String TABLE_NAME = "scaleuserdata";
    private static final String COLUMN_NAME_ID = "id";
    private static final String COLUMN_NAME_USER_NAME = "user_name";
    private static final String COLUMN_NAME_BIRTHDAY = "birthday";
    private static final String COLUMN_NAME_BODY_HEIGHT = "body_height";
    private static final String COLUMN_NAME_SCALE_UNIT = "scale_unit";
    private static final String COLUMN_NAME_GENDER = "gender";
    private static final String COLUMN_NAME_INITIAL_WEIGHT = "initial_weight";
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
                    COLUMN_NAME_INITIAL_WEIGHT + " REAL," +
                    COLUMN_NAME_GOAL_WEIGHT + " REAL," +
                    COLUMN_NAME_GOAL_DATE + " TEXT" +
                    ")";

    private static String[] projection = {
            COLUMN_NAME_ID,
            COLUMN_NAME_USER_NAME,
            COLUMN_NAME_BIRTHDAY,
            COLUMN_NAME_BODY_HEIGHT,
            COLUMN_NAME_SCALE_UNIT,
            COLUMN_NAME_GENDER,
            COLUMN_NAME_INITIAL_WEIGHT,
            COLUMN_NAME_GOAL_WEIGHT,
            COLUMN_NAME_GOAL_DATE
    };

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

        if (oldVersion == 2 && newVersion == 3) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COLUMN_NAME_INITIAL_WEIGHT + " REAL DEFAULT 0");
        }
    }

    public ArrayList<ScaleUser> getScaleUserList() {
        SQLiteDatabase db = getReadableDatabase();
        ArrayList<ScaleUser> scaleUserDBEntries = new ArrayList<ScaleUser>();

        String sortOrder = COLUMN_NAME_ID + " ASC";

        Cursor cursorScaleDB = db.query(
            TABLE_NAME,     // The table to query
            projection,     // The columns to return
            null,             // The columns for the WHERE clause
            null,             // The values for the WHERE clause
            null,             // don't group the rows
            null,            // don't filter by row groups
            sortOrder          // The sort order
            );

            cursorScaleDB.moveToFirst();

            while (!cursorScaleDB.isAfterLast()) {
                scaleUserDBEntries.add(readAtCursor(cursorScaleDB));

                cursorScaleDB.moveToNext();
            }

        cursorScaleDB.close();

        return scaleUserDBEntries;
    }

    private ScaleUser readAtCursor (Cursor cur) {
        ScaleUser scaleUser = new ScaleUser();

        try {
            scaleUser.setId(cur.getInt(cur.getColumnIndexOrThrow(COLUMN_NAME_ID)));
            scaleUser.setUserName(cur.getString(cur.getColumnIndexOrThrow(COLUMN_NAME_USER_NAME)));
            String birthday = cur.getString(cur.getColumnIndexOrThrow(COLUMN_NAME_BIRTHDAY));
            scaleUser.setBodyHeight(cur.getInt(cur.getColumnIndexOrThrow(COLUMN_NAME_BODY_HEIGHT)));
            scaleUser.setScaleUnit(Converters.fromWeightUnitInt(cur.getInt(cur.getColumnIndexOrThrow(COLUMN_NAME_SCALE_UNIT))));
            scaleUser.setGender(Converters.fromGenderInt(cur.getInt(cur.getColumnIndexOrThrow(COLUMN_NAME_GENDER))));
            double initial_weight = cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_INITIAL_WEIGHT));
            double goal_weight = cur.getFloat(cur.getColumnIndexOrThrow(COLUMN_NAME_GOAL_WEIGHT));
            String goal_date = cur.getString(cur.getColumnIndexOrThrow(COLUMN_NAME_GOAL_DATE));

            scaleUser.setBirthday(formatDateTime.parse(birthday));
            scaleUser.setGoalDate(formatDateTime.parse(goal_date));

            scaleUser.setInitialWeight(Math.round(initial_weight * 100.0f) / 100.0f);
            scaleUser.setGoalWeight(Math.round(goal_weight * 100.0f) / 100.0f);
        } catch (ParseException ex) {
            Log.e("ScaleDatabase", "Can't parse the date time string: " + ex.getMessage());
        }
        catch (IllegalArgumentException ex) {
            Log.e("ScaleDatabase", "Illegal argument while reading from scale database: " + ex.getMessage());
        }

        return scaleUser;
    }
}
