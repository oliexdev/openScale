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
import static org.junit.Assert.assertArrayEquals;

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

    @Test
    public void unsignedInt16Converters() throws Exception {
        byte[] data = new byte[]{(byte) 0xfd, (byte) 0xfe, (byte) 0xfc, (byte) 0x10, (byte) 0x7f};

        assertEquals(0xfefd, Converters.fromUnsignedInt16Le(data, 0));
        assertEquals(0xfcfe, Converters.fromUnsignedInt16Le(data, 1));
        assertEquals(0x10fc, Converters.fromUnsignedInt16Le(data, 2));
        assertEquals(0x7f10, Converters.fromUnsignedInt16Le(data, 3));

        assertEquals(0xfdfe, Converters.fromUnsignedInt16Be(data, 0));
        assertEquals(0xfefc, Converters.fromUnsignedInt16Be(data, 1));
        assertEquals(0xfc10, Converters.fromUnsignedInt16Be(data, 2));
        assertEquals(0x107f, Converters.fromUnsignedInt16Be(data, 3));

        data = new byte[]{(byte) 0xff, (byte) 0xfe};
        assertArrayEquals(data, Converters.toUnsignedInt16Be(0xfffe));
        assertEquals(0xffff,
                Converters.fromUnsignedInt16Be(
                        Converters.toUnsignedInt16Be(0xffff), 0));
    }

    @Test
    public void unsignedInt24Converters() throws Exception {
        byte[] data = new byte[]{(byte) 0xfd, (byte) 0xfe, (byte) 0xfc, (byte) 0x10, (byte) 0x7f};

        assertEquals(0xfcfefd, Converters.fromUnsignedInt24Le(data, 0));
        assertEquals(0x10fcfe, Converters.fromUnsignedInt24Le(data, 1));
        assertEquals(0x7f10fc, Converters.fromUnsignedInt24Le(data, 2));
    }

    @Test
    public void unsignedInt32Converters() throws Exception {
        byte[] data = new byte[]{(byte) 0xf1, (byte) 0xf2, (byte) 0xf3, (byte) 0x7f, (byte) 0x7e};

        assertEquals(0x7ff3f2f1, Converters.fromUnsignedInt32Le(data, 0));
        assertEquals(0x7e7ff3f2, Converters.fromUnsignedInt32Le(data, 1));

        assertEquals(0xf1f2f37fL, Converters.fromUnsignedInt32Be(data, 0));
        assertEquals(0xf2f37f7eL, Converters.fromUnsignedInt32Be(data, 1));

        data = new byte[]{(byte) 0x80, (byte) 0x00, (byte) 0x01, (byte) 0xff, (byte) 0x00};

        assertEquals(0xff010080L, Converters.fromUnsignedInt32Le(data, 0));
        assertEquals(0xff0100, Converters.fromUnsignedInt32Le(data, 1));

        assertEquals(0x800001ffL, Converters.fromUnsignedInt32Be(data, 0));
        assertEquals(0x1ff00L, Converters.fromUnsignedInt32Be(data, 1));

        data = new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc};
        assertArrayEquals(data, Converters.toUnsignedInt32Le(0xfcfdfeffL));
        assertArrayEquals(data, Converters.toUnsignedInt32Be(0xfffefdfcL));
        assertEquals(0xffffffffL,
                Converters.fromUnsignedInt32Le(
                        Converters.toUnsignedInt32Le(0xffffffffL), 0));
        assertEquals(0xffffffffL,
                Converters.fromUnsignedInt32Be(
                        Converters.toUnsignedInt32Be(0xffffffffL), 0));
    }
}
