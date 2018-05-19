/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*                2017  DreamNik <dreamnik@mail.ru>
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

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.Calendar;
import java.util.Date;
import java.util.UUID;



public class BluetoothMGB extends BluetoothCommunication {

    private static final UUID uuid_service   =  UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb");
    private static final UUID uuid_char_cfg  =  UUID.fromString("0000ffb1-0000-1000-8000-00805f9b34fb");
    private static final UUID uuid_char_ctrl =  UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb");
    private static final UUID uuid_desc_ctrl =  UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");


    private Calendar  now;
    private ScaleUser user;
    private ScaleMeasurement measurement;
    private byte[]    packet_buf;
    private int       packet_pos;



    private int popInt() {
        return packet_buf[packet_pos++] & 0xFF;
    }


    private float popFloat() {
        int r = popInt();
        r = popInt() | (r<<8);
        return r * 0.1f;
    }


    private void writeCfg(int b2, int b3, int b4, int b5) {
        byte[] buf  = new byte[8];
        buf[0] = (byte)0xAC;
        buf[1] = (byte)0x02;
        buf[2] = (byte)b2;
        buf[3] = (byte)b3;
        buf[4] = (byte)b4;
        buf[5] = (byte)b5;
        buf[6] = (byte)0xCC;
        buf[7] = (byte)((buf[2] + buf[3] + buf[4] + buf[5] + buf[6]) & 0xFF);

        writeBytes(uuid_service, uuid_char_cfg, buf);
    }


    public BluetoothMGB(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "SWAN";
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                setNotificationOn(uuid_service, uuid_char_ctrl, uuid_desc_ctrl);
                now  = Calendar.getInstance();
                user = OpenScale.getInstance().getSelectedScaleUser();
                break;

            case 1:
                writeCfg(0xF7, 0, 0, 0);
                break;

            case 2:
                writeCfg(0xFA, 0, 0, 0);
                break;

            case 3:
                writeCfg(0xFB, (user.getGender().isMale() ? 1 : 2), user.getAge(), (int)user.getBodyHeight());
                break;

            case 4:
                writeCfg(0xFD, now.get(Calendar.YEAR) - 2000, now.get(Calendar.MONTH) - Calendar.JANUARY + 1, now.get(Calendar.DAY_OF_MONTH));
                break;

            case 5:
                writeCfg(0xFC, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND));
                break;

            case 6:
                writeCfg(0xFE, 6, user.getScaleUnit().toInt(), 0);
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


    @Override
    public void onBluetoothDataChange(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic gattCharacteristic) {
        packet_buf = gattCharacteristic.getValue();
        packet_pos = 0;

        if (packet_buf == null || packet_buf.length <= 0) {
            return;
        }

        if (packet_buf.length != 20) {
            return;
        }

        int hdr_1 = popInt();
        int hdr_2 = popInt();
        int hdr_3 = popInt();

        if (hdr_1 == 0xAC && hdr_2 == 0x02 && hdr_3 == 0xFF) {
            measurement = new ScaleMeasurement();

            popInt(); //unknown =00
            popInt(); //unknown =02
            popInt(); //unknown =21

            popInt(); //Year
            popInt(); //Month
            popInt(); //Day
            popInt(); //Hour
            popInt(); //Minute
            popInt(); //Second

            measurement.setDateTime(new Date());

            measurement.setWeight(popFloat());

            popFloat(); //BMI

            measurement.setFat(popFloat());

            popInt(); //unknown =00
            popInt(); //unknown =00

        } else if (hdr_1 == 0x01 && hdr_2 == 0x00) {
            measurement.setMuscle(popFloat());

            popFloat(); //BMR

            measurement.setBone(popFloat());

            measurement.setWater(popFloat());

            popInt();  // Age

            popFloat();//  protein rate

            popInt(); // unknown =00
            popInt(); // unknown =01
            popInt(); // unknown =1b
            popInt(); // unknown =a5
            popInt(); // unknown =02
            popInt(); // unknown =47;48;4e;4b;42

            addScaleData(measurement);

            //    Visceral fat?
            //    Standard weight?
            //    WeightControl?
            //    Body fat?
            //    Muscle weight?
        }
    }

}
