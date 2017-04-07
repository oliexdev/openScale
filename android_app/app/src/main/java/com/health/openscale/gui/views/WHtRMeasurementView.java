package com.health.openscale.gui.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleCalculator;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class WHtRMeasurementView extends MeasurementView {

    public WHtRMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_whtr), ContextCompat.getDrawable(context, R.drawable.whtr));
    }

    @Override
    public void updateValue(ScaleData updateData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        setValueOnView(updateCalculator.getWHtR(getScaleUser().body_height));
    }

    @Override
    public void updateDiff(ScaleData updateData, ScaleData lastData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        ScaleCalculator lastCalculator = new ScaleCalculator(lastData);
        setDiffOnView(updateCalculator.getWHtR(getScaleUser().body_height), lastCalculator.getWHtR(getScaleUser().body_height));
    }

    @Override
    public String getUnit() {
        return "";
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("waistEnable", true));
    }

    @Override
    public EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWHtR(value);
    }

    @Override
    public float getMinValue() {
        return 0;
    }

    @Override
    public float getMaxValue() {
        return 1;
    }

    @Override
    int getInputType() {
        return 0;
    }
}
