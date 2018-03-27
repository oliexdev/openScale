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
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TableRow;
import android.widget.TextView;

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
    private static float INC_DEC_DELTA = 0.1f;

    private Date dateTime;
    private float value = NO_VALUE;
    private float previousValue = NO_VALUE;
    private float userConvertedWeight;
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
                float evalValue = value;
                if (shouldConvertPercentageToAbsoluteWeight()) {
                    evalValue = makeRelativeWeight(value);
                }
                evaluationResult = evaluateSheet(evalSheet, evalValue);
                if (shouldConvertPercentageToAbsoluteWeight()) {
                    evaluationResult.value = value;
                    evaluationResult.lowLimit = makeAbsoluteWeight(evaluationResult.lowLimit);
                    evaluationResult.highLimit = makeAbsoluteWeight(evaluationResult.highLimit);
                }
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
        setValue(clampValue(value + INC_DEC_DELTA), previousValue, true);
    }
    private void decValue() {
        setValue(clampValue(value - INC_DEC_DELTA), previousValue, true);
    }

    protected String formatValue(float value) {
        return String.format(Locale.getDefault(), "%.2f", value);
    }

    protected abstract float getMeasurementValue(ScaleMeasurement measurement);
    protected abstract void setMeasurementValue(float value, ScaleMeasurement measurement);

    public abstract String getUnit();
    protected abstract float getMaxValue();

    public abstract int getColor();

    protected boolean isEstimationSupported() { return false; }
    protected void prepareEstimationFormulaPreference(ListPreference preference) {}

    protected abstract EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value);

    private boolean useAutoValue() {
        return isEstimationSupported()
                && getSettings().isEstimationEnabled()
                && getMeasurementMode() == MeasurementViewMode.ADD;
    }

    protected boolean canConvertPercentageToAbsoluteWeight() { return false; }
    protected boolean shouldConvertPercentageToAbsoluteWeight() {
        return canConvertPercentageToAbsoluteWeight()
                && !getSettings().isPercentageEnabled();
    }

    private float makeAbsoluteWeight(float percentage) {
        return userConvertedWeight / 100.0f * percentage;
    }

    private float makeRelativeWeight(float absolute) {
        return 100.0f / userConvertedWeight * absolute;
    }

    protected float maybeConvertPercentageToAbsolute(float value) {
        if (shouldConvertPercentageToAbsoluteWeight()) {
            return makeAbsoluteWeight(value);
        }

        return value;
    }

    private void updateUserConvertedWeight(ScaleMeasurement measurement) {
        if (shouldConvertPercentageToAbsoluteWeight()) {
            // Make sure weight is never 0 to avoid division by 0
            userConvertedWeight = Math.max(1.0f,
                    measurement.getConvertedWeight(getScaleUser().getScaleUnit()));
        }
        else {
            // Only valid when shouldConvertPercentageToAbsoluteWeight() returns true
            userConvertedWeight = -1.0f;
        }
    }

    @Override
    public void loadFrom(ScaleMeasurement measurement, ScaleMeasurement previousMeasurement) {
        dateTime = measurement.getDateTime();

        float newValue = AUTO_VALUE;
        float newPreviousValue = NO_VALUE;

        if (!useAutoValue()) {
            updateUserConvertedWeight(measurement);

            newValue = getMeasurementValue(measurement);
            newValue = maybeConvertPercentageToAbsolute(newValue);
            newValue = clampValue(newValue);

            if (previousMeasurement != null) {
                float saveUserConvertedWeight = userConvertedWeight;
                updateUserConvertedWeight(previousMeasurement);

                newPreviousValue = getMeasurementValue(previousMeasurement);
                newPreviousValue = maybeConvertPercentageToAbsolute(newPreviousValue);
                newPreviousValue = clampValue(newPreviousValue);

                userConvertedWeight = saveUserConvertedWeight;
            }
        }

        setValue(newValue, newPreviousValue, false);
    }

    @Override
    public void saveTo(ScaleMeasurement measurement) {
        if (!useAutoValue()) {
            if (shouldConvertPercentageToAbsoluteWeight()) {
                // Make sure to use the current weight to get a correct percentage
                updateUserConvertedWeight(measurement);
                setMeasurementValue(makeRelativeWeight(value), measurement);
            }
            else {
                setMeasurementValue(value, measurement);
            }
        }
    }

    @Override
    public void clearIn(ScaleMeasurement measurement) {
        setMeasurementValue(0.0f, measurement);
    }

    @Override
    public void restoreState(Bundle state) {
        setValue(state.getFloat(getKey()), previousValue, true);
    }

    @Override
    public void saveState(Bundle state) {
        state.putFloat(getKey(), value);
    }

    @Override
    public String getValueAsString() {
        if (useAutoValue()) {
            return getContext().getString(R.string.label_automatic);
        }
        return formatValue(value);
    }

    public float getValue() {
        return value;
    }

    @Override
    public CharSequence getName() {
        return nameText;
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

        text.append('\n');
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
    public String getPreferenceSummary() {
        MeasurementViewSettings settings = getSettings();
        Resources res = getResources();

        final String separator = ", ";
        String summary = "";
        if (settings.isInOverviewGraph()) {
            summary += res.getString(R.string.label_overview_graph) + separator;
        }
        if (canConvertPercentageToAbsoluteWeight() && settings.isPercentageEnabled()) {
            summary += res.getString(R.string.label_percent) + separator;
        }
        if (isEstimationSupported() && settings.isEstimationEnabled()) {
            summary += res.getString(R.string.label_estimated) + separator;
        }

        if (!summary.isEmpty()) {
            return summary.substring(0, summary.length() - separator.length());
        }

        return "";
    }

    @Override
    public boolean hasExtraPreferences() { return true; }

    @Override
    public void prepareExtraPreferencesScreen(PreferenceScreen screen) {
        MeasurementViewSettings settings = getSettings();

        CheckBoxPreference overview = new CheckBoxPreference(screen.getContext());
        overview.setKey(settings.getInOverviewGraphKey());
        overview.setTitle(R.string.label_include_in_overview_graph);
        overview.setPersistent(true);
        overview.setDefaultValue(settings.isInOverviewGraph());
        screen.addPreference(overview);

        if (canConvertPercentageToAbsoluteWeight()) {
            SwitchPreference percentage = new SwitchPreference(screen.getContext());
            percentage.setKey(settings.getPercentageEnabledKey());
            percentage.setTitle(R.string.label_measurement_in_percent);
            percentage.setPersistent(true);
            percentage.setDefaultValue(settings.isPercentageEnabled());
            screen.addPreference(percentage);
        }

        if (isEstimationSupported()) {
            final CheckBoxPreference estimate = new CheckBoxPreference(screen.getContext());
            estimate.setKey(settings.getEstimationEnabledKey());
            estimate.setTitle(R.string.label_estimate_measurement);
            estimate.setSummary(R.string.label_estimate_measurement_summary);
            estimate.setPersistent(true);
            estimate.setDefaultValue(settings.isEstimationEnabled());
            screen.addPreference(estimate);

            final ListPreference formula = new ListPreference(screen.getContext());
            formula.setKey(settings.getEstimationFormulaKey());
            formula.setTitle(R.string.label_estimation_formula);
            formula.setPersistent(true);
            formula.setDefaultValue(settings.getEstimationFormula());
            prepareEstimationFormulaPreference(formula);
            formula.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    ListPreference list = (ListPreference) preference;
                    int idx = list.findIndexOfValue((String) newValue);
                    if (idx == -1) {
                        return false;
                    }
                    preference.setSummary(list.getEntries()[idx]);
                    return true;
                }
            });

            final ListAdapter adapter = screen.getRootAdapter();
            adapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    adapter.unregisterDataSetObserver(this);

                    formula.setDependency(estimate.getKey());
                    formula.setSummary(formula.getEntry());
                }
            });
            screen.addPreference(formula);
        }
    }

    private float validateAndGetInput(View view) {
        EditText editText = view.findViewById(R.id.float_input);
        String text = editText.getText().toString();

        float newValue = -1;
        if (text.isEmpty()) {
            editText.setError(getResources().getString(R.string.error_value_required));
            return newValue;
        }

        try {
            newValue = Float.valueOf(text.replace(',', '.'));
        }
        catch (NumberFormatException ex) {
            newValue = -1;
        }

        if (newValue < 0 || newValue > getMaxValue()) {
            editText.setError(getResources().getString(R.string.error_value_range));
            newValue = -1;
        }

        return newValue;
    }

    @Override
    protected View getInputView() {
        final LinearLayout view = (LinearLayout) LayoutInflater.from(getContext())
                .inflate(R.layout.float_input_view, null);

        final EditText input = view.findViewById(R.id.float_input);
        input.setText(formatValue(value));

        final TextView unit = view.findViewById(R.id.float_input_unit);
        unit.setText(getUnit());

        View.OnClickListener onClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View button) {
                float newValue = validateAndGetInput(view);
                if (newValue < 0) {
                    return;
                }

                if (button.getId() == R.id.btn_inc) {
                    newValue += INC_DEC_DELTA;
                }
                else {
                    newValue -= INC_DEC_DELTA;
                }

                input.setText(formatValue(clampValue(newValue)));
                input.selectAll();
            }
        };

        RepeatListener repeatListener =
                new RepeatListener(400, 100, onClickListener);

        final Button inc = view.findViewById(R.id.btn_inc);
        inc.setText("\u25b2 +" + formatValue(INC_DEC_DELTA));
        inc.setOnClickListener(onClickListener);
        inc.setOnTouchListener(repeatListener);

        final Button dec = view.findViewById(R.id.btn_dec);
        dec.setText("\u25bc -" + formatValue(INC_DEC_DELTA));
        dec.setOnClickListener(onClickListener);
        dec.setOnTouchListener(repeatListener);

        return view;
    }

    @Override
    protected boolean validateAndSetInput(View view) {
        float newValue = validateAndGetInput(view);
        if (newValue >= 0) {
            setValue(newValue, previousValue, true);
            return true;
        }

        return false;
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
