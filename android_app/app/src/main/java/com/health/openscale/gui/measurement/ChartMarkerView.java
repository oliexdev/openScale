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
package com.health.openscale.gui.measurement;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.widget.TextView;

import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;
import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.utils.ColorUtil;

import java.text.DateFormat;

@SuppressLint("ViewConstructor")
public class ChartMarkerView extends MarkerView {
    private final TextView markerTextField;

    public ChartMarkerView(Context context, int layoutResource) {
        super(context, layoutResource);

        markerTextField = findViewById(R.id.markerTextField);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        Object[] extraData = (Object[])e.getData();
        ScaleMeasurement measurement = (ScaleMeasurement)extraData[0];
        ScaleMeasurement prevMeasurement = (ScaleMeasurement)extraData[1];
        FloatMeasurementView measurementView = (FloatMeasurementView)extraData[2];

        SpannableStringBuilder markerText = new SpannableStringBuilder();

        if (measurement != null) {
            measurementView.loadFrom(measurement, prevMeasurement);
            DateFormat dateFormat = DateFormat.getDateInstance();
            markerText.append(dateFormat.format(measurement.getDateTime()));
            markerText.setSpan(new RelativeSizeSpan(0.8f), 0, markerText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            markerText.append("\n");

            if (measurement.isAverageValue()) {
                markerText.append(getContext().getString(R.string.label_trend) + " ");
            }
        }

        markerText.append(measurementView.getValueAsString(true));

        if (prevMeasurement != null) {
            markerText.append("\n");
            int textPosAfterSymbol = markerText.length() + 1;

            measurementView.appendDiffValue(markerText, false);

            // set color diff value to text color
            if (markerText.length() > textPosAfterSymbol) {
                markerText.setSpan(new ForegroundColorSpan(ColorUtil.COLOR_WHITE), textPosAfterSymbol, markerText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        markerText.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),0, markerText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        markerTextField.setText(markerText);

        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 5f);
    }
}