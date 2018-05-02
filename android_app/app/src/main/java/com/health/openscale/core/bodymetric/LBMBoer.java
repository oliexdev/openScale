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

import android.content.Context;

import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

public class LBMBoer extends EstimatedLBMMetric {
    @Override
    public String getName(Context context) {
        return "Boer (1984)";
    }

    @Override
    public float getLBM(ScaleUser user, ScaleMeasurement data) {
        if (user.getGender().isMale()) {
            return (0.4071f * data.getWeight()) + (0.267f * user.getBodyHeight()) - 19.2f;
        }

        return (0.252f * data.getWeight()) + (0.473f * user.getBodyHeight()) - 48.3f;
    }
}
