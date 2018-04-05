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
import android.arch.persistence.room.ForeignKey;
import android.arch.persistence.room.Index;
import android.arch.persistence.room.PrimaryKey;
import android.util.Log;

import com.health.openscale.core.utils.Converters;
import com.j256.simplecsv.common.CsvColumn;

import java.lang.reflect.Field;
import java.util.Date;

@Entity(tableName = "scaleMeasurements",
        indices = {@Index(value = {"userId", "datetime"}, unique = true)},
        foreignKeys = @ForeignKey(
                entity = ScaleUser.class,
                parentColumns = "id",
                childColumns = "userId",
                onDelete = ForeignKey.CASCADE))
public class ScaleMeasurement implements Cloneable {

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "userId")
    private int userId;
    @ColumnInfo(name = "enabled")
    private boolean enabled;
    @CsvColumn (format = "dd.MM.yyyy HH:mm")
    @ColumnInfo(name = "datetime")
    private Date dateTime;
    @CsvColumn
    @ColumnInfo(name = "weight")
    private float weight;
    @CsvColumn
    @ColumnInfo(name = "fat")
    private float fat;
    @CsvColumn
    @ColumnInfo(name = "water")
    private float water;
    @CsvColumn
    @ColumnInfo(name = "muscle")
    private float muscle;
    @CsvColumn
    @ColumnInfo(name = "lbw")
    private float lbw;
    @CsvColumn
    @ColumnInfo(name = "waist")
    private float waist;
    @CsvColumn
    @ColumnInfo(name = "hip")
    private float hip;
    @CsvColumn
    @ColumnInfo(name = "bone")
    private float bone;
    @CsvColumn
    @ColumnInfo(name = "comment")
    private String comment;

    public ScaleMeasurement()
    {
        userId = -1;
        enabled = true;
        dateTime = new Date();
        weight = 0.0f;
        fat = 0.0f;
        water = 0.0f;
        muscle = 0.0f;
        lbw = 0.0f;
        bone = 0.0f;
        waist = 0.0f;
        hip = 0.0f;
        comment = "";
    }

    @Override
    public ScaleMeasurement clone() {
        ScaleMeasurement clone;
        try {
            clone = (ScaleMeasurement) super.clone();
        }
        catch (CloneNotSupportedException e) {
            throw new RuntimeException("failed to clone ScaleMeasurement", e);
        }
        clone.dateTime = (Date) dateTime.clone();
        return clone;
    }

    public void add(final ScaleMeasurement summand) {
        try {
            Field[] fields = getClass().getDeclaredFields();

            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(this);

                if (value != null && Float.class.isAssignableFrom(value.getClass())) {
                    field.set(this, (float)value + (float)field.get(summand));
                }
                field.setAccessible(false);
            }
        } catch (IllegalAccessException e) {
            Log.e("ScaleMeasurement", "Error: " + e.getMessage());
        }
    }

    public void divide(final float divisor) {
        try {
            Field[] fields = getClass().getDeclaredFields();

            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(this);
                if (value != null && Float.class.isAssignableFrom(value.getClass())) {
                    field.set(this, (float)value / divisor);
                }
                field.setAccessible(false);
            }
        } catch (IllegalAccessException e) {
            Log.e("ScaleMeasurement", "Error: " + e.getMessage());
        }
    }

    public void merge(ScaleMeasurement measurements) {
        try {
            Field[] fields = getClass().getDeclaredFields();

            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(measurements);
                if (value != null && Float.class.isAssignableFrom(value.getClass())) {
                    if ((float)field.get(this) == 0.0f) {
                        field.set(this, value);
                    }
                }
                field.setAccessible(false);
            }
        } catch (IllegalAccessException e) {
            Log.e("ScaleMeasurement", "Error: " + e.getMessage());
        }
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int user_id) {
        this.userId = user_id;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Date getDateTime() {
        return dateTime;
    }

    public void setDateTime(Date date_time) {
        this.dateTime = date_time;
    }

    public float getWeight() {
        return weight;
    }

    public float getConvertedWeight(Converters.WeightUnit unit) {
        return Converters.fromKilogram(weight, unit);
    }

    public void setWeight(float weight) {
        this.weight = weight;
    }

    public void setConvertedWeight(float weight, Converters.WeightUnit unit) {
        this.weight = Converters.toKilogram(weight, unit);
    }

    public float getFat() {
        return fat;
    }

    public void setFat(float fat) {
        this.fat = fat;
    }

    public float getWater() {
        return water;
    }

    public void setWater(float water) {
        this.water = water;
    }

    public float getMuscle() {
        return muscle;
    }

    public void setMuscle(float muscle) {
        this.muscle = muscle;
    }

    public float getLbw() {
        return lbw;
    }

    public void setLbw(float lbw) {
        this.lbw = lbw;
    }

    public float getWaist() {
        return waist;
    }

    public void setWaist(float waist) {
        this.waist = waist;
    }

    public float getHip() {
        return hip;
    }

    public void setHip(float hip) {
        this.hip = hip;
    }

    public float getBone() { return bone; }

    public void setBone(float bone) {this.bone = bone; }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public float getBMI(int body_height) {
        return weight / ((body_height / 100.0f)*(body_height / 100.0f));
    }

    public float getBMR(ScaleUser scaleUser) {
        float bmr = 0.0f;

        // BMR formula by Mifflin, St Jeor et al: A new predictive equation for resting energy expenditure in healthy individuals
        if (scaleUser.getGender().isMale()) {
            bmr = 10.0f * weight + 6.25f * scaleUser.getBodyHeight() - 5.0f * scaleUser.getAge(dateTime) + 5.0f;
        } else {
            bmr = 10.0f * weight + 6.25f * scaleUser.getBodyHeight() - 5.0f * scaleUser.getAge(dateTime) - 161.0f;
        }

        return bmr; // kCal / day
    }

    public float getWHtR(int body_height) {
        return waist / (float)body_height;
    }

    public float getWHR() {
        if (hip == 0) {
            return 0;
        }

        return waist / hip;
    }

    @Override
    public String toString()
    {
        return String.format(
                "ID: %d, USER_ID: %d, DATE_TIME: %s, WEIGHT: %.2f, FAT: %.2f, WATER: %.2f, " +
                "MUSCLE: %.2f, LBW: %.2f, WAIST: %.2f, HIP: %.2f, BONE: %.2f, COMMENT: %s",
                id, userId, dateTime.toString(), weight, fat, water,
                muscle, lbw, waist, hip, bone, comment);
    }
}
