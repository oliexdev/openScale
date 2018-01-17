/* Copyright (C) 2018 Erik Johansson <erik@ejohansson.se>
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

import com.health.openscale.core.utils.Converters;

import org.junit.Test;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

public class ConvertersTest {

    @Test
    public void weightUnitTypeConverters() throws Exception {
        assertEquals(0, Converters.toWeightUnitInt(Converters.WeightUnit.KG));
        assertEquals(1, Converters.toWeightUnitInt(Converters.WeightUnit.LB));
        assertEquals(2, Converters.toWeightUnitInt(Converters.WeightUnit.ST));

        for (Converters.WeightUnit unit : Converters.WeightUnit.values()) {
            assertEquals(Converters.toWeightUnitInt(unit), unit.toInt());
        }

        assertEquals(Converters.WeightUnit.KG, Converters.fromWeightUnitInt(0));
        assertEquals(Converters.WeightUnit.LB, Converters.fromWeightUnitInt(1));
        assertEquals(Converters.WeightUnit.ST, Converters.fromWeightUnitInt(2));

        for (int i = 0; i < Converters.WeightUnit.values().length; ++i) {
            assertEquals(Converters.fromWeightUnitInt(i), Converters.WeightUnit.fromInt(i));
        }

        assertEquals("kg", Converters.WeightUnit.KG.toString());
        assertEquals("lb", Converters.WeightUnit.LB.toString());
        assertEquals("st", Converters.WeightUnit.ST.toString());
    }

    @Test
    public void genderTypeConverters() throws Exception {
        assertEquals(0, Converters.toGenderInt(Converters.Gender.MALE));
        assertEquals(1, Converters.toGenderInt(Converters.Gender.FEMALE));

        for (Converters.Gender gender : Converters.Gender.values()) {
            assertEquals(Converters.toGenderInt(gender), gender.toInt());
        }

        assertEquals(Converters.Gender.MALE, Converters.fromGenderInt(0));
        assertEquals(Converters.Gender.FEMALE, Converters.fromGenderInt(1));

        for (int i = 0; i < Converters.Gender.values().length; ++i) {
            assertEquals(Converters.fromGenderInt(i), Converters.Gender.fromInt(i));
        }

        assertEquals("MALE", Converters.Gender.MALE.toString());
        assertEquals("FEMALE", Converters.Gender.FEMALE.toString());

        assertTrue(Converters.Gender.MALE.isMale());
        assertFalse(Converters.Gender.FEMALE.isMale());
    }

    @Test
    public void weightConverters() throws Exception {
        for (Converters.WeightUnit unit : Converters.WeightUnit.values()) {
            assertEquals(10.0f,
                    Converters.toKilogram(Converters.fromKilogram(10.0f, unit), unit));
        }
    }
}
