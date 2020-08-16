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

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.OpenableColumns;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.health.openscale.R;
import com.health.openscale.core.alarm.AlarmHandler;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothFactory;
import com.health.openscale.core.bodymetric.EstimatedFatMetric;
import com.health.openscale.core.bodymetric.EstimatedLBMMetric;
import com.health.openscale.core.bodymetric.EstimatedWaterMetric;
import com.health.openscale.core.database.AppDatabase;
import com.health.openscale.core.database.ScaleMeasurementDAO;
import com.health.openscale.core.database.ScaleUserDAO;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.CsvHelper;
import com.health.openscale.gui.measurement.FatMeasurementView;
import com.health.openscale.gui.measurement.LBMMeasurementView;
import com.health.openscale.gui.measurement.MeasurementViewSettings;
import com.health.openscale.gui.measurement.WaterMeasurementView;
import com.health.openscale.gui.widget.WidgetProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import timber.log.Timber;

public class OpenScale {
    public static boolean DEBUG_MODE = false;

    public static final String DATABASE_NAME = "openScale.db";

    private static OpenScale instance;

    private AppDatabase appDB;
    private ScaleMeasurementDAO measurementDAO;
    private ScaleUserDAO userDAO;

    private ScaleUser selectedScaleUser;

    private BluetoothCommunication btDeviceDriver;
    private AlarmHandler alarmHandler;

    private Context context;

    private OpenScale(Context context) {
        this.context = context;
        alarmHandler = new AlarmHandler();
        btDeviceDriver = null;

        reopenDatabase(false);
    }

    public static void createInstance(Context context) {
        if (instance != null) {
            return;
        }

        instance = new OpenScale(context);
    }

    public static OpenScale getInstance() {
        if (instance == null) {
            throw new RuntimeException("No OpenScale instance created");
        }

        return instance;
    }

    public void reopenDatabase(boolean truncate) throws SQLiteDatabaseCorruptException {
        if (appDB != null) {
            appDB.close();
        }

        appDB = Room.databaseBuilder(context, AppDatabase.class, DATABASE_NAME)
                .allowMainThreadQueries()
                .setJournalMode(truncate == true ? RoomDatabase.JournalMode.TRUNCATE : RoomDatabase.JournalMode.AUTOMATIC) // in truncate mode no sql cache files (-shm, -wal) are generated
                .addCallback(new RoomDatabase.Callback() {
                    @Override
                    public void onOpen(SupportSQLiteDatabase db) {
                        super.onOpen(db);
                        db.setForeignKeyConstraintsEnabled(true);
                    }
                })
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
                .build();
        measurementDAO = appDB.measurementDAO();
        userDAO = appDB.userDAO();
    }

