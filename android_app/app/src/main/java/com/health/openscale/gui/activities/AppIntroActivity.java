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

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.github.paolorotolo.appintro.AppIntro;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.gui.slides.BluetoothIntroSlide;
import com.health.openscale.gui.slides.MetricsIntroSlide;
import com.health.openscale.gui.slides.OpenSourceIntroSlide;
import com.health.openscale.gui.slides.PrivacyIntroSlide;
import com.health.openscale.gui.slides.SupportIntroSlide;
import com.health.openscale.gui.slides.UserIntroSlide;
import com.health.openscale.gui.slides.WelcomeIntroSlide;

public class AppIntroActivity extends AppIntro {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setBarColor(getResources().getColor(R.color.blue_normal));

        setSkipButtonEnabled(true);

        addSlide(WelcomeIntroSlide.newInstance(R.layout.slide_welcome));
        addSlide(PrivacyIntroSlide.newInstance(R.layout.slide_privacy));
        addSlide(UserIntroSlide.newInstance(R.layout.slide_user));
        addSlide(OpenSourceIntroSlide.newInstance(R.layout.slide_opensource));
        addSlide(BluetoothIntroSlide.newInstance(R.layout.slide_bluetooth));
        addSlide(MetricsIntroSlide.newInstance(R.layout.slide_metrics));
        addSlide(SupportIntroSlide.newInstance(R.layout.slide_support));
    }

    @Override
    public void onSkipPressed(Fragment currentFragment) {
        super.onSkipPressed(currentFragment);
        finish();
        checkUserCreation();
    }

    @Override
    public void onDonePressed(Fragment currentFragment) {
        super.onDonePressed(currentFragment);
        finish();
        checkUserCreation();
    }

    @Override
    public void onSlideChanged(@Nullable Fragment oldFragment, @Nullable Fragment newFragment) {
        super.onSlideChanged(oldFragment, newFragment);

        if (newFragment instanceof WelcomeIntroSlide) {
            setSkipButtonEnabled(true);
            setWizardMode(false);
        } else {
            setSkipButtonEnabled(false);
            setWizardMode(true);
        }
    }

    private void checkUserCreation() {
        if (OpenScale.getInstance().getSelectedScaleUserId() == -1) {
            Intent intent = new Intent(this, UserSettingsActivity.class);
            intent.putExtra(UserSettingsActivity.EXTRA_MODE, UserSettingsActivity.ADD_USER_REQUEST);
            startActivity(intent);
        }
    }
}
