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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.health.openscale.BuildConfig;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.util.Date;

import timber.log.Timber;

/**
 * Exposes the user and measurement data from openScale via
 * <a href="https://developer.android.com/guide/topics/providers/content-providers">Android
 * Content Providers</a>. This allows other apps to access the openScale data for their own purposes
 * (e.g. syncing to third-party services like Google Fit, Fitbit API, etc) without openScale itself
 * needing to do so or request additional permissions. <br />
 *
 * This access is gated by the com.health.openscale.READ_WRITE_DATA permission, which is defined in the
 * manifest; it is not accessible to any other app without user confirmation.<br />
 *
 * The following URIs are supported:
 * <ul>
 *     <li><code>content://com.health.openscale.provider/meta</code>: API and openScale version.</li>
 *     <li><code>content://com.health.openscale.provider/users</code>: list all users.</li>
 *     <li><code>content://com.health.openscale.provider/measurements/$ID</code>:
 *         retrieve all measurements for the supplied user ID.</li>
 * </ul>
 */
public class ScaleDatabaseProvider extends android.content.ContentProvider {
    private static final UriMatcher uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int API_VERSION = 1;

    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".provider";

    private static final int MATCH_TYPE_META = 1;
    private static final int MATCH_TYPE_USER_LIST = 2;
    private static final int MATCH_TYPE_MEASUREMENT_LIST = 3;


    static {
        uriMatcher.addURI(AUTHORITY, "meta", MATCH_TYPE_META);
        uriMatcher.addURI(AUTHORITY, "users", MATCH_TYPE_USER_LIST);
        uriMatcher.addURI(AUTHORITY, "measurements/#", MATCH_TYPE_MEASUREMENT_LIST);
    }

    @Override
    public String getType(Uri uri) {
        switch (uriMatcher.match(uri)) {
            case MATCH_TYPE_META:
                return "vnd.android.cursor.item/vnd." + AUTHORITY + ".meta";

            case MATCH_TYPE_USER_LIST:
                return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".user";

            case MATCH_TYPE_MEASUREMENT_LIST:
                return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".measurement";
        }
        return null;
    }

    @Override
    public boolean onCreate() {
        // need to create openScale instance for the provider if openScale app is closed
        OpenScale.createInstance(getContext().getApplicationContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        final Context context = getContext();

        Cursor cursor;

        switch (uriMatcher.match(uri)) {
            case MATCH_TYPE_META:
                cursor = new MatrixCursor(new String[]{"apiVersion", "versionCode"}, 1);
                ((MatrixCursor) cursor).addRow(new Object[]{API_VERSION, BuildConfig.VERSION_CODE});
                break;

            case MATCH_TYPE_USER_LIST:
                cursor = OpenScale.getInstance().getScaleUserListCursor();
                break;

            case MATCH_TYPE_MEASUREMENT_LIST:
                cursor = OpenScale.getInstance().getScaleMeasurementListCursor(
                        ContentUris.parseId(uri));
                break;

            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }

        cursor.setNotificationUri(context.getContentResolver(), uri);
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Date date = new Date(values.getAsLong("datetime"));
        float weight = values.getAsFloat("weight");
        int userId = values.getAsInteger("userId");

        ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

        scaleMeasurement.setUserId(userId);
        scaleMeasurement.setWeight(weight);
        scaleMeasurement.setDateTime(date);

        ScaleMeasurementDAO measurementDAO = OpenScale.getInstance().getScaleMeasurementDAO();

        if (measurementDAO.insert(scaleMeasurement) == -1) {
            update(uri, values, "", new String[]{});
        }

        return null;
    };

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {

        Date date  = new Date(values.getAsLong("datetime"));
        float weight = values.getAsFloat("weight");
        int userId = values.getAsInteger("userId");

        ScaleMeasurement scaleMeasurement = new ScaleMeasurement();

        scaleMeasurement.setWeight(weight);
        scaleMeasurement.setDateTime(date);

        ScaleMeasurementDAO measurementDAO = OpenScale.getInstance().getScaleMeasurementDAO();

        ScaleMeasurement databaseMeasurement = measurementDAO.get(date, userId);

        if (databaseMeasurement != null) {
            databaseMeasurement.merge(scaleMeasurement);
            databaseMeasurement.setEnabled(true);

            measurementDAO.update(databaseMeasurement);

            return 1;
        } else {
            Timber.e("no measurement for an update found");
        }

        return 0;
    }
}
