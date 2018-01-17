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

public class BFDeurenbergII extends EstimatedFatMetric {
    @Override
    public String getName() {
        return "Deurenberg et. al (1991)";
    }

    @Override
    public float getFat(ScaleUser user, ScaleMeasurement data) {
        if (user.getGender().isMale()) {
            return (data.getBMI(user.getBodyHeight()) * 1.2f) + (user.getAge(data.getDateTime()) * 0.23f) - 16.2f;
        }

        return (data.getBMI(user.getBodyHeight()) * 1.2f) + (user.getAge(data.getDateTime()) * 0.23f) - 5.4f;
    }
}
