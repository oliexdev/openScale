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

public class BFGallagherAsian extends EstimatedFatMetric {
    @Override
    public String getName() {
        return "Gallagher et. al [asian] (2000)";
    }

    @Override
    public float getFat(ScaleUser user, ScaleMeasurement data) {
        if (user.getGender().isMale()) {
            // asian male
            return 51.9f - 740.0f * (1.0f / data.getBMI(user.getBodyHeight())) + 0.029f * user.getAge(data.getDateTime());
        }

        // asian female
        return 64.8f - 752.0f * (1.0f / data.getBMI(user.getBodyHeight())) + 0.016f * user.getAge(data.getDateTime());
    }
}
