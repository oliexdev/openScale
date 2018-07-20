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
import android.content.res.Configuration;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.ViewInteraction;
import android.support.test.espresso.contrib.PickerActions;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.DatePicker;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.activities.BaseAppCompatActivity;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.Locale;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.closeSoftKeyboard;
import static android.support.test.espresso.action.ViewActions.replaceText;
import static android.support.test.espresso.action.ViewActions.scrollTo;
import static android.support.test.espresso.matcher.ViewMatchers.withClassName;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class AddUserTest {
    private static final double DELTA = 1e-15;

    private Context context;

    @Rule
    public ActivityTestRule<MainActivity> mActivityTestRule = new ActivityTestRule<>(MainActivity.class, false, false);

    @Before
    public void initTest() {
        context = InstrumentationRegistry.getTargetContext();

        // Set first start to true to get the user add dialog
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putBoolean("firstStart", true)
                .putString(BaseAppCompatActivity.PREFERENCE_LANGUAGE, "en")
                .commit();
    }

    @After
    public void addUserVerification() {
        ScaleUser user = OpenScale.getInstance().getSelectedScaleUser();

        assertEquals("test", user.getUserName());
        assertEquals(180, user.getBodyHeight(), DELTA);
        assertEquals(80, user.getInitialWeight(), DELTA);
        assertEquals(60, user.getGoalWeight(), DELTA);

        Calendar birthday = Calendar.getInstance();
        birthday.setTimeInMillis(0);
        birthday.set(Calendar.YEAR, 1990);
        birthday.set(Calendar.MONTH, Calendar.JANUARY);
        birthday.set(Calendar.DAY_OF_MONTH, 19);
        birthday.set(Calendar.HOUR_OF_DAY, 0);

        assertEquals(birthday.getTime().getTime(), user.getBirthday().getTime());

        Calendar goalDate = Calendar.getInstance();
        goalDate.setTimeInMillis(0);
        goalDate.set(Calendar.YEAR, 2018);
        goalDate.set(Calendar.MONTH, Calendar.JANUARY);
        goalDate.set(Calendar.DAY_OF_MONTH, 31);
        goalDate.set(Calendar.HOUR_OF_DAY, 0);

        assertEquals(goalDate.getTime().getTime(), user.getGoalDate().getTime());

        OpenScale.getInstance().deleteScaleUser(user.getId());
    }

    @Test
    public void addUserTest() {
        mActivityTestRule.launchActivity(null);

        ViewInteraction editText = onView(
                allOf(withId(R.id.txtUserName),
                        childAtPosition(
                                allOf(withId(R.id.rowUserName),
                                        childAtPosition(
                                                withId(R.id.tableUserData),
                                                0)),
                                1)));
        editText.perform(scrollTo(), click());

        ViewInteraction editText2 = onView(
                allOf(withId(R.id.txtUserName),
                        childAtPosition(
                                allOf(withId(R.id.rowUserName),
                                        childAtPosition(
                                                withId(R.id.tableUserData),
                                                0)),
                                1)));
        editText2.perform(scrollTo(), replaceText("test"), closeSoftKeyboard());

        ViewInteraction editText3 = onView(
                allOf(withId(R.id.txtBodyHeight),
                        childAtPosition(
                                allOf(withId(R.id.rowBodyHeight),
                                        childAtPosition(
                                                withId(R.id.tableUserData),
                                                6)),
                                1)));
        editText3.perform(scrollTo(), replaceText("180"), closeSoftKeyboard());

        onView(withId(R.id.txtBirthday)).perform(click());
        onView(withClassName(Matchers.equalTo(DatePicker.class.getName()))).perform(PickerActions.setDate(1990,  1, 19));
        onView(withId(android.R.id.button1)).perform(click());

        ViewInteraction editText5 = onView(
                allOf(withId(R.id.txtInitialWeight),
                        childAtPosition(
                                allOf(withId(R.id.tableRowInitialWeight),
                                        childAtPosition(
                                                withId(R.id.tableUserData),
                                                7)),
                                1)));
        editText5.perform(scrollTo(), replaceText("80"), closeSoftKeyboard());

        ViewInteraction editText6 = onView(
                allOf(withId(R.id.txtGoalWeight),
                        childAtPosition(
                                allOf(withId(R.id.rowGoalWeight),
                                        childAtPosition(
                                                withId(R.id.tableUserData),
                                                8)),
                                1)));
        editText6.perform(scrollTo(), replaceText("60"), closeSoftKeyboard());

        onView(withId(R.id.txtGoalDate)).perform(click());
        onView(withClassName(Matchers.equalTo(DatePicker.class.getName()))).perform(PickerActions.setDate(2018,  1, 31));
        onView(withId(android.R.id.button1)).perform(click());

        onView(withId(R.id.saveButton)).perform(click());
    }

    private static Matcher<View> childAtPosition(
            final Matcher<View> parentMatcher, final int position) {

        return new TypeSafeMatcher<View>() {
            @Override
            public void describeTo(Description description) {
                description.appendText("Child at position " + position + " in parent ");
                parentMatcher.describeTo(description);
            }

            @Override
            public boolean matchesSafely(View view) {
                ViewParent parent = view.getParent();
                return parent instanceof ViewGroup && parentMatcher.matches(parent)
                        && view.equals(((ViewGroup) parent).getChildAt(position));
            }
        };
    }
}
