package com.health.openscale.gui.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;
import android.text.InputType;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class MuscleMeasurementView extends MeasurementView {

    public MuscleMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_muscle), ContextCompat.getDrawable(context, R.drawable.muscle));
    }

    @Override
    public void updateValue(ScaleData updateData) {
        setValueOnView(updateData.muscle);
    }

    @Override
    public void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.muscle, lastData.muscle);
    }

    @Override
    public String getUnit() {
        return "%";
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("muscleEnable", true));
    }

    @Override
    public EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyMuscle(value);
    }

    @Override
    public float getMinValue() {
        return 10;
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
