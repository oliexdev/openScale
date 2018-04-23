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
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.preference.PreferenceManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.runner.screenshot.BasicScreenCaptureProcessor;
import android.support.test.runner.screenshot.ScreenCapture;
import android.support.test.runner.screenshot.Screenshot;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.Gravity;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.CsvHelper;
import com.health.openscale.gui.activities.BaseAppCompatActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;

import static android.os.Environment.DIRECTORY_PICTURES;
import static android.os.Environment.getExternalStoragePublicDirectory;
import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.Espresso.pressBack;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.contrib.DrawerActions.close;
import static android.support.test.espresso.contrib.DrawerActions.open;
import static android.support.test.espresso.contrib.DrawerMatchers.isClosed;
import static android.support.test.espresso.contrib.NavigationViewActions.navigateTo;
import static android.support.test.espresso.matcher.ViewMatchers.withId;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class ScreenshotRecorder {
    private Context context;
    private OpenScale openScale;
    private final int WAIT_MS = 500;

    @Rule
    public ActivityTestRule<MainActivity> mActivityTestRule = new ActivityTestRule<>(MainActivity.class, false , false);

    @Before
    public void initRecorder() {
        context = InstrumentationRegistry.getTargetContext();
        openScale = OpenScale.getInstance();

        // Set first start to true to get the user add dialog
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("firstStart", false)
                .putBoolean("waistEnable", true)
                .putBoolean("hipEnable", true)
                .putBoolean("boneEnable", true)
                .commit();
    }

    @Test
    public void captureScreenshots() {
        try {
            mActivityTestRule.runOnUiThread(new Runnable() {
                public void run() {
                    prepareData();
                }
            });
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String language = prefs.getString(BaseAppCompatActivity.PREFERENCE_LANGUAGE, "default");

        prefs.edit()
                .remove("lastFragmentId")
                .putString(BaseAppCompatActivity.PREFERENCE_LANGUAGE, "en")
                .commit();
        screenshotRecorder();

        prefs.edit()
                .remove("lastFragmentId")
                .putString(BaseAppCompatActivity.PREFERENCE_LANGUAGE, "de")
                .commit();
        screenshotRecorder();

        // Restore language setting
        prefs.edit()
                .putString(BaseAppCompatActivity.PREFERENCE_LANGUAGE, language)
                .commit();
    }

    private ScaleUser getTestUser() {
        ScaleUser user = new ScaleUser();
        user.setUserName("Test");
        user.setBodyHeight(180);
        user.setInitialWeight(80.0f);
        user.setGoalWeight(60.0f);

        Calendar birthday = Calendar.getInstance();
        birthday.add(Calendar.YEAR, -28);
        birthday.set(birthday.get(Calendar.YEAR), Calendar.JANUARY, 19, 0, 0, 0);
        birthday.set(Calendar.MILLISECOND, 0);

        user.setBirthday(birthday.getTime());

        Calendar goalDate = Calendar.getInstance();
        goalDate.add(Calendar.YEAR, 1);
        goalDate.set(goalDate.get(Calendar.YEAR), Calendar.JANUARY, 31, 0, 0, 0);
        goalDate.set(Calendar.MILLISECOND, 0);

        user.setGoalDate(goalDate.getTime());

        return user;
    }

    private List<ScaleMeasurement> getTestMeasurements() {
        List<ScaleMeasurement> scaleMeasurementList = new ArrayList<>();

        String data = "\"dateTime\",\"weight\",\"fat\",\"water\",\"muscle\",\"lbm\",\"bone\",\"waist\",\"hip\",\"comment\"\n" +
                        "04.08.2015 08:08,89.7,21.2,58.0,41.5\n" +
                        "03.08.2015 05:17,89.0,26.4,54.6,41.6\n" +
                        "02.08.2015 07:32,88.8,25.0,55.6,41.7\n" +
                        "31.07.2015 04:39,89.1,29.2,52.8,41.6\n" +
                        "18.07.2015 07:54,91.3,22.1,57.4,41.2\n" +
                        "12.07.2015 07:14,91.1,21.9,57.6,41.3\n" +
                        "16.06.2015 05:16,89.5,25.3,55.4,41.5\n" +
                        "15.06.2015 05:34,90.1,26.3,54.7,41.4\n" +
                        "12.06.2015 05:36,90.3,26.4,54.6,41.4\n" +
                        "10.06.2015 04:22,90.8,22.3,57.3,41.3\n" +
                        "07.06.2015 10:17,90.0,22.6,57.1,41.4\n" +
                        "06.06.2015 06:36,91.0,21.6,57.8,41.3\n" +
                        "05.06.2015 06:57,91.6,21.7,57.7,41.2\n" +
                        "04.06.2015 06:35,90.4,23.5,56.5,41.4\n" +
                        "25.05.2015 10:25,89.5,21.6,57.8,41.5\n" +
                        "17.05.2015 09:55,92.5,21.9,57.6,41.0\n" +
                        "09.05.2015 09:30,89.0,21.6,57.8,41.6\n" +
                        "29.04.2015 08:25,89.2,21.0,58.2,41.4\n" +
                        "13.04.2015 04:54,87.6,32.7,50.6,41.9\n" +
                        "11.04.2015 07:41,86.8,20.9,58.3,42.0\n" +
                        "10.04.2015 05:27,86.4,24.0,56.3,42.1\n" +
                        "06.04.2015 06:45,87.6,24.4,56.0,41.9\n" +
                        "01.04.2015 05:03,88.6,25.6,55.2,41.7\n" +
                        "28.03.2015 07:06,87.1,23.5,56.6,42.2\n" +
                        "21.03.2015 18:21,88.1,20.7,58.5,42.0\n" +
                        "15.03.2015 20:56,90.3,22.6,57.1,41.6\n" +
                        "14.03.2015 07:37,87.2,25.3,55.5,42.1\n" +
                        "13.03.2015 06:11,85.6,27.4,54.1,42.4\n" +
                        "17.02.2015 10:32,86.6,20.6,58.5,42.2\n" +
                        "16.02.2015 07:59,87.5,27.6,53.9,42.1\n" +
                        "15.02.2015 10:38,86.4,23.4,56.7,42.3\n" +
                        "14.02.2015 09:18,87.5,20.5,58.6,42.1\n" +
                        "08.02.2015 07:05,85.5,26.6,54.6,42.4\n" +
                        "06.02.2015 06:09,85.8,30.3,52.2,42.4\n" +
                        "05.02.2015 06:16,86.5,31.2,51.6,42.3\n" +
                        "04.02.2015 06:10,86.7,28.3,53.5,42.2\n" +
                        "01.02.2015 08:59,87.4,22.2,57.5,42.1\n" +
                        "24.01.2015 09:55,85.1,24.1,56.2,42.5\n" +
                        "18.01.2015 11:11,86.1,20.1,58.9,42.3\n" +
                        "14.01.2015 06:11,86.9,26.3,54.8,42.2\n" +
                        "07.01.2015 07:08,85.6,20.3,58.7,42.4\n" +
                        "06.01.2015 10:34,85.5,19.7,59.1,42.4\n" +
                        "05.01.2015 08:25,85.6,26.1,54.9,42.4\n" +
                        "02.01.2015 18:06,86.3,19.8,59.1,42.3\n" +
                        "13.12.2014 13:16,85.2,19.3,59.4,42.5\n" +
                        "09.12.2014 19:36,86.9,20.3,58.7,42.2\n" +
                        "08.12.2014 20:28,86.8,19.9,59.0,42.2\n" +
                        "05.12.2014 18:21,86.7,20.3,58.7,42.2\n";

        try {
            scaleMeasurementList = CsvHelper.importFrom(new BufferedReader(new StringReader(data)));
        } catch (IOException | ParseException e) {
            Timber.e(e);
        }

        // set current year to the measurement data
        Calendar measurementDate = Calendar.getInstance();
        int year = measurementDate.get(Calendar.YEAR);

        for (ScaleMeasurement measurement : scaleMeasurementList) {
            measurementDate.setTime(measurement.getDateTime());
            measurementDate.set(Calendar.YEAR, year);
            measurement.setDateTime(measurementDate.getTime());
        }

        return scaleMeasurementList;
    }

    private void prepareData() {
        int userId = openScale.addScaleUser(getTestUser());
        openScale.selectScaleUser(userId);

        List<ScaleMeasurement> scaleMeasurementList = getTestMeasurements();

        for (ScaleMeasurement measurement : scaleMeasurementList) {
            openScale.addScaleData(measurement, true);
        }
    }

    private void screenshotRecorder() {
        try {
            mActivityTestRule.launchActivity(null);

            Thread.sleep(WAIT_MS);
            captureScreenshot("overview");

            onView(withId(R.id.action_add_measurement)).perform(click());

            Thread.sleep(WAIT_MS);
            captureScreenshot("dataentry");

            pressBack();

            onView(withId(R.id.drawer_layout))
                    .perform(open()); // Open Drawer

            onView(withId(R.id.navigation_view))
                    .perform(navigateTo(R.id.nav_graph));

            onView(withId(R.id.drawer_layout))
                    .perform(close()); // Close Drawer

            onView(withId(R.id.drawer_layout))
                    .check(matches(isClosed(Gravity.LEFT)));

            Thread.sleep(WAIT_MS);
            captureScreenshot("graph");

            onView(withId(R.id.drawer_layout))
                    .perform(open()); // Open Drawer

            onView(withId(R.id.navigation_view))
                    .perform(navigateTo(R.id.nav_table));

            onView(withId(R.id.drawer_layout))
                    .perform(close()); // Close Drawer

            onView(withId(R.id.drawer_layout))
                    .check(matches(isClosed(Gravity.LEFT)));

            Thread.sleep(WAIT_MS);
            captureScreenshot("table");

            onView(withId(R.id.drawer_layout))
                    .perform(open()); // Open Drawer

            onView(withId(R.id.navigation_view))
                    .perform(navigateTo(R.id.nav_statistic));

            onView(withId(R.id.drawer_layout))
                    .perform(close()); // Close Drawer

            onView(withId(R.id.drawer_layout))
                    .check(matches(isClosed(Gravity.LEFT)));

            Thread.sleep(WAIT_MS);
            captureScreenshot("statistics");

            mActivityTestRule.finishActivity();
        } catch (InterruptedException e) {
            Timber.e(e);
        }
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
