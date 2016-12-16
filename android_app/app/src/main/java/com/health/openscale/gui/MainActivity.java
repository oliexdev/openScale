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

import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.BluetoothCommunication;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;

import java.util.Locale;


public class MainActivity extends ActionBarActivity implements
		ActionBar.TabListener {
	
	/**
	 * The {@link android.support.v4.view.PagerAdapter} that will provide
	 * fragments for each of the sections. We use a {@link FragmentPagerAdapter}
	 * derivative, which will keep every loaded fragment in memory. If this
	 * becomes too memory intensive, it may be best to switch to a
	 * {@link android.support.v4.app.FragmentStatePagerAdapter}.
	 */
	private SectionsPagerAdapter mSectionsPagerAdapter;

	private static boolean firstAppStart = false;
	private static int bluetoothStatusIcon = 0;
	private static MenuItem bluetoothStatus;

	/**
	 * The {@link ViewPager} that will host the section contents.
	 */
	private ViewPager mViewPager;


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		// Set up the action bar.
		final ActionBar actionBar = getSupportActionBar();
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        actionBar.setLogo(R.drawable.ic_launcher);
        actionBar.setDisplayUseLogoEnabled(true);
        actionBar.setDisplayShowHomeEnabled(true);

		// Create the adapter that will return a fragment for each of the three
		// primary sections of the activity.
		mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());

		// Set up the ViewPager with the sections adapter.
		mViewPager = (ViewPager) findViewById(R.id.pager);
		mViewPager.setAdapter(mSectionsPagerAdapter);

		// When swiping between different sections, select the corresponding
		// tab. We can also use ActionBar.Tab#select() to do this if we have
		// a reference to the Tab.
		mViewPager.setOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
					@Override
					public void onPageSelected(int position) {
						actionBar.setSelectedNavigationItem(position);
					}
				});

		// For each of the sections in the app, add a tab to the action bar.
		for (int i = 0; i < mSectionsPagerAdapter.getCount(); i++) {
			// Create a tab with text corresponding to the page title defined by
			// the adapter. Also specify this Activity object, which implements
			// the TabListener interface, as the callback (listener) for when
			// this tab is selected.
			actionBar.addTab(actionBar.newTab()
					.setText(mSectionsPagerAdapter.getPageTitle(i))
					.setTabListener(this));
		}


        if (prefs.getBoolean("firstStart", true)) {
            Intent intent = new Intent(this, UserSettingsActivity.class);
            intent.putExtra("mode", UserSettingsActivity.ADD_USER_REQUEST);
            startActivity(intent);

            prefs.edit().putBoolean("firstStart", false).commit();
        }
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);

		bluetoothStatus = menu.findItem(R.id.action_bluetooth_status);

		// Just search for a bluetooth device just once at the start of the app
		if (!firstAppStart) {
			invokeSearchBluetoothDevice();
			firstAppStart = true;
		} else {
			// Set current bluetooth status icon while e.g. orientation changes
			setBluetoothStatusIcon(bluetoothStatusIcon);
		}

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();

		if (id == R.id.action_general_settings) {
			Intent intent = new Intent(this, SettingsActivity.class);
			startActivityForResult(intent, 0);
			return true;
		}

		if (id == R.id.action_bluetooth_status) {
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

			if (prefs.getBoolean("btEnable", false) && BluetoothAdapter.getDefaultAdapter().isEnabled()) {
				String deviceName = prefs.getString("btDeviceName", "MI_SCALE");
				Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_try_reconnection) + " " + deviceName, Toast.LENGTH_SHORT).show();
				invokeSearchBluetoothDevice();
			} else {
				setBluetoothStatusIcon(R.drawable.bluetooth_disabled);
				Toast.makeText(getApplicationContext(), "Bluetooth " + getResources().getString(R.string.info_is_not_enable), Toast.LENGTH_SHORT).show();
			}
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		invokeSearchBluetoothDevice();
	}

	private void invokeSearchBluetoothDevice() {
		if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
			return;
		}

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

		if(prefs.getBoolean("btEnable", false)) {
			String deviceName = prefs.getString("btDeviceName", "MI_SCALE");
			String deviceType = prefs.getString("btDeviceTypes", "0");

			// Check if Bluetooth 4.x is available
			if (Integer.parseInt(deviceType) == BluetoothCommunication.BT_MI_SCALE) {
				if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
					setBluetoothStatusIcon(R.drawable.bluetooth_disabled);
					Toast.makeText(getApplicationContext(), "Bluetooth 4.x " + getResources().getString(R.string.info_is_not_available), Toast.LENGTH_SHORT).show();
					return;
				}
			}

			setBluetoothStatusIcon(R.drawable.bluetooth_searching);

			OpenScale.getInstance(getApplicationContext()).stopSearchingForBluetooth();
			OpenScale.getInstance(getApplicationContext()).startSearchingForBluetooth(Integer.parseInt(deviceType), deviceName, callbackBtHandler);
		} else {
			setBluetoothStatusIcon(R.drawable.bluetooth_disabled);
		}
	}

	private final Handler callbackBtHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {

			switch (msg.what) {
				case BluetoothCommunication.BT_RETRIEVE_SCALE_DATA:
					setBluetoothStatusIcon(R.drawable.bluetooth_connection_success);
					ScaleData scaleBtData = (ScaleData) msg.obj;

					// if no user id is set, use the current user id
					if (scaleBtData.user_id == -1) {
						scaleBtData.user_id = OpenScale.getInstance(getApplicationContext()).getSelectedScaleUser().id;
					}

					OpenScale.getInstance(getApplicationContext()).addScaleData(scaleBtData);
					OpenScale.getInstance(getApplicationContext()).updateScaleData();
					break;
				case BluetoothCommunication.BT_CONNECTION_LOST:
					setBluetoothStatusIcon(R.drawable.bluetooth_connection_lost);
					Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_lost), Toast.LENGTH_SHORT).show();
					Log.d("OpenScale", "Bluetooth connection lost");
					break;
				case BluetoothCommunication.BT_NO_DEVICE_FOUND:
					setBluetoothStatusIcon(R.drawable.bluetooth_connection_lost);
					Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_no_device), Toast.LENGTH_SHORT).show();
					Log.d("OpenScale", "No Bluetooth device found");
					break;
				case BluetoothCommunication.BT_CONNECTION_ESTABLISHED:
					setBluetoothStatusIcon(R.drawable.bluetooth_connection_success);
					Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_successful), Toast.LENGTH_SHORT).show();
					Log.d("OpenScale", "Bluetooth connection successful established");
					break;
				case BluetoothCommunication.BT_UNEXPECTED_ERROR:
					setBluetoothStatusIcon(R.drawable.bluetooth_connection_lost);
					Toast.makeText(getApplicationContext(), getResources().getString(R.string.info_bluetooth_connection_error) + ": " + msg.obj, Toast.LENGTH_SHORT).show();
					Log.e("OpenScale", "Bluetooth unexpected error: " + msg.obj);
					break;
			}
		}
	};

	private void setBluetoothStatusIcon(int iconRessource) {
		bluetoothStatusIcon = iconRessource;
		bluetoothStatus.setIcon(getResources().getDrawable(bluetoothStatusIcon));
	}

	@Override
	public void onTabSelected(ActionBar.Tab tab,
			FragmentTransaction fragmentTransaction) {
		// When the given tab is selected, switch to the corresponding page in
		// the ViewPager.
		mViewPager.setCurrentItem(tab.getPosition());
	}

	@Override
	public void onTabUnselected(ActionBar.Tab tab,
			FragmentTransaction fragmentTransaction) {
	}

	@Override
	public void onTabReselected(ActionBar.Tab tab,
			FragmentTransaction fragmentTransaction) {
	}
	
	/**
	 * A {@link FragmentPagerAdapter} that returns a fragment corresponding to
	 * one of the sections/tabs/pages.
	 */
	public class SectionsPagerAdapter extends FragmentPagerAdapter {
		
		private OverviewFragment overviewFrag;
		private GraphFragment graphFrag;
		private TableFragment tableFrag;
		
		public SectionsPagerAdapter(FragmentManager fm) {
			super(fm);
			
			overviewFrag = new OverviewFragment();
			graphFrag = new GraphFragment();
			tableFrag = new TableFragment();
		}

		@Override
		public Fragment getItem(int position) {
			// getItem is called to instantiate the fragment for the given page.
			// Return a PlaceholderFragment (defined as a static inner class
			// below).
			
			switch (position) {
			case 0:
				return overviewFrag;
			case 1:
				return graphFrag;
			case 2:
				return tableFrag;
			}

			return null;
		}

		@Override
		public int getCount() {
			return 3;
		}

		@Override
		public CharSequence getPageTitle(int position) {
			Locale l = Locale.getDefault();
			switch (position) {
			case 0:
				return getString(R.string.title_overview).toUpperCase(l);
			case 1:
				return getString(R.string.title_graph).toUpperCase(l);
			case 2:
				return getString(R.string.title_frag).toUpperCase(l);				
			}
			return null;
		}
	}
}
