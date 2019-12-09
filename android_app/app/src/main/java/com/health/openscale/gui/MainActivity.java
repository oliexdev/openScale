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

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import com.health.openscale.BuildConfig;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.activities.AppIntroActivity;
import com.health.openscale.gui.activities.BaseAppCompatActivity;
import com.health.openscale.gui.activities.BluetoothSettingsActivity;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.activities.SettingsActivity;
import com.health.openscale.gui.fragments.GraphFragment;
import com.health.openscale.gui.fragments.OverviewFragment;
import com.health.openscale.gui.fragments.StatisticsFragment;
import com.health.openscale.gui.fragments.TableFragment;

import java.io.File;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;
import timber.log.Timber;

public class MainActivity extends BaseAppCompatActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener{
    private SharedPreferences prefs;
    private static boolean firstAppStart = true;
    private static boolean valueOfCountModified = false;
    private static int bluetoothStatusIcon = R.drawable.ic_bluetooth_disabled;
    private static MenuItem bluetoothStatus;

    private static final int IMPORT_DATA_REQUEST = 100;
    private static final int EXPORT_DATA_REQUEST = 101;
    private static final int ENABLE_BLUETOOTH_REQUEST = 102;

    private DrawerLayout drawerLayout;
    private NavigationView navDrawer;
    private BottomNavigationView navBottomDrawer;
    private ActionBarDrawerToggle drawerToggle;

    private boolean settingsActivityRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        CaocConfig.Builder.create()
                .trackActivities(false)
                .apply();

        setContentView(R.layout.activity_main);

        // Set a Toolbar to replace the ActionBar.
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Find our drawer view
        drawerLayout = findViewById(R.id.drawer_layout);

        // Find our drawer view
        navDrawer = findViewById(R.id.navigation_view);

