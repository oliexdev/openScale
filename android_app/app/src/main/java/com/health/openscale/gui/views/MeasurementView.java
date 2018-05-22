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
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.support.v4.content.ContextCompat;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
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

import java.util.ArrayList;
import java.util.List;

import lecho.lib.hellocharts.util.ChartUtils;

import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.ADD;
import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.EDIT;
import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.STATISTIC;
import static com.health.openscale.gui.views.MeasurementView.MeasurementViewMode.VIEW;

public abstract class MeasurementView extends TableLayout {
    public enum MeasurementViewMode {VIEW, EDIT, ADD, STATISTIC}

    public static final String PREF_MEASUREMENT_ORDER = "measurementOrder";

    private MeasurementViewSettings settings;

    private TableRow measurementRow;
    private ImageView iconView;
    private int iconId;
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

    public MeasurementView(Context context, int textId, int iconId) {
        super(context);
        initView(context);

        nameView.setText(textId);
        this.iconId = iconId;
        iconView.setImageResource(iconId);
    }

    public enum DateTimeOrder { FIRST, LAST, NONE }

    public static List<MeasurementView> getMeasurementList(
            Context context, DateTimeOrder dateTimeOrder) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        final List<MeasurementView> sorted = new ArrayList<>();
        if (dateTimeOrder == DateTimeOrder.FIRST) {
            sorted.add(new DateMeasurementView(context));
            sorted.add(new TimeMeasurementView(context));
        }

        {
            final List<MeasurementView> unsorted = new ArrayList<>();

            unsorted.add(new WeightMeasurementView(context));
            unsorted.add(new BMIMeasurementView(context));
            unsorted.add(new WaterMeasurementView(context));
            unsorted.add(new MuscleMeasurementView(context));
            unsorted.add(new LBMMeasurementView(context));
            unsorted.add(new FatMeasurementView(context));
            unsorted.add(new BoneMeasurementView(context));
            unsorted.add(new VisceralFatMeasurementView(context));
            unsorted.add(new WaistMeasurementView(context));
            unsorted.add(new WHtRMeasurementView(context));
            unsorted.add(new HipMeasurementView(context));
            unsorted.add(new WHRMeasurementView(context));
            unsorted.add(new ChestMeasurementView(context));
            unsorted.add(new ThighMeasurementView(context));
            unsorted.add(new BicepsMeasurementView(context));
            unsorted.add(new NeckMeasurementView(context));
            unsorted.add(new FatCaliperMeasurementView(context));
            unsorted.add(new Caliper1MeasurementView(context));
            unsorted.add(new Caliper2MeasurementView(context));
            unsorted.add(new Caliper3MeasurementView(context));
            unsorted.add(new BMRMeasurementView(context));
            unsorted.add(new CommentMeasurementView(context));

            // Get sort order
            final String[] sortOrder = TextUtils.split(
                    prefs.getString(PREF_MEASUREMENT_ORDER, ""), ",");

            // Move views from unsorted to sorted in the correct order
            for (String key : sortOrder) {
                for (MeasurementView measurement : unsorted) {
                    if (key.equals(measurement.getKey())) {
                        sorted.add(measurement);
                        unsorted.remove(measurement);
                        break;
                    }
                }
            }

            // Any new views end up at the end
            sorted.addAll(unsorted);
        }

        if (dateTimeOrder == DateTimeOrder.LAST) {
            sorted.add(new DateMeasurementView(context));
            sorted.add(new TimeMeasurementView(context));
        }

        for (MeasurementView measurement : sorted) {
            measurement.setVisible(measurement.getSettings().isEnabled());
        }

