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

import com.health.openscale.R;
import com.health.openscale.core.bodymetric.EstimatedLBMMetric;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;
import com.health.openscale.core.utils.Converters;

public class LBMMeasurementView extends FloatMeasurementView {
    // Don't change key value, it may be stored persistent in preferences
    public static final String KEY = "lbw";

    public LBMMeasurementView(Context context) {
        super(context, R.string.label_lbm, R.drawable.ic_lbm);
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    protected float getMeasurementValue(ScaleMeasurement measurement) {
        return Converters.fromKilogram(measurement.getLbm(), getScaleUser().getScaleUnit());
    }

    @Override
    protected void setMeasurementValue(float value, ScaleMeasurement measurement) {
        measurement.setLbm(Converters.toKilogram(value, getScaleUser().getScaleUnit()));
    }

    @Override
    public String getUnit() {
        return getScaleUser().getScaleUnit().toString();
    }

    @Override
    protected float getMaxValue() {
        return Converters.fromKilogram(300, getScaleUser().getScaleUnit());
    }

    @Override
    public int getColor() {
        return Color.parseColor("#5C6BC0");
    }

    @Override
    protected boolean isEstimationSupported() { return true; }

    @Override
    protected void prepareEstimationFormulaPreference(ListPreference preference) {
        String[] entries = new String[EstimatedLBMMetric.FORMULA.values().length];
        String[] values = new String[entries.length];

        int idx = 0;
        for (EstimatedLBMMetric.FORMULA formula : EstimatedLBMMetric.FORMULA.values()) {
            entries[idx] = EstimatedLBMMetric.getEstimatedMetric(formula).getName(getContext());
            values[idx] = formula.name();
            ++idx;
        }

        preference.setEntries(entries);
        preference.setEntryValues(values);
    }

    @Override
    protected EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return null;
    }
}
