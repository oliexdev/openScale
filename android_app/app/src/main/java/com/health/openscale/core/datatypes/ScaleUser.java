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
import android.support.annotation.NonNull;

import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.DateTimeHelpers;

import java.util.Calendar;
import java.util.Date;

@Entity(tableName = "scaleUsers")
public class ScaleUser {
    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "username")
    private String userName;
    @ColumnInfo(name = "birthday")
    private Date birthday;
    @ColumnInfo(name = "bodyHeight")
    private int bodyHeight;
    @ColumnInfo(name = "scaleUnit")
    @NonNull
    private Converters.WeightUnit scaleUnit;
    @ColumnInfo(name = "gender")
    @NonNull
    private Converters.Gender gender;
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
        scaleUnit = Converters.WeightUnit.KG;
        gender = Converters.Gender.MALE;
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

    public Converters.WeightUnit getScaleUnit() {
        return scaleUnit;
    }

    public void setScaleUnit(Converters.WeightUnit scaleUnit) {
        this.scaleUnit = scaleUnit;
    }

    public Converters.Gender getGender() {
        return gender;
    }

    public void setGender(Converters.Gender gender) {
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

    public int getAge(Date todayDate) {
        Calendar calToday = Calendar.getInstance();
        calToday.setTime(todayDate);

        Calendar calBirthday = Calendar.getInstance();
        calBirthday.setTime(birthday);

        return DateTimeHelpers.yearsBetween(calBirthday, calToday);
    }

    public void setInitialWeight(float weight) {
        this.initialWeight = weight;
    }

    public void setConvertedInitialWeight(float weight) {
        initialWeight = Converters.toKilogram(weight, scaleUnit);
    }

    public float getInitialWeight() {
        return initialWeight;
    }

    public float getConvertedInitialWeight() {
        return Converters.fromKilogram(initialWeight, scaleUnit);
    }

    @Override
    public String toString()
    {
        return String.format(
                "ID: %d, NAME: %s, BIRTHDAY: %s, BODY_HEIGHT: %d, SCALE_UNIT: %s, " +
                "GENDER: %s, INITIAL_WEIGHT: %.2f, GOAL_WEIGHT: %.2f, GOAL_DATE: %s",
                id, userName, birthday.toString(), bodyHeight, scaleUnit.toString(),
                gender.toString().toLowerCase(), initialWeight, goalWeight, goalDate.toString());
    }
}
