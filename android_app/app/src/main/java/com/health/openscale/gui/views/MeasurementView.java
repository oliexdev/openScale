package com.health.openscale.gui.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Space;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.health.openscale.R;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.datatypes.ScaleUser;

import lecho.lib.hellocharts.util.ChartUtils;

public abstract class MeasurementView extends TableLayout {
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

    private boolean editMode;

    public MeasurementView(Context context, String text, Drawable icon) {
        super(context);
        initView(context);

        editMode = false;
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

        iconView.getLayoutParams().height = pxImageDp(30);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameView.setTextColor(Color.BLACK);
        nameView.setLines(2);
        nameView.setLayoutParams(new TableRow.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 0.90f));

        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setTextColor(Color.BLACK);
        valueView.setLayoutParams(new TableRow.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, 0.01f));

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

    public abstract void updateValue(ScaleData updateData);
    public abstract void updateDiff(ScaleData updateData, ScaleData lastData);
    public abstract void updatePreferences(SharedPreferences preferences);
    public abstract String getUnit();
    public abstract EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value);
    public abstract float getMinValue();
    public abstract float getMaxValue();

    abstract int getInputType();

    public float getValue() {
        if (valueView.getText().length() == 0) {
            return -1;
        }

        return Float.valueOf(valueView.getText().toString());
    }

    public void setEditMode(boolean mode) {
        editMode = mode;

        if (editMode) {
            valueView = new EditText(getContext());
            valueView.setInputType(getInputType());
            valueView.setHint(getContext().getResources().getString(R.string.info_enter_value_unit) + " " + getUnit());
            measurementRow.addView(valueView);
            indicatorView.setVisibility(View.GONE);
        }
    }

    protected boolean isEditModeOn() {
        return editMode;
    }

    protected void setValueOnView(float value) {
        if (isEditModeOn()) {
            valueView.setText(String.valueOf(value));
        } else {
            valueView.setText(String.format("%.2f ", value) + getUnit());
            evaluate(value);
        }
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
                                String.format("%.2f ", diffValue) + getUnit() +
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

    private int pxImageDp(float dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    public boolean validateInput() {
        if (measurementRow.getVisibility() == View.VISIBLE) {
            if (valueView.getText().toString().length() == 0) {
                valueView.setError(getResources().getString(R.string.error_value_required));
                return false;
            }

            float value = Float.valueOf(valueView.getText().toString());

            if (!(value >= 0 && value <= getMaxValue())) {
                valueView.setError(getResources().getString(R.string.error_value_range));
                return false;
            }
        }

        return true;
    }

    private void evaluate(float value) {
        EvaluationSheet evalSheet = new EvaluationSheet(getScaleUser());
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

    protected ScaleUser getScaleUser() {
        OpenScale openScale = OpenScale.getInstance(getContext());

        return openScale.getSelectedScaleUser();
    }

    private class onClickListenerEvaluation implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (isEditModeOn()) {
                return;
            }

            if (evaluatorRow.getVisibility() == View.VISIBLE) {
                evaluatorRow.setVisibility(View.GONE);
            } else {
                evaluatorRow.setVisibility(View.VISIBLE);
            }
        }
    }
}

