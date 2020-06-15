/* Copyright (C) 2019  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.gui.measurement;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;

import java.util.ArrayList;

public class UserMeasurementView extends MeasurementView  {
    // Don't change key value, it may be stored persistent in preferences
    public static final String KEY = "user";

    private OpenScale openScale = OpenScale.getInstance();
    private int userId;

    public UserMeasurementView(Context context) {
        super(context, R.string.label_user_name, R.drawable.ic_user);
        userId = -1;
    }

    @Override
    public String getKey() {
        return KEY;
    }

    private void setValue(int newUserId, boolean callListener) {
        if (newUserId == -1) {
            setValueView(openScale.getSelectedScaleUser().getUserName(), callListener);
        } else if (userId != newUserId) {
            userId = newUserId;

            setValueView(openScale.getScaleUser(userId).getUserName(), callListener);
        }
    }

    @Override
    public void loadFrom(ScaleMeasurement measurement, ScaleMeasurement previousMeasurement) {
        setValue(measurement.getUserId(), false);
    }

    @Override
    public void saveTo(ScaleMeasurement measurement) {
        measurement.setUserId(userId);
    }

    @Override
    public void clearIn(ScaleMeasurement measurement) {
        // ignore
    }

    @Override
    public void restoreState(Bundle state) {
        setValue(state.getInt(getKey()), true);
    }

    @Override
    public void saveState(Bundle state) {
        state.putInt(getKey(), userId);
    }

    @Override
    public String getValueAsString(boolean withUnit) {
        return openScale.getScaleUser(userId).getUserName();
    }

    @Override
    protected View getInputView() {
        Spinner spinScaleUer = new Spinner(getContext());
        ArrayAdapter<String> spinScaleUserAdapter = new ArrayAdapter<>(getContext(), R.layout.support_simple_spinner_dropdown_item, new ArrayList<>());

        spinScaleUer.setAdapter(spinScaleUserAdapter);

        int spinPos = 0;

        for (ScaleUser scaleUser : openScale.getScaleUserList()) {
            spinScaleUserAdapter.add(scaleUser.getUserName());

            if (scaleUser.getId() == userId) {
                spinPos = spinScaleUserAdapter.getCount() - 1;
            }
        }

        spinScaleUer.setSelection(spinPos);

        return spinScaleUer;
    }

    @Override
    protected boolean validateAndSetInput(View view) {
        Spinner spinScaleUser = (Spinner)view;

        int pos = spinScaleUser.getSelectedItemPosition();
        setValue(openScale.getScaleUserList().get(pos).getId(), true);

        return true;
    }
}
