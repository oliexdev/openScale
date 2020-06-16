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
import android.preference.PreferenceManager;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.TimePicker;

import androidx.test.espresso.contrib.PickerActions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.gui.measurement.BicepsMeasurementView;
import com.health.openscale.gui.measurement.BoneMeasurementView;
import com.health.openscale.gui.measurement.Caliper1MeasurementView;
import com.health.openscale.gui.measurement.Caliper2MeasurementView;
import com.health.openscale.gui.measurement.Caliper3MeasurementView;
import com.health.openscale.gui.measurement.ChestMeasurementView;
import com.health.openscale.gui.measurement.CommentMeasurementView;
import com.health.openscale.gui.measurement.DateMeasurementView;
import com.health.openscale.gui.measurement.FatMeasurementView;
import com.health.openscale.gui.measurement.HipMeasurementView;
import com.health.openscale.gui.measurement.LBMMeasurementView;
import com.health.openscale.gui.measurement.MuscleMeasurementView;
import com.health.openscale.gui.measurement.NeckMeasurementView;
import com.health.openscale.gui.measurement.ThighMeasurementView;
import com.health.openscale.gui.measurement.TimeMeasurementView;
import com.health.openscale.gui.measurement.VisceralFatMeasurementView;
import com.health.openscale.gui.measurement.WaistMeasurementView;
import com.health.openscale.gui.measurement.WaterMeasurementView;
import com.health.openscale.gui.measurement.WeightMeasurementView;

