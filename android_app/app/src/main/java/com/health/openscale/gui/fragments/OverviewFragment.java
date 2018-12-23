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
package com.health.openscale.gui.fragments;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TextView;

import com.github.mikephil.charting.animation.Easing;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;
import com.github.mikephil.charting.formatter.IValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.github.mikephil.charting.utils.ViewPortHandler;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.views.FloatMeasurementView;
import com.health.openscale.gui.views.MeasurementView;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import androidx.fragment.app.Fragment;

public class OverviewFragment extends Fragment implements FragmentUpdateListener {

    private View overviewView;
    private View userLineSeparator;

    private TextView txtTitleUser;
    private TextView txtTitleLastMeasurement;

    private List<MeasurementView> measurementViews;

    private LineChart rollingChart;

    private Spinner spinUser;

    private ScaleUser currentScaleUser;

    private ArrayAdapter<String> spinUserAdapter;

    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // retain this fragment otherwise the app crashed in landscape mode for small devices (see "Handling Runtime Changes")
        setRetainInstance(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        overviewView = inflater.inflate(R.layout.fragment_overview, container, false);
        userLineSeparator = overviewView.findViewById(R.id.userLineSeparator);

        prefs = PreferenceManager.getDefaultSharedPreferences(overviewView.getContext());

        txtTitleUser = overviewView.findViewById(R.id.txtTitleUser);
        txtTitleLastMeasurement = overviewView.findViewById(R.id.txtTitleLastMeasurement);

        rollingChart = overviewView.findViewById(R.id.rollingChart);

        rollingChart.getDescription().setEnabled(false);

        rollingChart.setTouchEnabled(true);
        rollingChart.setOnChartValueSelectedListener(new rollingChartSelectionListener());

        rollingChart.setHighlightPerTapEnabled(true);

        rollingChart.getLegend().setEnabled(prefs.getBoolean("legendEnable", true));
        rollingChart.getLegend().setTextColor(txtTitleLastMeasurement.getCurrentTextColor());
        rollingChart.getLegend().setWordWrapEnabled(true);
        rollingChart.getLegend().setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        rollingChart.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);