        return sorted;
    }

    public static void saveMeasurementViewsOrder(Context context, List<MeasurementView> measurementViews) {
        ArrayList<String> order = new ArrayList<>();
        for (MeasurementView measurement : measurementViews) {
            order.add(measurement.getKey());
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(PREF_MEASUREMENT_ORDER, TextUtils.join(",", order))
                .apply();
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
        iconView.setColorFilter(getForegroundColor());

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
        editModeView.setColorFilter(getForegroundColor());

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

        setOnClickListener(new onClickListenerEvaluation());
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

    public abstract String getKey();

    public MeasurementViewSettings getSettings() {
        if (settings ==  null) {
            settings = new MeasurementViewSettings(
                    PreferenceManager.getDefaultSharedPreferences(getContext()), getKey());
        }
        return settings;
    }

    public abstract void loadFrom(ScaleMeasurement measurement, ScaleMeasurement previousMeasurement);
    public abstract void saveTo(ScaleMeasurement measurement);
    public abstract void clearIn(ScaleMeasurement measurement);

    public abstract void restoreState(Bundle state);
    public abstract void saveState(Bundle state);

    public CharSequence getName() { return nameView.getText(); }
    public abstract String getValueAsString(boolean withUnit);
    public void appendDiffValue(SpannableStringBuilder builder, boolean newLine) { }
    public Drawable getIcon() { return iconView.getDrawable(); }
    public int getIconResource() { return iconId; }

    protected boolean isEditable() {
        return true;
    }

    public void setEditMode(MeasurementViewMode mode) {
        measurementMode = mode;

        nameView.setGravity(Gravity.LEFT | (mode == ADD ? Gravity.CENTER : Gravity.TOP));
        valueView.setGravity(Gravity.CENTER | (mode == STATISTIC ? 0 : Gravity.RIGHT));

        switch (mode) {
            case VIEW:
                indicatorView.setVisibility(View.VISIBLE);
                editModeView.setVisibility(View.GONE);
                incDecLayout.setVisibility(View.GONE);
                nameView.setVisibility(View.VISIBLE);
                break;
            case EDIT:
            case ADD:
                indicatorView.setVisibility(View.GONE);
                editModeView.setVisibility(View.VISIBLE);
                incDecLayout.setVisibility(View.VISIBLE);
                nameView.setVisibility(View.VISIBLE);

                if (!isEditable()) {
                    editModeView.setVisibility(View.INVISIBLE);
                }
                break;
            case STATISTIC:
                indicatorView.setVisibility(View.GONE);
                incDecLayout.setVisibility(View.GONE);
                editModeView.setVisibility(View.GONE);
                nameView.setVisibility(View.GONE);
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

    public int getForegroundColor() {
        return valueView.getCurrentTextColor();
    }

    public int getIndicatorColor() {
        ColorDrawable background = (ColorDrawable)indicatorView.getBackground();
        return background.getColor();
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

    public void setVisible(boolean isVisible) {
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
        OpenScale openScale = OpenScale.getInstance();

        return openScale.getSelectedScaleUser();
    }

    public String getPreferenceSummary() { return ""; }
    public boolean hasExtraPreferences() { return false; }
    public void prepareExtraPreferencesScreen(PreferenceScreen screen) { }

    protected abstract View getInputView();
    protected abstract boolean validateAndSetInput(View view);

    private MeasurementView getNextView() {
        ViewGroup parent = (ViewGroup) getParent();
        for (int i = parent.indexOfChild(this) + 1; i < parent.getChildCount(); ++i) {
            MeasurementView next = (MeasurementView) parent.getChildAt(i);
            if (next.isVisible() && next.isEditable()) {
                return next;
            }
        }
        return null;
    }

    private void prepareInputDialog(final AlertDialog dialog) {
        dialog.setTitle(getName());
        dialog.setIcon(getIcon());

        final View input = getInputView();

        FrameLayout fl = dialog.findViewById(android.R.id.custom);
        fl.removeAllViews();
        fl.addView(input, new LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        View.OnClickListener clickListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (view == dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                    && !validateAndSetInput(input)) {
                    return;
                }
                dialog.dismiss();
            }
        };

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(clickListener);
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(clickListener);

        final MeasurementView next = getNextView();
        if (next != null) {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (validateAndSetInput(input)) {
                        next.prepareInputDialog(dialog);
                    }
                }
            });
        }
        else {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(GONE);
        }
    }

    private void showInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());

        builder.setTitle(getName());
        builder.setIcon(getIcon());

        // Dummy view to have the "custom" frame layout being created and show
        // the soft input (if needed).
        builder.setView(new EditText(getContext()));

        builder.setPositiveButton(R.string.label_ok, null);
        builder.setNegativeButton(R.string.label_cancel, null);
        builder.setNeutralButton(R.string.label_next, null);

        final AlertDialog dialog = builder.create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                prepareInputDialog(dialog);
            }
        });

        dialog.show();
    }

    private class onClickListenerEvaluation implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (getMeasurementMode() == STATISTIC) {
                return;
            }

            if (getMeasurementMode() == EDIT || getMeasurementMode() == ADD) {
                if (isEditable()) {
                    showInputDialog();
                }
                return;
            }

            setExpand(evaluatorRow.getVisibility() != View.VISIBLE);
        }
    }
}
