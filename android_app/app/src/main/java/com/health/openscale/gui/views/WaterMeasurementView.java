/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.gui.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class WaterMeasurementView extends FloatMeasurementView {

    private boolean estimateWaterEnable;
    private boolean percentageEnable;

    public WaterMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_water), ContextCompat.getDrawable(context, R.drawable.ic_water));
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("waterEnable", true));
        estimateWaterEnable = preferences.getBoolean("estimateWaterEnable", false);
        percentageEnable = preferences.getBoolean("waterPercentageEnable", true);
    }

    @Override
    protected float getMeasurementValue(ScaleMeasurement measurement) {
        if (percentageEnable) {
            return measurement.getWater();
        }

        return measurement.getConvertedWeight(getScaleUser().getScaleUnit()) / 100.0f * measurement.getWater();
    }

    @Override
    protected void setMeasurementValue(float value, ScaleMeasurement measurement) {
        if (percentageEnable) {
            measurement.setWater(value);
        } else {
            measurement.setWater(100.0f / measurement.getConvertedWeight(getScaleUser().getScaleUnit()) * value);
        }
    }

    @Override
    protected String getUnit() {
        if (percentageEnable) {
            return "%";
        }

        return getScaleUser().getScaleUnit().toString();
    }

    @Override
    protected float getMaxValue() {
        if (percentageEnable) {
            return 80;
        }

        return 300;
    }

    @Override
    protected boolean isEstimationEnabled() {
        return estimateWaterEnable;
    }

    @Override
    protected EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyWater(value);
    }
}
