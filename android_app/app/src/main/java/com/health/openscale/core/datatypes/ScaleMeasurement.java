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

import com.health.openscale.core.utils.CsvHelper;
import com.j256.simplecsv.common.CsvColumn;

import java.lang.reflect.Field;
import java.util.Date;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import timber.log.Timber;

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
    @CsvColumn(converterClass = CsvHelper.DateTimeConverter.class, format ="yyyy-MM-dd HH:mm", mustNotBeBlank = true)
    @ColumnInfo(name = "datetime")
    private Date dateTime;
    @CsvColumn(mustNotBeBlank = true)
    @ColumnInfo(name = "weight")
    private float weight;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "fat")
    private float fat;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "water")
    private float water;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "muscle")
    private float muscle;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "visceralFat")
    private float visceralFat;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "lbm")
    private float lbm;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "waist")
    private float waist;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "hip")
    private float hip;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "bone")
    private float bone;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "chest")
    private float chest;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "thigh")
    private float thigh;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "biceps")
    private float biceps;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "neck")
    private float neck;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "caliper1")
    private float caliper1;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "caliper2")
    private float caliper2;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "caliper3")
    private float caliper3;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "calories")
    private float calories;
    @CsvColumn(mustBeSupplied = false)
    @ColumnInfo(name = "comment")
    private String comment;
    @Ignore
    private int count;

    public ScaleMeasurement()
    {
        userId = -1;
        enabled = true;
        dateTime = new Date();
        weight = 0.0f;
        fat = 0.0f;
        water = 0.0f;
        muscle = 0.0f;
        lbm = 0.0f;
        bone = 0.0f;
        waist = 0.0f;
        hip = 0.0f;
        chest = 0.0f;
        thigh = 0.0f;
        biceps = 0.0f;
        neck = 0.0f;
        caliper1 = 0.0f;
        caliper2 = 0.0f;
        caliper3 = 0.0f;
        comment = "";
        count = 1;
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

            count++;
        } catch (IllegalAccessException e) {
            Timber.e(e);
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
            Timber.e(e);
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
            Timber.e(e);
        }
    }

    public int count() { return count; }

    public boolean isAverageValue() { return (count > 1); }

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

    public void setWeight(float weight) {
        this.weight = weight;
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

    public float getVisceralFat() {
        return visceralFat;
    }

    public void setVisceralFat(float visceralFat) {
        this.visceralFat = visceralFat;
    }

    public float getLbm() {
        return lbm;
    }

    public void setLbm(float lbm) {
        this.lbm = lbm;
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

    public float getChest() {
        return chest;
    }

    public void setChest(float chest) {
        this.chest = chest;
    }

    public float getThigh() {
        return thigh;
    }

    public void setThigh(float thigh) {
        this.thigh = thigh;
    }

    public float getBiceps() {
        return biceps;
    }

    public void setBiceps(float biceps) {
        this.biceps = biceps;
    }

    public float getNeck() {
        return neck;
    }

    public void setNeck(float neck) {
        this.neck = neck;
    }

    public float getCaliper1() {
        return caliper1;
    }

    public void setCaliper1(float caliper1) {
        this.caliper1 = caliper1;
    }

    public float getCaliper2() {
        return caliper2;
    }

    public void setCaliper2(float caliper2) {
        this.caliper2 = caliper2;
    }

    public float getCaliper3() {
        return caliper3;
    }

    public void setCaliper3(float caliper3) {
        this.caliper3 = caliper3;
    }

    public float getCalories() { return calories; }

    public void setCalories(float calories) { this.calories = calories; }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        if (comment == null) {
            this.comment = "";
        }
        else {
            this.comment = comment;
        }
    }

    public float getBMI(float body_height) {
        return weight / ((body_height / 100.0f)*(body_height / 100.0f));
    }

    public float getBMR(ScaleUser scaleUser) {
        float bmr;

        // BMR Harris-Benedict equation
        if (scaleUser.getGender().isMale()) {
            bmr = 66.4730f + (13.7516f * weight) + (5.0033f * scaleUser.getBodyHeight()) - (6.7550f * scaleUser.getAge(dateTime));
        } else {
            bmr = 655.0955f + (9.5634f * weight) + (1.8496f * scaleUser.getBodyHeight()) - (4.6756f * scaleUser.getAge(dateTime));
        }

        return bmr; // kCal / day
    }

    public float getTDEE(ScaleUser scaleUser) {
        float factor = 1.0f;

        switch (scaleUser.getActivityLevel()) {
            case SEDENTARY:
                factor = 1.2f;
                break;
            case MILD:
                factor = 1.375f;
                break;
            case MODERATE:
                factor = 1.55f;
                break;
            case HEAVY:
                factor = 1.725f;
                break;
            case EXTREME:
                factor = 1.9f;
                break;
        }

        return factor * getBMR(scaleUser);
    }

    public float getWHtR(float body_height) {
        return waist / body_height;
    }

    public float getWHR() {
        if (hip == 0) {
            return 0;
        }

        return waist / hip;
    }

    public float getFatCaliper(ScaleUser scaleUser) {
        float fat_caliper;

        float k0, k1, k2, ka;

        float s = (caliper1 + caliper2 + caliper3) * 10.0f; // cm to mm

        if (scaleUser.getGender().isMale()) {
            k0 = 1.10938f;
            k1 = 0.0008267f;
            k2 = 0.0000016f;
            ka = 0.0002574f;
        } else {
            k0 = 1.0994921f;
            k1 = 0.0009929f;
            k2 = 0.0000023f;
            ka = 0.0001392f;
        }

        // calipometrie formula by Jackson, Pollock: Generalized equations for predicting body density of women. In: British Journal of Nutrition. Nr.40, Oktober 1978, S.497–504
        fat_caliper = ((4.95f / (k0 - (k1*s) + (k2 * s*s) - (ka*scaleUser.getAge()))) - 4.5f) * 100.0f;

        return fat_caliper;
    }

    @Override
    public String toString()
    {
        return String.format(
                "ID: %d, USER_ID: %d, DATE_TIME: %s, WEIGHT: %.2f, FAT: %.2f, WATER: %.2f, " +
                "MUSCLE: %.2f, LBM: %.2f, WAIST: %.2f, HIP: %.2f, BONE: %.2f, CHEST: %.2f, " +
                        "THIGH: %.2f, ARM: %.2f, NECK: %.2f, CALIPER1: %.2f, CALIPER2: %.2f, CALIPER3: %.2f, COMMENT: %s",
                id, userId, dateTime.toString(), weight, fat, water,
                muscle, lbm, waist, hip, bone, chest, thigh, biceps, neck, caliper1, caliper2, caliper3, comment);
    }
}
