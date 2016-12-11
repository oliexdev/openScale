package com.health.openscale.gui;

import android.app.Fragment;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.health.openscale.R;
import com.health.openscale.core.EvaluationResult;
import com.health.openscale.core.EvaluationSheet;
import com.health.openscale.core.LinearGaugeView;
import com.health.openscale.core.ScaleUser;

import lecho.lib.hellocharts.util.ChartUtils;

abstract class Measurement {
    private TextView txtLabel;
    private TextView txtView;
    private final String label;
    private LinearGaugeView linearGaugeView;
    private ImageView imageView;
    private TableRow tableRow;

    ScaleUser scaleUser;

    public Measurement(View overviewView) {
        txtLabel = (TextView) overviewView.findViewById(getTxtLabelId());
        txtView = (TextView) overviewView.findViewById(getTxtViewId());
        linearGaugeView = (LinearGaugeView) overviewView.findViewById(getLinearGaugeViewId());
        imageView = (ImageView)overviewView.findViewById(getImageViewId());

        label = overviewView.getResources().getString(getLabelId());

        tableRow = (TableRow)overviewView.findViewById(getTableRowId());
        tableRow.setOnClickListener(new onClickListenerEvaluation());
    }

    public void updateValue(float value) {
        setText(value);
        evaluate(value);
    }

    abstract int getTxtLabelId();
    abstract int getTxtViewId();
    abstract int getLabelId();
    abstract int getLinearGaugeViewId();
    abstract int getImageViewId();
    abstract int getTableRowId();

    abstract String getFormat();

    abstract EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value);

    abstract int getMinValue();

    abstract int getMaxValue();

    private void setText(float value) {
        txtView.setText(String.format(getFormat(), value));
    }

    private void evaluate(float value) {
        EvaluationSheet evalSheet = new EvaluationSheet(scaleUser);
        EvaluationResult evalResult = evaluateSheet(evalSheet, value);

        linearGaugeView.setMinMaxValue(getMinValue(), getMaxValue());
        linearGaugeView.setLimits(evalResult.lowLimit, evalResult.highLimit);
        linearGaugeView.setValue(value);

        switch(evalResult.eval_state)
        {
            case LOW:
                imageView.setBackgroundColor(ChartUtils.COLOR_BLUE);
                break;
            case NORMAL:
                imageView.setBackgroundColor(ChartUtils.COLOR_GREEN);
                break;
            case HIGH:
                imageView.setBackgroundColor(ChartUtils.COLOR_RED);
                break;
            case UNDEFINED:
                imageView.setBackgroundColor(Color.GRAY);
                break;
        }
    }

    public void setDiff(float value, float diffValue) {
        ScaleDiff.setDiff(
                txtLabel,
                value - diffValue,
                label,
                getFormat());
    }

    public void updateVisibleRow(SharedPreferences preferences){
        if(isPreferenceSet(preferences)) {
            tableRow.setVisibility(View.VISIBLE);
        } else {
            tableRow.setVisibility(View.GONE);
        }
    }

    abstract boolean isPreferenceSet(SharedPreferences preferences);

    private class onClickListenerEvaluation implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            TableRow row = (TableRow)v;

            TableLayout tableLayout = (TableLayout)row.getParent();
            int index = tableLayout.indexOfChild(row);

            TableRow rowEvaluation = (TableRow)tableLayout.getChildAt(index+1);

            if (rowEvaluation.getVisibility() == View.VISIBLE) {
                rowEvaluation.setVisibility(View.GONE);
            } else {
                rowEvaluation.setVisibility(View.VISIBLE);
            }
        }
    }
}

class WeightMeasurement extends Measurement {

    public WeightMeasurement(View overviewView) {
        super(overviewView);
    }

    @Override
    int getTxtLabelId() {
        return R.id.txtLabelWeight;
    }

    @Override
    int getTxtViewId() {
        return R.id.txtWeightLast;
    }

    @Override
    int getLabelId() {
        return R.string.label_weight;
    }

    @Override
    int getLinearGaugeViewId() {
       return R.id.linearGaugeWeight;
    }

    @Override
    int getImageViewId() {
        return R.id.indicatorWeight;
    }

    @Override
    int getTableRowId() {
        return R.id.tableRowWeight;
    }

