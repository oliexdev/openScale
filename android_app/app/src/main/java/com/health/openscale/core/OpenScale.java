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

import android.arch.persistence.room.Room;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.alarm.AlarmHandler;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
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
import com.health.openscale.gui.fragments.FragmentUpdateListener;
import com.j256.simplecsv.processor.CsvProcessor;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OpenScale {

    private static OpenScale instance;

    private AppDatabase appDB;
    private ScaleMeasurementDAO measurementDAO;
    private ScaleUserDAO userDAO;
    private ScaleDatabase scaleDB;
    private ScaleUserDatabase scaleUserDB;
    private List<ScaleMeasurement> scaleMeasurementList;

    private BluetoothCommunication btCom;
    private String btDeviceName;
    private AlarmHandler alarmHandler;

    private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    private Context context;

    private ArrayList<FragmentUpdateListener> fragmentList;

    private OpenScale(Context context) {
        this.context = context;
        scaleDB = new ScaleDatabase(context);
        scaleUserDB = new ScaleUserDatabase(context);
        alarmHandler = new AlarmHandler();
        btCom = null;
        fragmentList = new ArrayList<>();
        appDB = Room.databaseBuilder(context, AppDatabase.class, "openScale.db").allowMainThreadQueries().build();
        measurementDAO = appDB.measurementDAO();
        userDAO = appDB.userDAO();

        migrateSQLtoRoom();
        updateScaleData();
    }

    public static OpenScale getInstance(Context context) {
        if (instance == null) {
            instance = new OpenScale(context);
        }

        return instance;
    }

    private void migrateSQLtoRoom() {
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
    }

    public void addScaleUser(final ScaleUser user)
    {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                userDAO.insert(user);
            }
        });
    }

    public List<ScaleUser> getScaleUserList()
    {
        return userDAO.getAll();
    }

    public ScaleUser getScaleUser(int userId)
    {
        return userDAO.get(userId);
    }

    public ScaleUser getSelectedScaleUser()
    {
        ScaleUser scaleUser = new ScaleUser();

        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int selectedUserId  = prefs.getInt("selectedUserId", -1);

            if (selectedUserId == -1) {
                return scaleUser;
            }

            scaleUser = userDAO.get(selectedUserId);
        } catch (Exception e) {
            Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        return scaleUser;
    }

    public void deleteScaleUser(int id)
    {
        userDAO.delete(userDAO.get(id));
    }

    public void updateScaleUser(ScaleUser user)
    {
        userDAO.update(user);
    }

    public List<ScaleMeasurement> getScaleMeasurementList() {
        return scaleMeasurementList;
    }


    public ScaleMeasurement[] getTupleScaleData(int id)
    {
        ScaleMeasurement[]  tupleScaleData = new ScaleMeasurement[3];

        tupleScaleData[0] = measurementDAO.getPrevious(id, getSelectedScaleUser().getId());
        tupleScaleData[1] = measurementDAO.get(id);
        tupleScaleData[2] = measurementDAO.getNext(id, getSelectedScaleUser().getId());

        return tupleScaleData;
    }

    public int addScaleData(final ScaleMeasurement scaleMeasurement) {
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

        if (prefs.getBoolean("estimateWaterEnable", false)) {
            EstimatedWaterMetric waterMetric = EstimatedWaterMetric.getEstimatedMetric(EstimatedWaterMetric.FORMULA.valueOf(prefs.getString("estimateWaterFormula", "TBW_LEESONGKIM")));

            scaleMeasurement.setWater(waterMetric.getWater(getScaleUser(scaleMeasurement.getUserId()), scaleMeasurement));
        }

        if (prefs.getBoolean("estimateLBWEnable", false)) {
            EstimatedLBWMetric lbwMetric = EstimatedLBWMetric.getEstimatedMetric(EstimatedLBWMetric.FORMULA.valueOf(prefs.getString("estimateLBWFormula", "LBW_HUME")));

            scaleMeasurement.setLbw(lbwMetric.getLBW(getScaleUser(scaleMeasurement.getUserId()), scaleMeasurement));
        }

        if (prefs.getBoolean("estimateFatEnable", false)) {
            EstimatedFatMetric fatMetric = EstimatedFatMetric.getEstimatedMetric(EstimatedFatMetric.FORMULA.valueOf(prefs.getString("estimateFatFormula", "BF_GALLAGHER")));

            scaleMeasurement.setFat(fatMetric.getFat(getScaleUser(scaleMeasurement.getUserId()), scaleMeasurement));
        }

        if (measurementDAO.insert(scaleMeasurement) != -1) {
            ScaleUser scaleUser = getScaleUser(scaleMeasurement.getUserId());

            String infoText = String.format(context.getString(R.string.info_new_data_added), scaleMeasurement.getConvertedWeight(scaleUser.getScaleUnit()), scaleUser.UNIT_STRING[scaleUser.getScaleUnit()], dateTimeFormat.format(scaleMeasurement.getDateTime()), scaleUser.getUserName());
            Toast.makeText(context, infoText, Toast.LENGTH_LONG).show();
            alarmHandler.entryChanged(context, scaleMeasurement);
            updateScaleData();
        } else {
            Toast.makeText(context, context.getString(R.string.info_new_data_duplicated), Toast.LENGTH_LONG).show();
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

    public void importData(String filename) {
        CsvProcessor<ScaleMeasurement> csvProcessor = new CsvProcessor<ScaleMeasurement>(ScaleMeasurement.class).withHeaderValidation(true).withFlexibleOrder(true);
        File csvFile = new File(filename);

        try {
            List<ScaleMeasurement> csvScaleMeasurementList = csvProcessor.readAll(csvFile, null);

            measurementDAO.insertAll(csvScaleMeasurementList);
            updateScaleData();
            Toast.makeText(context, context.getString(R.string.info_data_imported) + " /sdcard" + filename, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(context, context.getString(R.string.error_importing) + e.getMessage(), Toast.LENGTH_SHORT).show();
        } catch (ParseException e) {
            Toast.makeText(context, context.getString(R.string.error_importing) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void exportData(String filename) {
        CsvProcessor<ScaleMeasurement> csvProcessor = new CsvProcessor<ScaleMeasurement>(ScaleMeasurement.class);

        File csvFile = new File(filename);

        try {
            csvProcessor.writeAll(csvFile, scaleMeasurementList, true);
            Toast.makeText(context, context.getString(R.string.info_data_exported) + " /sdcard" + filename, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(context, context.getResources().getString(R.string.error_exporting) + " " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void clearScaleData(int userId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putInt("uniqueNumber", 0x00).commit();
        measurementDAO.deleteAll(userId);

        updateScaleData();
    }

    public int[] getCountsOfMonth(int year) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, month, 1, 0, 0, 0);
        endCalender.set(year, month, 1, 0, 0, 0);
        endCalender.add(Calendar.MONTH, 1);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public List<ScaleMeasurement> getScaleDataOfYear(int year) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        endCalender.set(year+1, Calendar.JANUARY, 1, 0, 0, 0);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public boolean startSearchingForBluetooth(String deviceName, Handler callbackBtHandler) {
        Log.d("OpenScale", "Bluetooth Server started! I am searching for device ...");

        for (BluetoothCommunication.BT_DEVICE_ID btScaleID : BluetoothCommunication.BT_DEVICE_ID.values()) {
            btCom = BluetoothCommunication.getBtDevice(context, btScaleID);

            if (btCom.checkDeviceName(deviceName)) {
                btCom.registerCallbackHandler(callbackBtHandler);
                btDeviceName = deviceName;

                btCom.startSearching(btDeviceName);

                return true;
            }
        }

        return false;
    }

    public void stopSearchingForBluetooth() {
        if (btCom != null) {
            btCom.stopSearching();
            Log.d("OpenScale", "Bluetooth Server explicit stopped!");
        }
    }

    public void registerFragment(FragmentUpdateListener fragment) {
        fragmentList.add(fragment);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        scaleMeasurementList = measurementDAO.getAll(selectedUserId);

        fragment.updateOnView(scaleMeasurementList);
    }

    public void updateScaleData()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

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
