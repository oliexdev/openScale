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

public abstract class EstimatedWaterMetric {
    // Don't change enum names, they are stored persistent in preferences
    public enum FORMULA { TBW_BEHNKE, TBW_DELWAIDECRENIER, TBW_HUMEWEYERS, TBW_LEESONGKIM }

    public static EstimatedWaterMetric getEstimatedMetric(FORMULA metric) {
        switch (metric) {
            case TBW_BEHNKE:
                return new TBWBehnke();
            case TBW_DELWAIDECRENIER:
                return new TBWDelwaideCrenier();
            case TBW_HUMEWEYERS:
                return new TBWHumeWeyers();
            case TBW_LEESONGKIM:
                return new TBWLeeSongKim();
        }

        return null;
    }

    public abstract String getName();
    public abstract float getWater(ScaleUser user, ScaleMeasurement data);
}
