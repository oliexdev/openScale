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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import com.health.openscale.BuildConfig;
import com.health.openscale.MobileNavigationDirections;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.gui.measurement.MeasurementEntryFragment;
import com.health.openscale.gui.preferences.BluetoothSettingsFragment;
import com.health.openscale.gui.preferences.UserSettingsFragment;
import com.health.openscale.gui.slides.AppIntroActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import cat.ereza.customactivityoncrash.config.CaocConfig;
import timber.log.Timber;

public class MainActivity extends AppCompatActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener{
    public static final String PREFERENCE_LANGUAGE = "language";
    private static Locale systemDefaultLocale = null;
    private SharedPreferences prefs;
    private static boolean firstAppStart = true;
    private static boolean valueOfCountModified = false;
    private static int bluetoothStatusIcon = R.drawable.ic_bluetooth_disabled;
    private static MenuItem bluetoothStatus;

    private static final int IMPORT_DATA_REQUEST = 100;
    private static final int EXPORT_DATA_REQUEST = 101;
    private static final int ENABLE_BLUETOOTH_REQUEST = 102;
    private static final int APPINTRO_REQUEST = 103;

    private AppBarConfiguration mAppBarConfiguration;
    private DrawerLayout drawerLayout;
    private NavController navController;
    private NavigationView navigationView;
    private BottomNavigationView navigationBottomView;

    private boolean settingsActivityRunning = false;

    public static Context createBaseContext(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String language = prefs.getString(PREFERENCE_LANGUAGE, "");
        if (language.isEmpty() || language.equals("default")) {
            if (systemDefaultLocale != null) {
                Locale.setDefault(systemDefaultLocale);
                systemDefaultLocale = null;
            }
            return context;
        }

        if (systemDefaultLocale == null) {
            systemDefaultLocale = Locale.getDefault();
        }

        Locale locale;
        String[] localeParts = TextUtils.split(language, "-");
        if (localeParts.length == 2) {
            locale = new Locale(localeParts[0], localeParts[1]);
        }
        else {
            locale = new Locale(localeParts[0]);
        }
        Locale.setDefault(locale);

        Configuration config = context.getResources().getConfiguration();
        config.setLocale(locale);

        return context.createConfigurationContext(config);
    }

    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(createBaseContext(context));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        String prefTheme = prefs.getString("app_theme", "Light");

