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

import com.health.openscale.gui.FragmentUpdateListener;

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
import java.util.Map;
import java.util.TreeMap;

import static com.health.openscale.core.BluetoothCommunication.BT_MI_SCALE;
import static com.health.openscale.core.BluetoothCommunication.BT_OPEN_SCALE;

public class OpenScale {

	private static OpenScale instance;

	private ScaleDatabase scaleDB;
    private ScaleUserDatabase scaleUserDB;
	private ArrayList<ScaleData> scaleDataList;

	private BluetoothCommunication btCom;
	private String btDeviceName;

	private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    private Context context;

    private ArrayList<FragmentUpdateListener> fragmentList;

	private OpenScale(Context con) {
        context = con;
		scaleDB = new ScaleDatabase(context);
        scaleUserDB = new ScaleUserDatabase(context);
        btCom = null;
        fragmentList = new ArrayList<>();

        updateScaleData();
	}

	public static OpenScale getInstance(Context con) {
		if (instance == null) {
			instance = new OpenScale(con);
		}

		return instance;
	}

    public void addScaleUser(String name, String birthday, int body_height, int scale_unit, int gender, float goal_weight, String goal_date)
    {
        ScaleUser scaleUser = new ScaleUser();

        try {
            scaleUser.user_name = name;
            scaleUser.birthday = new SimpleDateFormat("dd.MM.yyyy").parse(birthday);
            scaleUser.body_height = body_height;
            scaleUser.scale_unit = scale_unit;
            scaleUser.gender = gender;
            scaleUser.goal_weight = goal_weight;
            scaleUser.goal_date = new SimpleDateFormat("dd.MM.yyyy").parse(goal_date);

        } catch (ParseException e) {
            Log.e("OpenScale", "Can't parse date time string while adding to the database");
        }

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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        if (selectedUserId == -1) {
            ScaleUser scaleUser = new ScaleUser();

            return scaleUser;
        }

        return scaleUserDB.getScaleUser(selectedUserId);
    }

    public void deleteScaleUser(int id)
    {
        scaleUserDB.deleteEntry(id);
    }

    public void updateScaleUser(int id, String name, String birthday, int body_height, int scale_unit, int gender, float goal_weight, String goal_date)
    {
        ScaleUser scaleUser = new ScaleUser();

        try {
            scaleUser.id = id;
            scaleUser.user_name = name;
            scaleUser.birthday = new SimpleDateFormat("dd.MM.yyyy").parse(birthday);
            scaleUser.body_height = body_height;
            scaleUser.scale_unit = scale_unit;
            scaleUser.gender = gender;
            scaleUser.goal_weight = goal_weight;
            scaleUser.goal_date = new SimpleDateFormat("dd.MM.yyyy").parse(goal_date);
        } catch (ParseException e) {
            Log.e("OpenScale", "Can't parse date time string while adding to the database");
        }

        scaleUserDB.updateScaleUser(scaleUser);
    }


	public ArrayList<ScaleData> getScaleDataList() {
		return scaleDataList;
	}


    public ScaleData getScaleData(long id)
    {
        return scaleDB.getDataEntry(id);
    }

    public int addScaleData(ScaleData scaleData) {
        return addScaleData(scaleData.user_id, dateTimeFormat.format(scaleData.date_time).toString(), scaleData.weight, scaleData.fat,
                scaleData.water, scaleData.muscle, scaleData.waist, scaleData.hip, scaleData.comment);
    }

	public int addScaleData(int user_id, String date_time, float weight, float fat,
			float water, float muscle, float waist, float hip, String comment) {
		ScaleData scaleData = new ScaleData();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (user_id == -1) {
            if (prefs.getBoolean("smartUserAssign", false)) {
                user_id = getSmartUserAssignment(weight, 15.0f);
            } else {
                user_id = getSelectedScaleUser().id;
            }

            if (user_id == -1) {
                return -1;
            }
        }

		try {
            scaleData.user_id = user_id;
			scaleData.date_time = dateTimeFormat.parse(date_time);
			scaleData.weight = weight;
			scaleData.fat = fat;
			scaleData.water = water;
			scaleData.muscle = muscle;
            scaleData.waist = waist;
            scaleData.hip = hip;
            scaleData.comment = comment;
		} catch (ParseException e) {
			Log.e("OpenScale", "Can't parse date time string while adding to the database");
		}

		if (scaleDB.insertEntry(scaleData)) {
            updateScaleData();
        }

        return user_id;
	}

