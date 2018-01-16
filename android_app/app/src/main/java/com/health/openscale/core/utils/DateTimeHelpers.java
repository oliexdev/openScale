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

package com.health.openscale.core.utils;

import java.util.Calendar;

public final class DateTimeHelpers {
    static public int daysBetween(Calendar start, Calendar end) {
        if (start.after(end)) {
            return -daysBetween(end, start);
        }

        int days = 0;

        Calendar current = (Calendar)start.clone();
        while (current.get(Calendar.YEAR) < end.get(Calendar.YEAR)) {
            final int daysInYear =
                current.getActualMaximum(Calendar.DAY_OF_YEAR)
                - current.get(Calendar.DAY_OF_YEAR) + 1;
            days += daysInYear;
            current.add(Calendar.DAY_OF_YEAR, daysInYear);
        }

        days += end.get(Calendar.DAY_OF_YEAR) - current.get(Calendar.DAY_OF_YEAR);

        return days;
    }

    static public int yearsBetween(Calendar start, Calendar end) {
        int years = end.get(Calendar.YEAR) - start.get(Calendar.YEAR);

        final int startMonth = start.get(Calendar.MONTH);
        final int endMonth = end.get(Calendar.MONTH);
        if (endMonth < startMonth
            || (endMonth == startMonth
                && end.get(Calendar.DAY_OF_MONTH) < start.get(Calendar.DAY_OF_MONTH))) {
            years -= 1;
        }
        return years;
    }
}
