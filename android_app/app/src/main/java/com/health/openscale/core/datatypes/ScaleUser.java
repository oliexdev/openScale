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

package com.health.openscale.core.datatypes;

import java.util.Calendar;
import java.util.Date;

public class ScaleUser {
    public static final String[] UNIT_STRING = new String[] {"kg", "lb", "st"};
    private static float KG_LB = 2.20462f;
    private static float KG_ST = 0.157473f;

    public int id;
    public String user_name;
    public Date birthday;
    public int body_height;
    public int scale_unit;
    public int gender;
    private float initial_weight;
    public float goal_weight;
    public Date goal_date;

    public ScaleUser() {
        id = -1;
        user_name = new String();
        birthday = new Date();
        body_height = -1;
        scale_unit = 0;
        gender = 0;
        initial_weight = -1;
        goal_weight = -1;
        goal_date = new Date();
    }

    public boolean isMale()
    {
        if (gender == 0)
            return true;

        return false;
    }

    public int getAge(Date todayDate) {
        Calendar cal_today = Calendar.getInstance();
        cal_today.setTime(todayDate);
        Calendar cal_birthday = Calendar.getInstance();
        cal_birthday.setTime(birthday);
        int userAge = cal_today.get(Calendar.YEAR) - cal_birthday.get(Calendar.YEAR);
        if (cal_today.get(Calendar.DAY_OF_YEAR) < cal_birthday.get(Calendar.DAY_OF_YEAR)) userAge--;

        return userAge;
    }

    public void setInitialWeight(float weight) {
        this.initial_weight = weight;

    }

    public void setConvertedInitialWeight(float weight) {
        switch (ScaleUser.UNIT_STRING[scale_unit]) {
            case "kg":
                this.initial_weight = weight;
                break;
            case "lb":
                this.initial_weight = weight / KG_LB;
                break;
            case "st":
                this.initial_weight = weight / KG_ST;
                break;
        }
    }

    public float getInitialWeight() {
        return initial_weight;
    }

    public float getConvertedInitialWeight() {
        float converted_weight = 0.0f;

        switch (ScaleUser.UNIT_STRING[scale_unit]) {
            case "kg":
                converted_weight = initial_weight;
                break;
            case "lb":
                converted_weight = initial_weight * KG_LB;
                break;
            case "st":
                converted_weight = initial_weight * KG_ST;
                break;
        }

        return converted_weight;
    }

    @Override
    public String toString()
    {
        return "ID : " + id + " NAME: " + user_name + " BIRTHDAY: " + birthday.toString() + " BODY_HEIGHT: " + body_height + " SCALE_UNIT: " + UNIT_STRING[scale_unit] + " GENDER " + gender + " INITIAL WEIGHT " + initial_weight + " GOAL WEIGHT " + goal_weight + " GOAL DATE " + goal_date.toString();
    }
}
