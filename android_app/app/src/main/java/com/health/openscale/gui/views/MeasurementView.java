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
import android.os.Handler;
import android.support.v4.content.ContextCompat;
import android.text.Html;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
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
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

import java.util.Date;

import lecho.lib.hellocharts.util.ChartUtils;

import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.ADD;
import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.EDIT;
import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.VIEW;

public abstract class MeasurementView extends TableLayout {
    public enum MeasurementViewMode {VIEW, EDIT, ADD};
    private static String SYMBOL_UP = "&#10138;";
    private static String SYMBOL_NEUTRAL = "&#10137;";
    private static String SYMBOL_DOWN = "&#10136;";

    private TableRow measurementRow;
    private ImageView iconView;
    private TextView nameView;
    private TextView valueView;
    private LinearLayout incdecLayout;
    private Button incView;
    private Button decView;
    private ImageView editModeView;
    private ImageView indicatorView;

    private TableRow evaluatorRow;
    private LinearGaugeView evaluatorView;

    private String nameText;

    private Date dateTime;
    private String value;
    private float previousValue;
    private String diffValue;

    private MeasurementViewUpdateListener updateListener = null;
    private MeasurementViewMode measurementMode;

    public MeasurementView(Context context, String text, Drawable icon) {
        super(context);
        initView(context);

        measurementMode = VIEW;
        nameText = text;
        dateTime = new Date();
        value = new String();
        diffValue = new String();
        nameView.setText(text);
        iconView.setImageDrawable(icon);
    }