        if (prefTheme.equals("Dark")) {
            if (Build.VERSION.SDK_INT >= 29) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            } else {
                setTheme(R.style.AppTheme_Dark);
            }
        }

        super.onCreate(savedInstanceState);

        CaocConfig.Builder.create()
                .trackActivities(false)
                .apply();

        setContentView(R.layout.activity_main);

        // Set a Toolbar to replace the ActionBar.
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSupportNavigateUp();
            }
        });

        // Find our drawer view
        drawerLayout = findViewById(R.id.drawer_layout);

        // Find our drawer view
        navigationView = findViewById(R.id.navigation_view);
        navigationBottomView = findViewById(R.id.navigation_bottom_view);

        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_overview, R.id.nav_graph, R.id.nav_table, R.id.nav_statistic, R.id.nav_main_preferences)
                .setOpenableLayout(drawerLayout)
                .build();
        navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);
        NavigationUI.setupWithNavController(navigationBottomView, navController);

        navigationView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.nav_donation:
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=H5KSTQA6TKTE4&source=url")));
                        drawerLayout.closeDrawers();
                        return true;
                    case R.id.nav_help:
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/oliexdev/openScale/wiki")));
                        drawerLayout.closeDrawers();
                        return true;
                }

                prefs.edit().putInt("lastFragmentId", item.getItemId()).apply();
                NavigationUI.onNavDestinationSelected(item, navController);

                // Close the navigation drawer
                drawerLayout.closeDrawers();

                return true;
            }
        });

        navigationBottomView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                prefs.edit().putInt("lastFragmentId", item.getItemId()).apply();
                NavigationUI.onNavDestinationSelected(item, navController);
                return true;
            }
        });

        navigationBottomView.setSelectedItemId(prefs.getInt("lastFragmentId", R.id.nav_overview));

        if (BuildConfig.BUILD_TYPE == "light") {
            ImageView launcherIcon = navigationView.getHeaderView(0).findViewById(R.id.profileImageView);
            launcherIcon.setImageResource(R.drawable.ic_launcher_openscale_light);
            navigationView.getMenu().findItem(R.id.nav_donation).setVisible(false);
        } else if (BuildConfig.BUILD_TYPE == "pro") {
            ImageView launcherIcon = navigationView.getHeaderView(0).findViewById(R.id.profileImageView);
            launcherIcon.setImageResource(R.drawable.ic_launcher_openscale_pro);
            navigationView.getMenu().findItem(R.id.nav_donation).setVisible(false);
        }

        if (prefs.getBoolean("firstStart", true)) {
            Intent appIntroIntent = new Intent(this, AppIntroActivity.class);
            startActivityForResult(appIntroIntent, APPINTRO_REQUEST);

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
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration)
                || super.onSupportNavigateUp();
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

    private void showNoSelectedUserDialog() {
        AlertDialog.Builder infoDialog = new AlertDialog.Builder(this);

        infoDialog.setMessage(getResources().getString(R.string.info_no_selected_user));
        infoDialog.setPositiveButton(getResources().getString(R.string.label_ok), null);
        infoDialog.show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                drawerLayout.openDrawer(GravityCompat.START);
                return true;
            case R.id.action_add_measurement:
                if (OpenScale.getInstance().getSelectedScaleUserId() == -1) {
                    showNoSelectedUserDialog();
                    return true;
                }

                if (OpenScale.getInstance().getSelectedScaleUser().isAssistedWeighing()) {
                    showAssistedWeighingDialog(true);
                } else {
                    MobileNavigationDirections.ActionNavMobileNavigationToNavDataentry action = MobileNavigationDirections.actionNavMobileNavigationToNavDataentry();
                    action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.ADD);
                    action.setTitle(getString(R.string.label_add_measurement));
                    Navigation.findNavController(this, R.id.nav_host_fragment).navigate(action);
                }
                return true;
            case R.id.action_bluetooth_status:
                if (OpenScale.getInstance().disconnectFromBluetoothDevice()) {
                    setBluetoothStatusIcon(R.drawable.ic_bluetooth_disabled);
                }
                else {
                    if (OpenScale.getInstance().getSelectedScaleUserId() == -1) {
                        showNoSelectedUserDialog();
                        return true;
                    }

                    if (OpenScale.getInstance().getSelectedScaleUser().isAssistedWeighing()) {
                        showAssistedWeighingDialog(false);
                    } else {
                        invokeConnectToBluetoothDevice();
                    }
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

    private void showAssistedWeighingDialog(boolean manuelEntry) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setPadding(50, 50, 0, 0);
        TextView title = new TextView(this);
        title.setText(R.string.label_assisted_weighing);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);

        TextView description = new TextView(this);
        description.setPadding(0, 20, 0, 0);
        description.setText(R.string.info_assisted_weighing_choose_reference_user);
        linearLayout.addView(title);
        linearLayout.addView(description);

        builder.setCustomTitle(linearLayout);

        List<ScaleUser> scaleUserList = OpenScale.getInstance().getScaleUserList();
        ArrayList<String> infoTexts = new ArrayList<>();
        ArrayList<Integer> userIds = new ArrayList<>();

        int assistedWeighingRefUserId = prefs.getInt("assistedWeighingRefUserId", -1);
        int checkedItem = 0;

        for (ScaleUser scaleUser : scaleUserList) {
            String singleInfoText = scaleUser.getUserName();

            if (!scaleUser.isAssistedWeighing()) {
                ScaleMeasurement lastRefScaleMeasurement = OpenScale.getInstance().getLastScaleMeasurement(scaleUser.getId());

                if (lastRefScaleMeasurement != null) {
                    singleInfoText += " [" + Converters.fromKilogram(lastRefScaleMeasurement.getWeight(), scaleUser.getScaleUnit()) + scaleUser.getScaleUnit().toString() + "]";
                } else {
                    singleInfoText += " [" + getString(R.string.label_empty) + "]";
                }

                infoTexts.add(singleInfoText);
                userIds.add(scaleUser.getId());
            }

            if (scaleUser.getId() == assistedWeighingRefUserId) {
                checkedItem = infoTexts.indexOf(singleInfoText);
            }
        }

        if (!infoTexts.isEmpty()) {
            builder.setSingleChoiceItems(infoTexts.toArray(new CharSequence[infoTexts.size()]), checkedItem, null);
        } else {
            builder.setMessage(getString(R.string.info_assisted_weighing_no_reference_user));
        }

        builder.setNegativeButton(R.string.label_cancel, null);
        builder.setPositiveButton(R.string.label_ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int selectedPosition = ((AlertDialog)dialog).getListView().getCheckedItemPosition();
                prefs.edit().putInt("assistedWeighingRefUserId", userIds.get(selectedPosition)).commit();

                ScaleMeasurement lastRefScaleMeasurement = OpenScale.getInstance().getLastScaleMeasurement(userIds.get(selectedPosition));

                if (lastRefScaleMeasurement != null) {
                    Calendar calMinusOneDay = Calendar.getInstance();
                    calMinusOneDay.add(Calendar.DAY_OF_YEAR, -1);

                    if (calMinusOneDay.getTime().after(lastRefScaleMeasurement.getDateTime())) {
                        Toast.makeText(getApplicationContext(), getString(R.string.info_assisted_weighing_old_reference_measurement), Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(getApplicationContext(), getString(R.string.info_assisted_weighing_no_reference_measurements), Toast.LENGTH_LONG).show();
                    return;
                }

                if (manuelEntry) {
                    MobileNavigationDirections.ActionNavMobileNavigationToNavDataentry action = MobileNavigationDirections.actionNavMobileNavigationToNavDataentry();
                    action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.ADD);
                    action.setTitle(getString(R.string.label_add_measurement));
                    navController.navigate(action);
                } else {
                    invokeConnectToBluetoothDevice();
                }
            }
        });

        AlertDialog dialog = builder.create();
        dialog.show();
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

        return super.onCreateOptionsMenu(menu);
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
                BluetoothSettingsFragment.PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME, "");
        String hwAddress = prefs.getString(
                BluetoothSettingsFragment.PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS, "");

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
                        if (!openScale.isScaleMeasurementListEmpty()) {
                            ScaleMeasurement lastMeasurement = openScale.getLastScaleMeasurement();
                            scaleBtData.merge(lastMeasurement);
                        }
                    }

                    openScale.addScaleMeasurement(scaleBtData, true);
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
                    try {
                        String toastMessage = String.format(getResources().getString(msg.arg1), msg.obj);
                        Toast.makeText(getApplicationContext(), toastMessage, Toast.LENGTH_LONG).show();
                        Timber.d("Bluetooth scale message: " + toastMessage);
                    } catch (Exception ex) {
                        Timber.e("Bluetooth scale message error: " + ex);
                    }
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

        OpenScale openScale = OpenScale.getInstance();

        if (requestCode == ENABLE_BLUETOOTH_REQUEST) {
            if (resultCode == RESULT_OK) {
                invokeConnectToBluetoothDevice();
            }
            else {
                Toast.makeText(this, "Bluetooth " + getResources().getString(R.string.info_is_not_enable), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == APPINTRO_REQUEST) {
            if (openScale.getSelectedScaleUserId() == -1) {
                MobileNavigationDirections.ActionNavMobileNavigationToNavUsersettings action = MobileNavigationDirections.actionNavMobileNavigationToNavUsersettings();
                action.setMode(UserSettingsFragment.USER_SETTING_MODE.ADD);
                action.setTitle(getString(R.string.label_add_user));
                Navigation.findNavController(this, R.id.nav_host_fragment).navigate(action);
            }
        }

        if (resultCode != RESULT_OK || data == null) {
            return;
        }


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
