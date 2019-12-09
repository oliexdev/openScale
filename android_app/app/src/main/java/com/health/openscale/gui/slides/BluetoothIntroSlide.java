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
package com.health.openscale.gui.slides;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.health.openscale.R;
import com.health.openscale.gui.activities.BluetoothSettingsActivity;

import static android.app.Activity.RESULT_OK;

public class BluetoothIntroSlide extends Fragment {
    private static final String ARG_LAYOUT_RES_ID = "layoutResId";
    private int layoutResId;

    private Button btnSearchScale;
    private TextView txtFoundDevice;

    public static BluetoothIntroSlide newInstance(int layoutResId) {
        BluetoothIntroSlide sampleSlide = new BluetoothIntroSlide();

        Bundle args = new Bundle();
        args.putInt(ARG_LAYOUT_RES_ID, layoutResId);
        sampleSlide.setArguments(args);

        return sampleSlide;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null && getArguments().containsKey(ARG_LAYOUT_RES_ID)) {
            layoutResId = getArguments().getInt(ARG_LAYOUT_RES_ID);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(layoutResId, container, false);

        txtFoundDevice = view.findViewById(R.id.txtFoundDevice);
        txtFoundDevice.setText(getCurrentDeviceName());

        btnSearchScale = view.findViewById(R.id.btnSearchScale);
        btnSearchScale.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(getContext(), BluetoothSettingsActivity.class);
                startActivityForResult(intent, BluetoothSettingsActivity.GET_SCALE_REQUEST);
            }
        });

        return view;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK) {
            return;
        }
        if (requestCode == BluetoothSettingsActivity.GET_SCALE_REQUEST) {
            txtFoundDevice.setText(getCurrentDeviceName());
        }
    }

    private final String formatDeviceName(String name, String address) {
        if (name.isEmpty() || address.isEmpty()) {
            return "[" + getContext().getString(R.string.label_empty) + "]";
        }
        return String.format("%s [%s]", name, address);
    }

    private String getCurrentDeviceName() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        return formatDeviceName(
                prefs.getString(BluetoothSettingsActivity.PREFERENCE_KEY_BLUETOOTH_DEVICE_NAME, ""),
                prefs.getString(BluetoothSettingsActivity.PREFERENCE_KEY_BLUETOOTH_HW_ADDRESS, ""));
    }
}
