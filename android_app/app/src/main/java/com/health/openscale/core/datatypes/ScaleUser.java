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

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.PrimaryKey;

import java.util.Calendar;
import java.util.Date;

@Entity(tableName = "scaleUsers")
public class ScaleUser {
    public static final String[] UNIT_STRING = new String[] {"kg", "lb", "st"};
    private static float KG_LB = 2.20462f;
    private static float KG_ST = 0.157473f;

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "username")
    private String userName;
    @ColumnInfo(name = "birthday")
    private Date birthday;
    @ColumnInfo(name = "bodyHeight")
    private int bodyHeight;
    @ColumnInfo(name = "scaleUnit")
    private int scaleUnit;
    @ColumnInfo(name = "gender")
    private int gender;
    @ColumnInfo(name = "initialWeight")
    private float initialWeight;
    @ColumnInfo(name = "goalWeight")
    private float goalWeight;
    @ColumnInfo(name = "goalDate")
    private Date goalDate;

    public ScaleUser() {
        userName = new String();
        birthday = new Date();
        bodyHeight = -1;
        scaleUnit = 0;
        gender = 0;
        initialWeight = -1;
        goalWeight = -1;
        goalDate = new Date();
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

    public int getBodyHeight() {
        return bodyHeight;
    }

    public void setBodyHeight(int bodyHeight) {
        this.bodyHeight = bodyHeight;
    }

    public int getScaleUnit() {
        return scaleUnit;
    }

    public void setScaleUnit(int scaleUnit) {
        this.scaleUnit = scaleUnit;
    }

    public int getGender() {
        return gender;
    }

    public void setGender(int gender) {
        this.gender = gender;
    }

    public float getGoalWeight() {
        return goalWeight;
    }

    public void setGoalWeight(float goalWeight) {
        this.goalWeight = goalWeight;
    }

    public Date getGoalDate() {
        return goalDate;
    }

    public void setGoalDate(Date goalDate) {
        this.goalDate = goalDate;
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
        this.initialWeight = weight;

    }

    public void setConvertedInitialWeight(float weight) {
        switch (ScaleUser.UNIT_STRING[scaleUnit]) {
            case "kg":
                this.initialWeight = weight;
                break;
            case "lb":
                this.initialWeight = weight / KG_LB;
                break;
            case "st":
                this.initialWeight = weight / KG_ST;
                break;
        }
    }

    public float getInitialWeight() {
        return initialWeight;
    }

    public float getConvertedInitialWeight() {
        float converted_weight = 0.0f;

        switch (ScaleUser.UNIT_STRING[scaleUnit]) {
            case "kg":
                converted_weight = initialWeight;
                break;
            case "lb":
                converted_weight = initialWeight * KG_LB;
                break;
            case "st":
                converted_weight = initialWeight * KG_ST;
                break;
        }

        return converted_weight;
    }

    @Override
    public String toString()
    {
        return "ID : " + id + " NAME: " + userName + " BIRTHDAY: " + birthday.toString() + " BODY_HEIGHT: " + bodyHeight + " SCALE_UNIT: " + UNIT_STRING[scaleUnit] + " GENDER " + gender + " INITIAL WEIGHT " + initialWeight + " GOAL WEIGHT " + goalWeight + " GOAL DATE " + goalDate.toString();
    }
}
