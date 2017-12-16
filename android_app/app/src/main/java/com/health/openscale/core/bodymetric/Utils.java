/* Copyright (C) 2017  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.core.bodymetric;

import com.health.openscale.core.datatypes.ScaleUser;

public class Utils {
    public static float genderizeMetric(ScaleUser user, float maleValue, float femaleValue) {
        switch (user.gender) {
            case ScaleUser.MALE:
                return maleValue;
            case ScaleUser.FEMALE:
                return femaleValue;
            default:
                return (maleValue + femaleValue) / 2.0f;
        }
    }
}
