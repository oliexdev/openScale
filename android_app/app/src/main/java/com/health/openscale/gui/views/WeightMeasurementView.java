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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;
import com.health.openscale.core.utils.Converters;

public class WeightMeasurementView extends FloatMeasurementView {
    // Don't change key value, it may be stored persistent in preferences
    public static final String KEY = "weight";

    public WeightMeasurementView(Context context) {
        super(context, R.string.label_weight, R.drawable.ic_weight);
    }

    @Override
    public String getKey() {
        return KEY;
    }

    @Override
    protected float getMeasurementValue(ScaleMeasurement measurement) {
        return Converters.fromKilogram(measurement.getWeight(), getScaleUser().getScaleUnit());
    }

    @Override
    protected void setMeasurementValue(float value, ScaleMeasurement measurement) {
        measurement.setWeight(Converters.toKilogram(value, getScaleUser().getScaleUnit()));
    }

    @Override
    public String getUnit() {
        return getScaleUser().getScaleUnit().toString();
    }

    @Override
    protected float getMaxValue() {
        return Converters.fromKilogram(300.0f, getScaleUser().getScaleUnit());
    }

    @Override
    public int getColor() {
        return Color.parseColor("#AA66CC");
    }

    @Override
    protected EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWeight(value);
    }

    @Override
    public void appendDiffValue(SpannableStringBuilder text, boolean newLine) {
        float value = getValue();
        float previousValue = getPreviousValue();

        if (previousValue < 0.0f) {
            return;
        }

        char symbol = SYMBOL_NEUTRAL;
        int color = Color.GRAY;

        final float diff = value - previousValue;
        if (diff > 0.0f) {
            symbol = SYMBOL_UP;
        } else if (diff < 0.0f) {
            symbol = SYMBOL_DOWN;
        }

        ScaleUser user = getScaleUser();
        if(user != null) {
            float goalWeight = user.getGoalWeight();
            if(goalWeight > 0) {

                if (value - previousValue > 0) {
                    color = (value > goalWeight) ? Color.RED : Color.GREEN;
                } else if (value - previousValue < 0) {
                    color = (value < goalWeight) ? Color.RED : Color.GREEN;
                }
            }
        }

        if (newLine) {
            text.append('\n');
        }
        int start = text.length();
        text.append(symbol);
        text.setSpan(new ForegroundColorSpan(color), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        text.append(' ');

        start = text.length();
        text.append(formatValue(diff));
        text.setSpan(new ForegroundColorSpan(Color.GRAY), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.8f), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
