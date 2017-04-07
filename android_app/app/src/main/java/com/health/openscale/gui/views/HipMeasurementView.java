package com.health.openscale.gui.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;
import android.text.InputType;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class HipMeasurementView extends MeasurementView {

    public HipMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_hip), ContextCompat.getDrawable(context, R.drawable.hip));
    }

    @Override
    public void updateValue(ScaleData updateData) {
        setValueOnView(updateData.hip);
    }

    @Override
    public void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.hip, lastData.hip);
    }

    @Override
    public String getUnit() {
        return "cm";
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("hipEnable", true));
    }

    @Override
    public EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateHip(value);
    }

    @Override
    public float getMinValue() {
        return 30;
    }

    @Override
    public float getMaxValue() {
        return 200;
    }

    @Override
    int getInputType() {
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
    }
}
