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

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.Handler;

import com.health.openscale.core.datatypes.ScaleData;

import static com.health.openscale.core.bluetooth.BluetoothCommunication.BT_STATUS_CODE.BT_RETRIEVE_SCALE_DATA;

public abstract class BluetoothCommunication {
    public enum BT_STATUS_CODE {BT_RETRIEVE_SCALE_DATA, BT_INIT_PROCESS, BT_CONNECTION_ESTABLISHED, BT_CONNECTION_LOST, BT_NO_DEVICE_FOUND, BT_UNEXPECTED_ERROR };

    private Handler callbackBtHandler;
    protected BluetoothAdapter btAdapter;

    protected Context context;

    public BluetoothCommunication(Context context)
    {
        this.context = context;
        btAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public static BluetoothCommunication getBtDevice(Context context, int i) {
        switch (i) {
            case 0:
                return new BluetoothCustomOpenScale(context);
            case 1:
                return new BluetoothMiScale(context);
            case 2:
                return new BluetoothSanitasSbf70(context);
            case 3:
                return new BluetoothMedisanaBS444(context);
        }

        return null;
    }

    public void registerCallbackHandler(Handler cbBtHandler) {
        callbackBtHandler = cbBtHandler;
    }


    protected void setBtStatus(BT_STATUS_CODE statusCode) {
        setBtStatus(statusCode, "");
    }

    protected void setBtStatus(BT_STATUS_CODE statusCode, String infoText) {
        callbackBtHandler.obtainMessage(statusCode.ordinal(), infoText).sendToTarget();
    }

    protected void addScaleData(ScaleData scaleData) {
        callbackBtHandler.obtainMessage(BT_RETRIEVE_SCALE_DATA.ordinal(), scaleData).sendToTarget();
    }

    abstract public String deviceName();
    abstract public String defaultDeviceName();

    abstract public void startSearching(String deviceName);
    abstract public void stopSearching();

    public boolean initSupported() {
        return true;
    }

    public boolean transferSupported() {
        return true;
    }

    public boolean historySupported() {
        return true;
    }

    public boolean isBLE() {
        return true;
    }
}

