/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.gui.utils;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.widget.Toast;

import com.health.openscale.R;

public class PermissionHelper {
    public final static int PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 1;
    public final static int PERMISSIONS_REQUEST_ACCESS_READ_STORAGE = 2;
    public final static int PERMISSIONS_REQUEST_ACCESS_WRITE_STORAGE = 3;

    public final static int ENABLE_BLUETOOTH_REQUEST = 5;

    public static boolean requestBluetoothPermission(final Activity activity, Fragment fragment) {
        final BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter btAdapter = bluetoothManager.getAdapter();

        if (btAdapter == null || !btAdapter.isEnabled()) {
            Toast.makeText(activity, "Bluetooth " + activity.getResources().getString(R.string.info_is_not_enable), Toast.LENGTH_SHORT).show();

            if (btAdapter != null) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                fragment.startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST);
            }
            return false;
        }

        // Check if Bluetooth 4.x is available
        if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(activity, "Bluetooth 4.x " + activity.getResources().getString(R.string.info_is_not_available), Toast.LENGTH_SHORT).show();
            return false;
         }

        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);

            builder.setMessage(R.string.permission_bluetooth_info)
                    .setTitle(R.string.permission_bluetooth_info_title)
                    .setIcon(R.drawable.ic_preferences_about)
                    .setPositiveButton(R.string.label_ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.dismiss();
                            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION);
                        }
                    });

            builder.show();
            return false;
        }

        return true;
    }

    public static boolean requestReadPermission(final Activity activity) {
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_ACCESS_READ_STORAGE);
        } else {
            return true;
        }

        return false;
    }

    public static boolean requestWritePermission(final Activity activity) {
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_ACCESS_WRITE_STORAGE);
        } else {
            return true;
        }

        return false;
    }
}
