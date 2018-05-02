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

public abstract class EstimatedLBMMetric {
    // Don't change enum names, they are stored persistent in preferences
    public enum FORMULA { LBW_HUME, LBW_BOER, LBW_WEIGHT_MINUS_FAT }

    public static EstimatedLBMMetric getEstimatedMetric(FORMULA metric) {
        switch (metric) {
            case LBW_HUME:
                return new LBMHume();
            case LBW_BOER:
                return new LBMBoer();
            case LBW_WEIGHT_MINUS_FAT:
                return new LBMWeightMinusFat();
        }

        return null;
    }

    public abstract String getName(Context context);
    public abstract float getLBM(ScaleUser user, ScaleMeasurement data);
}
