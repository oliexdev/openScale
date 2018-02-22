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
package com.health.openscale.gui.preferences;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bodymetric.EstimatedFatMetric;
import com.health.openscale.core.bodymetric.EstimatedLBWMetric;
import com.health.openscale.core.bodymetric.EstimatedWaterMetric;
import com.health.openscale.gui.views.MeasurementView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MeasurementPreferences extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener  {
    public static final String PREFERENCE_KEY_DELETE_ALL = "deleteAll";
    public static final String PREFERENCE_KEY_FAT = "fatEnable";
    public static final String PREFERENCE_KEY_FAT_PERCENTAGE = "fatPercentageEnable";
    public static final String PREFERENCE_KEY_WATER = "waterEnable";
    public static final String PREFERENCE_KEY_WATER_PERCENTAGE = "waterPercentageEnable";
    public static final String PREFERENCE_KEY_MUSCLE = "muscleEnable";
    public static final String PREFERENCE_KEY_MUSCLE_PERCENTAGE = "musclePercentageEnable";
    public static final String PREFERENCE_KEY_ESTIMATE_WATER = "estimateWaterEnable";
    public static final String PREFERENCE_KEY_ESTIMATE_WATER_FORMULA = "estimateWaterFormula";
    public static final String PREFERENCE_KEY_ESTIMATE_LBW = "estimateLBWEnable";
    public static final String PREFERENCE_KEY_ESTIMATE_LBW_FORMULA = "estimateLBWFormula";
    public static final String PREFERENCE_KEY_ESTIMATE_FAT = "estimateFatEnable";
    public static final String PREFERENCE_KEY_ESTIMATE_FAT_FORMULA = "estimateFatFormula";

    private Preference deleteAll;

    private PreferenceScreen measurementOrderScreen;
    private PreferenceCategory measurementOrderCategory;

    private CheckBoxPreference fatEnable;
    private SwitchPreference fatPercentageEnable;
    private CheckBoxPreference waterEnable;
    private SwitchPreference waterPercentageEnable;
    private CheckBoxPreference muscleEnable;
    private SwitchPreference musclePercentageEnable;

    private CheckBoxPreference estimateWaterEnable;
    private ListPreference estimateWaterFormula;
    private CheckBoxPreference estimateLBWEnable;
    private ListPreference estimateLBWFormula;
    private CheckBoxPreference estimateFatEnable;
    private ListPreference estimateFatFormula;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.measurement_preferences);

        deleteAll = (Preference) findPreference(PREFERENCE_KEY_DELETE_ALL);
        deleteAll.setOnPreferenceClickListener(new onClickListenerDeleteAll());

        final Context context = getActivity().getApplicationContext();
        measurementOrderScreen = (PreferenceScreen) findPreference(MeasurementView.PREF_MEASUREMENT_ORDER);

        measurementOrderCategory = new PreferenceCategory(context);
        measurementOrderCategory.setTitle(R.string.label_press_hold_reorder);
        measurementOrderCategory.setOrderingAsAdded(true);

        Preference resetOrder = new Preference(context);
        resetOrder.setTitle(R.string.label_set_default_order);
        resetOrder.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .remove(MeasurementView.PREF_MEASUREMENT_ORDER).commit();
                measurementOrderCategory.removeAll();
                updateMeasurementOrderScreen(context, measurementOrderCategory);
                return true;
            }
        });
        measurementOrderScreen.addPreference(resetOrder);
        measurementOrderScreen.addPreference(measurementOrderCategory);

        updateMeasurementOrderScreen(context, measurementOrderCategory);

        estimateWaterEnable = (CheckBoxPreference) findPreference(PREFERENCE_KEY_ESTIMATE_WATER);
        estimateWaterFormula = (ListPreference) findPreference(PREFERENCE_KEY_ESTIMATE_WATER_FORMULA);
        estimateLBWEnable = (CheckBoxPreference) findPreference(PREFERENCE_KEY_ESTIMATE_LBW);
        estimateLBWFormula = (ListPreference) findPreference(PREFERENCE_KEY_ESTIMATE_LBW_FORMULA);
        estimateFatEnable = (CheckBoxPreference) findPreference(PREFERENCE_KEY_ESTIMATE_FAT);
        estimateFatFormula = (ListPreference) findPreference(PREFERENCE_KEY_ESTIMATE_FAT_FORMULA);

        fatEnable = (CheckBoxPreference) findPreference(PREFERENCE_KEY_FAT);
        fatPercentageEnable = (SwitchPreference) findPreference(PREFERENCE_KEY_FAT_PERCENTAGE);
        waterEnable = (CheckBoxPreference) findPreference(PREFERENCE_KEY_WATER);
        waterPercentageEnable = (SwitchPreference) findPreference(PREFERENCE_KEY_WATER_PERCENTAGE);
        muscleEnable = (CheckBoxPreference) findPreference(PREFERENCE_KEY_MUSCLE);
        musclePercentageEnable = (SwitchPreference) findPreference(PREFERENCE_KEY_MUSCLE_PERCENTAGE);

        updateWaterListPreferences();
        updateLBWListPreferences();
        updateFatListPreferences();

        initSummary(getPreferenceScreen());
    }

    private void updateMeasurementOrderScreen(Context context, PreferenceCategory category) {
        List<MeasurementView> measurementViews = MeasurementView.getMeasurementList(
                context, MeasurementView.DateTimeOrder.NONE);
        for (MeasurementView measurement : measurementViews) {
            Preference preference = new MeasurementOrderPreference(context, category, measurement);
            preference.setShouldDisableView(true);
            preference.setEnabled(measurement.isVisible());
            category.addPreference(preference);
        }
    }

    public void updateWaterListPreferences() {
        ArrayList<String> listEntries = new ArrayList();
        ArrayList<String> listEntryValues = new ArrayList();

        for (EstimatedWaterMetric.FORMULA formulaWater : EstimatedWaterMetric.FORMULA.values()) {
            EstimatedWaterMetric waterMetric = EstimatedWaterMetric.getEstimatedMetric(formulaWater);

            listEntries.add(waterMetric.getName());
            listEntryValues.add(formulaWater.toString());
        }

        estimateWaterFormula.setEntries(listEntries.toArray(new CharSequence[listEntries.size()]));
        estimateWaterFormula.setEntryValues(listEntryValues.toArray(new CharSequence[listEntryValues.size()]));
    }

    public void updateLBWListPreferences() {
        ArrayList<String> listEntries = new ArrayList();
        ArrayList<String> listEntryValues = new ArrayList();

        for (EstimatedLBWMetric.FORMULA formulaLBW : EstimatedLBWMetric.FORMULA.values()) {
            EstimatedLBWMetric muscleMetric = EstimatedLBWMetric.getEstimatedMetric(formulaLBW);

            listEntries.add(muscleMetric.getName());
            listEntryValues.add(formulaLBW.toString());
        }

        estimateLBWFormula.setEntries(listEntries.toArray(new CharSequence[listEntries.size()]));
        estimateLBWFormula.setEntryValues(listEntryValues.toArray(new CharSequence[listEntryValues.size()]));
    }


    public void updateFatListPreferences() {
        ArrayList<String> listEntries = new ArrayList();
        ArrayList<String> listEntryValues = new ArrayList();

        for (EstimatedFatMetric.FORMULA formulaFat : EstimatedFatMetric.FORMULA.values()) {
            EstimatedFatMetric fatMetric = EstimatedFatMetric.getEstimatedMetric(formulaFat);

            listEntries.add(fatMetric.getName());
            listEntryValues.add(formulaFat.toString());
        }

        estimateFatFormula.setEntries(listEntries.toArray(new CharSequence[listEntries.size()]));
        estimateFatFormula.setEntryValues(listEntryValues.toArray(new CharSequence[listEntryValues.size()]));
    }


    private void initSummary(Preference p) {
        if (p instanceof PreferenceGroup) {
            PreferenceGroup pGrp = (PreferenceGroup) p;
            for (int i = 0; i < pGrp.getPreferenceCount(); i++) {
                initSummary(pGrp.getPreference(i));
            }
        } else {
            updatePrefSummary(p);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        updatePrefSummary(findPreference(key));
        if (!key.equals(MeasurementView.PREF_MEASUREMENT_ORDER)) {
            measurementOrderCategory.removeAll();
            updateMeasurementOrderScreen(getContext(), measurementOrderCategory);
        }
    }

    private void updatePrefSummary(Preference p) {
        if (estimateWaterEnable.isChecked()) {
            estimateWaterFormula.setEnabled(true);
        } else {
            estimateWaterFormula.setEnabled(false);
        }

        if (estimateLBWEnable.isChecked()) {
            estimateLBWFormula.setEnabled(true);
        } else {
            estimateLBWFormula.setEnabled(false);
        }

        if (estimateFatEnable.isChecked()) {
            estimateFatFormula.setEnabled(true);
        } else {
            estimateFatFormula.setEnabled(false);
        }

        if (fatEnable.isChecked()) {
            fatPercentageEnable.setEnabled(true);
        } else {
            fatPercentageEnable.setEnabled(false);
        }

        if (waterEnable.isChecked()) {
            waterPercentageEnable.setEnabled(true);
        } else {
            waterPercentageEnable.setEnabled(false);
        }

        if (muscleEnable.isChecked()) {
            musclePercentageEnable.setEnabled(true);
        } else {
            musclePercentageEnable.setEnabled(false);
        }

        estimateWaterFormula.setSummary(EstimatedWaterMetric.getEstimatedMetric(EstimatedWaterMetric.FORMULA.valueOf(estimateWaterFormula.getValue())).getName());
        estimateLBWFormula.setSummary(EstimatedLBWMetric.getEstimatedMetric(EstimatedLBWMetric.FORMULA.valueOf(estimateLBWFormula.getValue())).getName());
        estimateFatFormula.setSummary(EstimatedFatMetric.getEstimatedMetric(EstimatedFatMetric.FORMULA.valueOf(estimateFatFormula.getValue())).getName());

        if (p instanceof EditTextPreference) {
            EditTextPreference editTextPref = (EditTextPreference) p;
            if (p.getTitle().toString().contains("assword"))
            {
                p.setSummary("******");
            } else {
                p.setSummary(editTextPref.getText());
            }
        }

        if (p instanceof MultiSelectListPreference) {
            MultiSelectListPreference editMultiListPref = (MultiSelectListPreference) p;

            CharSequence[] entries = editMultiListPref.getEntries();
            CharSequence[] entryValues = editMultiListPref.getEntryValues();
            List<String> currentEntries = new ArrayList<>();
            Set<String> currentEntryValues = editMultiListPref.getValues();

            for (int i = 0; i < entries.length; i++)
                if (currentEntryValues.contains(entryValues[i]))
                    currentEntries.add(entries[i].toString());

            p.setSummary(currentEntries.toString());
        }
    }

    private class onClickListenerDeleteAll implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {

            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(getActivity());

            deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete_all));

            deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    OpenScale openScale = OpenScale.getInstance(getActivity().getApplicationContext());
                    int selectedUserId = openScale.getSelectedScaleUserId();

                    openScale.clearScaleData(selectedUserId);

                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.info_data_all_deleted), Toast.LENGTH_SHORT).show();
                }
            });

            deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            deleteAllDialog.show();

            return false;
        }
    }

    private class MeasurementOrderPreference extends Preference {
        PreferenceGroup parentGroup;
        MeasurementView measurement;
        View boundView;

        MeasurementOrderPreference(Context context, PreferenceGroup parent, MeasurementView measurementView) {
            super(context);
            parentGroup = parent;
            measurement = measurementView;
            setIcon(measurement.getIcon());
            setTitle(measurement.getNameText());
        }

        public PreferenceGroup getParent() {
            return parentGroup;
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            boundView = view;

            onTouchClickListener touchClickListener = new onTouchClickListener(this);
            view.setOnTouchListener(touchClickListener);
            view.setOnLongClickListener(touchClickListener);
            view.setOnDragListener(new onDragListener());
        }

        private class onTouchClickListener implements View.OnTouchListener, View.OnLongClickListener {
            MeasurementOrderPreference preference;
            int x = 0;
            int y = 0;

            onTouchClickListener(MeasurementOrderPreference pref) {
                preference = pref;
            }

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    x = Math.round(event.getX());
                    y = Math.round(event.getY());
                }
                return false;
            }

            @Override
            public boolean onLongClick(View view) {
                return view.startDrag(null, new dragShadowBuilder(view), preference, 0);
            }

            private class dragShadowBuilder extends View.DragShadowBuilder {
                public dragShadowBuilder(View view) {
                    super(view);
                }

                @Override
                public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
                    super.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint);
                    outShadowTouchPoint.set(x, y);
                }
            }
        }

        private class onDragListener implements View.OnDragListener {
            Drawable background = null;
            // background may be set to null, thus the extra boolean
            boolean hasBackground = false;

            private MeasurementOrderPreference castLocalState(DragEvent event) {
                return (MeasurementOrderPreference) event.getLocalState();
            }

            private boolean isDraggedView(View view, DragEvent event) {
                return castLocalState(event).boundView == view;
            }

            private void setTemporaryBackgroundColor(View view, int color) {
                if (!hasBackground) {
                    background = view.getBackground();
                    hasBackground = true;
                    view.setBackgroundColor(color);
                }
            }

            private void restoreBackground(View view) {
                if (hasBackground) {
                    view.setBackground(background);
                    background = null;
                    hasBackground = false;
                }
            }

            @Override
            public boolean onDrag(View view, DragEvent event) {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        if (isDraggedView(view, event)) {
                            setTemporaryBackgroundColor(view, Color.GRAY);
                        }
                        break;
                    case DragEvent.ACTION_DRAG_ENTERED:
                        if (!isDraggedView(view, event)) {
                            setTemporaryBackgroundColor(view, Color.LTGRAY);
                        }
                        break;
                    case DragEvent.ACTION_DRAG_EXITED:
                        if (!isDraggedView(view, event)) {
                            restoreBackground(view);
                        }
                        break;
                    case DragEvent.ACTION_DROP:
                        MeasurementOrderPreference draggedPref = castLocalState(event);
                        PreferenceGroup group = draggedPref.getParent();

                        ArrayList<MeasurementOrderPreference> preferences = new ArrayList<>();
                        for (int i = 0; i < group.getPreferenceCount(); ++i) {
                            MeasurementOrderPreference pref = (MeasurementOrderPreference) group.getPreference(i);
                            // Add all preferences except the dragged one
                            if (pref != draggedPref) {
                                preferences.add(pref);
                            }
                            // When we find the view that is the drop target use add(index, ...).
                            // This will add the dragged preference before the drop if dragged upwards,
                            // and after if dragged downwards.
                            if (pref.boundView == view) {
                                preferences.add(i, draggedPref);
                            }
                        }

                        ArrayList<MeasurementView> measurementViews = new ArrayList<>();
                        // Re-add all preferences in the new order
                        group.removeAll();
                        for (MeasurementOrderPreference p : preferences) {
                            p.setOrder(DEFAULT_ORDER);
                            group.addPreference(p);
                            measurementViews.add(p.measurement);
                        }
                        MeasurementView.saveMeasurementViewsOrder(getContext(), measurementViews);
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        restoreBackground(view);
                        break;
                }
                return true;
            }
        }

    }
}
