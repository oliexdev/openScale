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

import android.content.Context;
import android.content.SharedPreferences;
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
import com.health.openscale.core.database.ScaleDatabase;
import com.health.openscale.core.database.ScaleUserDatabase;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.fragments.FragmentUpdateListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

public class OpenScale {

    private static OpenScale instance;

    private ScaleDatabase scaleDB;
    private ScaleUserDatabase scaleUserDB;
    private ArrayList<ScaleData> scaleDataList;

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

        updateScaleData();
    }

    public static OpenScale getInstance(Context context) {
        if (instance == null) {
            instance = new OpenScale(context);
        }

        return instance;
    }

    public void addScaleUser(String name, Date birthday, int body_height, int scale_unit, int gender, float initial_weight, float goal_weight, Date goal_date)
    {
        ScaleUser scaleUser = new ScaleUser();

        scaleUser.user_name = name;
        scaleUser.birthday = birthday;
        scaleUser.body_height = body_height;
        scaleUser.scale_unit = scale_unit;
        scaleUser.gender = gender;
        scaleUser.setConvertedInitialWeight(initial_weight);
        scaleUser.goal_weight = goal_weight;
        scaleUser.goal_date = goal_date;

        scaleUserDB.insertEntry(scaleUser);
    }

    public ArrayList<ScaleUser> getScaleUserList()
    {
        return scaleUserDB.getScaleUserList();
    }

    public ScaleUser getScaleUser(int userId)
    {
        return scaleUserDB.getScaleUser(userId);
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

            scaleUser = scaleUserDB.getScaleUser(selectedUserId);
        } catch (Exception e) {
            Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        return scaleUser;
    }

    public void deleteScaleUser(int id)
    {
        scaleUserDB.deleteEntry(id);
    }

    public void updateScaleUser(int id, String name, Date birthday, int body_height, int scale_unit, int gender, float initial_weight, float goal_weight, Date goal_date)
    {
        ScaleUser scaleUser = new ScaleUser();

        scaleUser.id = id;
        scaleUser.user_name = name;
        scaleUser.birthday = birthday;
        scaleUser.body_height = body_height;
        scaleUser.scale_unit = scale_unit;
        scaleUser.gender = gender;
        scaleUser.setConvertedInitialWeight(initial_weight);
        scaleUser.goal_weight = goal_weight;
        scaleUser.goal_date = goal_date;

        scaleUserDB.updateScaleUser(scaleUser);
    }


    public ArrayList<ScaleData> getScaleDataList() {
        return scaleDataList;
    }


    public ScaleData[] getTupleScaleData(long id)
    {
        return scaleDB.getTupleDataEntry(getSelectedScaleUser().id, id);
    }

    public int addScaleData(ScaleData scaleData) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (scaleData.getUserId() == -1) {
            if (prefs.getBoolean("smartUserAssign", false)) {
                scaleData.setUserId(getSmartUserAssignment(scaleData.getWeight(), 15.0f));
            } else {
                scaleData.setUserId(getSelectedScaleUser().id);
            }

            // don't add scale data if no user is selected
            if (scaleData.getUserId() == -1) {
                return -1;
            }
        }

        if (prefs.getBoolean("estimateWaterEnable", false)) {
            EstimatedWaterMetric waterMetric = EstimatedWaterMetric.getEstimatedMetric(EstimatedWaterMetric.FORMULA.valueOf(prefs.getString("estimateWaterFormula", "TBW_LEESONGKIM")));

            scaleData.setWater(waterMetric.getWater(getScaleUser(scaleData.getUserId()), scaleData));
        }

        if (prefs.getBoolean("estimateLBWEnable", false)) {
            EstimatedLBWMetric lbwMetric = EstimatedLBWMetric.getEstimatedMetric(EstimatedLBWMetric.FORMULA.valueOf(prefs.getString("estimateLBWFormula", "LBW_HUME")));

            scaleData.setLBW(lbwMetric.getLBW(getScaleUser(scaleData.getUserId()), scaleData));
        }

        if (prefs.getBoolean("estimateFatEnable", false)) {
            EstimatedFatMetric fatMetric = EstimatedFatMetric.getEstimatedMetric(EstimatedFatMetric.FORMULA.valueOf(prefs.getString("estimateFatFormula", "BF_GALLAGHER")));

            scaleData.setFat(fatMetric.getFat(getScaleUser(scaleData.getUserId()), scaleData));
        }

        if (scaleDB.insertEntry(scaleData)) {
            ScaleUser scaleUser = getScaleUser(scaleData.getUserId());

            String infoText = String.format(context.getString(R.string.info_new_data_added), scaleData.getConvertedWeight(scaleUser.scale_unit), scaleUser.UNIT_STRING[scaleUser.scale_unit], dateTimeFormat.format(scaleData.getDateTime()), scaleUser.user_name);
            Toast.makeText(context, infoText, Toast.LENGTH_LONG).show();
            alarmHandler.entryChanged(context, scaleData);
            updateScaleData();
        }

        return scaleData.getUserId();
    }

    private int getSmartUserAssignment(float weight, float range) {
        ArrayList<ScaleUser> scaleUser = getScaleUserList();
        Map<Float, Integer> inRangeWeights = new TreeMap<>();

        for (int i = 0; i < scaleUser.size(); i++) {
            ArrayList<ScaleData> scaleUserData = scaleDB.getScaleDataList(scaleUser.get(i).id);

            float lastWeight = 0;

            if (scaleUserData.size() > 0) {
                lastWeight = scaleUserData.get(0).getWeight();
            } else {
                lastWeight = scaleUser.get(i).getInitialWeight();
            }

            if ((lastWeight - range) <= weight && (lastWeight + range) >= weight) {
                inRangeWeights.put(Math.abs(lastWeight - weight), scaleUser.get(i).id);
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
        return getSelectedScaleUser().id;
    }

    public void updateScaleData(ScaleData scaleData) {
        scaleDB.updateEntry(scaleData.getId(), scaleData);
        alarmHandler.entryChanged(context, scaleData);

        updateScaleData();
    }

    public void deleteScaleData(long id)
    {
        scaleDB.deleteEntry(id);

        updateScaleData();
    }

    public void importData(String filename) throws IOException {
        File file = new File(filename);

        FileInputStream inputStream = new FileInputStream(file);

        InputStreamReader inputReader = new InputStreamReader(inputStream);
        BufferedReader csvReader = new BufferedReader(inputReader);

        String line = csvReader.readLine();

        try {
            while (line != null) {
                String csvField[] = line.split(",", -1);

                if (csvField.length < 9) {
                    throw new IOException("Can't parse CSV file. Field length is wrong.");
                }

                ScaleData newScaleData = new ScaleData();

                newScaleData.setDateTime(dateTimeFormat.parse(csvField[0]));
                newScaleData.setWeight(Float.parseFloat(csvField[1]));
                newScaleData.setFat(Float.parseFloat(csvField[2]));
                newScaleData.setWater(Float.parseFloat(csvField[3]));
                newScaleData.setMuscle(Float.parseFloat(csvField[4]));
                newScaleData.setLBW(Float.parseFloat(csvField[5]));
                newScaleData.setBone(Float.parseFloat(csvField[6]));
                newScaleData.setWaist(Float.parseFloat(csvField[7]));
                newScaleData.setHip(Float.parseFloat(csvField[8]));
                newScaleData.setComment(csvField[9]);

                newScaleData.setUserId(getSelectedScaleUser().id);

                scaleDB.insertEntry(newScaleData);

                line = csvReader.readLine();
            }

        } catch (ParseException e) {
            throw new IOException("Can't parse date format. Please set the date time format as <dd.MM.yyyy HH:mm> (e.g. 31.10.2014 05:23)");
        } catch (NumberFormatException e) {
            throw new IOException("Can't parse float number (" + e.getMessage()+")");
        } catch (ArrayIndexOutOfBoundsException e) {
		    throw new IOException("Can't parse format column number mismatch");
        }

        updateScaleData();

        csvReader.close();
        inputReader.close();
    }

    public void exportData(String filename) throws IOException {
        File file = new File(filename);
        file.createNewFile();

        FileOutputStream outputStream = new FileOutputStream(file);

        OutputStreamWriter csvWriter = new OutputStreamWriter(outputStream);

        for (ScaleData scaleData : scaleDataList) {
            csvWriter.append(dateTimeFormat.format(scaleData.getDateTime()) + ",");
            csvWriter.append(Float.toString(scaleData.getWeight()) + ",");
            csvWriter.append(Float.toString(scaleData.getFat()) + ",");
            csvWriter.append(Float.toString(scaleData.getWater()) + ",");
            csvWriter.append(Float.toString(scaleData.getMuscle()) + ",");
            csvWriter.append(Float.toString(scaleData.getLBW()) + ",");
            csvWriter.append(Float.toString(scaleData.getBone()) + ",");
            csvWriter.append(Float.toString(scaleData.getWaist()) + ",");
            csvWriter.append(Float.toString(scaleData.getHip()) + ",");
            if (!scaleData.getComment().isEmpty()) {
                csvWriter.append(scaleData.getComment());
            }

            csvWriter.append("\n");
        }

        csvWriter.close();
        outputStream.close();
    }

    public void clearScaleData(int userId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putInt("uniqueNumber", 0x00).commit();
        scaleDB.clearScaleData(userId);

        updateScaleData();
    }

    public int[] getCountsOfMonth(int year) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        return scaleDB.getCountsOfAllMonth(selectedUserId, year);
    }

    public ArrayList<ScaleData> getScaleDataOfMonth(int year, int month) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        return scaleDB.getScaleDataOfMonth(selectedUserId, year, month);
    }

    public ArrayList<ScaleData> getScaleDataOfYear(int year) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        return scaleDB.getScaleDataOfYear(selectedUserId, year);
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

        scaleDataList = scaleDB.getScaleDataList(selectedUserId);

        fragment.updateOnView(scaleDataList);
    }

    public void updateScaleData()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        scaleDataList = scaleDB.getScaleDataList(selectedUserId);

        for (FragmentUpdateListener fragment : fragmentList) {
            if (fragment != null) {
                if (((Fragment)fragment).isAdded()) {
                    fragment.updateOnView(scaleDataList);
                }
            }
        }
    }
}
