/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
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
import android.graphics.Color;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class FatCaliperMeasurementView extends FloatMeasurementView {
    // Don't change key value, it may be stored persistent in preferences
    public static final String KEY = "fat_caliper";

    public FatCaliperMeasurementView(Context context) {
        super(context, R.string.label_fat_caliper, R.drawable.ic_fat_caliper);
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public boolean isEditable() {
        return false;
    }

    @Override
    protected boolean supportsPercentageToAbsoluteWeightConversion() {
        return true;
    }

    @Override
    protected float getMeasurementValue(ScaleMeasurement measurement) {
        return measurement.getFatCaliper(getScaleUser());
    }

    @Override
    protected void setMeasurementValue(float value, ScaleMeasurement measurement) {

    }

    @Override
    public String getUnit() {
        if (shouldConvertPercentageToAbsoluteWeight()) {
            return getScaleUser().getScaleUnit().toString();
        }

        return "%";
    }

    @Override
    protected float getMaxValue() {
        return maybeConvertPercentageToAbsoluteWeight(80);
    }

    @Override
    public int getColor() {
        return Color.parseColor("#f3e5f5");
    }

    @Override
    protected EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyFat(value);
    }
}
