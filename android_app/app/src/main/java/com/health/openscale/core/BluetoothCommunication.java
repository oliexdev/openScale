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

import android.bluetooth.BluetoothAdapter;
import android.os.Handler;

public abstract class BluetoothCommunication {
    public static final int BT_MI_SCALE = 0;
    public static final int BT_OPEN_SCALE = 1;

    public static final int BT_RETRIEVE_SCALE_DATA = 0;
    public static final int BT_INIT_PROCESS = 1;
    public static final int BT_CONNECTION_ESTABLISHED = 2;
    public static final int BT_CONNECTION_LOST = 3;
    public static final int BT_NO_DEVICE_FOUND = 4;
    public static final int BT_UNEXPECTED_ERROR = 5;

    protected Handler callbackBtHandler;
    protected BluetoothAdapter btAdapter;

    public BluetoothCommunication()
    {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void registerCallbackHandler(Handler cbBtHandler) {
        callbackBtHandler = cbBtHandler;
    }

    abstract void startSearching(String deviceName);
    abstract void stopSearching();
}

