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
package com.health.openscale.gui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.runner.screenshot.BasicScreenCaptureProcessor;
import android.support.test.runner.screenshot.ScreenCapture;
import android.support.test.runner.screenshot.Screenshot;
import android.test.suitebuilder.annotation.LargeTest;

import com.health.openscale.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import static android.os.Environment.DIRECTORY_PICTURES;
import static android.os.Environment.getExternalStoragePublicDirectory;
import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.Espresso.pressBack;
import static android.support.test.espresso.contrib.DrawerActions.close;
import static android.support.test.espresso.contrib.DrawerActions.open;
import static android.support.test.espresso.contrib.NavigationViewActions.navigateTo;
import static android.support.test.espresso.matcher.ViewMatchers.withId;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class ScreenshotRecorder {
    private Context context;

    @Rule
    public ActivityTestRule<MainActivity> mActivityTestRule = new ActivityTestRule<>(MainActivity.class, false , false);

    private void setLangauge(String language, String country) {
        Locale locale = new Locale(language, country);
        Locale.setDefault(locale);
        Resources res = context.getResources();
        Configuration config = res.getConfiguration();
        config.locale = locale;
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    @Before
    public void initRecorder() {
        context = InstrumentationRegistry.getTargetContext();
    }

    @Test
    public void captureScreenshots() {
        setLangauge("en", "EN");
        screenshotRecorder();

        setLangauge("de", "DE");
        screenshotRecorder();
    }

    private void screenshotRecorder() {
        mActivityTestRule.launchActivity(null);

        captureScreenshot("overview");

        onView(withId(R.id.drawer_layout))
                .perform(open()); // Open Drawer

        onView(withId(R.id.navigation_view))
                .perform(navigateTo(R.id.nav_graph));

        onView(withId(R.id.drawer_layout))
                .perform(close()); // Close Drawer

        captureScreenshot("graph");

        onView(withId(R.id.drawer_layout))
                .perform(open()); // Open Drawer

        onView(withId(R.id.navigation_view))
                .perform(navigateTo(R.id.nav_table));

        onView(withId(R.id.drawer_layout))
                .perform(close()); // Close Drawer

        captureScreenshot("table");

        onView(withId(R.id.drawer_layout))
                .perform(open()); // Open Drawer

        onView(withId(R.id.navigation_view))
                .perform(navigateTo(R.id.nav_statistic));

        onView(withId(R.id.drawer_layout))
                .perform(close()); // Close Drawer

        captureScreenshot("statistic");

        onView(withId(R.id.drawer_layout))
                .perform(open()); // Open Drawer

        onView(withId(R.id.navigation_view))
                .perform(navigateTo(R.id.nav_settings));

        captureScreenshot("settings");

        pressBack();

        mActivityTestRule.finishActivity();
    }

    private void captureScreenshot(String name) {
        BasicScreenCaptureProcessor processor = new BasicScreenCaptureProcessor();

        ScreenCapture capture = Screenshot.capture();
        capture.setFormat(Bitmap.CompressFormat.PNG);
        capture.setName(name);
        try {
            String filename = processor.process(capture);

            // rename file to remove UUID suffix
            File folder = new File(getExternalStoragePublicDirectory(DIRECTORY_PICTURES) +  "/screenshots/openScale_" + Locale.getDefault().getLanguage());
            if (!folder.exists()) {
                folder.mkdir();
            }

            File from = new File(getExternalStoragePublicDirectory(DIRECTORY_PICTURES) + "/screenshots/" + filename);
            File to = new File(getExternalStoragePublicDirectory(DIRECTORY_PICTURES) +  "/screenshots/openScale_" + Locale.getDefault().getLanguage() + "/screen_" + name + ".png");
            from.renameTo(to);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
