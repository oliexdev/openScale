/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.data;

import com.health.openscale.core.data.ActivityLevel;
import com.health.openscale.core.data.GenderType;
import com.health.openscale.core.data.WeightUnit;

import java.util.Calendar;
import java.util.Date;

public class ScaleUser {
    private int id;


    private String userName;
    private Date birthday;

    private float bodyHeight;

    private GenderType gender;

    private WeightUnit scaleUnit;

    private ActivityLevel activityLevel;

    public ScaleUser() {
        userName = "";
        birthday = new Date();
        bodyHeight = -1;
        gender = GenderType.MALE;
        activityLevel = ActivityLevel.SEDENTARY;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public Date getBirthday() {
        return birthday;
    }

    public void setBirthday(Date birthday) {
        this.birthday = birthday;
    }

    public float getBodyHeight() {
        return bodyHeight;
    }

    public void setBodyHeight(float bodyHeight) {
        this.bodyHeight = bodyHeight;
    }

    public int getAge(Date todayDate) {
        Calendar calToday = Calendar.getInstance();
        if (todayDate != null) {
            calToday.setTime(todayDate);
        }

        Calendar calBirthday = Calendar.getInstance();
        calBirthday.setTime(birthday);

        return yearsBetween(calBirthday, calToday);
    }

    public int getAge() {
        return getAge(null);
    }

    public WeightUnit getScaleUnit() {
        return scaleUnit;
    }

    public void setScaleUnit(WeightUnit scaleUnit) {
        this.scaleUnit = scaleUnit;
    }

    public void setActivityLevel(ActivityLevel level) {
        activityLevel = level;
    }

    public ActivityLevel getActivityLevel() {
        return activityLevel;
    }

    private int yearsBetween(Calendar start, Calendar end) {
        int years = end.get(Calendar.YEAR) - start.get(Calendar.YEAR);

        final int startMonth = start.get(Calendar.MONTH);
        final int endMonth = end.get(Calendar.MONTH);
        if (endMonth < startMonth
                || (endMonth == startMonth
                && end.get(Calendar.DAY_OF_MONTH) < start.get(Calendar.DAY_OF_MONTH))) {
            years -= 1;
        }
        return years;
    }

    public GenderType getGender() {
        return gender;
    }

    public void setGender(GenderType gender) {
        this.gender = gender;
    }
}
