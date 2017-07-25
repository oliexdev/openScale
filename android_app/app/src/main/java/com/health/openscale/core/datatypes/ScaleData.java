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

import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ScaleData {
    private static final float A_HUNDRED = 100.0f;
    private static float KG_LB = 2.20462f;
    private static float KG_ST = 0.157473f;

    private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    private long id;
    private int user_id;
    private Date date_time;
    private float weight;
    private float fat;
    private float water;
    private float muscle;
    private float waist;
    private float hip;
    private String comment;

    public ScaleData()
    {
        id = -1;
        user_id = -1;
        date_time = new Date();
        weight = 0.0f;
        fat = 0.0f;
        water = 0.0f;
        muscle = 0.0f;
        waist = 0.0f;
        hip = 0.0f;
        comment = new String();
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getUserId() {
        return user_id;
    }

    public void setUserId(int user_id) {
        this.user_id = user_id;
    }

    public Date getDateTime() {
        return date_time;
    }

    public void setDateTime(Date date_time) {
        this.date_time = date_time;
    }

    public void setDateTime(String date_time) {
        try {
            this.date_time = dateTimeFormat.parse(date_time);
        } catch (ParseException e) {
            Log.e("OpenScale", "Can't parse date time string while adding to the database");
        }
    }

    public float getWeight() {
        return weight;
    }

    public float getConvertedWeight(int scale_unit) {
        float converted_weight = 0.0f;

        switch (ScaleUser.UNIT_STRING[scale_unit]) {
            case "kg":
                converted_weight = weight;
                break;
            case "lb":
                converted_weight = weight * KG_LB;
                break;
            case "st":
                converted_weight = weight * KG_ST;
                break;
        }

        return converted_weight;
    }

    public void setWeight(float weight) {
        this.weight = weight;

    }

    public void setConvertedWeight(float weight, int scale_unit) {
        switch (ScaleUser.UNIT_STRING[scale_unit]) {
            case "kg":
                this.weight = weight;
                break;
            case "lb":
                this.weight = weight / KG_LB;
                break;
            case "st":
                this.weight = weight / KG_ST;
                break;
        }
    }

    public float getFat() {
        return fat;
    }

    public float getFatAbsolute(int scale_unit) {
        return this.fat * this.getConvertedWeight(scale_unit) / A_HUNDRED;
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

    public float getMuscleAbsolute(int scale_unit) {
        return this.muscle * this.getConvertedWeight(scale_unit) / A_HUNDRED;
    }

    public void setMuscle(float muscle) {
        this.muscle = muscle;
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

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public float getBMI(int body_height) {
        return weight / ((body_height / A_HUNDRED)*(body_height / A_HUNDRED));
    }

    public float getWHtR(int body_height) {
        return waist / (float)body_height ;
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
		return "ID : " + id + " USER_ID: " + user_id + " DATE_TIME: " + date_time.toString() + " WEIGHT: " + weight + " FAT: " + fat + " WATER: " + water + " MUSCLE: " + muscle + " WAIST: " + waist + " HIP: " + hip + " COMMENT: " + comment;
	}
}
