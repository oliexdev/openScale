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
import android.text.InputType;
import android.widget.EditText;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.evaluation.EvaluationResult;
import com.health.openscale.core.evaluation.EvaluationSheet;

public class CommentMeasurementView extends MeasurementView {

    public CommentMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_comment), ContextCompat.getDrawable(context, R.drawable.ic_comment));
    }

    @Override
    public boolean validateInput(EditText view) {
        return true;
    }

    protected int getInputType() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    }

    @Override
    protected String getHintText() {
        return getResources().getString(R.string.info_enter_comment);
    }

    @Override
    public void updateValue(ScaleMeasurement newMeasurement) {
        setValueOnView(newMeasurement.getDateTime(), newMeasurement.getComment());
    }

    @Override
    public void updateDiff(ScaleMeasurement newMeasurement, ScaleMeasurement lastMeasurement) {

    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {

    }

    @Override
    public String getUnit() {
        return null;
    }

    @Override
    public EvaluationResult evaluateSheet(EvaluationSheet evalSheet, float value) {
        return null;
    }

    @Override
    public float getMaxValue() {
        return 0;
    }

}