    private void initView(Context context) {
        measurementRow = new TableRow(context);

        iconView = new ImageView(context);
        nameView = new TextView(context);
        valueView = new TextView(context);
        incView = new Button(context);
        decView = new Button(context);
        editModeView = new ImageView(context);
        indicatorView = new ImageView(context);

        evaluatorRow = new TableRow(context);
        evaluatorView = new LinearGaugeView(context);

        incdecLayout = new LinearLayout(context);

        measurementRow.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT, 1.0f));
        measurementRow.setGravity(Gravity.CENTER);
        measurementRow.addView(iconView);
        measurementRow.addView(nameView);
        measurementRow.addView(valueView);
        measurementRow.addView(incdecLayout);
        measurementRow.addView(editModeView);
        measurementRow.addView(indicatorView);

        addView(measurementRow);
        addView(evaluatorRow);

        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconView.setPadding(20,0,20,0);

        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        nameView.setTextColor(Color.BLACK);
        nameView.setLines(2);
        nameView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.55f));

        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setTextColor(Color.BLACK);
        valueView.setGravity(Gravity.RIGHT | Gravity.CENTER);
        valueView.setPadding(0,0,20,0);
        valueView.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.MATCH_PARENT, 0.29f));

        incdecLayout.setOrientation(VERTICAL);
        incdecLayout.addView(incView);
        incdecLayout.addView(decView);
        incdecLayout.setVisibility(View.GONE);
        incdecLayout.setPadding(0,0,0,0);
        incdecLayout.setLayoutParams(new TableRow.LayoutParams(0, LayoutParams.MATCH_PARENT, 0.05f));

        incView.setText("+");
        incView.setBackgroundColor(Color.TRANSPARENT);
        incView.setPadding(0,0,0,0);
        incView.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.50f));
        incView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                incValue();
            }
        });
        incView.setOnTouchListener(new RepeatListener(400, 100, new OnClickListener() {
            @Override
            public void onClick(View view) {
                incValue();
            }
        }));
        incView.setVisibility(View.GONE);

        decView.setText("-");
        decView.setBackgroundColor(Color.TRANSPARENT);
        decView.setPadding(0,0,0,0);
        decView.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.50f));
        decView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                decValue();
            }
        });

        decView.setOnTouchListener(new RepeatListener(400, 100, new OnClickListener() {
            @Override
            public void onClick(View view) {
                decValue();
            }
        }));
        decView.setVisibility(View.GONE);

        editModeView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_editable));
        editModeView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
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

        onClickListenerEvaluation onClickListener = new onClickListenerEvaluation();
        measurementRow.setOnClickListener(onClickListener);
        evaluatorRow.setOnClickListener(onClickListener);
    }

    public void setOnUpdateListener(MeasurementViewUpdateListener listener) {
        updateListener = listener;
    }

    public abstract void updateValue(ScaleData updateData);
    public abstract void updateDiff(ScaleData updateData, ScaleData lastData);
    public abstract void updatePreferences(SharedPreferences preferences);
    public abstract String getUnit();
    public abstract EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value);
    public abstract float getMaxValue();

    public float getValue() {
        if (value.length() == 0) {
            return -1;
        }
        try {
            return Float.valueOf(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void incValue() {
        float incValue = Math.min(getMaxValue(), getValue() + 0.1f);
        setValueOnView(dateTime, incValue);
    }

    public void decValue() {
        float decValue = Math.max(0.0f, getValue() - 0.1f);
        setValueOnView(dateTime, decValue);
    }

    public String getValueAsString() {
        return value;
    }

    public Drawable getIcon() { return iconView.getDrawable(); }

    public String getDiffValue() { return diffValue; }

    public Date getDateTime() { return dateTime; }

    protected boolean isEditable() {
        return true;
    }

    public void setEditMode(MeasurementViewMode mode) {
        measurementMode = mode;

        switch (mode) {
            case VIEW:
                indicatorView.setVisibility(View.VISIBLE);
                editModeView.setVisibility(View.GONE);
                incdecLayout.setVisibility(View.GONE);
                incView.setVisibility(View.GONE);
                decView.setVisibility(View.GONE);
                break;
            case EDIT:
            case ADD:
                editModeView.setVisibility(View.VISIBLE);
                incView.setVisibility(View.VISIBLE);
                decView.setVisibility(View.VISIBLE);
                incdecLayout.setVisibility(View.VISIBLE);

                if (!isEditable()) {
                    editModeView.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_noteeditable));
                    incView.setVisibility(View.GONE);
                    decView.setVisibility(View.GONE);
                }

                if (getUnit() == null) {
                    incView.setVisibility(View.GONE);
                    decView.setVisibility(View.GONE);
                }

                indicatorView.setVisibility(View.GONE);
                evaluatorRow.setVisibility(View.GONE);
                break;
        }
    }

    protected MeasurementViewMode getMeasurementMode() {
        return measurementMode;
    }

    protected void setValueOnView(Date objTimeDate, Object objValue) {
        dateTime = objTimeDate;
        value = String.valueOf(objValue);

        try {
            Float floatValue = Float.parseFloat(value);
            if (measurementMode == VIEW || measurementMode == EDIT) {
                evaluate(floatValue);
            }
            valueView.setText(String.format("%.2f ", floatValue) + getUnit());
            value = String.valueOf(Math.round(floatValue*100.0f)/100.0f);
            // Only update diff value if setDiffOnView has been called previously
            if (!diffValue.isEmpty()) {
                setDiffOnView(floatValue, previousValue);
            }
        } catch (NumberFormatException e) {
            valueView.setText(value);
        }
        if (updateListener != null) {
            updateListener.onMeasurementViewUpdate(this);
        }
    }

    protected void setDiffOnView(float value, float prevValue) {
        previousValue = prevValue;
        float diff = value - prevValue;

        String symbol;
        String symbol_color;

        if (diff > 0.0) {
            symbol = SYMBOL_UP;
            symbol_color = "<font color='green'>" + SYMBOL_UP + "</font>";
        } else if (diff < 0.0) {
            symbol = SYMBOL_DOWN;
            symbol_color = "<font color='red'>" + SYMBOL_DOWN + "</font>";
        } else {
            symbol = SYMBOL_NEUTRAL;
            symbol_color = "<font color='grey'>" + SYMBOL_NEUTRAL + "</font>";
        }
        diffValue = symbol_color + "<font color='grey'><small>" + String.format("%.2f", diff) + "</small></font>";

        nameView.setText(
                Html.fromHtml(
                        nameText +
                                " <br> <font color='grey'>" +
                                symbol +
                                "<small> " +
                                String.format("%.2f ", diff) + getUnit() +
                                "</small></font>"
                )
        );
    }

    public void setExpand(boolean state) {
        if (state && measurementRow.getVisibility() == View.VISIBLE && evaluateSheet(new EvaluationSheet(getScaleUser(), dateTime), 0.0f) != null) {
            evaluatorRow.setVisibility(View.VISIBLE);
        } else {
            evaluatorRow.setVisibility(View.GONE);
        }
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
        EvaluationSheet evalSheet = new EvaluationSheet(getScaleUser(), dateTime);
        EvaluationResult evalResult = evaluateSheet(evalSheet, value);

        if (evalResult == null) {
            evalResult = new EvaluationResult();
        }

        evaluatorView.setLimits(evalResult.lowLimit, evalResult.highLimit);
        evaluatorView.setValue(value);

        switch (evalResult.eval_state)
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
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
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
        input.setText(value);
        input.setSelectAllOnFocus(true);
        builder.setView(input);

        builder.setPositiveButton(getResources().getString(R.string.label_ok), null);
        builder.setNegativeButton(getResources().getString(R.string.label_cancel), null);

        floatDialog = builder.create();

        floatDialog.setOnShowListener(new DialogInterface.OnShowListener() {

            @Override
            public void onShow(DialogInterface dialog) {

                Button positiveButton = floatDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positiveButton.setOnClickListener(new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        if (validateInput(input)) {
                            setValueOnView(dateTime, input.getText().toString());
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
            if (getMeasurementMode() == EDIT || getMeasurementMode() == ADD) {
                if (isEditable()) {
                    getInputDialog().show();
                }
                return;
            }

            setExpand(evaluatorRow.getVisibility() != View.VISIBLE);
        }
    }

    private class RepeatListener implements OnTouchListener {

        private Handler handler = new Handler();

        private int initialInterval;
        private final int normalInterval;
        private final OnClickListener clickListener;

        private Runnable handlerRunnable = new Runnable() {
            @Override
            public void run() {
                handler.postDelayed(this, normalInterval);
                clickListener.onClick(downView);
            }
        };

        private View downView;

        /**
         * RepeatListener cyclically runs a clickListener, emulating keyboard-like behaviour. First
         * click is fired immediately, next one after the initialInterval, and subsequent ones after the normalInterval.
         *
         * @param initialInterval The interval after first click event
         * @param normalInterval The interval after second and subsequent click events
         * @param clickListener The OnClickListener, that will be called periodically
         */
        public RepeatListener(int initialInterval, int normalInterval,
                              OnClickListener clickListener) {
            if (clickListener == null)
                throw new IllegalArgumentException("null runnable");
            if (initialInterval < 0 || normalInterval < 0)
                throw new IllegalArgumentException("negative interval");

            this.initialInterval = initialInterval;
            this.normalInterval = normalInterval;
            this.clickListener = clickListener;
        }

        public boolean onTouch(View view, MotionEvent motionEvent) {
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    handler.removeCallbacks(handlerRunnable);
                    handler.postDelayed(handlerRunnable, initialInterval);
                    downView = view;
                    downView.setPressed(true);
                    clickListener.onClick(view);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(handlerRunnable);
                    downView.setPressed(false);
                    downView = null;
                    return true;
            }

            return false;
        }

    }
}

