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
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.MarkerView;
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
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.DateTimeHelpers;
import com.health.openscale.gui.utils.ColorUtil;
import com.health.openscale.gui.views.ChartMarkerView;
import com.health.openscale.gui.views.FloatMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.concurrent.TimeUnit;

import androidx.fragment.app.Fragment;

public class OverviewFragment extends Fragment implements FragmentUpdateListener {

    private enum RANGE_GRAPH {RANGE_WEEK, RANGE_MONTH, RANGE_YEAR, RANGE_ALL}

    private View overviewView;
    private View userLineSeparator;

    private TextView txtTitleUser;
    private TextView txtTitleLastMeasurement;

    private List<MeasurementView> measurementViews;
    private List<MeasurementView> lastMeasurementViews;

    private LineChart rollingChart;

    private Spinner spinUser;

    private PopupMenu rangePopupMenu;

    private ScaleUser currentScaleUser;

    private ArrayAdapter<String> spinUserAdapter;

    private SharedPreferences prefs;

    private ScaleMeasurement markedMeasurement;

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

        rollingChart.setDoubleTapToZoomEnabled(false);
        rollingChart.setTouchEnabled(true);
        rollingChart.setOnChartValueSelectedListener(new rollingChartSelectionListener());

        rollingChart.setHighlightPerTapEnabled(true);

        rollingChart.getAxisLeft().setEnabled(prefs.getBoolean("yaxisEnable", false));
        rollingChart.getAxisRight().setEnabled(prefs.getBoolean("yaxisEnable", false));

