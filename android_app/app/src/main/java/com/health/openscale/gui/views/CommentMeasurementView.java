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
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.text.InputType;
import android.widget.EditText;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;

public class CommentMeasurementView extends MeasurementView {
    private String comment;
    private static String COMMENT_KEY = "comment";

    public CommentMeasurementView(Context context) {
        super(context, context.getResources().getString(R.string.label_comment), ContextCompat.getDrawable(context, R.drawable.ic_comment));
    }

    private void setValue(String newComment, boolean callListener) {
        if (!newComment.equals(comment)) {
            comment = newComment;
            setValueView(comment, callListener);
        }
    }

    @Override
    public void loadFrom(ScaleMeasurement measurement, ScaleMeasurement previousMeasurement) {
        setValue(measurement.getComment(), false);
    }

    @Override
    public void saveTo(ScaleMeasurement measurement) {
        measurement.setComment(comment);
    }

    @Override
    public void restoreState(Bundle state) {
        setValue(state.getString(COMMENT_KEY), true);
    }

    @Override
    public void saveState(Bundle state) {
        state.putString(COMMENT_KEY, comment);
    }

    @Override
    public void updatePreferences(SharedPreferences preferences) {
        // Empty
    }

    @Override
    public String getValueAsString() {
        return comment;
    }

    @Override
    protected boolean validateAndSetInput(EditText view) {
        setValue(view.getText().toString(), true);
        return true;
    }

    @Override
    protected int getInputType() {
        return InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    }

    @Override
    protected String getHintText() {
        return getResources().getString(R.string.info_enter_comment);
    }
}
