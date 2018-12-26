/* Copyright (C) 2018  olie.xdev <olie.xdev@googlemail.com>
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

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.TextView;

import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;
import com.health.openscale.R;

import java.text.DecimalFormat;

@SuppressLint("ViewConstructor")
public class ChartMarkerView extends MarkerView {
    private final TextView markerTextField;

    DecimalFormat mFormat = new DecimalFormat("###,###,##0.00");

    public ChartMarkerView(Context context, int layoutResource) {
        super(context, layoutResource);

        markerTextField = findViewById(R.id.markerTextField);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        Object[] extraData = (Object[])e.getData();
        //ScaleMeasurement measurement = (ScaleMeasurement)extraData[0];
        FloatMeasurementView measurementView = (FloatMeasurementView)extraData[1];

        markerTextField.setText(String.format("%s %s", mFormat.format(e.getY()), measurementView.getUnit()));

        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2), -getHeight() - 5);
    }
}