        rollingChart.getLegend().setEnabled(prefs.getBoolean("legendEnable", true));
        rollingChart.getLegend().setTextColor(ColorUtil.getTextColor(overviewView.getContext()));
        rollingChart.getLegend().setWordWrapEnabled(true);
        rollingChart.getLegend().setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        rollingChart.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);

        MarkerView mv = new ChartMarkerView(rollingChart.getContext(), R.layout.chart_markerview);
        rollingChart.setMarker(mv);

        XAxis xAxis = rollingChart.getXAxis();
        xAxis.setTextColor(ColorUtil.getTextColor(overviewView.getContext()));
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

        spinUser = overviewView.findViewById(R.id.spinUser);

        ImageView optionMenu = overviewView.findViewById(R.id.rangeOptionMenu);
        optionMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rangePopupMenu.show();
            }
        });


        rangePopupMenu = new PopupMenu(getContext(), optionMenu);
        rangePopupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {

                item.setChecked(true);

                RANGE_GRAPH selectRange = RANGE_GRAPH.RANGE_WEEK;

                switch (item.getItemId()) {
                    case R.id.menu_range_week:
                        selectRange = RANGE_GRAPH.RANGE_WEEK;
                        break;
                    case R.id.menu_range_month:
                        selectRange = RANGE_GRAPH.RANGE_MONTH;
                        break;
                    case R.id.menu_range_year:
                        selectRange = RANGE_GRAPH.RANGE_YEAR;
                        break;
                    case R.id.menu_range_all:
                        selectRange = RANGE_GRAPH.RANGE_ALL;
                        break;
                }

                prefs.edit().putString("selectRange", selectRange.name()).commit();
                updateRollingChart(OpenScale.getInstance().getScaleMeasurementList());
                getActivity().recreate(); // TODO HACK to refresh graph; graph.invalidate and notfiydatachange is not enough!?

                return true;
            }
        });
        rangePopupMenu.getMenuInflater().inflate(R.menu.overview_menu, rangePopupMenu.getMenu());

        RANGE_GRAPH selectRange = RANGE_GRAPH.RANGE_WEEK;
        selectRange = selectRange.valueOf(prefs.getString("selectRange", RANGE_GRAPH.RANGE_WEEK.name()));
        rangePopupMenu.getMenu().getItem(selectRange.ordinal()).setChecked(true);

        measurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.NONE);

        lastMeasurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.NONE);

        TableLayout tableOverviewLayout = overviewView.findViewById(R.id.tableLayoutMeasurements);

        for (MeasurementView measurement : lastMeasurementViews) {
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

        rollingChart.animateY(700);

        return overviewView;
    }

    @Override
    public void onDestroyView() {
        OpenScale.getInstance().unregisterFragment(this);
        super.onDestroyView();
    }

    @Override
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList) {
        if (scaleMeasurementList.isEmpty()) {
            markedMeasurement = new ScaleMeasurement();
        } else {
            markedMeasurement = scaleMeasurementList.get(0);
        }

        updateUserSelection();
        updateRollingChart(scaleMeasurementList);
        updateMesurementViews(markedMeasurement);
    }

    private void updateMesurementViews(ScaleMeasurement selectedMeasurement) {
        ScaleMeasurement[] tupleScaleData = OpenScale.getInstance().getTupleScaleData(selectedMeasurement.getId());
        ScaleMeasurement prevScaleMeasurement = tupleScaleData[0];

        for (MeasurementView measurement : lastMeasurementViews) {
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

        Calendar firstMeasurement = Calendar.getInstance();
        firstMeasurement.setTime(scaleMeasurementList.get(scaleMeasurementList.size()-1).getDateTime());
        Calendar lastMeasurement = Calendar.getInstance();
        lastMeasurement.setTime(scaleMeasurementList.get(0).getDateTime());

        Collections.reverse(scaleMeasurementList);

        List<ILineDataSet> dataSets = new ArrayList<>();

        boolean isWeightOnRightAxis= false;

        for (MeasurementView view : measurementViews) {

            if (!view.isVisible()
                    || !view.getSettings().isInOverviewGraph()
                    || !(view instanceof FloatMeasurementView)) {
                continue;
            }

            final FloatMeasurementView measurementView = (FloatMeasurementView) view;

            if (measurementView instanceof WeightMeasurementView) {
                isWeightOnRightAxis = measurementView.getSettings().isOnRightAxis();
            }

            List<Entry> entries = new ArrayList<>();

            for (int i = 0; i < scaleMeasurementList.size(); i++) {
                ScaleMeasurement measurement = scaleMeasurementList.get(i);
                ScaleMeasurement prevMeasurement = i == 0 ? null : scaleMeasurementList.get(i-1);

                measurementView.loadFrom(measurement, prevMeasurement);

                if (measurementView.getValue() != 0.0f) {
                    Entry entry = new Entry();
                    entry.setX(TimeUnit.MILLISECONDS.toDays(measurement.getDateTime().getTime()));
                    entry.setY(measurementView.getValue());
                    Object[] extraData = new Object[4];
                    extraData[0] = measurement;
                    extraData[1] = prevMeasurement;
                    extraData[2] = measurementView;
                    extraData[3] = false;
                    entry.setData(extraData);

                    entries.add(entry);
                }
            }

            LineDataSet dataSet = new LineDataSet(entries, measurementView.getName().toString());
            dataSet.setLineWidth(1.5f);
            dataSet.setValueTextColor(ColorUtil.getTextColor(overviewView.getContext()));
            dataSet.setValueTextSize(8.0f);
            dataSet.setColor(measurementView.getColor());
            dataSet.setCircleColor(measurementView.getColor());
            dataSet.setAxisDependency(measurementView.getSettings().isOnRightAxis() ? YAxis.AxisDependency.RIGHT : YAxis.AxisDependency.LEFT);
            dataSet.setHighlightEnabled(true);
            dataSet.setDrawHighlightIndicators(true);
            dataSet.setHighlightLineWidth(1.5f);
            dataSet.setDrawHorizontalHighlightIndicator(false);
            dataSet.setHighLightColor(Color.RED);
            dataSet.setDrawCircles(prefs.getBoolean("pointsEnable", true));
            dataSet.setDrawValues(prefs.getBoolean("labelsEnable", true));
            dataSet.setValueFormatter(new IValueFormatter() {
                DecimalFormat mFormat = new DecimalFormat("###,###,##0.00");

                @Override
                public String getFormattedValue(float value, Entry entry, int dataSetIndex, ViewPortHandler viewPortHandler) {
                    String prefix = new String();

                    Object[] extraData = (Object[])entry.getData();
                    ScaleMeasurement measurement = (ScaleMeasurement)extraData[0];
                    ScaleMeasurement prevMeasurement = (ScaleMeasurement)extraData[1];
                    FloatMeasurementView measurementView = (FloatMeasurementView)extraData[2];
                    boolean isAverageValue = (boolean)extraData[3];

                    measurementView.loadFrom(measurement, prevMeasurement);

                    if (isAverageValue) {
                        prefix = "Ø ";
                    }

                    return prefix + measurementView.getValueAsString(true);
                }
            });
            dataSets.add(dataSet);
        }

        if (prefs.getBoolean("goalLine", true)) {
            List<Entry> valuesGoalLine = new Stack<>();

            final ScaleUser user = OpenScale.getInstance().getSelectedScaleUser();
            float goalWeight = Converters.fromKilogram(user.getGoalWeight(), user.getScaleUnit());

            valuesGoalLine.add(new Entry(TimeUnit.MILLISECONDS.toDays(firstMeasurement.getTimeInMillis()), goalWeight));
            valuesGoalLine.add(new Entry(TimeUnit.MILLISECONDS.toDays(lastMeasurement.getTimeInMillis()), goalWeight));

            LineDataSet goalLine = new LineDataSet(valuesGoalLine, getString(R.string.label_goal_line));
            goalLine.setLineWidth(1.5f);
            goalLine.setColor(ColorUtil.COLOR_GREEN);
            goalLine.setAxisDependency(isWeightOnRightAxis ? YAxis.AxisDependency.RIGHT : YAxis.AxisDependency.LEFT);
            goalLine.setDrawValues(false);
            goalLine.setDrawCircles(false);
            goalLine.setHighlightEnabled(false);
            goalLine.enableDashedLine(10, 30, 0);

            dataSets.add(goalLine);
        }

        LineData data = new LineData(dataSets);
        rollingChart.setData(data);
        rollingChart.notifyDataSetChanged();
        rollingChart.invalidate();

        if (!scaleMeasurementList.isEmpty()) {
            Collections.reverse(scaleMeasurementList);

            int xRange = 7;

            RANGE_GRAPH selectRange = RANGE_GRAPH.RANGE_WEEK;
            selectRange = selectRange.valueOf(prefs.getString("selectRange", RANGE_GRAPH.RANGE_WEEK.name()));

            switch (selectRange) {
                case RANGE_WEEK:
                    xRange = 7;
                    break;
                case RANGE_MONTH:
                    xRange = 31;
                    break;
                case RANGE_YEAR:
                    xRange = 366;
                    break;
                case RANGE_ALL:
                    xRange = DateTimeHelpers.daysBetween(firstMeasurement, lastMeasurement);
                    break;
            }

            rollingChart.setVisibleXRangeMaximum(xRange);
            rollingChart.moveViewToX(TimeUnit.MILLISECONDS.toDays(lastMeasurement.getTimeInMillis()));
        }
    }

    private class rollingChartSelectionListener implements OnChartValueSelectedListener {

        @Override
        public void onValueSelected(Entry e, Highlight h) {
            Object[] extraData = (Object[])e.getData();

            markedMeasurement = (ScaleMeasurement)extraData[0];
            //MeasurementView measurementView = (MeasurementView)extraData[1];

            updateMesurementViews(markedMeasurement);
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
