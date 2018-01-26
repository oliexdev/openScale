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
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.bodymetric.EstimatedFatMetric;
import com.health.openscale.core.bodymetric.EstimatedLBWMetric;
import com.health.openscale.core.bodymetric.EstimatedWaterMetric;

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
}