        XAxis xAxis = rollingChart.getXAxis();
        xAxis.setTextColor(txtTitleLastMeasurement.getCurrentTextColor());
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new IAxisValueFormatter() {

            private final SimpleDateFormat mFormat = new SimpleDateFormat("dd MMM", Locale.getDefault());

            @Override
            public String getFormattedValue(float value, AxisBase axis) {
                long millis = TimeUnit.DAYS.toMillis((long) value);
                return mFormat.format(new Date(millis));
            }
        });


        YAxis leftAxis = rollingChart.getAxisLeft();
        leftAxis.setEnabled(false);

        YAxis rightAxis = rollingChart.getAxisRight();
        rightAxis.setEnabled(false);

        spinUser = overviewView.findViewById(R.id.spinUser);

        measurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.NONE);

        TableLayout tableOverviewLayout = overviewView.findViewById(R.id.tableLayoutMeasurements);

        for (MeasurementView measurement : measurementViews) {
            tableOverviewLayout.addView(measurement);
        }

        spinUserAdapter = new ArrayAdapter<>(overviewView.getContext(), R.layout.support_simple_spinner_dropdown_item, new ArrayList<String>());
        spinUser.setAdapter(spinUserAdapter);

        // Set item select listener after spinner is created because otherwise item listener fires a lot!?!?
        spinUser.post(new Runnable() {
            public void run() {
                spinUser.setOnItemSelectedListener(new spinUserSelectionListener());
                updateUserSelection();
            }
        });

        txtTitleUser.setText(getResources().getString(R.string.label_title_user).toUpperCase());
        txtTitleLastMeasurement.setText(getResources().getString(R.string.label_title_last_measurement).toUpperCase());

        OpenScale.getInstance().registerFragment(this);

        rollingChart.animateX(500);

        return overviewView;
    }

    @Override
    public void onDestroyView() {
        OpenScale.getInstance().unregisterFragment(this);
        super.onDestroyView();
    }

    @Override
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList) {
        ScaleMeasurement selectedMeasurement;

        if (scaleMeasurementList.isEmpty()) {
            selectedMeasurement = new ScaleMeasurement();
        } else {
            selectedMeasurement = scaleMeasurementList.get(0);
        }

        updateUserSelection();
        updateRollingChart(scaleMeasurementList);
        updateMesurementViews(selectedMeasurement);
    }

    private void updateMesurementViews(ScaleMeasurement selectedMeasurement) {
        ScaleMeasurement[] tupleScaleData = OpenScale.getInstance().getTupleScaleData(selectedMeasurement.getId());
        ScaleMeasurement prevScaleMeasurement = tupleScaleData[0];

        for (MeasurementView measurement : measurementViews) {
            measurement.loadFrom(selectedMeasurement, prevScaleMeasurement);
        }
    }

    private void updateUserSelection() {

        currentScaleUser = OpenScale.getInstance().getSelectedScaleUser();

        spinUserAdapter.clear();
        List<ScaleUser> scaleUserList = OpenScale.getInstance().getScaleUserList();

        int posUser = 0;

        for (ScaleUser scaleUser : scaleUserList) {
            spinUserAdapter.add(scaleUser.getUserName());

            if (scaleUser.getId() == currentScaleUser.getId()) {
                posUser = spinUserAdapter.getCount() - 1;
            }
        }

        spinUser.setSelection(posUser, true);

        // Hide user selector when there is only one user
        int visibility = spinUserAdapter.getCount() < 2 ? View.GONE : View.VISIBLE;
        txtTitleUser.setVisibility(visibility);
        spinUser.setVisibility(visibility);
        userLineSeparator.setVisibility(visibility);
    }

    private void updateRollingChart(List<ScaleMeasurement> scaleMeasurementList) {
        rollingChart.clear();
        Collections.reverse(scaleMeasurementList);

        List<ILineDataSet> dataSets = new ArrayList<>();

        for (MeasurementView view : measurementViews) {

            if (!view.isVisible()
                    || !view.getSettings().isInOverviewGraph()
                    || !(view instanceof FloatMeasurementView)) {
                continue;
            }

            final FloatMeasurementView measurementView = (FloatMeasurementView) view;

            List<Entry> entries = new ArrayList<>();

            for (ScaleMeasurement measurement : scaleMeasurementList) {

                measurementView.loadFrom(measurement, null);

                if (measurementView.getValue() != 0.0f) {
                    Entry entry = new Entry();
                    entry.setX(TimeUnit.MILLISECONDS.toDays(measurement.getDateTime().getTime()));
                    entry.setY(measurementView.getValue());
                    entry.setData(measurement);

                    entries.add(entry);
                }
            }

            LineDataSet dataSet = new LineDataSet(entries, measurementView.getName().toString());
            dataSet.setValueTextColor(txtTitleLastMeasurement.getCurrentTextColor());
            dataSet.setColor(measurementView.getColor());
            dataSet.setCircleColor(measurementView.getColor());
            dataSet.setAxisDependency(measurementView.getSettings().isOnRightAxis() ? YAxis.AxisDependency.RIGHT : YAxis.AxisDependency.LEFT);
            dataSet.setHighlightEnabled(true);
            dataSet.setDrawHighlightIndicators(true);
            dataSet.setDrawHorizontalHighlightIndicator(false);
            dataSet.setHighLightColor(Color.RED);
            dataSet.setDrawCircles(prefs.getBoolean("pointsEnable", true));
            dataSet.setDrawValues(prefs.getBoolean("labelsEnable", true));
            dataSet.setValueFormatter(new IValueFormatter() {
                DecimalFormat mFormat = new DecimalFormat("###,###,##0.00");

                @Override
                public String getFormattedValue(float value, Entry entry, int dataSetIndex, ViewPortHandler viewPortHandler) {
                    return mFormat.format(value) + " " + measurementView.getUnit();
                }
            });
            dataSets.add(dataSet);
        }

        LineData data = new LineData(dataSets);
        rollingChart.setData(data);

        if (!scaleMeasurementList.isEmpty()) {
            Collections.reverse(scaleMeasurementList);

            rollingChart.moveViewToX(TimeUnit.MILLISECONDS.toDays(scaleMeasurementList.get(0).getDateTime().getTime()));
            rollingChart.setVisibleXRangeMaximum(7);
        }

        rollingChart.invalidate();
    }

    private class rollingChartSelectionListener implements OnChartValueSelectedListener {

        @Override
        public void onValueSelected(Entry e, Highlight h) {
            updateMesurementViews((ScaleMeasurement) e.getData());
        }

        @Override
        public void onNothingSelected() {

        }
    }

    private class spinUserSelectionListener implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
             if (parent.getChildCount() > 0) {
                 ((TextView) parent.getChildAt(0)).setTextColor(Color.GRAY);

                 OpenScale openScale = OpenScale.getInstance();

                 List<ScaleUser> scaleUserList = openScale.getScaleUserList();
                 ScaleUser scaleUser = scaleUserList.get(position);

                 openScale.selectScaleUser(scaleUser.getId());
                 openScale.updateScaleData();
             }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }

}
