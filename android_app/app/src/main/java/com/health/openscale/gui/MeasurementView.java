package com.health.openscale.gui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.support.v4.content.ContextCompat;
import android.text.Html;
import android.view.View;
import android.widget.ImageView;
import android.widget.Space;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.health.openscale.R;
import com.health.openscale.core.EvaluationResult;
import com.health.openscale.core.EvaluationSheet;
import com.health.openscale.core.ScaleCalculator;
import com.health.openscale.core.ScaleData;
import com.health.openscale.core.ScaleUser;

import lecho.lib.hellocharts.util.ChartUtils;

abstract class MeasurementView extends TableLayout {
    private static String SYMBOL_UP = "&#x2197;";
    private static String SYMBOL_DOWN = "&#x2198;";

    private TableRow measurementRow;
    private ImageView iconView;
    private TextView nameView;
    private TextView valueView;
    private ImageView indicatorView;

    private TableRow evaluatorRow;
    private LinearGaugeView evaluatorView;

    private String nameText;

    protected ScaleUser scaleUser;

    public MeasurementView(Context context, String text, Drawable icon) {
        super(context);
        initView(context);

        nameText = text;
        nameView.setText(text);
        iconView.setImageDrawable(icon);
    }

    private void initView(Context context) {
        measurementRow = new TableRow(context);

        iconView = new ImageView(context);
        nameView = new TextView(context);
        valueView = new TextView(context);
        indicatorView = new ImageView(context);

        evaluatorRow = new TableRow(context);
        evaluatorView = new LinearGaugeView(context);


        measurementRow.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT, 1.0f));
        measurementRow.addView(iconView);
        measurementRow.addView(nameView);
        measurementRow.addView(valueView);
        measurementRow.addView(indicatorView);

        addView(measurementRow);
        addView(evaluatorRow);

        iconView.getLayoutParams().height = 80;
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        nameView.setTextSize(20);
        nameView.setTextColor(Color.BLACK);
        nameView.setLines(2);
        nameView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.92f));

        valueView.setTextSize(20);
        valueView.setTextColor(Color.BLACK);
        valueView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.MATCH_PARENT, 0.07f));

        indicatorView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.MATCH_PARENT, 0.01f));
        indicatorView.setBackgroundColor(Color.GRAY);

        evaluatorRow.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT, 1.0f));
        evaluatorRow.addView(new Space(context));
        evaluatorRow.addView(evaluatorView);
        Space spaceAfterEvaluatorView = new Space(context);
        evaluatorRow.addView(spaceAfterEvaluatorView);
        evaluatorRow.setVisibility(View.GONE);

        evaluatorView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.99f));
        spaceAfterEvaluatorView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.01f));

        measurementRow.setOnClickListener(new onClickListenerEvaluation());
    }

    abstract void updateValue(ScaleData updateData);
    abstract void updateDiff(ScaleData updateData, ScaleData lastData);
    abstract void updatePreferences(SharedPreferences preferences);
    abstract String getFormat();
    abstract EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value);
    abstract float getMinValue();
    abstract float getMaxValue();

    protected void setValueOnView(float value) {
        valueView.setText(String.format(getFormat(), value));
        evaluate(value);
    }

    protected void setDiffOnView(float value, float lastValue) {
        float diffValue = value - lastValue;

        String symbol;

        if (diffValue > 0.0) {
            symbol = SYMBOL_UP;
        } else {
            symbol = SYMBOL_DOWN;
        }

        nameView.setText(
                Html.fromHtml(
                        nameText +
                                " <br> <font color='grey'>" +
                                symbol +
                                "<small> " +
                                String.format(getFormat(), diffValue) +
                                "</small></font>"
                )
        );
    }

    protected void setVisible(boolean isVisible){
        if(isVisible) {
            measurementRow.setVisibility(View.VISIBLE);
        } else {
            measurementRow.setVisibility(View.GONE);
        }
    }

    private void evaluate(float value) {
        EvaluationSheet evalSheet = new EvaluationSheet(scaleUser);
        EvaluationResult evalResult = evaluateSheet(evalSheet, value);

        evaluatorView.setMinMaxValue(getMinValue(), getMaxValue());
        evaluatorView.setLimits(evalResult.lowLimit, evalResult.highLimit);
        evaluatorView.setValue(value);

        switch(evalResult.eval_state)
        {
            case LOW:
                indicatorView.setBackgroundColor(ChartUtils.COLOR_BLUE);
                break;
            case NORMAL:
                indicatorView.setBackgroundColor(ChartUtils.COLOR_GREEN);
                break;
            case HIGH:
                indicatorView.setBackgroundColor(ChartUtils.COLOR_RED);
                break;
            case UNDEFINED:
                indicatorView.setBackgroundColor(Color.GRAY);
                break;
        }
    }

    public void updateScaleUser(ScaleUser user) {
        scaleUser = user;
    }

    private class onClickListenerEvaluation implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (evaluatorRow.getVisibility() == View.VISIBLE) {
                evaluatorRow.setVisibility(View.GONE);
            } else {
                evaluatorRow.setVisibility(View.VISIBLE);
            }
        }
    }
}

class WeightMeasurementView extends MeasurementView {

    public WeightMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_weight), ContextCompat.getDrawable(context, R.drawable.weight));
    }

    @Override
    void updateValue(ScaleData updateData) {
        setValueOnView(updateData.weight);
    }

    @Override
    void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.weight, lastData.weight);
    }

    @Override
    String getFormat() {
        return "%.1f " + ScaleUser.UNIT_STRING[scaleUser.scale_unit];
    }

    @Override
    void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("weightEnable", true));
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWeight(value);
    }

    @Override
    float getMinValue() {
        return 30;
    }

    @Override
    float getMaxValue() {
        return 300;
    }
}

