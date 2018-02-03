/* Copyright (C) 2018 Erik Johansson <erik@ejohansson.se>
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

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TableRow;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

import java.util.Date;
import java.util.Locale;

public abstract class FloatMeasurementView extends MeasurementView {
    private static char SYMBOL_UP = '\u279a';
    private static char SYMBOL_NEUTRAL = '\u2799';
    private static char SYMBOL_DOWN = '\u2798';

    private static float NO_VALUE = -1.0f;
    private static float AUTO_VALUE = -2.0f;

    private Date dateTime;
    private float value = NO_VALUE;
    private float previousValue = NO_VALUE;
    private EvaluationResult evaluationResult;

    private String nameText;

    private Button incButton;
    private Button decButton;

    public FloatMeasurementView(Context context, String text, Drawable icon) {
        super(context, text, icon);
        initView(context);

        nameText = text;
    }

    private void initView(Context context) {
        incButton = new Button(context);
        decButton = new Button(context);

        LinearLayout incDecLayout = getIncDecLayout();
        incDecLayout.addView(incButton);
        incDecLayout.addView(decButton);

        incButton.setText("+");
        incButton.setBackgroundColor(Color.TRANSPARENT);
        incButton.setPadding(0,0,0,0);
        incButton.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.50f));
        incButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                incValue();
            }
        });
        incButton.setOnTouchListener(new RepeatListener(400, 100, new OnClickListener() {
            @Override
            public void onClick(View view) {
                incValue();
            }
        }));
        incButton.setVisibility(View.GONE);

        decButton.setText("-");
        decButton.setBackgroundColor(Color.TRANSPARENT);
        decButton.setPadding(0,0,0,0);
        decButton.setLayoutParams(new TableRow.LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.50f));
        decButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                decValue();
            }
        });

        decButton.setOnTouchListener(new RepeatListener(400, 100, new OnClickListener() {
            @Override
            public void onClick(View view) {
                decValue();
            }
        }));
        decButton.setVisibility(View.GONE);
    }

    private float clampValue(float value) {
        return Math.max(0.0f, Math.min(getMaxValue(), value));
    }

    private void setValueInner(float newValue, String suffix, boolean callListener) {
        value = newValue;
        evaluationResult = null;

        if (!getUpdateViews()) {
            return;
        }

        if (value == AUTO_VALUE) {
            setValueView(getContext().getString(R.string.label_automatic), false);
        }
        else {
            setValueView(formatValue(value) + suffix, callListener);

            if (getMeasurementMode() != MeasurementViewMode.ADD) {
                EvaluationSheet evalSheet = new EvaluationSheet(getScaleUser(), dateTime);
                evaluationResult = evaluateSheet(evalSheet, value);
            }
        }
        setEvaluationView(evaluationResult);
    }

    private void setPreviousValueInner(float newPreviousValue, String suffix) {
        previousValue = newPreviousValue;

        if (!getUpdateViews()) {
            return;
        }

        if (previousValue >= 0.0f) {
            final float diff = value - previousValue;

            char symbol;

            if (diff > 0.0) {
                symbol = SYMBOL_UP;
            } else if (diff < 0.0) {
                symbol = SYMBOL_DOWN;
            } else {
                symbol = SYMBOL_NEUTRAL;
            }

            SpannableStringBuilder text = new SpannableStringBuilder(nameText);
            text.append("\n");

            int start = text.length();
            text.append(symbol);
            text.setSpan(new ForegroundColorSpan(Color.GRAY), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_INCLUSIVE);

            start = text.length();
            text.append(' ');
            text.append(formatValue(diff));
            text.append(suffix);
            text.setSpan(new RelativeSizeSpan(0.8f), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            setNameView(text);
        }
        else {
            setNameView(nameText);
        }
    }

    private void setValue(float newValue, float newPreviousValue, boolean callListener) {
        final String unit = getUnit();
        final String suffix = unit.isEmpty() ? "" : " " + unit;

        final boolean valueChanged = newValue != value;
        final boolean previousValueChanged = newPreviousValue != previousValue;

        if (valueChanged) {
            setValueInner(newValue, suffix, callListener);
        }

        if (valueChanged || previousValueChanged) {
            setPreviousValueInner(newPreviousValue, suffix);
        }
    }

    private void incValue() {
        setValue(clampValue(value + 0.1f), previousValue, true);
    }
    private void decValue() {
        setValue(clampValue(value - 0.1f), previousValue, true);
    }

    protected String formatValue(float value) {
        return String.format(Locale.getDefault(), "%.2f", value);
    }

    protected abstract float getMeasurementValue(ScaleMeasurement measurement);
    protected abstract void setMeasurementValue(float value, ScaleMeasurement measurement);

    protected abstract String getUnit();
    protected abstract float getMaxValue();

    protected boolean isEstimationEnabled() {
        return false;
    }

    protected abstract EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value);

    private boolean useAutoValue() {
        return isEstimationEnabled() && getMeasurementMode() == MeasurementViewMode.ADD;
    }

    @Override
    public void loadFrom(ScaleMeasurement measurement, ScaleMeasurement previousMeasurement) {
        dateTime = measurement.getDateTime();

        float newValue = AUTO_VALUE;
        float newPreviousValue = NO_VALUE;

        if (!useAutoValue()) {
            newValue = clampValue(getMeasurementValue(measurement));
            if (previousMeasurement != null) {
                newPreviousValue = clampValue(getMeasurementValue(previousMeasurement));
            }
        }

        setValue(newValue, newPreviousValue, false);
    }

    @Override
    public void saveTo(ScaleMeasurement measurement) {
        if (!useAutoValue()) {
            setMeasurementValue(value, measurement);
        }
    }

    @Override
    public void restoreState(Bundle state) {
        setValue(state.getFloat(nameText), previousValue, true);
    }

    @Override
    public void saveState(Bundle state) {
        state.putFloat(nameText, value);
    }

    @Override
    public String getValueAsString() {
        if (useAutoValue()) {
            return getContext().getString(R.string.label_automatic);
        }
        return formatValue(value);
    }

    @Override
    public void appendDiffValue(SpannableStringBuilder text) {
        if (previousValue < 0.0f) {
            return;
        }

        char symbol;
        int color;

        final float diff = value - previousValue;
        if (diff > 0.0f) {
            symbol = SYMBOL_UP;
            color = Color.GREEN;
        } else if (diff < 0.0f) {
            symbol = SYMBOL_DOWN;
            color = Color.RED;
        } else {
            symbol = SYMBOL_NEUTRAL;
            color = Color.GRAY;
        }

        int start = text.length();
        text.append(symbol);
        text.setSpan(new ForegroundColorSpan(color), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        text.append(' ');

        start = text.length();
        text.append(formatValue(diff));
        text.setSpan(new ForegroundColorSpan(Color.GRAY), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.8f), start, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    @Override
    protected boolean isEditable() {
        if (useAutoValue()) {
            return false;
        }
        return true;
    }

    @Override
    public void setEditMode(MeasurementViewMode mode) {
        super.setEditMode(mode);

        if (mode == MeasurementViewMode.VIEW || !isEditable()) {
            incButton.setVisibility(View.GONE);
            decButton.setVisibility(View.GONE);
        }
        else {
            incButton.setVisibility(View.VISIBLE);
            decButton.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void setExpand(boolean state) {
        final boolean show = state && isVisible() && evaluationResult != null;
        showEvaluatorRow(show);
    }

    @Override
    protected boolean validateAndSetInput(EditText view) {
        final String text = view.getText().toString();

        if (text.isEmpty()) {
            view.setError(getResources().getString(R.string.error_value_required));
            return false;
        }

        float newValue;
        try {
            newValue = Float.valueOf(text.replace(',', '.'));
        }
        catch (NumberFormatException ex) {
            newValue = -1;
        }

        if (newValue < 0 || newValue > getMaxValue()) {
            view.setError(getResources().getString(R.string.error_value_range));
            return false;
        }

        setValue(newValue, previousValue, true);
        return true;
    }

    @Override
    protected int getInputType() {
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    }

    @Override
    protected String getHintText() {
        return getResources().getString(R.string.info_enter_value_unit) + " " + getUnit();
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
            if (clickListener == null) {
                throw new IllegalArgumentException("null runnable");
            }
            if (initialInterval < 0 || normalInterval < 0) {
                throw new IllegalArgumentException("negative interval");
            }

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
