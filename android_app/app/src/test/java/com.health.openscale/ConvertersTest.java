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

public class ConvertersTest {

    @Test
    public void weightUnitTypeConverters() throws Exception {
        assertEquals(0, Converters.toInt(Converters.WeightUnit.KG));
        assertEquals(1, Converters.toInt(Converters.WeightUnit.LB));
        assertEquals(2, Converters.toInt(Converters.WeightUnit.ST));

        assertEquals(Converters.WeightUnit.KG, Converters.fromInt(0));
        assertEquals(Converters.WeightUnit.LB, Converters.fromInt(1));
        assertEquals(Converters.WeightUnit.ST, Converters.fromInt(2));

        assertEquals("kg", Converters.WeightUnit.KG.toString());
        assertEquals("lb", Converters.WeightUnit.LB.toString());
        assertEquals("st", Converters.WeightUnit.ST.toString());
    }

    @Test
    public void weightConverters() throws Exception {
        for (Converters.WeightUnit unit : Converters.WeightUnit.values()) {
            assertEquals(10.0f,
                    Converters.toKilogram(Converters.fromKilogram(10.0f, unit), unit));
        }
    }
}
