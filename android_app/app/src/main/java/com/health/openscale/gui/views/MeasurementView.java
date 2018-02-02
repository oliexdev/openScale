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
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.evaluation.EvaluationResult;

import lecho.lib.hellocharts.util.ChartUtils;

import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.ADD;
import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.EDIT;
import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.STATISTIC;
import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.VIEW;

public abstract class MeasurementView extends TableLayout {
    public enum MeasurementViewMode {VIEW, EDIT, ADD, STATISTIC}

    private TableRow measurementRow;
    private ImageView iconView;
    private TextView nameView;
    private TextView valueView;
    private LinearLayout incDecLayout;
    private ImageView editModeView;
    private ImageView indicatorView;

    private TableRow evaluatorRow;
    private LinearGaugeView evaluatorView;

    private MeasurementViewUpdateListener updateListener = null;
    private MeasurementViewMode measurementMode = VIEW;

    private boolean updateViews = true;

    public MeasurementView(Context context, String text, Drawable icon) {
        super(context);
        initView(context);

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

        incDecLayout = new LinearLayout(context);

        measurementRow.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT, 1.0f));
        measurementRow.setGravity(Gravity.CENTER);
        measurementRow.addView(iconView);
        measurementRow.addView(nameView);
        measurementRow.addView(valueView);
        measurementRow.addView(incDecLayout);
        measurementRow.addView(editModeView);
        measurementRow.addView(indicatorView);

        addView(measurementRow);
        addView(evaluatorRow);

        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconView.setPadding(20,0,20,0);
        iconView.setColorFilter(nameView.getCurrentTextColor());

        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameView.setLines(2);
        nameView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.55f));

        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setGravity(Gravity.RIGHT | Gravity.CENTER);
        valueView.setPadding(0,0,20,0);
        valueView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.29f));

        incDecLayout.setOrientation(VERTICAL);
        incDecLayout.setVisibility(View.GONE);
        incDecLayout.setPadding(0,0,0,0);
        incDecLayout.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.MATCH_PARENT, 0.05f));

        editModeView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_editable));
        editModeView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        editModeView.setVisibility(View.GONE);
        editModeView.setColorFilter(nameView.getCurrentTextColor());

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

        onClickListenerEvaluation onClickListener = new onClickListenerEvaluation();
        measurementRow.setOnClickListener(onClickListener);
        evaluatorRow.setOnClickListener(onClickListener);
    }

    protected LinearLayout getIncDecLayout() {
        return incDecLayout;
    }

    public void setOnUpdateListener(MeasurementViewUpdateListener listener) {
        updateListener = listener;
    }

    public void setUpdateViews(boolean update) {
        updateViews = update;
    }
    protected boolean getUpdateViews() {
        return updateViews;
    }

    public abstract void loadFrom(ScaleMeasurement measurement, ScaleMeasurement previousMeasurement);
    public abstract void saveTo(ScaleMeasurement measurement);

    public abstract void restoreState(Bundle state);
    public abstract void saveState(Bundle state);

    public abstract void updatePreferences(SharedPreferences preferences);

    public abstract String getValueAsString();
    public void appendDiffValue(SpannableStringBuilder builder) { }
    public Drawable getIcon() { return iconView.getDrawable(); }

    protected boolean isEditable() {
        return true;
    }

    public void setEditMode(MeasurementViewMode mode) {
        measurementMode = mode;

        switch (mode) {
            case VIEW:
                indicatorView.setVisibility(View.VISIBLE);
                editModeView.setVisibility(View.GONE);
                incDecLayout.setVisibility(View.GONE);
                nameView.setVisibility(View.VISIBLE);
                valueView.setGravity(Gravity.RIGHT | Gravity.CENTER);
                break;
            case EDIT:
            case ADD:
                indicatorView.setVisibility(View.GONE);
                editModeView.setVisibility(View.VISIBLE);
                incDecLayout.setVisibility(View.VISIBLE);
                nameView.setVisibility(View.VISIBLE);
                valueView.setGravity(Gravity.RIGHT | Gravity.CENTER);

                if (!isEditable()) {
                    editModeView.setVisibility(View.INVISIBLE);
                }

                showEvaluatorRow(false);
                break;
            case STATISTIC:
                indicatorView.setVisibility(View.GONE);
                incDecLayout.setVisibility(View.GONE);
                editModeView.setVisibility(View.GONE);
                nameView.setVisibility(View.GONE);
                valueView.setGravity(Gravity.CENTER);
                break;
        }
    }

    protected MeasurementViewMode getMeasurementMode() {
        return measurementMode;
    }

    protected void setValueView(String text, boolean callListener) {
        if (updateViews) {
            valueView.setText(text);
        }
        if (callListener && updateListener != null) {
            updateListener.onMeasurementViewUpdate(this);
        }
    }

    protected void setNameView(CharSequence text) {
        if (updateViews) {
            nameView.setText(text);
        }
    }

    protected void showEvaluatorRow(boolean show) {
        if (show) {
            evaluatorRow.setVisibility(View.VISIBLE);
        }
        else {
            evaluatorRow.setVisibility(View.GONE);
        }
    }

    public void setExpand(boolean state) {
        showEvaluatorRow(false);
    }

    protected void setVisible(boolean isVisible) {
        if (isVisible) {
            measurementRow.setVisibility(View.VISIBLE);
        } else {
            measurementRow.setVisibility(View.GONE);
        }
    }

    public boolean isVisible() {
        if (measurementRow.getVisibility() == View.GONE) {
            return false;
        }

        return true;
    }

    protected void setEvaluationView(EvaluationResult evalResult) {
        if (!updateViews) {
            return;
        }

        if (evalResult == null) {
            evaluatorView.setLimits(-1.0f, -1.0f);
            indicatorView.setBackgroundColor(Color.GRAY);
            return;
        }

        evaluatorView.setLimits(evalResult.lowLimit, evalResult.highLimit);
        evaluatorView.setValue(evalResult.value);

        switch (evalResult.eval_state) {
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

    protected abstract boolean validateAndSetInput(EditText view);
    protected abstract int getInputType();
    protected abstract String getHintText();

    protected AlertDialog getInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(nameView.getText());
        builder.setIcon(iconView.getDrawable());

        final EditText input = new EditText(getContext());

        input.setInputType(getInputType());
        input.setHint(getHintText());
        input.setText(getValueAsString());
        input.setSelectAllOnFocus(true);
        builder.setView(input);

        builder.setPositiveButton(getResources().getString(R.string.label_ok), null);
        builder.setNegativeButton(getResources().getString(R.string.label_cancel), null);

        final AlertDialog floatDialog = builder.create();

        floatDialog.setOnShowListener(new DialogInterface.OnShowListener() {

            @Override
            public void onShow(DialogInterface dialog) {

                Button positiveButton = floatDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        if (validateAndSetInput(input)) {
                            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                            floatDialog.dismiss();
                        }
                    }
                });

                Button negativeButton = floatDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                        floatDialog.dismiss();
                    }
                });
            }
        });

        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);

        return floatDialog;
    }

    private class onClickListenerEvaluation implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (getMeasurementMode() == STATISTIC) {
                return;
            }

            if (getMeasurementMode() == EDIT || getMeasurementMode() == ADD) {
                if (isEditable()) {
                    getInputDialog().show();
                }
                return;
            }

            setExpand(evaluatorRow.getVisibility() != View.VISIBLE);
        }
    }
}

