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
package com.health.openscale.core.bluetooth;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;

import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothCustomOpenScale extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private final UUID WEIGHT_MEASUREMENT_CHARACTERISTIC = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"); // Bluetooth Modul HM-10
    private final UUID WEIGHT_MEASUREMENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private String string_data = new String();

    public BluetoothCustomOpenScale(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Custom openScale";
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                setNotificationOn(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC, WEIGHT_MEASUREMENT_CONFIG);
                break;
            case 1:
                Calendar cal = Calendar.getInstance();

                String date_time = String.format(Locale.US, "2%1d,%1d,%1d,%1d,%1d,%1d,",
                        cal.get(Calendar.YEAR)-2000,
                        cal.get(Calendar.MONTH) + 1,
                        cal.get(Calendar.DAY_OF_MONTH),
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        cal.get(Calendar.SECOND));

                writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC, date_time.getBytes());
                break;
            default:
                return false;
        }

        return true;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        return false;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
        return false;
    }

    public void clearEEPROM()
    {
        byte[] cmd = {(byte)'9'};
        writeBytes(WEIGHT_MEASUREMENT_SERVICE, WEIGHT_MEASUREMENT_CHARACTERISTIC, cmd);
    }

    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        final byte[] data = gattCharacteristic.getValue();

        if (data != null) {
            for (byte character : data) {
                string_data += (char) (character & 0xFF);

                if (character == '\n') {
                    parseBtString(string_data);
                    string_data = new String();
                }
            }
        }
    }

    private void parseBtString(String btString) {
        btString = btString.substring(0, btString.length() - 1); // delete newline '\n' of the string

        if (btString.charAt(0) != '$' && btString.charAt(2) != '$') {
            setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Parse error of bluetooth string. String has not a valid format: " + btString);
        }

        String btMsg = btString.substring(3, btString.length()); // message string

        switch (btString.charAt(1)) {
            case 'I':
                Timber.d("MCU Information: %s", btMsg);
                break;
            case 'E':
                Timber.e("MCU Error: %s", btMsg);
                break;
            case 'S':
                Timber.d("MCU stored data size: %s", btMsg);
                break;
            case 'F':
                Timber.d("All data sent");
                clearEEPROM();
                disconnect(false);
                break;
            case 'D':
                String[] csvField = btMsg.split(",");

                try {
                    int checksum = 0;

                    checksum ^= Integer.parseInt(csvField[0]);
                    checksum ^= Integer.parseInt(csvField[1]);
                    checksum ^= Integer.parseInt(csvField[2]);
                    checksum ^= Integer.parseInt(csvField[3]);
                    checksum ^= Integer.parseInt(csvField[4]);
                    checksum ^= Integer.parseInt(csvField[5]);
                    checksum ^= (int) Float.parseFloat(csvField[6]);
                    checksum ^= (int) Float.parseFloat(csvField[7]);
                    checksum ^= (int) Float.parseFloat(csvField[8]);
                    checksum ^= (int) Float.parseFloat(csvField[9]);

                    int btChecksum = Integer.parseInt(csvField[10]);

                    if (checksum == btChecksum) {
                        ScaleMeasurement scaleBtData = new ScaleMeasurement();

                        String date_string = csvField[1] + "/" + csvField[2] + "/" + csvField[3] + "/" + csvField[4] + "/" + csvField[5];
                        scaleBtData.setDateTime(new SimpleDateFormat("yyyy/MM/dd/HH/mm").parse(date_string));

                        scaleBtData.setWeight(Float.parseFloat(csvField[6]));
                        scaleBtData.setFat(Float.parseFloat(csvField[7]));
                        scaleBtData.setWater(Float.parseFloat(csvField[8]));
                        scaleBtData.setMuscle(Float.parseFloat(csvField[9]));

                        addScaleData(scaleBtData);
                    } else {
                        setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Error calculated checksum (" + checksum + ") and received checksum (" + btChecksum + ") is different");
                    }
                } catch (ParseException e) {
                    setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Error while decoding bluetooth date string (" + e.getMessage() + ")");
                } catch (NumberFormatException e) {
                    setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Error while decoding a number of bluetooth string (" + e.getMessage() + ")");
                }
                break;
            default:
                setBtStatus(BT_STATUS_CODE.BT_UNEXPECTED_ERROR, "Error unknown MCU command : " + btString);
        }
        }
}
