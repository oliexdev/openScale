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

import android.arch.persistence.db.SupportSQLiteDatabase;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.OpenableColumns;
import android.support.v4.app.Fragment;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.alarm.AlarmHandler;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothFactory;
import com.health.openscale.core.bodymetric.EstimatedFatMetric;
import com.health.openscale.core.bodymetric.EstimatedLBWMetric;
import com.health.openscale.core.bodymetric.EstimatedWaterMetric;
import com.health.openscale.core.database.AppDatabase;
import com.health.openscale.core.database.ScaleDatabase;
import com.health.openscale.core.database.ScaleMeasurementDAO;
import com.health.openscale.core.database.ScaleUserDAO;
import com.health.openscale.core.database.ScaleUserDatabase;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.CsvHelper;
import com.health.openscale.gui.fragments.FragmentUpdateListener;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.LBWMeasurementView;
import com.health.openscale.gui.views.MeasurementViewSettings;
import com.health.openscale.gui.views.WaterMeasurementView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OpenScale {

    public static final String DATABASE_NAME = "openScale.db";

    private static OpenScale instance;

    private AppDatabase appDB;
    private ScaleMeasurementDAO measurementDAO;
    private ScaleUserDAO userDAO;

    private ScaleUser selectedScaleUser;
    private List<ScaleMeasurement> scaleMeasurementList;

    private BluetoothCommunication btDeviceDriver;
    private AlarmHandler alarmHandler;

    private Context context;

    private ArrayList<FragmentUpdateListener> fragmentList;

    private OpenScale(Context context) {
        this.context = context;
        alarmHandler = new AlarmHandler();
        btDeviceDriver = null;
        fragmentList = new ArrayList<>();

        reopenDatabase();

        migrateSQLtoRoom();
        updateScaleData();
    }

    public static OpenScale getInstance(Context context) {
        if (instance == null) {
            instance = new OpenScale(context.getApplicationContext());
        }

        return instance;
    }

    public void reopenDatabase() {
        if (appDB != null) {
            appDB.close();
        }

        appDB = Room.databaseBuilder(context, AppDatabase.class, DATABASE_NAME)
                .allowMainThreadQueries()
                .addCallback(new RoomDatabase.Callback() {
                    @Override
                    public void onOpen(SupportSQLiteDatabase db) {
                        super.onOpen(db);
                        db.setForeignKeyConstraintsEnabled(true);
                    }
                })
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .build();
        measurementDAO = appDB.measurementDAO();
        userDAO = appDB.userDAO();
    }

    private void migrateSQLtoRoom() {
        if (!context.getDatabasePath(ScaleUserDatabase.DATABASE_NAME).exists()
            || !context.getDatabasePath(ScaleDatabase.DATABASE_NAME).exists()) {
            return;
        }

        ScaleDatabase scaleDB = new ScaleDatabase(context);
        ScaleUserDatabase scaleUserDB = new ScaleUserDatabase(context);

        List<ScaleUser> oldScaleUserList = scaleUserDB.getScaleUserList();

        if (scaleDB.getReadableDatabase().getVersion() == 6 && userDAO.getAll().isEmpty() && !oldScaleUserList.isEmpty()) {
            Toast.makeText(context, "Migrating old SQL database to new database format...", Toast.LENGTH_LONG).show();
            userDAO.insertAll(oldScaleUserList);

            for (ScaleUser user : oldScaleUserList) {
                List<ScaleMeasurement> oldScaleMeasurementList = scaleDB.getScaleDataList(user.getId());
                measurementDAO.insertAll(oldScaleMeasurementList);
            }

            Toast.makeText(context, "Finished migrating old SQL database to new database format", Toast.LENGTH_LONG).show();
        }

        scaleUserDB.close();
        scaleDB.close();
    }

    public int addScaleUser(final ScaleUser user) {
        return (int)userDAO.insert(user);
    }

    public void selectScaleUser(int userId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putInt("selectedUserId", userId).commit();

        selectedScaleUser = null;
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
            Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        return new ScaleUser();
    }

    public void deleteScaleUser(int id) {
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

    public List<ScaleMeasurement> getScaleMeasurementList() {
        return scaleMeasurementList;
    }


    public ScaleMeasurement[] getTupleScaleData(int id)
    {
        ScaleMeasurement[] tupleScaleData = new ScaleMeasurement[3];

        tupleScaleData[0] = measurementDAO.getPrevious(id, getSelectedScaleUser().getId());
        tupleScaleData[1] = measurementDAO.get(id);
        tupleScaleData[2] = measurementDAO.getNext(id, getSelectedScaleUser().getId());

        return tupleScaleData;
    }

    public int addScaleData(final ScaleMeasurement scaleMeasurement) {
        return addScaleData(scaleMeasurement, false);
    }

    public int addScaleData(final ScaleMeasurement scaleMeasurement, boolean silent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (scaleMeasurement.getUserId() == -1) {
            if (prefs.getBoolean("smartUserAssign", false)) {
                scaleMeasurement.setUserId(getSmartUserAssignment(scaleMeasurement.getWeight(), 15.0f));
            } else {
                scaleMeasurement.setUserId(getSelectedScaleUser().getId());
            }

            // don't add scale data if no user is selected
            if (scaleMeasurement.getUserId() == -1) {
                return -1;
            }
        }

        MeasurementViewSettings settings = new MeasurementViewSettings(prefs, WaterMeasurementView.KEY);
        if (settings.isEnabled() && settings.isEstimationEnabled()) {
            EstimatedWaterMetric waterMetric = EstimatedWaterMetric.getEstimatedMetric(
                    EstimatedWaterMetric.FORMULA.valueOf(settings.getEstimationFormula()));
            scaleMeasurement.setWater(waterMetric.getWater(getScaleUser(scaleMeasurement.getUserId()), scaleMeasurement));
        }

        settings = new MeasurementViewSettings(prefs, LBWMeasurementView.KEY);
        if (settings.isEnabled() && settings.isEstimationEnabled()) {
            EstimatedLBWMetric lbwMetric = EstimatedLBWMetric.getEstimatedMetric(
                    EstimatedLBWMetric.FORMULA.valueOf(settings.getEstimationFormula()));
            scaleMeasurement.setLbw(lbwMetric.getLBW(getScaleUser(scaleMeasurement.getUserId()), scaleMeasurement));
        }

        settings = new MeasurementViewSettings(prefs, FatMeasurementView.KEY);
        if (settings.isEnabled() && settings.isEstimationEnabled()) {
            EstimatedFatMetric fatMetric = EstimatedFatMetric.getEstimatedMetric(
                    EstimatedFatMetric.FORMULA.valueOf(settings.getEstimationFormula()));
            scaleMeasurement.setFat(fatMetric.getFat(getScaleUser(scaleMeasurement.getUserId()), scaleMeasurement));
        }

        if (measurementDAO.insert(scaleMeasurement) != -1) {
            ScaleUser scaleUser = getScaleUser(scaleMeasurement.getUserId());

            final java.text.DateFormat dateFormat = DateFormat.getDateFormat(context);
            final java.text.DateFormat timeFormat = DateFormat.getTimeFormat(context);
            final Date dateTime = scaleMeasurement.getDateTime();

            final Converters.WeightUnit unit = scaleUser.getScaleUnit();

            if (!silent) {
                String infoText = String.format(context.getString(R.string.info_new_data_added),
                        scaleMeasurement.getConvertedWeight(unit), unit.toString(),
                        dateFormat.format(dateTime) + " " + timeFormat.format(dateTime),
                        scaleUser.getUserName());
                Toast.makeText(context, infoText, Toast.LENGTH_LONG).show();
            }
            alarmHandler.entryChanged(context, scaleMeasurement);
            updateScaleData();
        } else {
            if (!silent) {
                Toast.makeText(context, context.getString(R.string.info_new_data_duplicated), Toast.LENGTH_LONG).show();
            }
        }

        return scaleMeasurement.getUserId();
    }

    private int getSmartUserAssignment(float weight, float range) {
        List<ScaleUser> scaleUsers = getScaleUserList();
        Map<Float, Integer> inRangeWeights = new TreeMap<>();

        for (int i = 0; i < scaleUsers.size(); i++) {
            List<ScaleMeasurement> scaleUserData = measurementDAO.getAll(scaleUsers.get(i).getId());

            float lastWeight = 0;

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
            return inRangeWeights.entrySet().iterator().next().getValue();
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // if ignore out of range preference is true don't add this data
        if (prefs.getBoolean("ignoreOutOfRange", false)) {
            return -1;
        }

        // return selected scale user id if not out of range preference is checked and weight is out of range of any user
        return getSelectedScaleUser().getId();
    }

    public void updateScaleData(ScaleMeasurement scaleMeasurement) {
        measurementDAO.update(scaleMeasurement);
        alarmHandler.entryChanged(context, scaleMeasurement);

        updateScaleData();
    }

    public void deleteScaleData(int id)
    {
        measurementDAO.delete(id);

        updateScaleData();
    }

    public String getFilenameFromUriMayThrow(Uri uri) {
        Cursor cursor = context.getContentResolver().query(
                uri, null, null, null, null);
        cursor.moveToFirst();
        return cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
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
            updateScaleData();
            Toast.makeText(context, context.getString(R.string.info_data_imported) + " " + filename, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(context, context.getString(R.string.error_importing) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } catch (ParseException e) {
            Toast.makeText(context, context.getString(R.string.error_importing) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public boolean exportData(Uri uri) {
        try {
            OutputStream output = context.getContentResolver().openOutputStream(uri);
            CsvHelper.exportTo(new OutputStreamWriter(output), scaleMeasurementList);
            return true;
        } catch (IOException e) {
            Toast.makeText(context, context.getResources().getString(R.string.error_exporting) + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        return false;
    }

    public void clearScaleData(int userId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putInt("uniqueNumber", 0x00).commit();
        measurementDAO.deleteAll(userId);

        updateScaleData();
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

    public List<ScaleMeasurement> getScaleDataOfMonth(int year, int month) {
        int selectedUserId = getSelectedScaleUserId();

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, month, 1, 0, 0, 0);
        endCalender.set(year, month, 1, 0, 0, 0);
        endCalender.add(Calendar.MONTH, 1);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public List<ScaleMeasurement> getScaleDataOfYear(int year) {
        int selectedUserId = getSelectedScaleUserId();

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        endCalender.set(year+1, Calendar.JANUARY, 1, 0, 0, 0);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public boolean connectToBluetoothDevice(String deviceName, String hwAddress, Handler callbackBtHandler) {
        Log.d("OpenScale", "Trying to connect to bluetooth device " + hwAddress
                + " (" + deviceName + ")");

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

        Log.d("OpenScale", "Disconnecting from bluetooth device");
        btDeviceDriver.disconnect(true);
        btDeviceDriver = null;

        return true;
    }

    public void registerFragment(FragmentUpdateListener fragment) {
        fragmentList.add(fragment);

        int selectedUserId = getSelectedScaleUserId();

        scaleMeasurementList = measurementDAO.getAll(selectedUserId);

        fragment.updateOnView(scaleMeasurementList);
    }

    public void unregisterFragment(FragmentUpdateListener fragment) {
        fragmentList.remove(fragment);
    }

    public void updateScaleData() {
        int selectedUserId = getSelectedScaleUserId();

        scaleMeasurementList = measurementDAO.getAll(selectedUserId);

        for (FragmentUpdateListener fragment : fragmentList) {
            if (fragment != null) {
                if (((Fragment)fragment).isAdded()) {
                    fragment.updateOnView(scaleMeasurementList);
                }
            }
        }
    }
}
