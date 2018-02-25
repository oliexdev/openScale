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

package com.health.openscale.gui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.internal.BottomNavigationItemView;
import android.support.design.internal.BottomNavigationMenuView;
import android.support.design.widget.BottomNavigationView;
import android.support.design.widget.NavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.FileProvider;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.health.openscale.BuildConfig;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.activities.SettingsActivity;
import com.health.openscale.gui.activities.UserSettingsActivity;
import com.health.openscale.gui.fragments.GraphFragment;
import com.health.openscale.gui.fragments.OverviewFragment;
import com.health.openscale.gui.fragments.StatisticsFragment;
import com.health.openscale.gui.fragments.TableFragment;
import com.health.openscale.gui.utils.PermissionHelper;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class MainActivity extends AppCompatActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener{
    private static boolean firstAppStart = true;
    private static boolean valueOfCountModified = false;
    private static int bluetoothStatusIcon = R.drawable.ic_bluetooth_disabled;
    private static MenuItem bluetoothStatus;

    private static final int IMPORT_DATA_REQUEST = 100;

    private Fragment currentFragment;
    private DrawerLayout drawerLayout;
    private Toolbar toolbar;
    private NavigationView navDrawer;
    private BottomNavigationView navBottomDrawer;
    private ActionBarDrawerToggle drawerToggle;

    private boolean settingsActivityRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String app_theme = prefs.getString("app_theme", "Light");

        if (app_theme.equals("Dark")) {
            setTheme(R.style.AppTheme_Dark);
        }

        super.onCreate(savedInstanceState);

        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);

        CaocConfig.Builder.create()
                .trackActivities(true)
                .apply();

        setContentView(R.layout.activity_main);

        currentFragment = null;

        // Set a Toolbar to replace the ActionBar.
        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Find our drawer view
        drawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);

        // Find our drawer view
        navDrawer = (NavigationView) findViewById(R.id.navigation_view);

        navBottomDrawer = (BottomNavigationView) findViewById(R.id.navigation_bottom_view);
        navBottomDrawer.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                selectDrawerItem(item.getItemId());
                return true;
            }
        });

        disableShiftMode(navBottomDrawer);

        //Create Drawer Toggle
        drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, R.string.open_drawer, R.string.close_drawer){
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                super.onDrawerClosed(drawerView);
            }
        };

        drawerLayout.addDrawerListener(drawerToggle);

        // Setup drawer view
        setupDrawerContent(navDrawer);

        selectDrawerItem(prefs.getInt("lastFragmentId", R.id.nav_overview));

        navBottomDrawer.setSelectedItemId(prefs.getInt("lastFragmentId", R.id.nav_overview));

        if (prefs.getBoolean("firstStart", true)) {
            Intent intent = new Intent(this, UserSettingsActivity.class);
            intent.putExtra(UserSettingsActivity.EXTRA_MODE, UserSettingsActivity.ADD_USER_REQUEST);
            startActivity(intent);

            prefs.edit().putBoolean("firstStart", false).commit();
        }

        if(!valueOfCountModified){
            int launchCount = prefs.getInt("launchCount", 0);

            if(prefs.edit().putInt("launchCount", ++launchCount).commit()){
                valueOfCountModified = true;

                // ask the user once for feedback on the 30th app launch
                if(launchCount == 30){
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);

                    builder.setMessage(R.string.label_feedback_message_enjoying)
                            .setPositiveButton(R.string.label_feedback_message_yes, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.dismiss();
                                    positiveFeedbackDialog();
                                }
                            })
                            .setNegativeButton(R.string.label_feedback_message_no, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int id) {
                                    dialog.dismiss();
                                    negativeFeedbackDialog();
                                }
                            });

                    AlertDialog dialog = builder.create();
                    dialog.show();
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        settingsActivityRunning = false;
    }

    @Override
    public void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (settingsActivityRunning) {
            recreate();
        }
    }

    private void positiveFeedbackDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setMessage(R.string.label_feedback_message_rate_app)
                .setPositiveButton(R.string.label_feedback_message_positive, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                        Uri uri = Uri.parse("market://details?id=" + getPackageName());
                        Intent goToMarket = new Intent(Intent.ACTION_VIEW, uri);
                        // To count with Play market backstack, After pressing back button,
                        // to taken back to our application, we need to add following flags to intent.
                        goToMarket.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY |
                                Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET |
                                Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        try {
                            startActivity(goToMarket);
                        } catch (ActivityNotFoundException e) {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + getPackageName())));
                        }
                    }
                })
                .setNegativeButton(R.string.label_feedback_message_negative, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void negativeFeedbackDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setMessage(R.string.label_feedback_message_issue)
                .setPositiveButton(R.string.label_feedback_message_positive, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/oliexdev/openScale/issues")));
                    }
                })
                .setNegativeButton(R.string.label_feedback_message_negative, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.dismiss();
                    }
                });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void setupDrawerContent(NavigationView navigationView) {
        navigationView.setNavigationItemSelectedListener(

                new NavigationView.OnNavigationItemSelectedListener() {
                    @Override
                    public boolean onNavigationItemSelected(MenuItem menuItem) {
                        selectDrawerItem(menuItem.getItemId());
                        navBottomDrawer.setSelectedItemId(menuItem.getItemId());
                        return true;

                    }

                });
    }

    public void selectDrawerItem(int menuItemId) {
        // Create a new fragment and specify the fragment to show based on nav item clicked
        Class fragmentClass;
        String fragmentTitle;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        switch(menuItemId) {
            case R.id.nav_overview:
                fragmentClass = OverviewFragment.class;
                fragmentTitle = getResources().getString(R.string.title_overview);
                prefs.edit().putInt("lastFragmentId", menuItemId).commit();
                break;
            case R.id.nav_graph:
                fragmentClass = GraphFragment.class;
                fragmentTitle = getResources().getString(R.string.title_graph);
                prefs.edit().putInt("lastFragmentId", menuItemId).commit();
                break;
            case R.id.nav_table:
                fragmentClass = TableFragment.class;
                fragmentTitle = getResources().getString(R.string.title_table);
                prefs.edit().putInt("lastFragmentId", menuItemId).commit();
                break;
            case R.id.nav_statistic:
                fragmentClass = StatisticsFragment.class;
                fragmentTitle = getResources().getString(R.string.title_statistics);
                prefs.edit().putInt("lastFragmentId", menuItemId).commit();
                break;
            case R.id.nav_settings:
                Intent settingsIntent = new Intent(this, SettingsActivity.class);
                settingsIntent.putExtra(SettingsActivity.EXTRA_TINT_COLOR, navDrawer.getItemTextColor().getDefaultColor());
                startActivity(settingsIntent);
                settingsActivityRunning = true;
                drawerLayout.closeDrawers();
                return;
            case R.id.nav_help:
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/oliexdev/openScale/wiki")));
                drawerLayout.closeDrawers();
                return;
            default:
                fragmentClass = OverviewFragment.class;
                fragmentTitle = getResources().getString(R.string.title_overview);
                prefs.edit().putInt("lastFragmentId", menuItemId).commit();
        }

        FragmentManager fragmentManager = getSupportFragmentManager();

        // hide previous fragment if it available
        if (currentFragment != null) {
            fragmentManager.beginTransaction().hide(currentFragment).commit();
        }

        // try to find selected fragment
        currentFragment = fragmentManager.findFragmentByTag(""+menuItemId);

        // if fragment not found then add the fragment
        if (currentFragment == null) {
            try {
                currentFragment = (Fragment) fragmentClass.newInstance();
            } catch (Exception e) {
                e.printStackTrace();
            }

            fragmentManager.beginTransaction().add(R.id.fragment_content, currentFragment, "" + menuItemId).commit();
        } else { // otherwise show fragment
            fragmentManager.beginTransaction().show(currentFragment).commit();
        }

        // Set action bar title
        setTitle(fragmentTitle);

        // Set checked item
        navDrawer.setCheckedItem(menuItemId);

        // Close the navigation drawer
        drawerLayout.closeDrawers();
    }

    private void showNoSelectedUserDialog() {
        AlertDialog.Builder infoDialog = new AlertDialog.Builder(this);

        infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));
        infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);
        infoDialog.show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        switch (item.getItemId()) {
            case android.R.id.home:
                drawerLayout.openDrawer(GravityCompat.START);
                return true;
            case R.id.action_add_measurement:
                if (OpenScale.getInstance(getApplicationContext()).getSelectedScaleUserId() == -1) {
                    showNoSelectedUserDialog();
                    return true;
                }

                Intent intent = new Intent(getApplicationContext(), DataEntryActivity.class);
                startActivity(intent);
                return true;
            case R.id.action_bluetooth_status:
                invokeSearchBluetoothDevice();
                return true;
            case R.id.importData:
                importCsvFile();
                return true;
            case R.id.exportData:
                if (PermissionHelper.requestWritePermission(this)) {
                    exportCsvFile();
                }
                return true;
            case R.id.shareData:
                shareCsvFile();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.action_menu, menu);

        bluetoothStatus = menu.findItem(R.id.action_bluetooth_status);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Just search for a bluetooth device just once at the start of the app and if start preference enabled
        if (firstAppStart && prefs.getBoolean("btEnable", false)) {
            invokeSearchBluetoothDevice();
            firstAppStart = false;
        } else {
            // Set current bluetooth status icon while e.g. orientation changes
            setBluetoothStatusIcon(bluetoothStatusIcon);
        }

        return true;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    private void invokeSearchBluetoothDevice() {
        if (OpenScale.getInstance(getApplicationContext()).getSelectedScaleUserId() == -1) {
            showNoSelectedUserDialog();
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        String deviceName = prefs.getString("btDeviceName", "-");

        boolean permGrantedCoarseLocation = false;

        // Check if Bluetooth 4.x is available
        if (deviceName != "openScale_MCU") {
            permGrantedCoarseLocation = PermissionHelper.requestBluetoothPermission(this, false);
        } else {
            permGrantedCoarseLocation = PermissionHelper.requestBluetoothPermission(this, true);
        }

        if (permGrantedCoarseLocation) {
            if (deviceName == "-") {
                Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_no_device_set), Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_try_connection) + " " + deviceName, Toast.LENGTH_SHORT).show();
            setBluetoothStatusIcon(R.drawable.ic_bluetooth_searching);

            OpenScale.getInstance(getApplicationContext()).stopSearchingForBluetooth();
            if (!OpenScale.getInstance(getApplicationContext()).startSearchingForBluetooth(deviceName, callbackBtHandler)) {
                Toast.makeText(getApplicationContext(), deviceName + " " + getResources().getString(R.string.label_bt_device_no_support), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final Handler callbackBtHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            BluetoothCommunication.BT_STATUS_CODE btStatusCode = BluetoothCommunication.BT_STATUS_CODE.values()[msg.what];

            switch (btStatusCode) {
                case BT_RETRIEVE_SCALE_DATA:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_success);
                    ScaleMeasurement scaleBtData = (ScaleMeasurement) msg.obj;

                    OpenScale openScale = OpenScale.getInstance(getApplicationContext());

                    List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleMeasurementList();

                    if (!scaleMeasurementList.isEmpty()) {
                        ScaleMeasurement lastMeasurement = scaleMeasurementList.get(0);
                        scaleBtData.merge(lastMeasurement);
                    }

                    openScale.addScaleData(scaleBtData);
                    break;
                case BT_INIT_PROCESS:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_success);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_init), Toast.LENGTH_SHORT).show();
                    Log.d("OpenScale", "Bluetooth initializing");
                    break;
                case BT_CONNECTION_LOST:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_lost), Toast.LENGTH_SHORT).show();
                    Log.d("OpenScale", "Bluetooth connection lost");
                    break;
                case BT_NO_DEVICE_FOUND:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_no_device), Toast.LENGTH_SHORT).show();
                    Log.d("OpenScale", "No Bluetooth device found");
                    break;
                case BT_CONNECTION_ESTABLISHED:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_success);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_successful), Toast.LENGTH_SHORT).show();
                    Log.d("OpenScale", "Bluetooth connection successful established");
                    break;
                case BT_UNEXPECTED_ERROR:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_error) + ": " + msg.obj, Toast.LENGTH_SHORT).show();
                    Log.e("OpenScale", "Bluetooth unexpected error: " + msg.obj);
                    break;
                case BT_SCALE_MESSAGE:
                    String toastMessage = String.format(getResources().getString(msg.arg1), msg.obj);
                    Toast.makeText(getApplicationContext(), toastMessage, Toast.LENGTH_LONG).show();
                    break;
            }
        }
    };

    private void setBluetoothStatusIcon(int iconRessource) {
        bluetoothStatusIcon = iconRessource;
        bluetoothStatus.setIcon(getResources().getDrawable(bluetoothStatusIcon));
    }

    private void importCsvFile() {
        int selectedUserId = OpenScale.getInstance(getApplicationContext()).getSelectedScaleUserId();

        if (selectedUserId == -1) {
            AlertDialog.Builder infoDialog = new AlertDialog.Builder(this);

            infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));
            infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);

            infoDialog.show();
        }
        else {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/*");

            startActivityForResult(
                    Intent.createChooser(intent, getResources().getString(R.string.label_import)),
                    IMPORT_DATA_REQUEST);
        }
    }

    private void exportCsvFile() {
        AlertDialog.Builder filenameDialog = new AlertDialog.Builder(this);

        filenameDialog.setTitle(getResources().getString(R.string.info_set_filename) + " "
                + Environment.getExternalStorageDirectory().getPath());

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        final ScaleUser selectedScaleUser = OpenScale.getInstance(getApplicationContext()).getSelectedScaleUser();
        String exportFilename = prefs.getString("exportFilename" + selectedScaleUser.getId(),
                "openScale_data_" + selectedScaleUser.getUserName() + ".csv");

        final EditText txtFilename = new EditText(this);
        txtFilename.setText(exportFilename);

        filenameDialog.setView(txtFilename);

        filenameDialog.setPositiveButton(getResources().getString(R.string.label_export), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                String fullPath = Environment.getExternalStorageDirectory().getPath() + "/" + txtFilename.getText().toString();

                if (OpenScale.getInstance(getApplicationContext()).exportData(fullPath)) {
                    prefs.edit().putString("exportFilename" + selectedScaleUser.getId(), txtFilename.getText().toString()).commit();
                    Toast.makeText(getApplicationContext(), getResources().getString(
                            R.string.info_data_exported) + " " + fullPath, Toast.LENGTH_SHORT).show();
                }
            }
        });

        filenameDialog.setNegativeButton(getResources().getString(R.string.label_cancel), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
            }
        });

        filenameDialog.show();
    }

    private void shareCsvFile() {
        final ScaleUser selectedScaleUser = OpenScale.getInstance(getApplicationContext()).getSelectedScaleUser();

        File shareFile = new File(getApplicationContext().getCacheDir(),
                String.format("openScale %s.csv", selectedScaleUser.getUserName()));
        if (!OpenScale.getInstance(getApplicationContext()).exportData(shareFile.getPath())) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType("text/csv");

        final Uri uri = FileProvider.getUriForFile(
                getApplicationContext(), BuildConfig.APPLICATION_ID + ".fileprovider", shareFile);
        intent.putExtra(Intent.EXTRA_STREAM, uri);

        intent.putExtra(Intent.EXTRA_SUBJECT,
                getResources().getString(R.string.label_share_subject, selectedScaleUser.getUserName()));

        startActivity(Intent.createChooser(intent, getResources().getString(R.string.label_share)));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == IMPORT_DATA_REQUEST && resultCode == RESULT_OK && data != null) {
            OpenScale.getInstance(getApplicationContext()).importData(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        boolean permissionGranted = true;
        switch (requestCode) {
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    invokeSearchBluetoothDevice();
                } else {
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_disabled);
                    permissionGranted = false;
                }
                break;
            case PermissionHelper.PERMISSIONS_REQUEST_ACCESS_WRITE_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    exportCsvFile();
                } else {
                    permissionGranted = false;
                }
                break;
        }

        if (!permissionGranted) {
            Toast.makeText(getApplicationContext(), getResources().getString(
                    R.string.permission_not_granted), Toast.LENGTH_SHORT).show();
        }

        currentFragment.onRequestPermissionsResult(requestCode, permissions, grantResults);
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @SuppressLint("RestrictedApi")
    public static void disableShiftMode(BottomNavigationView view) {
        BottomNavigationMenuView menuView = (BottomNavigationMenuView) view.getChildAt(0);
        try {
            Field shiftingMode = menuView.getClass().getDeclaredField("mShiftingMode");
            shiftingMode.setAccessible(true);
            shiftingMode.setBoolean(menuView, false);
            shiftingMode.setAccessible(false);
            for (int i = 0; i < menuView.getChildCount(); i++) {
                BottomNavigationItemView item = (BottomNavigationItemView) menuView.getChildAt(i);
                //noinspection RestrictedApi
                item.setShiftingMode(false);
                item.setPadding(0, 20, 0, 0);
                // set once again checked value, so view will be updated
                //noinspection RestrictedApi
                item.setChecked(item.getItemData().isChecked());
            }
        } catch (NoSuchFieldException e) {
            Log.e("BNVHelper", "Unable to get shift mode field", e);
        } catch (IllegalAccessException e) {
            Log.e("BNVHelper", "Unable to change value of shift mode", e);
        }
    }
}