    @Override
    String getFormat() {
        return "%.1f " + ScaleUser.UNIT_STRING[scaleUser.scale_unit];
    }

    @Override
    boolean isPreferenceSet(SharedPreferences preferences) {
        return preferences.getBoolean("weightEnable", true);
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWeight(value);
    }

    @Override
    int getMinValue() {
        return 30;
    }

    @Override
    int getMaxValue() {
        return 300;
    }
}

class BMIMeasurement extends Measurement {

    public BMIMeasurement(View overviewView) {
        super(overviewView);
    }

    @Override
    int getTxtLabelId() {
        return R.id.txtLabelBMI;
    }

    @Override
    int getTxtViewId() {
        return R.id.txtBMILast;
    }

    @Override
    int getLabelId() {
        return R.string.label_bmi;
    }

    @Override
    int getLinearGaugeViewId() {
        return R.id.linearGaugeBMI;
    }

    @Override
    int getImageViewId() {
        return R.id.indicatorBMI;
    }

    @Override
    int getTableRowId() {
        return R.id.tableRowBMI;
    }

    @Override
    String getFormat() {
        return "%.1f";
    }

    @Override
    boolean isPreferenceSet(SharedPreferences preferences) {
        // TODO implement
        return false;
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBMI(value);
    }

    @Override
    int getMinValue() {
        return 10;
    }

    @Override
    int getMaxValue() {
        return 50;
    }
}

class WaterMeasurement extends Measurement {

    public WaterMeasurement(View overviewView) {
        super(overviewView);
    }

    @Override
    int getTxtLabelId() {
        return R.id.txtLabelWater;
    }

    @Override
    int getTxtViewId() {
        return R.id.txtWaterLast;
    }

    @Override
    int getLabelId() {
        return R.string.label_water;
    }

    @Override
    int getLinearGaugeViewId() {
        return R.id.linearGaugeWater;
    }

    @Override
    int getImageViewId() {
        return R.id.indicatorWater;
    }

    @Override
    int getTableRowId() {
        return R.id.tableRowWater;
    }

    @Override
    String getFormat() {
        return "%.1f %%";
    }

    @Override
    boolean isPreferenceSet(SharedPreferences preferences) {
        return preferences.getBoolean("waterEnable", true);
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyWater(value);
    }

    @Override
    int getMinValue() {
        return 30;
    }

    @Override
    int getMaxValue() {
        return 80;
    }
}

class MuscleMeasurement extends Measurement {

    public MuscleMeasurement(View overviewView) {
        super(overviewView);
    }

    @Override
    int getTxtLabelId() {
        return R.id.txtLabelMuscle;
    }

    @Override
    int getTxtViewId() {
        return R.id.txtMuscleLast;
    }

    @Override
    int getLabelId() {
        return R.string.label_muscle;
    }

    @Override
    int getLinearGaugeViewId() {
        return R.id.linearGaugeMuscle;
    }

    @Override
    int getImageViewId() {
        return R.id.indicatorMuscle;
    }

    @Override
    int getTableRowId() {
        return R.id.tableRowMuscle;
    }

    @Override
    String getFormat() {
        return "%.1f %%";
    }

    @Override
    boolean isPreferenceSet(SharedPreferences preferences) {
        return preferences.getBoolean("muscleEnable", true);
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyMuscle(value);
    }

    @Override
    int getMinValue() {
        return 10;
    }

    @Override
    int getMaxValue() {
        return 80;
    }
}

class FatMeasurement extends Measurement {

    public FatMeasurement(View overviewView) {
        super(overviewView);
    }

    @Override
    int getTxtLabelId() {
        return R.id.txtLabelFat;
    }

    @Override
    int getTxtViewId() {
        return R.id.txtFatLast;
    }

    @Override
    int getLabelId() {
        return R.string.label_fat;
    }

    @Override
    int getLinearGaugeViewId() {
        return R.id.linearGaugeFat;
    }

    @Override
    int getImageViewId() {
        return R.id.indicatorFat;
    }

    @Override
    int getTableRowId() {
        return R.id.tableRowFat;
    }

    @Override
    String getFormat() {
        return "%.1f %%";
    }

    @Override
    boolean isPreferenceSet(SharedPreferences preferences) {
        return preferences.getBoolean("fatEnable", true);
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyFat(value);
    }