    private int getSmartUserAssignment(float weight, float range) {
        ArrayList<ScaleUser> scaleUser = getScaleUserList();
        Map<Float, Integer> inRangeWeights = new TreeMap<>();

        for (int i = 0; i < scaleUser.size(); i++) {
            ArrayList<ScaleData> scaleUserData = scaleDB.getScaleDataList(scaleUser.get(i).id);

            if (scaleUserData.size() > 0) {
                float lastWeight = scaleUserData.get(0).weight;

                if ((lastWeight - range) <= weight && (lastWeight + range) >= weight) {
                    inRangeWeights.put(Math.abs(lastWeight - weight), scaleUser.get(i).id);
                }
            }
        }

        if (inRangeWeights.size() > 0) {
            // return the user id which is nearest to the weight (first element of the tree map)
            return inRangeWeights.entrySet().iterator().next().getValue();
        }

        return getSelectedScaleUser().id;
    }

    public void updateScaleData(long id, String date_time, float weight, float fat, float water, float muscle, float waist, float hip, String comment) {
        ScaleData scaleData = new ScaleData();

        try {
            scaleData.date_time = dateTimeFormat.parse(date_time);
            scaleData.weight = weight;
            scaleData.fat = fat;
            scaleData.water = water;
            scaleData.muscle = muscle;
            scaleData.waist = waist;
            scaleData.hip = hip;
            scaleData.comment = comment;
        } catch (ParseException e) {
            Log.e("OpenScale", "Can't parse date time string while adding to the database");
        }

        scaleDB.updateEntry(id, scaleData);

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

                if (csvField.length < 8) {
                    throw new IOException("Can't parse CSV file. Field length is wrong.");
                }

				ScaleData newScaleData = new ScaleData();

				newScaleData.date_time = dateTimeFormat.parse(csvField[0]);
				newScaleData.weight = Float.parseFloat(csvField[1]);
				newScaleData.fat = Float.parseFloat(csvField[2]);
				newScaleData.water = Float.parseFloat(csvField[3]);
				newScaleData.muscle = Float.parseFloat(csvField[4]);
                newScaleData.waist = Float.parseFloat(csvField[5]);
                newScaleData.hip = Float.parseFloat(csvField[6]);
                newScaleData.comment = csvField[7];

                newScaleData.user_id = getSelectedScaleUser().id;

				scaleDB.insertEntry(newScaleData);

				line = csvReader.readLine();
			}

		} catch (ParseException e) {
			throw new IOException("Can't parse date format. Please set the date time format as <dd.MM.yyyy HH:mm> (e.g. 31.10.2014 05:23)");
		} catch (NumberFormatException e) {
            throw new IOException("Can't parse float number (" + e.getMessage()+")");
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
			csvWriter.append(dateTimeFormat.format(scaleData.date_time) + ",");
			csvWriter.append(Float.toString(scaleData.weight) + ",");
			csvWriter.append(Float.toString(scaleData.fat) + ",");
			csvWriter.append(Float.toString(scaleData.water) + ",");
			csvWriter.append(Float.toString(scaleData.muscle) + ",");
            csvWriter.append(Float.toString(scaleData.waist) + ",");
            csvWriter.append(Float.toString(scaleData.hip) + ",");
            if (!scaleData.comment.isEmpty()) {
                csvWriter.append(scaleData.comment);
            }

			csvWriter.append("\n");
		}


		csvWriter.close();
		outputStream.close();
	}

	public void clearScaleData(int userId) {
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

	public void startSearchingForBluetooth(int btScales, String deviceName, Handler callbackBtHandler) {
		Log.d("OpenScale", "Bluetooth Server started! I am searching for device ...");

        switch (btScales) {
            case BT_MI_SCALE:
                btCom = new BluetoothMiScale(context);
                break;
            case BT_OPEN_SCALE:
                btCom = new BluetoothCustomOpenScale();
                break;
            default:
                Log.e("OpenScale", "No valid scale type selected");
                return;
        }

		btCom.registerCallbackHandler(callbackBtHandler);
		btDeviceName = deviceName;

        btCom.startSearching(btDeviceName);
	}

	public void stopSearchingForBluetooth() {
		if (btCom != null) {
            btCom.stopSearching();
            Log.d("OpenScale", "Bluetooth Server explicit stopped!");
		}
	}

    public void registerFragment(FragmentUpdateListener fragment) {
        fragmentList.add(fragment);
        fragment.updateOnView(scaleDataList);
    }

    public void updateScaleData()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        scaleDataList = scaleDB.getScaleDataList(selectedUserId);

        for(FragmentUpdateListener fragment : fragmentList) {
            if (fragment != null) {
                if (((Fragment)fragment).isAdded()) {
                    fragment.updateOnView(scaleDataList);
                }
            }
        }
    }
}