class BMIMeasurementView extends MeasurementView {

    public BMIMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_bmi), ContextCompat.getDrawable(context, R.drawable.bmi));
    }

    @Override
    void updateValue(ScaleData updateData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        setValueOnView(updateCalculator.getBMI(scaleUser.body_height));
    }

    @Override
    void updateDiff(ScaleData updateData, ScaleData lastData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        ScaleCalculator lastCalculator = new ScaleCalculator(lastData);
        setDiffOnView(updateCalculator.getBMI(scaleUser.body_height), lastCalculator.getBMI(scaleUser.body_height));
    }

    @Override
    String getFormat() {
        return "%.1f";
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBMI(value);
    }

    @Override
    float getMinValue() {
        return 10;
    }

    @Override
    float getMaxValue() {
        return 50;
    }

    @Override
    void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("weightEnable", true));
    }
}

class WaterMeasurementView extends MeasurementView {

    public WaterMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_water), ContextCompat.getDrawable(context, R.drawable.water));
    }

    @Override
    void updateValue(ScaleData updateData) {
        setValueOnView(updateData.water);
    }

    @Override
    void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.water, lastData.water);
    }

    @Override
    String getFormat() {
        return "%.1f %%";
    }

    @Override
    void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("waterEnable", true));
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyWater(value);
    }

    @Override
    float getMinValue() {
        return 30;
    }

    @Override
    float getMaxValue() {
        return 80;
    }
}

class MuscleMeasurementView extends MeasurementView {

    public MuscleMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_muscle), ContextCompat.getDrawable(context, R.drawable.muscle));
    }

    @Override
    void updateValue(ScaleData updateData) {
        setValueOnView(updateData.muscle);
    }

    @Override
    void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.muscle, lastData.muscle);
    }

    @Override
    String getFormat() {
        return "%.1f %%";
    }

    @Override
    void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("muscleEnable", true));
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyMuscle(value);
    }

    @Override
    float getMinValue() {
        return 10;
    }

    @Override
    float getMaxValue() {
        return 80;
    }
}

class FatMeasurementView extends MeasurementView {

    public FatMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_fat), ContextCompat.getDrawable(context, R.drawable.fat));
    }

    @Override
    void updateValue(ScaleData updateData) {
        setValueOnView(updateData.fat);
    }

    @Override
    void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.fat, lastData.fat);
    }

    @Override
    String getFormat() {
        return "%.1f %%";
    }

    @Override
    void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("fatEnable", true));
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyFat(value);
    }

    @Override
    float getMinValue() {
        return 10;
    }

    @Override
    float getMaxValue() {
        return 40;
    }
}

class WaistMeasurementView extends MeasurementView {

    public WaistMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_waist), ContextCompat.getDrawable(context, R.drawable.waist));
    }

    @Override
    void updateValue(ScaleData updateData) {
        setValueOnView(updateData.waist);
    }

    @Override
    void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.waist, lastData.waist);
    }

    @Override
    String getFormat() {
        return "%.1f cm";
    }

    @Override
    void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("waistEnable", true));
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWaist(value);
    }

    @Override
    float getMinValue() {
        return 30;
    }

    @Override
    float getMaxValue() {
        return 200;
    }
}

class WHtRMeasurementView extends MeasurementView {

    public WHtRMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_whtr), ContextCompat.getDrawable(context, R.drawable.whtr));
    }

    @Override
    void updateValue(ScaleData updateData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        setValueOnView(updateCalculator.getWHtR(scaleUser.body_height));
    }

    @Override
    void updateDiff(ScaleData updateData, ScaleData lastData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        ScaleCalculator lastCalculator = new ScaleCalculator(lastData);
        setDiffOnView(updateCalculator.getWHtR(scaleUser.body_height), lastCalculator.getWHtR(scaleUser.body_height));
    }

    @Override
    String getFormat() {
        return "%.2f";
    }

    @Override
    void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("waistEnable", true));
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWHtR(value);
    }

    @Override
    float getMinValue() {
        return 0;
    }

    @Override
    float getMaxValue() {
        return 1;
    }
}

class HipMeasurementView extends MeasurementView {

    public HipMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_hip), ContextCompat.getDrawable(context, R.drawable.hip));
    }

    @Override
    void updateValue(ScaleData updateData) {
        setValueOnView(updateData.hip);
    }

    @Override
    void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.hip, lastData.hip);
    }

    @Override
    String getFormat() {
        return "%.1f cm";
    }

    @Override
    void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("hipEnable", true));
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateHip(value);
    }

    @Override
    float getMinValue() {
        return 30;
    }

    @Override
    float getMaxValue() {
        return 200;
    }
}

class WHRMeasurementView extends MeasurementView {

    public WHRMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_whr), ContextCompat.getDrawable(context, R.drawable.whr));
    }

    @Override
    void updateValue(ScaleData updateData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        setValueOnView(updateCalculator.getWHR());
    }

    @Override
    void updateDiff(ScaleData updateData, ScaleData lastData) {
        ScaleCalculator updateCalculator = new ScaleCalculator(updateData);
        ScaleCalculator lastCalculator = new ScaleCalculator(lastData);
        setDiffOnView(updateCalculator.getWHR(), lastCalculator.getWHR());
    }

    @Override
    String getFormat() {
        return "%.2f";
    }

    @Override
    void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("hipEnable", true) && preferences.getBoolean("waistEnable", true));
    }

    @Override
    EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateWHR(value);
    }

    @Override
    float getMinValue() {
        return 0.5f;
    }

    @Override
    float getMaxValue() {
        return 1.5f;
    }

}
