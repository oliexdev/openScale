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

public abstract class EstimatedLBWMetric {
    public enum FORMULA { LBW_HUME, LBW_BOER };

    public static EstimatedLBWMetric getEstimatedMetric(FORMULA metric) {
        switch (metric) {
            case LBW_HUME:
                return new LBWHume();
            case LBW_BOER:
                return new LBWBoer();
        }

        return null;
    }

    public abstract String getName();
    public abstract float getLBW(ScaleUser user, ScaleData data);
}
