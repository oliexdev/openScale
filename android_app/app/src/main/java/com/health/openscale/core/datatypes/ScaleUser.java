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

import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.DateTimeHelpers;

import java.util.Date;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "scaleUsers")
public class ScaleUser {
    @PrimaryKey(autoGenerate = true)
    private int id;

    @NonNull
    @ColumnInfo(name = "username")
    private String userName;
    @NonNull
    @ColumnInfo(name = "birthday")
    private Date birthday;
    @NonNull
    @ColumnInfo(name = "bodyHeight")
    private float bodyHeight;
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
    @NonNull
    @ColumnInfo(name = "measureUnit")
    private Converters.MeasureUnit measureUnit;
    @NonNull
    @ColumnInfo(name = "activityLevel")
    private Converters.ActivityLevel activityLevel;

    public ScaleUser() {
        userName = "";
        birthday = new Date();
        bodyHeight = -1;
        scaleUnit = Converters.WeightUnit.KG;
        gender = Converters.Gender.MALE;
        initialWeight = -1;
        goalWeight = -1;
        goalDate = new Date();
        measureUnit = Converters.MeasureUnit.CM;
        activityLevel = Converters.ActivityLevel.SEDENTARY;
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
        return DateTimeHelpers.yearsBetween(birthday, todayDate != null? todayDate: new Date());
    }

    public int getAge() {
        return getAge(null);
    }

    public void setInitialWeight(float weight) {
        this.initialWeight = weight;
    }

    public float getInitialWeight() {
        return initialWeight;
    }

    public void setMeasureUnit(Converters.MeasureUnit unit) {
        measureUnit = unit;
    }

    public Converters.MeasureUnit getMeasureUnit() {
        return measureUnit;
    }

    public void setActivityLevel(Converters.ActivityLevel level) {
        activityLevel = level;
    }

    public Converters.ActivityLevel getActivityLevel() {
        return activityLevel;
    }

    public static String getPreferenceKey(int userId, String key) {
        return String.format("user.%d.%s", userId, key);
    }

    public String getPreferenceKey(String key) {
        return getPreferenceKey(getId(), key);
    }

    @Override
    public String toString()
    {
        return String.format(
                "id(%d) name(%s) birthday(%s) age(%d) body height(%.2f) scale unit(%s) " +
                "gender(%s) initial weight(%.2f) goal weight(%.2f) goal date(%s) " +
                "measure unt(%s) activity level(%d)",
                id, userName, birthday.toString(), getAge(), bodyHeight, scaleUnit.toString(),
                gender.toString().toLowerCase(), initialWeight, goalWeight, goalDate.toString(),
                measureUnit.toString(), activityLevel.toInt());
    }
}
