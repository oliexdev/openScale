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
import android.os.Handler;
import android.os.Message;
import android.util.Log;

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
	private ArrayList<ScaleData> scaleDBEntries;

	private BluetoothCommunication btCom;
	private String btDeviceName;

	private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");

	private OpenScale(Context con) {
		scaleDB = new ScaleDatabase(con);
		scaleDBEntries = scaleDB.getAllDBEntries();
	}

	public static OpenScale getInstance(Context con) {
		if (instance == null) {
			instance = new OpenScale(con);
		}

		return instance;
	}

	public ArrayList<ScaleData> getScaleDBEntries() {
		return scaleDBEntries;
	}

	public void addScaleData(int user_id, String date_time, float weight, float fat,
			float water, float muscle) {
		ScaleData scaleData = new ScaleData();

		try {
            scaleData.user_id = user_id;
			scaleData.date_time = dateTimeFormat.parse(date_time);
			scaleData.weight = weight;
			scaleData.fat = fat;
			scaleData.water = water;
			scaleData.muscle = muscle;
		} catch (ParseException e) {
			Log.e("OpenScale", "Can't parse date time string while adding to the database");
		}

		scaleDB.insertEntry(scaleData);

		scaleDBEntries = scaleDB.getAllDBEntries();
	}

    public void deleteScaleData(long id)
    {
        scaleDB.deleteEntry(id);

        scaleDBEntries = scaleDB.getAllDBEntries();
    }

	public void importData(String filename) throws IOException {
		File file = new File(filename);

		FileInputStream inputStream = new FileInputStream(file);

		InputStreamReader inputReader = new InputStreamReader(inputStream);
		BufferedReader csvReader = new BufferedReader(inputReader);

		String line = csvReader.readLine();

		try {
			while (line != null) {
				String csvField[] = line.split(",");

				ScaleData newScaleData = new ScaleData();

                newScaleData.user_id = Integer.parseInt(csvField[0]);
				newScaleData.date_time = dateTimeFormat.parse(csvField[1]);
				newScaleData.weight = Float.parseFloat(csvField[2]);
				newScaleData.fat = Float.parseFloat(csvField[3]);
				newScaleData.water = Float.parseFloat(csvField[4]);
				newScaleData.muscle = Float.parseFloat(csvField[5]);

				scaleDB.insertEntry(newScaleData);

				line = csvReader.readLine();
			}

		} catch (ParseException e) {
			throw new IOException("Can't parse date format. Please set the date time format as <dd.MM.yyyy HH:mm> (e.g. 31.10.2014 05:23)");
		}

		scaleDBEntries = scaleDB.getAllDBEntries();

		csvReader.close();
		inputReader.close();
	}

	public void exportData(String filename) throws IOException {
		File file = new File(filename);
		file.createNewFile();

		FileOutputStream outputStream = new FileOutputStream(file);

		OutputStreamWriter csvWriter = new OutputStreamWriter(outputStream);

		for (ScaleData scaleData : scaleDBEntries) {
            csvWriter.append(Integer.toString(scaleData.user_id) + ",");
			csvWriter.append(dateTimeFormat.format(scaleData.date_time) + ",");
			csvWriter.append(Float.toString(scaleData.weight) + ",");
			csvWriter.append(Float.toString(scaleData.fat) + ",");
			csvWriter.append(Float.toString(scaleData.water) + ",");
			csvWriter.append(Float.toString(scaleData.muscle));
			csvWriter.append("\n");
		}

		csvWriter.close();
		outputStream.close();
	}

	public void deleteAllDBEntries() {
		scaleDB.deleteAllEntries();

		scaleDBEntries = scaleDB.getAllDBEntries();
	}

    public int[] getCountsOfMonth(int year) {
        return scaleDB.getCountsOfAllMonth(year);
    }

    public ArrayList<ScaleData> getAllDataOfMonth(int year, int month) {
        return scaleDB.getAllDBEntriesOfMonth(year, month);
    }

    public float getMaxValueOfDBEntries(int year, int month) {
        return scaleDB.getMaxValueOfDBEntries(year, month);
    }

	public void startBluetoothServer(String deviceName) {
		Log.d("OpenScale", "Bluetooth Server started! I am searching for device ...");

		btCom = new BluetoothCommunication(btHandler);
		btDeviceName = deviceName;

		try {
			btCom.findBT(btDeviceName);
			btCom.start();
		} catch (IOException e) {
			Log.e("OpenScale", "Error " + e.getMessage());
		}
	}

	public void stopBluetoothServer() {
		Log.d("OpenScale", "Bluetooth Server stopped!");

		if (btCom != null) {
			btCom.cancel();
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
				scaleDBEntries = scaleDB.getAllDBEntries();
				
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
}