    public void triggerWidgetUpdate() {
        int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, WidgetProvider.class));
        if (ids.length > 0) {
            Intent intent = new Intent(context, WidgetProvider.class);
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
            context.sendBroadcast(intent);
        }
    }

    public int addScaleUser(final ScaleUser user) {
        return (int)userDAO.insert(user);
    }

    public void selectScaleUser(int userId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putInt("selectedUserId", userId).apply();

        selectedScaleUser = getScaleUser(userId);
    }

    public int getSelectedScaleUserId() {
        if (selectedScaleUser != null) {
            return selectedScaleUser.getId();
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getInt("selectedUserId", -1);
    }

    public List<ScaleUser> getScaleUserList() {
        return userDAO.getAll();
    }

    public ScaleUser getScaleUser(int userId) {
        if (selectedScaleUser != null && selectedScaleUser.getId() == userId) {
            return selectedScaleUser;
        }
        return userDAO.get(userId);
    }

    public ScaleUser getSelectedScaleUser() {
        if (selectedScaleUser != null) {
            return selectedScaleUser;
        }

        try {
            final int selectedUserId = getSelectedScaleUserId();
            if (selectedUserId != -1) {
                selectedScaleUser = userDAO.get(selectedUserId);
                if (selectedScaleUser == null) {
                    selectScaleUser(-1);
                    throw new Exception("could not find the selected user");
                }

                return selectedScaleUser;
            }
        } catch (Exception e) {
            Timber.e(e);
            runUiToastMsg("Error: " + e.getMessage());
        }

        return new ScaleUser();
    }

    public void deleteScaleUser(int id) {
        Timber.d("Delete user " + getScaleUser(id));
        userDAO.delete(userDAO.get(id));
        selectedScaleUser = null;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Remove user specific settings
        SharedPreferences.Editor editor = prefs.edit();
        final String prefix = ScaleUser.getPreferenceKey(id, "");
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    public void updateScaleUser(ScaleUser user) {
        userDAO.update(user);
        selectedScaleUser = null;
    }

    public boolean isScaleMeasurementListEmpty() {
        if (measurementDAO.getCount(getSelectedScaleUserId()) == 0) {
            return true;
        }

        return false;
    }

    public ScaleMeasurement getLastScaleMeasurement() {
        return measurementDAO.getLatest(getSelectedScaleUserId());
    }

    public ScaleMeasurement getLastScaleMeasurement(int userId) {
        return measurementDAO.getLatest(userId);
    }

    public ScaleMeasurement getFirstScaleMeasurement() {
        return measurementDAO.getFirst(getSelectedScaleUserId());
    }

    public List<ScaleMeasurement> getScaleMeasurementList() {
        return measurementDAO.getAll(getSelectedScaleUserId());
    }

    public ScaleMeasurement[] getTupleOfScaleMeasurement(int id)
    {
        ScaleMeasurement[] tupleScaleMeasurement = new ScaleMeasurement[3];

        tupleScaleMeasurement[0] = null;
        tupleScaleMeasurement[1] = measurementDAO.get(id);
        tupleScaleMeasurement[2] = null;

        if (tupleScaleMeasurement[1] != null) {
            tupleScaleMeasurement[0] = measurementDAO.getPrevious(id, tupleScaleMeasurement[1].getUserId());
            tupleScaleMeasurement[2] = measurementDAO.getNext(id, tupleScaleMeasurement[1].getUserId());
        }

        return tupleScaleMeasurement;
    }

    public int addScaleMeasurement(final ScaleMeasurement scaleMeasurement) {
        return addScaleMeasurement(scaleMeasurement, false);
    }

    public int addScaleMeasurement(final ScaleMeasurement scaleMeasurement, boolean silent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Check user id and do a smart user assign if option is enabled
        if (scaleMeasurement.getUserId() == -1) {
            if (prefs.getBoolean("smartUserAssign", false)) {
                scaleMeasurement.setUserId(getSmartUserAssignment(scaleMeasurement.getWeight(), 15.0f));
            } else {
                scaleMeasurement.setUserId(getSelectedScaleUser().getId());
            }

            // don't add scale data if no user is selected
            if (scaleMeasurement.getUserId() == -1) {
                Timber.e("to be added measurement are thrown away because no user is selected");
                return -1;
            }
        }

        // Assisted weighing
        if (getScaleUser(scaleMeasurement.getUserId()).isAssistedWeighing()) {
            int assistedWeighingRefUserId = prefs.getInt("assistedWeighingRefUserId", -1);
            if (assistedWeighingRefUserId != -1) {
                ScaleMeasurement lastRefScaleMeasurement = getLastScaleMeasurement(assistedWeighingRefUserId);

                if (lastRefScaleMeasurement != null) {
                    float refWeight = lastRefScaleMeasurement.getWeight();
                    float diffToRef = scaleMeasurement.getWeight() - refWeight;
                    scaleMeasurement.setWeight(diffToRef);
                }
            } else {
                Timber.e("assisted weighing reference user id is -1");
            }
        }

        // Calculate the amputation correction factor for the weight, if available
        scaleMeasurement.setWeight((scaleMeasurement.getWeight() * 100.0f) / getScaleUser(scaleMeasurement.getUserId()).getAmputationCorrectionFactor());

        // If option is enabled then calculate body measurements from generic formulas
        MeasurementViewSettings settings = new MeasurementViewSettings(prefs, WaterMeasurementView.KEY);
        if (settings.isEnabled() && settings.isEstimationEnabled()) {
            EstimatedWaterMetric waterMetric = EstimatedWaterMetric.getEstimatedMetric(
                    EstimatedWaterMetric.FORMULA.valueOf(settings.getEstimationFormula()));
            scaleMeasurement.setWater(waterMetric.getWater(getScaleUser(scaleMeasurement.getUserId()), scaleMeasurement));
        }

        settings = new MeasurementViewSettings(prefs, FatMeasurementView.KEY);
        if (settings.isEnabled() && settings.isEstimationEnabled()) {
            EstimatedFatMetric fatMetric = EstimatedFatMetric.getEstimatedMetric(
                    EstimatedFatMetric.FORMULA.valueOf(settings.getEstimationFormula()));
            scaleMeasurement.setFat(fatMetric.getFat(getScaleUser(scaleMeasurement.getUserId()), scaleMeasurement));
        }

        // Must be after fat estimation as one formula is based on fat
        settings = new MeasurementViewSettings(prefs, LBMMeasurementView.KEY);
        if (settings.isEnabled() && settings.isEstimationEnabled()) {
            EstimatedLBMMetric lbmMetric = EstimatedLBMMetric.getEstimatedMetric(
                    EstimatedLBMMetric.FORMULA.valueOf(settings.getEstimationFormula()));
            scaleMeasurement.setLbm(lbmMetric.getLBM(getScaleUser(scaleMeasurement.getUserId()), scaleMeasurement));
        }

        // Insert measurement into the database, check return if it was successful inserted
        if (measurementDAO.insert(scaleMeasurement) != -1) {
            Timber.d("Added measurement: %s", scaleMeasurement);
            if (!silent) {
                ScaleUser scaleUser = getScaleUser(scaleMeasurement.getUserId());

                final java.text.DateFormat dateFormat = DateFormat.getDateFormat(context);
                final java.text.DateFormat timeFormat = DateFormat.getTimeFormat(context);
                final Date dateTime = scaleMeasurement.getDateTime();

                final Converters.WeightUnit unit = scaleUser.getScaleUnit();

                String infoText = String.format(context.getString(R.string.info_new_data_added),
                        Converters.fromKilogram(scaleMeasurement.getWeight(), unit), unit.toString(),
                        dateFormat.format(dateTime) + " " + timeFormat.format(dateTime),
                        scaleUser.getUserName());
                runUiToastMsg(infoText);
            }

            syncInsertMeasurement(scaleMeasurement);
            alarmHandler.entryChanged(context, scaleMeasurement);
            triggerWidgetUpdate();
        } else {
            Timber.d("to be added measurement is thrown away because measurement with the same date and time already exist");
            if (!silent) {
                runUiToastMsg(context.getString(R.string.info_new_data_duplicated));
            }
        }

        return scaleMeasurement.getUserId();
    }

    private int getSmartUserAssignment(float weight, float range) {
        List<ScaleUser> scaleUsers = getScaleUserList();
        Map<Float, Integer> inRangeWeights = new TreeMap<>();

        for (int i = 0; i < scaleUsers.size(); i++) {
            List<ScaleMeasurement> scaleUserData = measurementDAO.getAll(scaleUsers.get(i).getId());

            float lastWeight;

            if (scaleUserData.size() > 0) {
                lastWeight = scaleUserData.get(0).getWeight();
            } else {
                lastWeight = scaleUsers.get(i).getInitialWeight();
            }

            if ((lastWeight - range) <= weight && (lastWeight + range) >= weight) {
                inRangeWeights.put(Math.abs(lastWeight - weight), scaleUsers.get(i).getId());
            }
        }

        if (inRangeWeights.size() > 0) {
            // return the user id which is nearest to the weight (first element of the tree map)
            int userId = inRangeWeights.entrySet().iterator().next().getValue();
            Timber.d("assign measurement to the nearest measurement with the user " + getScaleUser(userId).getUserName() + " (smartUserAssignment=on)");
            return userId;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // if ignore out of range preference is true don't add this data
        if (prefs.getBoolean("ignoreOutOfRange", false)) {
            Timber.d("to be added measurement is thrown away because measurement is out of range (smartUserAssignment=on;ignoreOutOfRange=on)");
            return -1;
        }

        // return selected scale user id if not out of range preference is checked and weight is out of range of any user
        Timber.d("assign measurement to the selected user (smartUserAssignment=on;ignoreOutOfRange=off)");
        return getSelectedScaleUser().getId();
    }

    public void updateScaleMeasurement(ScaleMeasurement scaleMeasurement) {
        Timber.d("Update measurement: %s", scaleMeasurement);
        measurementDAO.update(scaleMeasurement);
        alarmHandler.entryChanged(context, scaleMeasurement);
        syncUpdateMeasurement(scaleMeasurement);

        triggerWidgetUpdate();
    }

    public void deleteScaleMeasurement(int id) {
        syncDeleteMeasurement(measurementDAO.get(id).getDateTime());
        measurementDAO.delete(id);
    }

    public String getFilenameFromUriMayThrow(Uri uri) {
        Cursor cursor = context.getContentResolver().query(
                uri, null, null, null, null);
        try {
            cursor.moveToFirst();
            return cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
        }
        finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public String getFilenameFromUri(Uri uri) {
        try {
            return getFilenameFromUriMayThrow(uri);
        }
        catch (Exception e) {
            String name = uri.getLastPathSegment();
            if (name != null) {
                return name;
            }
            name = uri.getPath();
            if (name != null) {
                return name;
            }
            return uri.toString();
        }
    }

    public void importDatabase(Uri importFile) throws IOException {
        File exportFile = context.getApplicationContext().getDatabasePath("openScale.db");
        File tmpExportFile = context.getApplicationContext().getDatabasePath("openScale_tmp.db");

        try {
            copyFile(Uri.fromFile(exportFile), Uri.fromFile(tmpExportFile));
            copyFile(importFile, Uri.fromFile(exportFile));

            reopenDatabase(false);

            if (!getScaleUserList().isEmpty()) {
                selectScaleUser(getScaleUserList().get(0).getId());
            }
        } catch (SQLiteDatabaseCorruptException e) {
            copyFile(Uri.fromFile(tmpExportFile), Uri.fromFile(exportFile));
            throw new IOException(e.getMessage());
        } finally {
            tmpExportFile.delete();
        }
    }

    public void exportDatabase(Uri exportFile) throws IOException {
        File dbFile = context.getApplicationContext().getDatabasePath("openScale.db");
        reopenDatabase(true); // re-open database without caching sql -shm, -wal files

        copyFile(Uri.fromFile(dbFile), exportFile);
    }

    private void copyFile(Uri src, Uri dst) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(src);
        OutputStream output = context.getContentResolver().openOutputStream(dst);

        try {
            byte[] bytes = new byte[4096];
            int count;

            while ((count = input.read(bytes)) != -1){
                output.write(bytes, 0, count);
            }
        } finally {
            if (input != null) {
                input.close();
            }
            if (output != null) {
                output.flush();
                output.close();
            }
        }
    }

    public void importData(Uri uri) {
        try {
            final String filename = getFilenameFromUri(uri);

            InputStream input = context.getContentResolver().openInputStream(uri);
            List<ScaleMeasurement> csvScaleMeasurementList =
                    CsvHelper.importFrom(new BufferedReader(new InputStreamReader(input)));

            final int userId = getSelectedScaleUser().getId();
            for (ScaleMeasurement measurement : csvScaleMeasurementList) {
                measurement.setUserId(userId);
            }

            measurementDAO.insertAll(csvScaleMeasurementList);
            runUiToastMsg(context.getString(R.string.info_data_imported) + " " + filename);
        } catch (IOException | ParseException e) {
            runUiToastMsg(context.getString(R.string.error_importing) + ": " + e.getMessage());
        }
    }

    public boolean exportData(Uri uri) {
        try {
            List<ScaleMeasurement> scaleMeasurementList = getScaleMeasurementList();
            OutputStream output = context.getContentResolver().openOutputStream(uri);
            CsvHelper.exportTo(new OutputStreamWriter(output), scaleMeasurementList);
            return true;
        } catch (IOException e) {
            runUiToastMsg(context.getResources().getString(R.string.error_exporting) + " " + e.getMessage());
        }

        return false;
    }

    public void clearScaleMeasurements(int userId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putInt("uniqueNumber", 0x00).apply();
        syncClearMeasurements();
        measurementDAO.deleteAll(userId);
    }

    public int[] getCountsOfMonth(int year) {
        int selectedUserId = getSelectedScaleUserId();

        int [] numOfMonth = new int[12];

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        for (int i=0; i<12; i++) {
            startCalender.set(year, i, 1, 0, 0, 0);
            endCalender.set(year, i, 1, 0, 0, 0);
            endCalender.add(Calendar.MONTH, 1);

            numOfMonth[i] = measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId).size();
        }

        return numOfMonth;
    }

    public List<ScaleMeasurement> getScaleMeasurementOfStartDate(int year, int month, int day) {
        int selectedUserId = getSelectedScaleUserId();

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, month, day, 0, 0, 0);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public List<ScaleMeasurement> getScaleMeasurementOfDay(int year, int month, int day) {
        int selectedUserId = getSelectedScaleUserId();

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, month, day, 0, 0, 0);
        endCalender.set(year, month, day, 0, 0, 0);
        endCalender.add(Calendar.DAY_OF_MONTH, 1);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public List<ScaleMeasurement> getScaleMeasurementOfMonth(int year, int month) {
        int selectedUserId = getSelectedScaleUserId();

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, month, 1, 0, 0, 0);
        endCalender.set(year, month, 1, 0, 0, 0);
        endCalender.add(Calendar.MONTH, 1);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public List<ScaleMeasurement> getScaleMeasurementOfYear(int year) {
        int selectedUserId = getSelectedScaleUserId();

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        endCalender.set(year+1, Calendar.JANUARY, 1, 0, 0, 0);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public void connectToBluetoothDeviceDebugMode(String hwAddress, Handler callbackBtHandler) {
        Timber.d("Trying to connect to bluetooth device [%s] in debug mode", hwAddress);

        disconnectFromBluetoothDevice();

        btDeviceDriver = BluetoothFactory.createDebugDriver(context);
        btDeviceDriver.registerCallbackHandler(callbackBtHandler);
        btDeviceDriver.connect(hwAddress);
    }

    public boolean connectToBluetoothDevice(String deviceName, String hwAddress, Handler callbackBtHandler) {
        Timber.d("Trying to connect to bluetooth device [%s] (%s)", hwAddress, deviceName);

        disconnectFromBluetoothDevice();

        btDeviceDriver = BluetoothFactory.createDeviceDriver(context, deviceName);
        if (btDeviceDriver == null) {
            return false;
        }

        btDeviceDriver.registerCallbackHandler(callbackBtHandler);
        btDeviceDriver.connect(hwAddress);

        return true;
    }

    public boolean disconnectFromBluetoothDevice() {
        if (btDeviceDriver == null) {
            return false;
        }

        Timber.d("Disconnecting from bluetooth device");
        btDeviceDriver.disconnect();
        btDeviceDriver = null;

        return true;
    }

    public LiveData<List<ScaleMeasurement>> getScaleMeasurementsLiveData() {
        int selectedUserId = getSelectedScaleUserId();

        return measurementDAO.getAllAsLiveData(selectedUserId);
    }

    // As getScaleUserList(), but as a Cursor for export via a Content Provider.
    public Cursor getScaleUserListCursor() {
        return userDAO.selectAll();
    }

    // As getScaleMeasurementList(), but as a Cursor for export via a Content Provider.
    public Cursor getScaleMeasurementListCursor(long userId) {
        return measurementDAO.selectAll(userId);
    }

    private void runUiToastMsg(String text) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void syncInsertMeasurement(ScaleMeasurement scaleMeasurement) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.health.openscale.sync", "com.health.openscale.sync.core.service.SyncService"));
        intent.putExtra("mode", "insert");
        intent.putExtra("userId", scaleMeasurement.getUserId());
        intent.putExtra("weight", scaleMeasurement.getWeight());
        intent.putExtra("date", scaleMeasurement.getDateTime().getTime());
        ContextCompat.startForegroundService(context, intent);
    }

    private void syncUpdateMeasurement(ScaleMeasurement scaleMeasurement) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.health.openscale.sync", "com.health.openscale.sync.core.service.SyncService"));
        intent.putExtra("mode", "update");
        intent.putExtra("userId", scaleMeasurement.getUserId());
        intent.putExtra("weight", scaleMeasurement.getWeight());
        intent.putExtra("date", scaleMeasurement.getDateTime().getTime());
        ContextCompat.startForegroundService(context, intent);
    }

    private void syncDeleteMeasurement(Date date) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.health.openscale.sync", "com.health.openscale.sync.core.service.SyncService"));
        intent.putExtra("mode", "delete");
        intent.putExtra("date", date.getTime());
        ContextCompat.startForegroundService(context, intent);
    }

    private void syncClearMeasurements() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.health.openscale.sync", "com.health.openscale.sync.core.service.SyncService"));
        intent.putExtra("mode", "clear");
        ContextCompat.startForegroundService(context, intent);
    }

    public ScaleMeasurementDAO getScaleMeasurementDAO() {
        return measurementDAO;
    }

    public ScaleUserDAO getScaleUserDAO() {
        return userDAO;
    }
}
