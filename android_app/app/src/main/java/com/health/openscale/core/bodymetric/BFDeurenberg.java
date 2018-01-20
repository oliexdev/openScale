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

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

public class BFDeurenberg extends EstimatedFatMetric {
    @Override
    public String getName() {
        return "Deurenberg (1992)";
    }

    @Override
    public float getFat(ScaleUser user, ScaleMeasurement data) {
        final int gender = user.getGender().isMale() ? 1 : 0;
        if (user.getAge(data.getDateTime()) >= 16) {
            return (1.2f * data.getBMI(user.getBodyHeight())) + (0.23f*user.getAge(data.getDateTime())) - (10.8f * gender) - 5.4f;
        }

        return (1.294f * data.getBMI(user.getBodyHeight())) + (0.20f*user.getAge(data.getDateTime())) - (11.4f * gender) - 8.0f;
    }
}
