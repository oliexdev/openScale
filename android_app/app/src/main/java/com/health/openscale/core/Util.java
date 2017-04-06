package com.health.openscale.core;

import java.util.Calendar;

public class Util
{
    public static boolean isSameDate(Calendar c1, Calendar c2)
    {
        int[] dateFields = {Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH};
        for (int dateField : dateFields)
        {
            if (c1.get(dateField) != c2.get(dateField)) return false;
        }
        return true;
    }
}