    @Override
    int getMinValue() {
        return 10;
    }

    @Override
    int getMaxValue() {
        return 40;
    }
}

class WaistMeasurement extends Measurement {

    public WaistMeasurement(View overviewView) {
        super(overviewView);
    }

    @Override
    int getTxtLabelId() {
        return R.id.txtLabelWaist;
    }

    @Override
    int getTxtViewId() {
        return R.id.txtWaistLast;
    }

    @Override
    int getLabelId() {
        return R.string.label_waist;
    }

    @Override
    int getLinearGaugeViewId() {
        return R.id.linearGaugeWaist;
    }

    @Override
    int getImageViewId() {
        return R.id.indicatorWaist;
    }

    @Override
    int getTableRowId() {
        return R.id.tableRowWaist;
    }

    @Override
    String getFormat() {
        return "%.1f cm";
    }

    @Override
    boolean isPreferenceSet(SharedPreferences preferences) {
        return preferences.getBoolean("waistEnable", true);
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWaist(value);
    }

    @Override
    int getMinValue() {
        return 30;
    }

    @Override
    int getMaxValue() {
        return 200;
    }
}

class WHtRMeasurement extends Measurement {

    public WHtRMeasurement(View overviewView) {
        super(overviewView);
    }

    @Override
    int getTxtLabelId() {
        return R.id.txtLabelWHtR;
    }

    @Override
    int getTxtViewId() {
        return R.id.txtWHtRLast;
    }

    @Override
    int getLabelId() {
        return R.string.label_whtr;
    }

    @Override
    int getLinearGaugeViewId() {
        return R.id.linearGaugeWHtR;
    }

    @Override
    int getImageViewId() {
        return R.id.indicatorWHtR;
    }

    @Override
    int getTableRowId() {
        return R.id.tableRowWHtR;
    }

    @Override
    String getFormat() {
        return "%.2f";
    }

    @Override
    boolean isPreferenceSet(SharedPreferences preferences) {
        return preferences.getBoolean("waistEnable", true);
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWHtR(value);
    }

    @Override
    int getMinValue() {
        return 0;
    }

    @Override
    int getMaxValue() {
        return 1;
    }
}

class HipMeasurement extends Measurement {

    public HipMeasurement(View overviewView) {
        super(overviewView);
    }

    @Override
    int getTxtLabelId() {
        return R.id.txtLabelHip;
    }

    @Override
    int getTxtViewId() {
        return R.id.txtHipLast;
    }

    @Override
    int getLabelId() {
        return R.string.label_hip;
    }

    @Override
    int getLinearGaugeViewId() {
        return R.id.linearGaugeHip;
    }

    @Override
    int getImageViewId() {
        return R.id.indicatorHip;
    }

    @Override
    int getTableRowId() {
        return R.id.tableRowHip;
    }

    @Override
    String getFormat() {
        return "%.1f cm";
    }

    @Override
    boolean isPreferenceSet(SharedPreferences preferences) {
        return preferences.getBoolean("hipEnable", true);
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateHip(value);
    }

    @Override
    int getMinValue() {
        return 30;
    }

    @Override
    int getMaxValue() {
        return 200;
    }
}

class WHRMeasurement extends Measurement {

    public WHRMeasurement(View overviewView) {
        super(overviewView);
    }

    @Override
    int getTxtLabelId() {
        return R.id.txtLabelWHR;
    }

    @Override
    int getTxtViewId() {
        return R.id.txtWHRLast;
    }

    @Override
    int getLabelId() {
        return R.string.label_whr;
    }

    @Override
    int getLinearGaugeViewId() {
        return R.id.linearGaugeWHR;
    }

    @Override
    int getImageViewId() {
        return R.id.indicatorWHR;
    }

    @Override
    int getTableRowId() {
        return R.id.tableRowWHR;
    }

    @Override
    String getFormat() {
        return "%.2f";
    }

    @Override
    boolean isPreferenceSet(SharedPreferences preferences) {
        return preferences.getBoolean("hipEnable", true) && preferences.getBoolean("waistEnable", true);
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWHR(value);
    }

    @Override
    int getMinValue() {
        return 0;
    }

    @Override
    int getMaxValue() {
        return 1;
    }

}
