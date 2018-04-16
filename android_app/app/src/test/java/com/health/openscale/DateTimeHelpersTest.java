/* Copyright (C) 2017-2018 Erik Johansson <erik@ejohansson.se>
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

import com.health.openscale.core.utils.DateTimeHelpers;

import org.junit.Test;

import java.util.Calendar;

import static junit.framework.Assert.assertEquals;

public class DateTimeHelpersTest {
    private Calendar getDate(int year, int month, int day, int hour, int minute, int second, int ms) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, day, hour, minute, second);
        cal.set(Calendar.MILLISECOND, ms);
        return cal;
    }
    private Calendar getDate(int year, int month, int day) {
        return getDate(year, month, day, 0, 0, 0, 0);
    }

    @Test
    public void daysBetween() throws Exception {
        assertEquals(0,
            DateTimeHelpers.daysBetween(
                getDate(2017, 1, 1),
                getDate(2017, 1, 1)));

        assertEquals(0,
            DateTimeHelpers.daysBetween(
                getDate(2017, 1, 1, 12, 10, 10, 0),
                getDate(2017, 1, 1, 12, 9, 0, 0)));

        assertEquals(0,
            DateTimeHelpers.daysBetween(
                getDate(2017, 1, 1, 23, 59, 59, 999),
                getDate(2017, 1, 1)));

        assertEquals(1,
            DateTimeHelpers.daysBetween(
                getDate(2017, 1, 1),
                getDate(2017, 1, 2)));

        assertEquals(-1,
            DateTimeHelpers.daysBetween(
                getDate(2017, 1, 2),
                getDate(2017, 1, 1)));

        assertEquals(1,
            DateTimeHelpers.daysBetween(
                getDate(2017, 1, 1, 23, 59, 59, 999),
                getDate(2017, 1, 2)));

        assertEquals(29 - 10 + 4 * 30 + 6 * 31 + 2,
            DateTimeHelpers.daysBetween(
                getDate(2016, 2, 10, 1, 2, 3, 10),
                getDate(2017, 1, 2)));

        assertEquals(1 + 365 + 366,
            DateTimeHelpers.daysBetween(
                getDate(2014, 12, 31),
                getDate(2017, 1, 1)));
    }

    @Test
    public void yearsBetween() throws Exception {
        assertEquals(19,
                DateTimeHelpers.yearsBetween(
                        getDate(1980, 3, 26),
                        getDate(2000, 3, 25)));
        assertEquals(20,
                DateTimeHelpers.yearsBetween(
                        getDate(1980, 3, 26),
                        getDate(2000, 3, 26)));
        assertEquals(20,
                DateTimeHelpers.yearsBetween(
                        getDate(1980, 3, 26),
                        getDate(2000, 3, 27)));

        assertEquals(0,
                DateTimeHelpers.yearsBetween(
                        getDate(2000, 3, 1),
                        getDate(2001, 2, 28)));
        assertEquals(1,
                DateTimeHelpers.yearsBetween(
                        getDate(2000, 3, 1),
                        getDate(2001, 3, 1)));
        assertEquals(1,
                DateTimeHelpers.yearsBetween(
                        getDate(2000, 3, 1),
                        getDate(2001, 3, 2)));
    }
}
