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
    public void fromInt16Converters() throws Exception {
        byte[] data = new byte[]{(byte) 0xfd, (byte) 0xfe, (byte) 0xfc, (byte) 0x10, (byte) 0x7f};

        assertEquals(0xfffffefd, Converters.fromSignedInt16Le(data, 0));
        assertEquals(0xfffffcfe, Converters.fromSignedInt16Le(data, 1));
        assertEquals(0x000010fc, Converters.fromSignedInt16Le(data, 2));
        assertEquals(0x00007f10, Converters.fromSignedInt16Le(data, 3));

        assertEquals(0xfefd, Converters.fromUnsignedInt16Le(data, 0));
        assertEquals(0xfcfe, Converters.fromUnsignedInt16Le(data, 1));
        assertEquals(0x10fc, Converters.fromUnsignedInt16Le(data, 2));
        assertEquals(0x7f10, Converters.fromUnsignedInt16Le(data, 3));

        assertEquals(0xfffffdfe, Converters.fromSignedInt16Be(data, 0));
        assertEquals(0xfffffefc, Converters.fromSignedInt16Be(data, 1));
        assertEquals(0xfffffc10, Converters.fromSignedInt16Be(data, 2));
        assertEquals(0x0000107f, Converters.fromSignedInt16Be(data, 3));

        assertEquals(0xfdfe, Converters.fromUnsignedInt16Be(data, 0));
        assertEquals(0xfefc, Converters.fromUnsignedInt16Be(data, 1));
        assertEquals(0xfc10, Converters.fromUnsignedInt16Be(data, 2));
        assertEquals(0x107f, Converters.fromUnsignedInt16Be(data, 3));

        assertEquals(-12345,
                Converters.fromSignedInt16Le(Converters.toInt16Le(-12345), 0));
        assertEquals(-12345,
                Converters.fromSignedInt16Be(Converters.toInt16Be(-12345), 0));
    }

    @Test
    public void toInt16Converters() throws Exception {
        assertArrayEquals(new byte[]{(byte) 0x12, (byte) 0x34}, Converters.toInt16Be(0x1234));
        assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xfe}, Converters.toInt16Be(0xfffe));
        assertArrayEquals(new byte[]{(byte) 0x34, (byte) 0x12}, Converters.toInt16Le(0x1234));
        assertArrayEquals(new byte[]{(byte) 0xfe, (byte) 0xff}, Converters.toInt16Le(0xfffe));

        byte[] data = new byte[6];
        Converters.toInt16Be(data, 1, 0x0102);
        Converters.toInt16Be(data, 3, 0x0304);
        Converters.toInt16Le(data, 2, 0x0506);
        assertArrayEquals(new byte[]{ 0, 1, 6, 5, 4, 0}, data);
    }

    @Test
    public void fromInt24Converters() throws Exception {
        byte[] data = new byte[]{(byte) 0xfd, (byte) 0xfe, (byte) 0xfc, (byte) 0x10, (byte) 0x7f};

        assertEquals(0xfffcfefd, Converters.fromSignedInt24Le(data, 0));
        assertEquals(0x0010fcfe, Converters.fromSignedInt24Le(data, 1));
        assertEquals(0x007f10fc, Converters.fromSignedInt24Le(data, 2));

        assertEquals(0xfffdfefc, Converters.fromSignedInt24Be(data, 0));
        assertEquals(0xfffefc10, Converters.fromSignedInt24Be(data, 1));
        assertEquals(0xfffc107f, Converters.fromSignedInt24Be(data, 2));

        assertEquals(0xfcfefd, Converters.fromUnsignedInt24Le(data, 0));
        assertEquals(0x10fcfe, Converters.fromUnsignedInt24Le(data, 1));
        assertEquals(0x7f10fc, Converters.fromUnsignedInt24Le(data, 2));

        assertEquals(0xfdfefc, Converters.fromUnsignedInt24Be(data, 0));
        assertEquals(0xfefc10, Converters.fromUnsignedInt24Be(data, 1));
        assertEquals(0xfc107f, Converters.fromUnsignedInt24Be(data, 2));

        assertEquals(-1234567,
                Converters.fromSignedInt24Le(Converters.toInt32Le(-1234567), 0));
    }

    @Test
    public void fromInt32Converters() throws Exception {
        byte[] data = new byte[]{(byte) 0xf1, (byte) 0xf2, (byte) 0xf3, (byte) 0x7f, (byte) 0x7e};

        assertEquals(0x7ff3f2f1, Converters.fromSignedInt32Le(data, 0));
        assertEquals(0x7e7ff3f2, Converters.fromSignedInt32Le(data, 1));

        assertEquals(0x7ff3f2f1, Converters.fromUnsignedInt32Le(data, 0));
        assertEquals(0x7e7ff3f2, Converters.fromUnsignedInt32Le(data, 1));

        assertEquals(0xf1f2f37f, Converters.fromSignedInt32Be(data, 0));
        assertEquals(0xf2f37f7e, Converters.fromSignedInt32Be(data, 1));

        assertEquals(0xf1f2f37fL, Converters.fromUnsignedInt32Be(data, 0));
        assertEquals(0xf2f37f7eL, Converters.fromUnsignedInt32Be(data, 1));

        data = new byte[]{(byte) 0x80, (byte) 0x00, (byte) 0x01, (byte) 0xff, (byte) 0x00};

        assertEquals(0xff010080, Converters.fromSignedInt32Le(data, 0));
        assertEquals(0xff0100, Converters.fromSignedInt32Le(data, 1));

        assertEquals(0xff010080L, Converters.fromUnsignedInt32Le(data, 0));
        assertEquals(0xff0100, Converters.fromUnsignedInt32Le(data, 1));

        assertEquals(0x800001ff, Converters.fromSignedInt32Be(data, 0));
        assertEquals(0x1ff00, Converters.fromSignedInt32Be(data, 1));

        assertEquals(0x800001ffL, Converters.fromUnsignedInt32Be(data, 0));
        assertEquals(0x1ff00L, Converters.fromUnsignedInt32Be(data, 1));

        assertEquals(-1234567890,
                Converters.fromSignedInt32Le(Converters.toInt32Le(-1234567890), 0));
        assertEquals(-1234567890,
                Converters.fromSignedInt32Be(Converters.toInt32Be(-1234567890), 0));
    }

    @Test
    public void toInt32Converters() throws Exception {
        byte[] data = new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfc};
        assertArrayEquals(data, Converters.toInt32Le(0xfcfdfeffL));
        assertArrayEquals(data, Converters.toInt32Be(0xfffefdfcL));
        assertEquals(0xffffffffL,
                Converters.fromUnsignedInt32Le(
                        Converters.toInt32Le(0xffffffffL), 0));
        assertEquals(0xffffffffL,
                Converters.fromUnsignedInt32Be(
                        Converters.toInt32Be(0xffffffffL), 0));

        data = new byte[10];
        Converters.toInt32Be(data, 1, 0x01020304);
        Converters.toInt32Be(data, 6, 0x05060708);
        Converters.toInt32Le(data, 3, 0x090a0b0c);
        assertArrayEquals(new byte[]{ 0, 1, 2, 12, 11, 10, 9, 6, 7, 8}, data);
    }
}
