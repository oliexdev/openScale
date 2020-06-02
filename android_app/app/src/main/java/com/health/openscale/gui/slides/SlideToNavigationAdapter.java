/* Copyright (C) 2020  olie.xdev <olie.xdev@googlemail.com>
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

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;

import com.health.openscale.R;
import com.health.openscale.SlideNavigationDirections;

// TODO HACK to access from AppIntro activity to MainActivity fragments until AppIntro support native Androidx navigation component
public class SlideToNavigationAdapter extends AppCompatActivity {
    public static String EXTRA_MODE = "mode";
    public static final int EXTRA_USER_SETTING_MODE = 100;
    public static final int EXTRA_BLUETOOTH_SETTING_MODE = 200;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_slidetonavigation);

        // Set a Toolbar to replace the ActionBar.
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        int mode = getIntent().getExtras().getInt(EXTRA_MODE);

        NavDirections action = null;

        switch (mode) {
            case EXTRA_USER_SETTING_MODE:
                action = SlideNavigationDirections.actionNavSlideNavigationToNavUsersettings();
                setTitle(R.string.label_add_user);
                break;
            case EXTRA_BLUETOOTH_SETTING_MODE:
                action = SlideNavigationDirections.actionNavSlideNavigationToNavBluetoothsettings();
                setTitle(R.string.label_bluetooth_title);
                break;
        }

        if (action != null) {
            Navigation.findNavController(this, R.id.nav_slide_navigation).navigate(action);
        }
    }
}
