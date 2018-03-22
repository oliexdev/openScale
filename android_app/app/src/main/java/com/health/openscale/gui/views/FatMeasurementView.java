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
import android.graphics.Color;
import android.support.v4.content.ContextCompat;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class FatMeasurementView extends FloatMeasurementView {
    public static final String KEY = "fat";
    private static final String[] DEPENDENCY = {WeightMeasurementView.KEY};

    public FatMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_fat), ContextCompat.getDrawable(context, R.drawable.ic_fat));
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    public String[] getDependencyKeys() {
        return DEPENDENCY;
    }

    @Override
    protected boolean canConvertPercentageToAbsoluteWeight() {
        return true;
    }

    @Override
    protected float getMeasurementValue(ScaleMeasurement measurement) {
        return measurement.getFat();
    }

    @Override
    protected void setMeasurementValue(float value, ScaleMeasurement measurement) {
        measurement.setFat(value);
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
        return maybeConvertPercentageToAbsolute(80);
    }

    @Override
    public int getColor() {
        return Color.parseColor("#FFBB33");
    }

    @Override
    protected boolean isEstimationSupported() { return true; }

    @Override
    protected EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyFat(value);
    }
}
