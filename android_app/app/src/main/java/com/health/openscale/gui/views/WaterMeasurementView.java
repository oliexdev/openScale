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
import android.preference.ListPreference;
import android.support.v4.content.ContextCompat;

import com.health.openscale.R;
import com.health.openscale.core.bodymetric.EstimatedWaterMetric;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class WaterMeasurementView extends FloatMeasurementView {
    public static final String KEY = "water";

    public WaterMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_water), ContextCompat.getDrawable(context, R.drawable.ic_water));
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    protected boolean canConvertPercentageToAbsoluteWeight() {
        return true;
    }

    @Override
    protected float getMeasurementValue(ScaleMeasurement measurement) {
        return measurement.getWater();
    }

    @Override
    protected void setMeasurementValue(float value, ScaleMeasurement measurement) {
        measurement.setWater(value);
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
        return Color.parseColor("#33B5E5");
    }

    @Override
    protected boolean isEstimationSupported() { return true; }

    @Override
    protected void prepareEstimationFormulaPreference(ListPreference preference) {
        String[] entries = new String[EstimatedWaterMetric.FORMULA.values().length];
        String[] values = new String[entries.length];

        int idx = 0;
        for (EstimatedWaterMetric.FORMULA formula : EstimatedWaterMetric.FORMULA.values()) {
            entries[idx] = EstimatedWaterMetric.getEstimatedMetric(formula).getName();
            values[idx] = formula.name();
            ++idx;
        }

        preference.setEntries(entries);
        preference.setEntryValues(values);
    }

    @Override
    protected EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyWater(value);
    }
}