import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.List;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withClassName;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class AddMeasurementTest {
    private static Context context;
    private static OpenScale openScale;

    private static final ScaleUser male = TestData.getMaleUser();
    private static final ScaleUser female = TestData.getFemaleUser();

    @Rule
    public ActivityTestRule<MainActivity> mActivityTestRule = new ActivityTestRule<>(MainActivity.class, false, true);

    @BeforeClass
    public static void initTest() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        openScale = OpenScale.getInstance();

        male.setId(openScale.addScaleUser(male));
        female.setId(openScale.addScaleUser(female));

        // Set first start to true to get the user add dialog
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putBoolean("firstStart", false)
                .putString(MainActivity.PREFERENCE_LANGUAGE, "en")
                .putBoolean(VisceralFatMeasurementView.KEY + "Enable", true)
                .putBoolean(LBMMeasurementView.KEY + "Enable", true)
                .putBoolean(BoneMeasurementView.KEY + "Enable", true)
                .putBoolean(WaistMeasurementView.KEY + "Enable", true)
                .putBoolean(HipMeasurementView.KEY + "Enable", true)
                .putBoolean(ChestMeasurementView.KEY + "Enable", true)
                .putBoolean(BicepsMeasurementView.KEY + "Enable", true)
                .putBoolean(ThighMeasurementView.KEY + "Enable", true)
                .putBoolean(NeckMeasurementView.KEY + "Enable", true)
                .putBoolean(Caliper1MeasurementView.KEY + "Enable", true)
                .putBoolean(Caliper2MeasurementView.KEY + "Enable", true)
                .putBoolean(Caliper3MeasurementView.KEY + "Enable", true)
                .commit();
    }

    @AfterClass
    public static void cleanup() {
        openScale.deleteScaleUser(male.getId());
        openScale.deleteScaleUser(female.getId());
    }

    @Test
    public void addMeasurementMaleTest() {
        openScale.selectScaleUser(male.getId());

        ScaleMeasurement measurement = TestData.getMeasurement(1);
        onView(withId(R.id.action_add_measurement)).perform(click());

        onView(withClassName(Matchers.equalTo(DateMeasurementView.class.getName()))).perform(scrollTo(), click());
        Calendar date = Calendar.getInstance();
        date.setTime(measurement.getDateTime());
        onView(withClassName(Matchers.equalTo(DatePicker.class.getName()))).perform(PickerActions.setDate(date.get(Calendar.YEAR), date.get(Calendar.MONTH)+1, date.get(Calendar.DAY_OF_MONTH)));
        onView(withId(android.R.id.button1)).perform(click());

        onView(withClassName(Matchers.equalTo(TimeMeasurementView.class.getName()))).perform(scrollTo(), click());
        onView(withClassName(Matchers.equalTo(TimePicker.class.getName()))).perform(PickerActions.setTime(date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE)));
        onView(withId(android.R.id.button1)).perform(click());

        setMeasuremntField(WeightMeasurementView.class.getName(), measurement.getWeight());
        setMeasuremntField(FatMeasurementView.class.getName(), measurement.getFat());
        setMeasuremntField(WaterMeasurementView.class.getName(), measurement.getWater());
        setMeasuremntField(MuscleMeasurementView.class.getName(), measurement.getMuscle());
        setMeasuremntField(LBMMeasurementView.class.getName(), measurement.getLbm());
        setMeasuremntField(BoneMeasurementView.class.getName(), measurement.getBone());
        setMeasuremntField(WaistMeasurementView.class.getName(), measurement.getWaist());
        setMeasuremntField(HipMeasurementView.class.getName(), measurement.getHip());
        setMeasuremntField(VisceralFatMeasurementView.class.getName(), measurement.getVisceralFat());
        setMeasuremntField(ChestMeasurementView.class.getName(), measurement.getChest());
        setMeasuremntField(ThighMeasurementView.class.getName(), measurement.getThigh());
        setMeasuremntField(BicepsMeasurementView.class.getName(), measurement.getBiceps());
        setMeasuremntField(NeckMeasurementView.class.getName(), measurement.getNeck());
        setMeasuremntField(Caliper1MeasurementView.class.getName(), measurement.getCaliper1());
        setMeasuremntField(Caliper2MeasurementView.class.getName(), measurement.getCaliper2());
        setMeasuremntField(Caliper3MeasurementView.class.getName(), measurement.getCaliper3());

        onView(withClassName(Matchers.equalTo(CommentMeasurementView.class.getName()))).perform(scrollTo(), click());
        onView(withClassName(Matchers.equalTo(EditText.class.getName()))).perform(replaceText(measurement.getComment()));
        onView(withId(android.R.id.button1)).perform(click());

        onView(withId(R.id.saveButton)).perform(click());

        List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleMeasurementList();
        assertEquals(1, scaleMeasurementList.size());

        TestData.compareMeasurements(measurement, scaleMeasurementList.get(0));
    }

    @Test
    public void addMeasurementFemaleTest() {
        openScale.selectScaleUser(female.getId());

        ScaleMeasurement measurement = TestData.getMeasurement(2);
        onView(withId(R.id.action_add_measurement)).perform(click());

        onView(withClassName(Matchers.equalTo(DateMeasurementView.class.getName()))).perform(scrollTo(), click());
        Calendar date = Calendar.getInstance();
        date.setTime(measurement.getDateTime());
        onView(withClassName(Matchers.equalTo(DatePicker.class.getName()))).perform(PickerActions.setDate(date.get(Calendar.YEAR), date.get(Calendar.MONTH)+1, date.get(Calendar.DAY_OF_MONTH)));
        onView(withId(android.R.id.button1)).perform(click());

        onView(withClassName(Matchers.equalTo(TimeMeasurementView.class.getName()))).perform(scrollTo(), click());
        onView(withClassName(Matchers.equalTo(TimePicker.class.getName()))).perform(PickerActions.setTime(date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE)));
        onView(withId(android.R.id.button1)).perform(click());

        setMeasuremntField(WeightMeasurementView.class.getName(), Converters.fromKilogram(measurement.getWeight(), Converters.WeightUnit.LB));
        setMeasuremntField(FatMeasurementView.class.getName(), measurement.getFat());
        setMeasuremntField(WaterMeasurementView.class.getName(), measurement.getWater());
        setMeasuremntField(MuscleMeasurementView.class.getName(), measurement.getMuscle());
        setMeasuremntField(LBMMeasurementView.class.getName(), Converters.fromKilogram(measurement.getLbm(), Converters.WeightUnit.LB));
        setMeasuremntField(BoneMeasurementView.class.getName(), Converters.fromKilogram(measurement.getBone(), Converters.WeightUnit.LB));
        setMeasuremntField(WaistMeasurementView.class.getName(), Converters.fromCentimeter(measurement.getWaist(), Converters.MeasureUnit.INCH));
        setMeasuremntField(HipMeasurementView.class.getName(), Converters.fromCentimeter(measurement.getHip(), Converters.MeasureUnit.INCH));
        setMeasuremntField(VisceralFatMeasurementView.class.getName(), measurement.getVisceralFat());
        setMeasuremntField(ChestMeasurementView.class.getName(), Converters.fromCentimeter(measurement.getChest(), Converters.MeasureUnit.INCH));
        setMeasuremntField(ThighMeasurementView.class.getName(), Converters.fromCentimeter(measurement.getThigh(), Converters.MeasureUnit.INCH));
        setMeasuremntField(BicepsMeasurementView.class.getName(), Converters.fromCentimeter(measurement.getBiceps(), Converters.MeasureUnit.INCH));
        setMeasuremntField(NeckMeasurementView.class.getName(), Converters.fromCentimeter(measurement.getNeck(), Converters.MeasureUnit.INCH));
        setMeasuremntField(Caliper1MeasurementView.class.getName(), Converters.fromCentimeter(measurement.getCaliper1(), Converters.MeasureUnit.INCH));
        setMeasuremntField(Caliper2MeasurementView.class.getName(), Converters.fromCentimeter(measurement.getCaliper2(), Converters.MeasureUnit.INCH));
        setMeasuremntField(Caliper3MeasurementView.class.getName(), Converters.fromCentimeter(measurement.getCaliper3(), Converters.MeasureUnit.INCH));

        onView(withClassName(Matchers.equalTo(CommentMeasurementView.class.getName()))).perform(scrollTo(), click());
        onView(withClassName(Matchers.equalTo(EditText.class.getName()))).perform(replaceText(measurement.getComment()));
        onView(withId(android.R.id.button1)).perform(click());

        onView(withId(R.id.saveButton)).perform(click());

        List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleMeasurementList();
        assertEquals(1, scaleMeasurementList.size());

        TestData.compareMeasurements(measurement, scaleMeasurementList.get(0));
    }

    private void setMeasuremntField(String className, float value) {
        onView(withClassName(Matchers.equalTo(className))).perform(scrollTo(), click());
        onView(withId(R.id.float_input)).perform(replaceText(String.valueOf(value)));
        onView(withId(android.R.id.button1)).perform(click());
    }
}
