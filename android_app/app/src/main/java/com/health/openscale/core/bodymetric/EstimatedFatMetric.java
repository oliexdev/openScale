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

public abstract class EstimatedFatMetric {
    public enum FORMULA_FAT { BF_DEURENBERG, BF_BJoN, BF_EDDY, BF_GALLAGHER, BF_GALLAGHER_ASIAN };

    public static EstimatedFatMetric getEstimatedFatMetric( FORMULA_FAT fatMetric) {
        switch (fatMetric) {
            case BF_DEURENBERG:
                return new BFDeurenberg();
            case BF_BJoN:
                return new BFBJoN();
            case BF_EDDY:
                return new BFEddy();
            case BF_GALLAGHER:
                return new BFGallagher();
            case BF_GALLAGHER_ASIAN:
                return new BFGallagherAsian();
        }

        return null;
    }

    public abstract String getName();
    public abstract float getFat(ScaleUser user, ScaleData data);
}
