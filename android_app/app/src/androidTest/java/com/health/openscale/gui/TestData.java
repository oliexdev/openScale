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

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.Calendar;
import java.util.Date;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class TestData {
    private static Random rand = new Random();
    private static final double DELTA = 1e-4;

    public static ScaleUser getMaleUser() {
        ScaleUser male = new ScaleUser();

        male.setUserName("Bob");
        male.setGender(Converters.Gender.MALE);
        male.setInitialWeight(80.0f);
        male.setScaleUnit(Converters.WeightUnit.KG);
        male.setActivityLevel(Converters.ActivityLevel.MILD);
        male.setBodyHeight(180.0f);
        male.setGoalWeight(60.0f);
        male.setMeasureUnit(Converters.MeasureUnit.CM);
        male.setBirthday(getDateFromYears(-20));
        male.setGoalDate(getDateFromYears(2));

        return male;
    }

    public static ScaleUser getFemaleUser() {
        ScaleUser female = new ScaleUser();

        female.setUserName("Alice");
        female.setGender(Converters.Gender.FEMALE);
        female.setInitialWeight(70.0f);
        female.setScaleUnit(Converters.WeightUnit.LB);
        female.setActivityLevel(Converters.ActivityLevel.EXTREME);
        female.setBodyHeight(160.0f);
        female.setGoalWeight(50.0f);
        female.setMeasureUnit(Converters.MeasureUnit.INCH);
        female.setBirthday(getDateFromYears(-25));
        female.setGoalDate(getDateFromYears(1));

        return female;
    }

    public static ScaleMeasurement getMeasurement(int nr) {
        ScaleMeasurement measurement = new ScaleMeasurement();

        rand.setSeed(nr);

        measurement.setDateTime(getDateFromDays(nr));
        measurement.setWeight(100.0f + getRandNumberInRange(0,50));
        measurement.setFat(30.0f + getRandNumberInRange(0,30));
        measurement.setWater(50.0f + getRandNumberInRange(0,20));
        measurement.setMuscle(40.0f + getRandNumberInRange(0,15));
        measurement.setLbm(20.0f + getRandNumberInRange(0,10));
        measurement.setBone(8.0f + getRandNumberInRange(0,50));
        measurement.setWaist(50.0f + getRandNumberInRange(0,50));
        measurement.setHip(60.0f + getRandNumberInRange(0,50));
        measurement.setChest(80.0f + getRandNumberInRange(0,50));
        measurement.setThigh(40.0f + getRandNumberInRange(0,50));
        measurement.setVisceralFat(10 + getRandNumberInRange(0,5));
        measurement.setBiceps(30.0f + getRandNumberInRange(0,50));
        measurement.setNeck(15.0f + getRandNumberInRange(0,50));
        measurement.setCaliper1(5.0f + getRandNumberInRange(0,10));
        measurement.setCaliper2(10.0f + getRandNumberInRange(0,10));
        measurement.setCaliper3(7.0f + getRandNumberInRange(0,10));
        measurement.setComment("my comment " + nr);

        return measurement;
    }

    public static void compareMeasurements(ScaleMeasurement measurementA, ScaleMeasurement measurementB) {
        assertEquals(measurementA.getDateTime().getTime(), measurementB.getDateTime().getTime(), DELTA);
        assertEquals(measurementA.getWeight(), measurementB.getWeight(), DELTA);
        assertEquals(measurementA.getFat(), measurementB.getFat(), DELTA);
        assertEquals(measurementA.getWater(), measurementB.getWater(), DELTA);
        assertEquals(measurementA.getMuscle(), measurementB.getMuscle(), DELTA);
        assertEquals(measurementA.getLbm(), measurementB.getLbm(), DELTA);
        assertEquals(measurementA.getBone(), measurementB.getBone(), DELTA);
        assertEquals(measurementA.getWaist(), measurementB.getWaist(), DELTA);
        assertEquals(measurementA.getHip(), measurementB.getHip(), DELTA);
        assertEquals(measurementA.getChest(), measurementB.getChest(), DELTA);
        assertEquals(measurementA.getThigh(), measurementB.getThigh(), DELTA);
        assertEquals(measurementA.getVisceralFat(), measurementB.getVisceralFat(), DELTA);
        assertEquals(measurementA.getBiceps(), measurementB.getBiceps(), DELTA);
        assertEquals(measurementA.getNeck(), measurementB.getNeck(), DELTA);
        assertEquals(measurementA.getCaliper1(), measurementB.getCaliper1(), DELTA);
        assertEquals(measurementA.getCaliper2(), measurementB.getCaliper2(), DELTA);
        assertEquals(measurementA.getCaliper3(), measurementB.getCaliper3(), DELTA);
        assertEquals(measurementA.getComment(), measurementB.getComment());
    }

    private static Date getDateFromYears(int years) {
        Calendar currentTime = Calendar.getInstance();

        currentTime.add(Calendar.YEAR, years);
        currentTime.set(Calendar.HOUR_OF_DAY, 8);
        currentTime.set(Calendar.MINUTE, 0);
        currentTime.set(Calendar.MILLISECOND, 0);
        currentTime.set(Calendar.SECOND, 0);

        return currentTime.getTime();
    }

    private static Date getDateFromDays(int days) {
        Calendar currentTime = Calendar.getInstance();

        currentTime.add(Calendar.DAY_OF_YEAR, days);
        currentTime.set(Calendar.HOUR_OF_DAY, 8);
        currentTime.set(Calendar.MINUTE, 0);
        currentTime.set(Calendar.MILLISECOND, 0);
        currentTime.set(Calendar.SECOND, 0);

        return currentTime.getTime();
    }

    private static float getRandNumberInRange(int min, int max) {
        return (float)(rand.nextInt(max*10 - min*10) + min*10) / 10.0f;
    }
}
