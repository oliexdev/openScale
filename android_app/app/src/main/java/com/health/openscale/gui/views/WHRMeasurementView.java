package com.health.openscale.gui.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleCalculator;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class WHRMeasurementView extends MeasurementView {

    public WHRMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_whr), ContextCompat.getDrawable(context, R.drawable.whr));
    }

    @Override
    public void updateValue(ScaleData updateData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        setValueOnView(updateCalculator.getWHR());
    }

    @Override
    public void updateDiff(ScaleData updateData, ScaleData lastData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        ScaleCalculator lastCalculator = new ScaleCalculator(lastData);
        setDiffOnView(updateCalculator.getWHR(), lastCalculator.getWHR());
    }

    @Override
    public String getUnit() {
        return "";
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("hipEnable", true) && preferences.getBoolean("waistEnable", true));
    }

    @Override
    public EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWHR(value);
    }

    @Override
    public float getMinValue() {
        return 0.5f;
    }

    @Override
    public float getMaxValue() {
        return 1.5f;
    }

    @Override
    int getInputType() {
        return 0;
    }

}
