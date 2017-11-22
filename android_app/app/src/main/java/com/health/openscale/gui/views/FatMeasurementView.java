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

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.content.ContextCompat;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class FatMeasurementView extends MeasurementView {

    private boolean estimateFatEnable;

    public FatMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_fat), ContextCompat.getDrawable(context, R.drawable.ic_fat));
    }

    @Override
    public boolean isEditable() {
        if (estimateFatEnable && getMeasurementMode() == MeasurementViewMode.ADD) {
            return false;
        }
        return true;
    }

    @Override
    public void updateValue(ScaleData updateData) {
        if (estimateFatEnable && getMeasurementMode() == MeasurementViewMode.ADD) {
            setValueOnView(updateData.getDateTime(), (getContext().getString(R.string.label_automatic)));
        } else {
            setValueOnView(updateData.getDateTime(), updateData.getFat());
        }
    }

    @Override
    public void updateDiff(ScaleData updateData, ScaleData lastData) {
        setDiffOnView(updateData.getFat(), lastData.getFat());
    }

    @Override
    public String getUnit() {
        return "%";
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        setVisible(preferences.getBoolean("fatEnable", true));
        estimateFatEnable = preferences.getBoolean("estimateFatEnable", false);
    }

    @Override
    public EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return evalSheet.evaluateBodyFat(value);
    }

    @Override
    public float getMaxValue() {
        return 80;
    }

}
