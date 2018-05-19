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
package com.health.openscale;

import com.health.openscale.core.datatypes.ScaleMeasurement;

import org.junit.Before;
import org.junit.Test;

import static junit.framework.Assert.assertEquals;

public class MeasurementTest {
    private static final double DELTA = 1e-15;
    private ScaleMeasurement measurementA;
    private ScaleMeasurement measurementB;

    @Before
    public void initData() {
        measurementA = new ScaleMeasurement();
        measurementB = new ScaleMeasurement();

        measurementA.setWeight(80.0f);
        measurementA.setBone(3.0f);
        measurementA.setMuscle(55.0f);

        measurementB.setWeight(90.0f);
        measurementB.setBone(10.0f);
        measurementB.setHip(5.0f);
        measurementB.setWater(12.0f);
    }

    @Test
    public void mergeTest() {
        measurementA.merge(measurementB);

        assertEquals(80.0f, measurementA.getWeight(), DELTA);
        assertEquals(3.0f, measurementA.getBone(), DELTA);
        assertEquals( 5.0f, measurementA.getHip(), DELTA);
        assertEquals( 12.0f, measurementA.getWater(), DELTA);
        assertEquals( 55.0f, measurementA.getMuscle(), DELTA);
    }

    @Test
    public void divideTest() {
        measurementA.divide(2.0f);

        assertEquals(40.0f, measurementA.getWeight(), DELTA);
        assertEquals(1.5f, measurementA.getBone(), DELTA);
        assertEquals(27.5f, measurementA.getMuscle(), DELTA);
        assertEquals(0.0f, measurementA.getWater(), DELTA);
    }

    @Test
    public void addTest() {
        measurementA.add(measurementB);

        assertEquals(170.0f, measurementA.getWeight(), DELTA);
        assertEquals(13.0f, measurementA.getBone(), DELTA);
        assertEquals(55.0f, measurementA.getMuscle(), DELTA);
        assertEquals(12.0f, measurementA.getWater(), DELTA);
        assertEquals(5.0f, measurementA.getHip(), DELTA);
    }

    @Test
    public void printTest() {
        System.out.println(measurementA.toString());
    }
}
