package com.health.openscale.gui.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;
import android.text.InputType;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class WaterMeasurementView extends MeasurementView {

    public WaterMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_water), ContextCompat.getDrawable(context, R.drawable.water));
    }

    @Override
    public void updateValue(ScaleData updateData) {
        setValueOnView(updateData.water);
    }

    @Override
    public void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.water, lastData.water);
    }

    @Override
    public String getUnit() {
        return "%";
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("waterEnable", true));
    }

    @Override
    public EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyWater(value);
    }

    @Override
    public float getMinValue() {
        return 30;
    }

    @Override
    public float getMaxValue() {
        return 80;
    }

    @Override
    int getInputType() {
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
    }
}
