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

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class WHtRMeasurementView extends FloatMeasurementView {
    // Don't change key value, it may be stored persistent in preferences
    public static final String KEY = "whtr";

    public WHtRMeasurementView(Context context) {
        super(context, R.string.label_whtr, R.drawable.ic_whtr);
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
    protected float getMeasurementValue(ScaleMeasurement measurement) {
        return measurement.getWHtR(getScaleUser().getBodyHeight());
    }

    @Override
    protected void setMeasurementValue(float value, ScaleMeasurement measurement) {
        // Empty
    }

    @Override
    public String getUnit() {
        return "";
    }

    @Override
    protected float getMaxValue() {
        return 1;
    }

    @Override
    public int getColor() {
        return Color.parseColor("#9CCC65");
    }

    @Override
    protected EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWHtR(value);
    }
}
