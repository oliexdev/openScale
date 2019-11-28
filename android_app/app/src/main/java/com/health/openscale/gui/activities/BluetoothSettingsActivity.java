/* Copyright (C) 2019  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.gui.activities;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.drawable.DrawableCompat;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bluetooth.BluetoothFactory;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.gui.preferences.BluetoothPreferences;
import com.health.openscale.gui.utils.ColorUtil;
import com.health.openscale.gui.utils.PermissionHelper;
import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothCentralCallback;
import com.welie.blessed.BluetoothPeripheral;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

public class BluetoothSettingsActivity extends BaseAppCompatActivity {
    private Context context;

    public static final int GET_SCALE_REQUEST = 150;

    public static final String PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME = "btDeviceName";
    public static final String PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS = "btHwAddress";

    private Map<String, BluetoothDevice> foundDevices = new HashMap<>();

    private LinearLayout deviceListView;
    private TextView txtSearching;
    private ProgressBar progressBar;
    private Handler progressHandler;
    private BluetoothCentral central;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_bluetoothsettings);
        context = this;

        deviceListView = findViewById(R.id.deviceListView);
        txtSearching = findViewById(R.id.txtSearching);
        progressBar = findViewById(R.id.progressBar);
        Toolbar toolbar = findViewById(R.id.bluetoothSettingToolbar);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(R.string.label_bluetooth_title);

        if (PermissionHelper.requestBluetoothPermission(this)) {
            if (PermissionHelper.requestLocationServicePermission(this)) {
                startBluetoothDiscovery();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            // Override the default behaviour in order to return to the correct fragment
            // (e.g. the table view) and not always go to the overview.
            case android.R.id.home:
                stopBluetoothDiscovery();
                onBackPressed();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private static final String formatDeviceName(String name, String address) {
        if (name.isEmpty() || address.isEmpty()) {
            return "-";
        }
        return String.format("%s [%s]", name, address);
    }

    private static final String formatDeviceName(BluetoothDevice device) {
        return formatDeviceName(device.getName(), device.getAddress());
    }

    private final BluetoothCentralCallback bluetoothCentralCallback = new BluetoothCentralCallback() {
        @Override
        public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
            onDeviceFound(scanResult);
        }
    };

    private void startBluetoothDiscovery() {
        deviceListView.removeAllViews();
        foundDevices.clear();

        central = new BluetoothCentral(getApplicationContext(), bluetoothCentralCallback, new Handler(Looper.getMainLooper()));
        central.scanForPeripherals();

        txtSearching.setVisibility(View.VISIBLE);
        txtSearching.setText(R.string.label_bluetooth_searching);
        progressBar.setVisibility(View.VISIBLE);

        progressHandler = new Handler();

        // Don't let the BLE discovery run forever
        progressHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopBluetoothDiscovery();

                txtSearching.setText(R.string.label_bluetooth_searching_finished);
                progressBar.setVisibility(View.GONE);

                BluetoothDeviceView notSupported = new BluetoothDeviceView(context);
                notSupported.setDeviceName(getString(R.string.label_scale_not_supported));
                notSupported.setSummaryText(getString(R.string.label_click_to_help_add_support));
                notSupported.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent notSupportedIntent = new Intent(Intent.ACTION_VIEW);
                        notSupportedIntent.setData(
                                Uri.parse("https://github.com/oliexdev/openScale/wiki/Supported-scales-in-openScale"));

                        startActivity(notSupportedIntent);
                    }
                });
                deviceListView.addView(notSupported);
            }
        }, 20 * 1000);
    }

    private void stopBluetoothDiscovery() {
        if (progressHandler != null) {
            progressHandler.removeCallbacksAndMessages(null);
            progressHandler = null;
        }

        if (central != null) {
            central.stopScan();
        }
    }

    private void onDeviceFound(final ScanResult bleScanResult) {
        BluetoothDevice device = bleScanResult.getDevice();

        if (device.getName() == null || foundDevices.containsKey(device.getAddress())) {
            return;
        }

        BluetoothDeviceView deviceView = new BluetoothDeviceView(this);
        deviceView.setDeviceName(formatDeviceName(bleScanResult.getDevice()));

        BluetoothCommunication btDevice = BluetoothFactory.createDeviceDriver(this, device.getName());
        if (btDevice != null) {
            Timber.d("Found supported device %s (driver: %s)",
                    formatDeviceName(device), btDevice.driverName());
            deviceView.setOnClickListener(new onClickListenerDeviceSelect());
            deviceView.setDeviceAddress(device.getAddress());
            deviceView.setIcon(R.drawable.ic_bluetooth_device_supported);
            deviceView.setSummaryText(btDevice.driverName());
        }
        else {
            Timber.d("Found unsupported device %s",
                    formatDeviceName(device));
            deviceView.setIcon(R.drawable.ic_bluetooth_device_not_supported);
            deviceView.setSummaryText(getString(R.string.label_bt_device_no_support));
            deviceView.setEnabled(false);

            if (OpenScale.DEBUG_MODE) {
                deviceView.setEnabled(true);
                deviceView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        getDebugInfo(device);
                    }
                });
            }
        }

        foundDevices.put(device.getAddress(), btDevice != null ? device : null);
        deviceListView.addView(deviceView);
    }

    private class onClickListenerDeviceSelect implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            BluetoothDeviceView deviceView = (BluetoothDeviceView)view;

            BluetoothDevice device = foundDevices.get(deviceView.getDeviceAddress());

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

            prefs.edit()
                    .putString(PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS, device.getAddress())
                    .putString(PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME, device.getName())
                    .apply();

            finishActivity(GET_SCALE_REQUEST);
        }
    }

    private void getDebugInfo(final BluetoothDevice device) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Fetching info")
                .setMessage("Please wait while we fetch extended info from your scale...")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        OpenScale.getInstance().disconnectFromBluetoothDevice();
                        dialog.dismiss();
                    }
                });

        final AlertDialog dialog = builder.create();

        Handler btHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (BluetoothCommunication.BT_STATUS.values()[msg.what]) {
                    case CONNECTION_LOST:
                        OpenScale.getInstance().disconnectFromBluetoothDevice();
                        dialog.dismiss();
                        break;
                }
            }
        };

        dialog.show();

        String macAddress = device.getAddress();
        stopBluetoothDiscovery();
        OpenScale.getInstance().connectToBluetoothDeviceDebugMode(macAddress, btHandler);
    }

    private class BluetoothDeviceView extends LinearLayout {

        private TextView deviceName;
        private ImageView deviceIcon;
        private String deviceAddress;

        public BluetoothDeviceView(Context context) {
            super(context);

            deviceName = new TextView(context);
            deviceName.setLines(2);
            deviceIcon = new ImageView(context);

            addView(deviceIcon);
            addView(deviceName);
        }

        public void setDeviceAddress(String address) {
            deviceAddress = address;
        }

        public String getDeviceAddress() {
            return deviceAddress;
        }

        public void setDeviceName(String name) {
            deviceName.setText(name);
        }

        public void setSummaryText(String text) {
            SpannableStringBuilder stringBuilder = new SpannableStringBuilder(new String());

            stringBuilder.append(deviceName.getText());
            stringBuilder.append("\n");

            int deviceNameLength = deviceName.getText().length();

            stringBuilder.append(text);
            stringBuilder.setSpan(new ForegroundColorSpan(Color.GRAY), deviceNameLength, deviceNameLength + text.length()+1,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            stringBuilder.setSpan(new RelativeSizeSpan(0.8f), deviceNameLength, deviceNameLength + text.length()+1,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);

            deviceName.setText(stringBuilder);
        }

        public void setIcon(int resId) {
            deviceIcon.setImageResource(resId);

            int tintColor = ColorUtil.getTextColor(getApplicationContext());
            deviceIcon.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN);
        }

        @Override
        public void setOnClickListener(OnClickListener listener) {
            super.setOnClickListener(listener);
            deviceName.setOnClickListener(listener);
            deviceIcon.setOnClickListener(listener);
        }

        @Override
        public void setEnabled(boolean status) {
            super.setEnabled(status);
            deviceName.setEnabled(status);
            deviceIcon.setEnabled(status);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PermissionHelper.ENABLE_BLUETOOTH_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                if (PermissionHelper.requestBluetoothPermission(this)) {
                    startBluetoothDiscovery();
                }
            }
            else {
                onBackPressed();
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (PermissionHelper.requestLocationServicePermission(this)) {
                        startBluetoothDiscovery();
                    }
                } else {
                    Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_SHORT).show();
                    onBackPressed();
                }
                break;
            }
        }
    }
}