        navBottomDrawer = findViewById(R.id.navigation_bottom_view);
        navBottomDrawer.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                selectDrawerItem(item.getItemId());
                return true;
            }
        });

        //Create Drawer Toggle
        drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, R.string.open_drawer, R.string.close_drawer){

        };

        drawerLayout.addDrawerListener(drawerToggle);

        // Setup drawer view
        setupDrawerContent(navDrawer);

        selectDrawerItem(prefs.getInt("lastFragmentId", R.id.nav_overview));

        navBottomDrawer.setSelectedItemId(prefs.getInt("lastFragmentId", R.id.nav_overview));

        if (BuildConfig.BUILD_TYPE == "light") {
            ImageView launcherIcon = navDrawer.getHeaderView(0).findViewById(R.id.profileImageView);
            launcherIcon.setImageResource(R.drawable.ic_launcher_openscale_light);
            navDrawer.getMenu().findItem(R.id.nav_donation).setVisible(false);
        } else if (BuildConfig.BUILD_TYPE == "pro") {
            ImageView launcherIcon = navDrawer.getHeaderView(0).findViewById(R.id.profileImageView);
            launcherIcon.setImageResource(R.drawable.ic_launcher_openscale_pro);
            navDrawer.getMenu().findItem(R.id.nav_donation).setVisible(false);
        }

        if (prefs.getBoolean("firstStart", true)) {
            Intent appIntroIntent = new Intent(this, AppIntroActivity.class);
            startActivity(appIntroIntent);

            prefs.edit().putBoolean("firstStart", false).apply();
        }

        if (prefs.getBoolean("resetLaunchCountForVersion2.0", true)) {
            prefs.edit().putInt("launchCount", 0).commit();

            prefs.edit().putBoolean("resetLaunchCountForVersion2.0", false).apply();
        }

        if(!valueOfCountModified){
            int launchCount = prefs.getInt("launchCount", 0);

            if(prefs.edit().putInt("launchCount", ++launchCount).commit()){
                valueOfCountModified = true;

                // ask the user once for feedback on the 15th app launch
                if(launchCount == 15){
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
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (settingsActivityRunning) {
            recreate();
            OpenScale.getInstance().triggerWidgetUpdate();
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
                        // To count with Play market back stack, After pressing back button,
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

    private void selectDrawerItem(int menuItemId) {
        // Create a new fragment and specify the fragment to show based on nav item clicked
        Class fragmentClass;
        String fragmentTitle;

        switch (menuItemId) {
            default:
            case R.id.nav_overview:
                fragmentClass = OverviewFragment.class;
                fragmentTitle = getResources().getString(R.string.title_overview);
                break;
            case R.id.nav_graph:
                fragmentClass = GraphFragment.class;
                fragmentTitle = getResources().getString(R.string.title_graph);
                break;
            case R.id.nav_table:
                fragmentClass = TableFragment.class;
                fragmentTitle = getResources().getString(R.string.title_table);
                break;
            case R.id.nav_statistic:
                fragmentClass = StatisticsFragment.class;
                fragmentTitle = getResources().getString(R.string.title_statistics);
                break;
            case R.id.nav_settings:
                drawerLayout.closeDrawer(navDrawer, false);
                Intent settingsIntent = new Intent(this, SettingsActivity.class);
                settingsActivityRunning = true;
                startActivity(settingsIntent);
                return;
            case R.id.nav_donation:
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=H5KSTQA6TKTE4&source=url")));
                drawerLayout.closeDrawers();
                return;
            case R.id.nav_help:
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/oliexdev/openScale/wiki")));
                drawerLayout.closeDrawers();
                return;
        }

        prefs.edit().putInt("lastFragmentId", menuItemId).apply();

        FragmentManager fragmentManager = getSupportFragmentManager();

        // Make sure that any pending transaction completes so that added fragments are
        // actually added and won't get added again (may happen during activity creation
        // when this method is called twice).
        fragmentManager.executePendingTransactions();

        FragmentTransaction transaction = fragmentManager.beginTransaction();
        final String tag = String.valueOf(menuItemId);

        boolean found = false;
        for (Fragment fragment : fragmentManager.getFragments()) {
            if (fragment.getTag().equals(tag)) {
                // Show selected fragment if already added
                transaction.show(fragment);
                found = true;
            }
            else if (!fragment.isHidden()) {
                // Hide currently shown fragment
                transaction.hide(fragment);
            }
        }

        // If fragment isn't found then add it
        if (!found) {
            try {
                transaction.add(R.id.fragment_content, (Fragment) fragmentClass.newInstance(), tag);
            } catch (Exception e) {
                Timber.e(e, "Failed to add fragment %s", tag);
            }
        }

        transaction.commit();

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
                if (OpenScale.getInstance().getSelectedScaleUserId() == -1) {
                    showNoSelectedUserDialog();
                    return true;
                }

                Intent intent = new Intent(getApplicationContext(), DataEntryActivity.class);
                intent.putExtra(DataEntryActivity.EXTRA_MODE, DataEntryActivity.ADD_MEASUREMENT_REQUEST);
                startActivity(intent);
                return true;
            case R.id.action_bluetooth_status:
                if (OpenScale.getInstance().disconnectFromBluetoothDevice()) {
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_disabled);
                }
                else {
                    invokeConnectToBluetoothDevice();
                }
                return true;
            case R.id.importData:
                importCsvFile();
                return true;
            case R.id.exportData:
                exportCsvFile();
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

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        boolean hasBluetooth = bluetoothManager.getAdapter() != null;

        if (!hasBluetooth) {
            bluetoothStatus.setEnabled(false);
            setBluetoothStatusIcon(R.drawable.ic_bluetooth_disabled);
        }
        // Just search for a bluetooth device just once at the start of the app and if start preference enabled
        else if (firstAppStart && prefs.getBoolean("btEnable", false)) {
            invokeConnectToBluetoothDevice();
            firstAppStart = false;
        }
        else {
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

    private void invokeConnectToBluetoothDevice() {
        if (BuildConfig.BUILD_TYPE == "light") {
            AlertDialog infoDialog = new AlertDialog.Builder(this)
                .setMessage(Html.fromHtml(getResources().getString(R.string.label_upgrade_to_openScale_pro) + "<br><br> <a href=\"https://play.google.com/store/apps/details?id=com.health.openscale.pro\">Install openScale pro version</a>"))
                .setPositiveButton(getResources().getString(R.string.label_ok), null)
                .setIcon(R.drawable.ic_launcher_openscale_light)
                .setTitle("openScale " + BuildConfig.VERSION_NAME)
                .create();

            infoDialog.show();

            ((TextView)infoDialog.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());

            return;
        }

        final OpenScale openScale = OpenScale.getInstance();

        if (openScale.getSelectedScaleUserId() == -1) {
            showNoSelectedUserDialog();
            return;
        }

        String deviceName = prefs.getString(
                BluetoothSettingsActivity.PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME, "");
        String hwAddress = prefs.getString(
                BluetoothSettingsActivity.PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS, "");

        if (!BluetoothAdapter.checkBluetoothAddress(hwAddress)) {
            setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
            Toast.makeText(getApplicationContext(), R.string.info_bluetooth_no_device_set, Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (!bluetoothManager.getAdapter().isEnabled()) {
            setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, ENABLE_BLUETOOTH_REQUEST);
            return;
        }

        Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_try_connection) + " " + deviceName, Toast.LENGTH_SHORT).show();
        setBluetoothStatusIcon(R.drawable.ic_bluetooth_searching);

        if (!openScale.connectToBluetoothDevice(deviceName, hwAddress, callbackBtHandler)) {
            setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
            Toast.makeText(getApplicationContext(), deviceName + " " + getResources().getString(R.string.label_bt_device_no_support), Toast.LENGTH_SHORT).show();
        }
    }

    private final Handler callbackBtHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            BluetoothCommunication.BT_STATUS btStatus = BluetoothCommunication.BT_STATUS.values()[msg.what];

            switch (btStatus) {
                case RETRIEVE_SCALE_DATA:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_success);
                    ScaleMeasurement scaleBtData = (ScaleMeasurement) msg.obj;

                    OpenScale openScale = OpenScale.getInstance();

                    if (prefs.getBoolean("mergeWithLastMeasurement", true)) {
                        List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleMeasurementList();

                        if (!scaleMeasurementList.isEmpty()) {
                            ScaleMeasurement lastMeasurement = scaleMeasurementList.get(0);
                            scaleBtData.merge(lastMeasurement);
                        }
                    }

                    openScale.addScaleData(scaleBtData, true);
                    break;
                case INIT_PROCESS:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_success);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_init), Toast.LENGTH_SHORT).show();
                    Timber.d("Bluetooth initializing");
                    break;
                case CONNECTION_LOST:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_lost), Toast.LENGTH_SHORT).show();
                    Timber.d("Bluetooth connection lost");
                    break;
                case NO_DEVICE_FOUND:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_no_device), Toast.LENGTH_SHORT).show();
                    Timber.e("No Bluetooth device found");
                    break;
                case CONNECTION_RETRYING:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_searching);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_no_device_retrying), Toast.LENGTH_SHORT).show();
                    Timber.e("No Bluetooth device found retrying");
                    break;
                case CONNECTION_ESTABLISHED:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_success);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_successful), Toast.LENGTH_SHORT).show();
                    Timber.d("Bluetooth connection successful established");
                    break;
                case CONNECTION_DISCONNECT:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_disconnected), Toast.LENGTH_SHORT).show();
                    Timber.d("Bluetooth connection successful disconnected");
                    break;
                case UNEXPECTED_ERROR:
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_connection_lost);
                    Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_error) + ": " + msg.obj, Toast.LENGTH_SHORT).show();
                    Timber.e("Bluetooth unexpected error: %s", msg.obj);
                    break;
                case SCALE_MESSAGE:
                    String toastMessage = String.format(getResources().getString(msg.arg1), msg.obj);
                    Toast.makeText(getApplicationContext(), toastMessage, Toast.LENGTH_LONG).show();
                    break;
            }
        }
    };

    private void setBluetoothStatusIcon(int iconResource) {
        bluetoothStatusIcon = iconResource;
        bluetoothStatus.setIcon(getResources().getDrawable(bluetoothStatusIcon));
    }

    private void importCsvFile() {
        int selectedUserId = OpenScale.getInstance().getSelectedScaleUserId();

        if (selectedUserId == -1) {
            AlertDialog.Builder infoDialog = new AlertDialog.Builder(this);

            infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));
            infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);

            infoDialog.show();
        }
        else {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");

            startActivityForResult(
                    Intent.createChooser(intent, getResources().getString(R.string.label_import)),
                    IMPORT_DATA_REQUEST);
        }
    }

    private String getExportFilename(ScaleUser selectedScaleUser) {
        return String.format("openScale %s.csv", selectedScaleUser.getUserName());
    }

    private void startActionCreateDocumentForExportIntent() {
        OpenScale openScale = OpenScale.getInstance();
        ScaleUser selectedScaleUser = openScale.getSelectedScaleUser();

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, getExportFilename(selectedScaleUser));

        startActivityForResult(intent, EXPORT_DATA_REQUEST);
    }

    private boolean doExportData(Uri uri) {
        OpenScale openScale = OpenScale.getInstance();
        if (openScale.exportData(uri)) {
            String filename = openScale.getFilenameFromUri(uri);
            Toast.makeText(this,
                    getResources().getString(R.string.info_data_exported) + " " + filename,
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private String getExportPreferenceKey(ScaleUser selectedScaleUser) {
        return selectedScaleUser.getPreferenceKey("exportUri");
    }

    private void exportCsvFile() {
        OpenScale openScale = OpenScale.getInstance();
        final ScaleUser selectedScaleUser = openScale.getSelectedScaleUser();

        Uri uri;
        try {
            String exportUri = prefs.getString(getExportPreferenceKey(selectedScaleUser), "");
            uri = Uri.parse(exportUri);

            // Verify that the file still exists and that we have write permission
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            openScale.getFilenameFromUriMayThrow(uri);
        }
        catch (Exception ex) {
            uri = null;
        }

        if (uri == null) {
            startActionCreateDocumentForExportIntent();
            return;
        }

        AlertDialog.Builder exportDialog = new AlertDialog.Builder(this);
        exportDialog.setTitle(R.string.label_export);
        exportDialog.setMessage(getResources().getString(R.string.label_export_overwrite,
                openScale.getFilenameFromUri(uri)));

        final Uri exportUri = uri;
        exportDialog.setPositiveButton(R.string.label_yes, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (!doExportData(exportUri)) {
                    prefs.edit().remove(getExportPreferenceKey(selectedScaleUser)).apply();
                }
            }
        });
        exportDialog.setNegativeButton(R.string.label_no, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                startActionCreateDocumentForExportIntent();
            }
        });
        exportDialog.setNeutralButton(R.string.label_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        exportDialog.show();
    }

    private void shareCsvFile() {
        final ScaleUser selectedScaleUser = OpenScale.getInstance().getSelectedScaleUser();

        File shareFile = new File(getApplicationContext().getCacheDir(),
                getExportFilename(selectedScaleUser));
        if (!OpenScale.getInstance().exportData(Uri.fromFile(shareFile))) {
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

        if (requestCode == ENABLE_BLUETOOTH_REQUEST) {
            if (resultCode == RESULT_OK) {
                invokeConnectToBluetoothDevice();
            }
            else {
                Toast.makeText(this, "Bluetooth " + getResources().getString(R.string.info_is_not_enable), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        OpenScale openScale = OpenScale.getInstance();

        switch (requestCode) {
            case IMPORT_DATA_REQUEST:
                openScale.importData(data.getData());
                break;
            case EXPORT_DATA_REQUEST:
                if (doExportData(data.getData())) {
                    SharedPreferences.Editor editor = prefs.edit();

                    String key = getExportPreferenceKey(openScale.getSelectedScaleUser());

                    // Remove any old persistable permission and export uri
                    try {
                        getContentResolver().releasePersistableUriPermission(
                                Uri.parse(prefs.getString(key, "")),
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        editor.remove(key);
                    }
                    catch (Exception ex) {
                        // Ignore
                    }

                    // Take persistable permission and save export uri
                    try {
                        getContentResolver().takePersistableUriPermission(
                                data.getData(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        editor.putString(key, data.getData().toString());
                    }
                    catch (Exception ex) {
                        // Ignore
                    }

                    editor.apply();
                }
                break;
        }
    }
}
