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

import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.datatypes.ScaleUser;

public class TBWLeeSongKim extends EstimatedWaterMetric {
    @Override
    public String getName() {
        return "Lee, Song, Kim, Lee et. al (2001)";
    }

    @Override
    public float getWater(ScaleUser user, ScaleData data) {
        float maleWater = -28.3497f + (0.243057f * user.body_height) + (0.366248f * data.getWeight());
        float femaleWater = -26.6224f + (0.262513f * user.body_height) + (0.232948f * data.getWeight());

        return Utils.genderizeMetric(user, maleWater, femaleWater);
    }
}
