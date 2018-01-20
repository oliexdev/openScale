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

public class TBWHumeWeyers extends EstimatedWaterMetric {
    @Override
    public String getName() {
        return "Hume & Weyers (1971)";
    }

    @Override
    public float getWater(ScaleUser user, ScaleMeasurement data) {
        if (user.getGender().isMale()) {
            return (0.194786f * user.getBodyHeight()) + (0.296785f * data.getWeight()) - 14.012934f;
        }

        return (0.34454f * user.getBodyHeight()) + (0.183809f * data.getWeight()) - 35.270121f;
    }
}
