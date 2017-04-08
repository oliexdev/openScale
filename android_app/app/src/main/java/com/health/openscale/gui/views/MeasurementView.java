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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.support.v4.content.ContextCompat;
import android.text.Html;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Space;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

import lecho.lib.hellocharts.util.ChartUtils;

import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.ADD;
import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.EDIT;

public abstract class MeasurementView extends TableLayout {
    public enum MeasurementViewMode {VIEW, EDIT, ADD};
    private static String SYMBOL_UP = "&#x2197;";
    private static String SYMBOL_DOWN = "&#x2198;";

    private TableRow measurementRow;
    private ImageView iconView;
    private TextView nameView;
    private TextView valueView;
    private ImageView editModeView;
    private ImageView indicatorView;

    private TableRow evaluatorRow;
    private LinearGaugeView evaluatorView;

    private String nameText;

    private String value;

    private MeasurementViewMode measurementMode;

    public MeasurementView(Context context, String text, Drawable icon) {
        super(context);
        initView(context);

        measurementMode = MeasurementViewMode.VIEW;
        nameText = text;
        value = new String();
        nameView.setText(text);
        iconView.setImageDrawable(icon);
    }

    private void initView(Context context) {
        measurementRow = new TableRow(context);

        iconView = new ImageView(context);
        nameView = new TextView(context);
        valueView = new TextView(context);
        editModeView = new ImageView(context);
        indicatorView = new ImageView(context);

        evaluatorRow = new TableRow(context);
        evaluatorView = new LinearGaugeView(context);

        measurementRow.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT, 1.0f));
        measurementRow.addView(iconView);
        measurementRow.addView(nameView);
        measurementRow.addView(valueView);
        measurementRow.addView(editModeView);
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

        editModeView.getLayoutParams().height = pxImageDp(20);
        editModeView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        editModeView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.edit));
        editModeView.setVisibility(View.GONE);

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

    public float getValue() {
        if (value.length() == 0) {
            return -1;
        }

        return Float.valueOf(value);
    }

    public String getValueAsString() {
        return value;
    }

    protected boolean isEditable() {
        return true;
    }

    public void setEditMode(MeasurementViewMode mode) {
        measurementMode = mode;

        switch (mode) {
            case VIEW:
                indicatorView.setVisibility(View.VISIBLE);
                editModeView.setVisibility(View.GONE);
                break;
            case EDIT:
            case ADD:
                if (isEditable()) {
                    editModeView.setVisibility(View.VISIBLE);
                }
                indicatorView.setVisibility(View.GONE);
                evaluatorRow.setVisibility(View.GONE);
                break;
        }
    }

    protected MeasurementViewMode getMeasurementMode() {
        return measurementMode;
    }

    protected void setValueOnView(Object objValue) {
        value = String.valueOf(objValue);

        try{
            Float floatValue = Float.parseFloat(value);
            evaluate(floatValue);
            valueView.setText(String.format("%.2f ", floatValue) + getUnit());
        } catch (NumberFormatException e) {

            valueView.setText(value);
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

    protected boolean validateInput(EditText view) {
        if (view.getText().toString().length() == 0) {
            view.setError(getResources().getString(R.string.error_value_required));
            return false;
        }

        float floatValue = Float.valueOf(view.getText().toString());

        if (!(floatValue >= 0 && floatValue <= getMaxValue())) {
            view.setError(getResources().getString(R.string.error_value_range));
            return false;
        }

        return true;
    }

    private void evaluate(float value) {
        EvaluationSheet evalSheet = new EvaluationSheet(getScaleUser());
        EvaluationResult evalResult = evaluateSheet(evalSheet, value);

        if (evalResult == null) {
            evalResult = new EvaluationResult();
        }

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

    protected int getInputType() {
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
    }

    protected String getHintText() {
        return getResources().getString(R.string.info_enter_value_unit) + " " + getUnit();
    }

    protected AlertDialog getInputDialog() {
        final AlertDialog floatDialog;
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(nameView.getText());
        builder.setIcon(iconView.getDrawable());

        final EditText input = new EditText(getContext());

        input.setInputType(getInputType());
        input.setHint(getHintText());
        builder.setView(input);

        builder.setPositiveButton(getResources().getString(R.string.label_ok), null);
        builder.setNegativeButton(getResources().getString(R.string.label_cancel), null);

        floatDialog = builder.create();

        floatDialog.setOnShowListener(new DialogInterface.OnShowListener() {

            @Override
            public void onShow(DialogInterface dialog) {

                Button b = floatDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                b.setOnClickListener(new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        if (validateInput(input)) {
                            setValueOnView(input.getText().toString());
                            floatDialog.dismiss();
                        }
                    }
                });
            }
        });

        return floatDialog;
    }

    private class onClickListenerEvaluation implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (getMeasurementMode() == EDIT || getMeasurementMode() == ADD) {
                if (isEditable()) {
                    getInputDialog().show();
                }
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

