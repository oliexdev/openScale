package com.health.openscale.gui.views;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.TextView;

import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;
import com.health.openscale.R;

import java.text.DecimalFormat;

@SuppressLint("ViewConstructor")
public class ChartMarkerView extends com.github.mikephil.charting.components.MarkerView {
    private final TextView markerTextField;

    DecimalFormat mFormat = new DecimalFormat("###,###,##0.00");

    public ChartMarkerView(Context context, int layoutResource) {
        super(context, layoutResource);

        markerTextField = findViewById(R.id.markerTextField);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        FloatMeasurementView measurementView = (FloatMeasurementView) e.getData();

        markerTextField.setText(String.format("%s %s", mFormat.format(e.getY()), measurementView.getUnit()));

        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2), -getHeight() - 5);
    }
}