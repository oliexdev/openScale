/* Copyright (C) 2018 Paul Cowan <paul@custardsource.com>
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
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;

import com.health.openscale.BuildConfig;
import com.health.openscale.core.OpenScale;

/**
 * Exposes the user and measurement data from openScale via
 * <a href="https://developer.android.com/guide/topics/providers/content-providers">Android
 * Content Providers</a>. This allows other apps to access the openScale data for their own purposes
 * (e.g. syncing to third-party services like Google Fit, Fitbit API, etc) without openScale itself
 * needing to do so or request additional permissions. <br />
 *
 * This access is gated by the com.health.openscale.READ_DATA permission, which is defined in the
 * manifest; it is not accessible to any other app without user confirmation.<br />
 *
 * The following URIs are supported:
 * <ul>
 *     <li><code>content://com.health.openscale.provider/user</code>: list all users.</li>
 *     <li><code>content://com.health.openscale.provider/user/$ID</code>: retrieve single user
 *         by ID.</li>
 *     <li><code>content://com.health.openscale.provider/user/$ID/measurements</code>:
 *         retrieve all measurements for the supplied user ID.</li>
 * </ul>
 */
public class ScaleDatabaseProvider extends android.content.ContentProvider {
    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".provider";

    private static final int MATCH_TYPE_USER_LIST = 1;
    private static final int MATCH_TYPE_USER_ENTRY = 2;
    private static final int MATCH_TYPE_USER_MEASUREMENTS = 3;


    static {
        uriMatcher.addURI(AUTHORITY, "user", MATCH_TYPE_USER_LIST);
        uriMatcher.addURI(AUTHORITY, "user/#", MATCH_TYPE_USER_ENTRY);
        uriMatcher.addURI(AUTHORITY, "user/#/measurements", MATCH_TYPE_USER_MEASUREMENTS);
    }

    @Override
    public String getType(Uri uri) {
        switch (uriMatcher.match(uri)) {
            case MATCH_TYPE_USER_LIST:
                return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".user";

            case MATCH_TYPE_USER_ENTRY:
                return "vnd.android.cursor.item/vnd." + AUTHORITY + ".user";

            case MATCH_TYPE_USER_MEASUREMENTS:
                return "vnd.android.cursor.item/vnd." + AUTHORITY + ".measurement";

            default:
                return null;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        final Context context = getContext();
        Cursor cursor;

        switch (uriMatcher.match(uri)) {
            case MATCH_TYPE_USER_LIST:
                cursor = OpenScale.getInstance().getScaleUserListCursor();
                break;

            case MATCH_TYPE_USER_ENTRY:
                cursor = OpenScale.getInstance().getScaleUserCursor(
                        Integer.valueOf(uri.getPathSegments().get(1)));
                break;

            case MATCH_TYPE_USER_MEASUREMENTS:
                cursor = OpenScale.getInstance().getScaleMeasurementListCursor(
                        Integer.valueOf(uri.getPathSegments().get(1)));
                break;

            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }

        cursor.setNotificationUri(context.getContentResolver(), uri);
        return cursor;
    }


    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
