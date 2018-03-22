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

import android.content.SharedPreferences;

import com.health.openscale.core.bodymetric.EstimatedFatMetric;
import com.health.openscale.core.bodymetric.EstimatedLBWMetric;
import com.health.openscale.core.bodymetric.EstimatedWaterMetric;

public class MeasurementViewSettings {
    private SharedPreferences preferences;
    private String key;

    private static final String PREFERENCE_SUFFIX_ENABLE = "Enable";
    private static final String PREFERENCE_SUFFIX_IN_OVERVIEW_GRAPH = "InOverviewGraph";
    private static final String PREFERENCE_SUFFIX_PERCENTAGE_ENABLE = "PercentageEnable";
    private static final String PREFERENCE_SUFFIX_ESTIMATE_ENABLE = "EstimateEnable";
    private static final String PREFERENCE_SUFFIX_ESTIMATE_FORMULA = "EstimateFormula";

    public MeasurementViewSettings(SharedPreferences prefs, String key) {
        preferences = prefs;
        this.key = key;
    }

    private String getPreferenceKey(String suffix) {
        return key + suffix;
    }

    public String getEnabledKey() {
        return getPreferenceKey(PREFERENCE_SUFFIX_ENABLE);
    }

    public boolean isEnabled() {
        boolean defaultValue;
        switch (key) {
            case LBWMeasurementView.KEY:
            case BoneMeasurementView.KEY:
            case WaistMeasurementView.KEY:
            case HipMeasurementView.KEY:
                defaultValue = false;
                break;
            default:
                defaultValue = true;
                break;
        }
        return preferences.getBoolean(getEnabledKey(), defaultValue);
    }

    public String getInOverviewGraphKey() {
        return getPreferenceKey(PREFERENCE_SUFFIX_IN_OVERVIEW_GRAPH);
    }

    public boolean isOnOverviewGraph() {
        boolean defaultValue;
        switch (key) {
            case BMRMeasurementView.KEY:
                defaultValue = false;
                break;
            default:
                defaultValue = true;
                break;
        }
        return preferences.getBoolean(getInOverviewGraphKey(), defaultValue);
    }

    public String getPercentageEnabledKey() {
        return getPreferenceKey(PREFERENCE_SUFFIX_PERCENTAGE_ENABLE);
    }

    public boolean isPercentageEnabled() {
        return preferences.getBoolean(getPercentageEnabledKey(), true);
    }

    public String getEstimationEnabledKey() {
        switch (key) {
            case FatMeasurementView.KEY:
                return "estimateFatEnable";
            case LBWMeasurementView.KEY:
                return "estimateLBWEnable";
            case WaterMeasurementView.KEY:
                return "estimateWaterEnable";
        }
        return getPreferenceKey(PREFERENCE_SUFFIX_ESTIMATE_ENABLE);
    }

    public boolean isEstimationEnabled() {
        return preferences.getBoolean(getEstimationEnabledKey(), false);
    }

    public String getEstimationFormulaKey() {
        switch (key) {
            case FatMeasurementView.KEY:
                return "estimateFatFormula";
            case LBWMeasurementView.KEY:
                return "estimateLBWFormula";
            case WaterMeasurementView.KEY:
                return "estimateWaterFormula";
        }
        return getPreferenceKey(PREFERENCE_SUFFIX_ESTIMATE_FORMULA);
    }

    public String getEstimationFormula() {
        String defaultValue;
        switch (key) {
            case FatMeasurementView.KEY:
                defaultValue = EstimatedFatMetric.FORMULA.BF_GALLAGHER.name();
                break;
            case LBWMeasurementView.KEY:
                defaultValue = EstimatedLBWMetric.FORMULA.LBW_HUME.name();
                break;
            case WaterMeasurementView.KEY:
                defaultValue = EstimatedWaterMetric.FORMULA.TBW_LEESONGKIM.name();
                break;
            default:
                defaultValue = "";
                break;
        }

        return preferences.getString(getEstimationFormulaKey(), defaultValue);
    }
}
