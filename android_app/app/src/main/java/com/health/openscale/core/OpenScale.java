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
import android.os.Message;
import android.preference.PreferenceManager;
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

	public void addScaleData(int user_id, String date_time, float weight, float fat,
			float water, float muscle, float waist, float hip, String comment) {
		ScaleData scaleData = new ScaleData();

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

		scaleDB.insertEntry(scaleData);

        updateScaleData();
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

    public boolean clearBtScaleData() {
        if (btCom == null)
            return false;

        return btCom.sendBtData("9");
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

	public void startBluetoothServer(String deviceName) {
		Log.d("OpenScale", "Bluetooth Server started! I am searching for device ...");

		btCom = new BluetoothCommunication(btHandler);
		btDeviceName = deviceName;

		try {
			btCom.findBT(btDeviceName);
			btCom.start();
		} catch (IOException e) {
            btCom = null;
			Log.e("OpenScale", "Error " + e.getMessage());
		}
	}

	public void stopBluetoothServer() {
		if (btCom != null) {
			btCom.cancel();
            Log.d("OpenScale", "Bluetooth Server stopped!");
        }
	}

	private final Handler btHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {

			switch (msg.what) {
			case BluetoothCommunication.BT_MESSAGE_READ:
				String line = (String) msg.obj;

				parseBtString(line);
				break;
			case BluetoothCommunication.BT_SOCKET_CLOSED:
				updateScaleData();
				
				Log.d("OpenScale", "Socket closed! Restarting socket ");

				startBluetoothServer(btDeviceName);
				break;
			case BluetoothCommunication.BT_NO_ADAPTER:
				Log.e("OpenScale", "No bluetooth adapter found!");
				break;
			}
		}
	};

	public void parseBtString(String btString) {
		btString = btString.substring(0, btString.length() - 1); // delete newline '\n' of the string
		
		if (btString.charAt(0) != '$' && btString.charAt(2) != '$') {
			Log.e("OpenScale", "Parse error of bluetooth string. String has not a valid format");
			return;
		}

		String btMsg = btString.substring(3, btString.length()); // message string
		
		switch (btString.charAt(1)) {
			case 'I':
				Log.i("OpenScale", "MCU Information: " + btMsg);
				break;
			case 'E':
				Log.e("OpenScale", "MCU Error: " + btMsg);
				break;
			case 'S':
				Log.i("OpenScale", "MCU stored data size: " + btMsg);
				break;
			case 'D':
				ScaleData scaleBtData = new ScaleData();

				String[] csvField = btMsg.split(",");

				try {
					int checksum = 0;

                    checksum ^= Integer.parseInt(csvField[0]);
					checksum ^= Integer.parseInt(csvField[1]);
					checksum ^= Integer.parseInt(csvField[2]);
					checksum ^= Integer.parseInt(csvField[3]);
					checksum ^= Integer.parseInt(csvField[4]);
					checksum ^= Integer.parseInt(csvField[5]);
					checksum ^= (int)Float.parseFloat(csvField[6]);
					checksum ^= (int)Float.parseFloat(csvField[7]);
					checksum ^= (int)Float.parseFloat(csvField[8]);
					checksum ^= (int)Float.parseFloat(csvField[9]);

					int btChecksum = Integer.parseInt(csvField[10]);

					if (checksum == btChecksum) {
                        scaleBtData.id = -1;
						scaleBtData.user_id = Integer.parseInt(csvField[0]);
						String date_string = csvField[1] + "/" + csvField[2] + "/" + csvField[3] + "/" + csvField[4] + "/" + csvField[5];
						scaleBtData.date_time = new SimpleDateFormat("yyyy/MM/dd/HH/mm").parse(date_string);

						scaleBtData.weight = Float.parseFloat(csvField[6]);
						scaleBtData.fat = Float.parseFloat(csvField[7]);
						scaleBtData.water = Float.parseFloat(csvField[8]);
						scaleBtData.muscle = Float.parseFloat(csvField[9]);

						scaleDB.insertEntry(scaleBtData);
					} else {
						Log.e("OpenScale", "Error calculated checksum (" + checksum + ") and received checksum (" + btChecksum + ") is different");
					}
				} catch (ParseException e) {
					Log.e("OpenScale", "Error while decoding bluetooth date string (" + e.getMessage() + ")");
				} catch (NumberFormatException e) {
					Log.e("OpenScale", "Error while decoding a number of bluetooth string (" + e.getMessage() + ")");
				}
				break;
			default:
				Log.e("OpenScale", "Error unknown MCU command");
				break;
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
                fragment.updateOnView(scaleDataList);
            }
        }
    }
}
