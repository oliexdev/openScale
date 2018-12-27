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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;
import com.github.mikephil.charting.formatter.IValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.github.mikephil.charting.utils.ViewPortHandler;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.PolynomialFitter;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.utils.ColorUtil;
import com.health.openscale.gui.views.BMRMeasurementView;
import com.health.openscale.gui.views.ChartMarkerView;
import com.health.openscale.gui.views.FloatMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MeasurementViewSettings;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

import androidx.fragment.app.Fragment;

public class GraphFragment extends Fragment implements FragmentUpdateListener {
    private View graphView;
    private LineChart chartBottom;
    private BarChart chartTop;
    private TextView txtYear;
    private Button btnLeftYear;
    private Button btnRightYear;
    private LinearLayout floatingActionBar;
    private PopupMenu popup;
    private FloatingActionButton showMenu;
    private FloatingActionButton editMenu;
    private FloatingActionButton deleteMenu;
    private SharedPreferences prefs;

    private List<MeasurementView> measurementViews;

    private OpenScale openScale;

    private final Calendar calYears;
    private Calendar calLastSelected;

    private ScaleMeasurement markedMeasurement;

    private static final String CAL_YEARS_KEY = "calYears";
    private static final String CAL_LAST_SELECTED_KEY = "calLastSelected";

    public GraphFragment() {
        calYears = Calendar.getInstance();
        calLastSelected = Calendar.getInstance();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        openScale = OpenScale.getInstance();

        if (savedInstanceState == null) {
            List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleMeasurementList();
            if (!scaleMeasurementList.isEmpty()) {
                calYears.setTime(scaleMeasurementList.get(0).getDateTime());
                calLastSelected.setTime(scaleMeasurementList.get(0).getDateTime());
            }
        }
        else {
            calYears.setTimeInMillis(savedInstanceState.getLong(CAL_YEARS_KEY));
            calLastSelected.setTimeInMillis(savedInstanceState.getLong(CAL_LAST_SELECTED_KEY));
        }

        graphView = inflater.inflate(R.layout.fragment_graph, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        chartBottom = graphView.findViewById(R.id.chart_bottom);
        chartBottom.setOnChartValueSelectedListener(new chartBottomValueTouchListener());
        chartBottom.getLegend().setEnabled(prefs.getBoolean("legendEnable", true));
        chartBottom.getLegend().setWordWrapEnabled(true);
        chartBottom.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        chartBottom.getLegend().setTextColor(ColorUtil.getTextColor(graphView.getContext()));
        chartBottom.getDescription().setEnabled(false);
        chartBottom.getAxisLeft().setEnabled(false);
        chartBottom.getAxisRight().setEnabled(false);
        chartBottom.setDoubleTapToZoomEnabled(false);
        chartBottom.setHighlightPerTapEnabled(true);

        MarkerView mv = new ChartMarkerView(chartBottom.getContext(), R.layout.chart_markerview);
        chartBottom.setMarker(mv);

        XAxis chartBottomxAxis = chartBottom.getXAxis();
        chartBottomxAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        chartBottomxAxis.setTextColor(ColorUtil.getTextColor(graphView.getContext()));

        chartTop = graphView.findViewById(R.id.chart_top);
        chartTop.setDoubleTapToZoomEnabled(false);
        chartTop.setDrawGridBackground(false);
        chartTop.getLegend().setEnabled(false);
        chartTop.getAxisLeft().setEnabled(false);
        chartTop.getAxisRight().setEnabled(false);
        chartTop.getDescription().setEnabled(false);
        chartTop.setOnChartValueSelectedListener(new chartTopValueTouchListener());

        XAxis chartTopxAxis = chartTop.getXAxis();
        chartTopxAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        chartTopxAxis.setDrawGridLines(false);
        chartTopxAxis.setTextColor(ColorUtil.getTextColor(graphView.getContext()));
        chartTopxAxis.setValueFormatter(new IAxisValueFormatter() {

            private final SimpleDateFormat mFormat = new SimpleDateFormat("MMM", Locale.getDefault());

            @Override
            public String getFormattedValue(float value, AxisBase axis) {
                Calendar calendar = Calendar.getInstance();
                calendar.set(Calendar.MONTH, (int)value);
                return mFormat.format(calendar.getTime());
            }
        });

        txtYear = graphView.findViewById(R.id.txtYear);
        txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));

        floatingActionBar = graphView.findViewById(R.id.floatingActionBar);

        ImageView optionMenu = graphView.findViewById(R.id.optionMenu);
        optionMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                popup.show();
            }
        });

        btnLeftYear = graphView.findViewById(R.id.btnLeftYear);
        btnLeftYear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                calYears.roll(Calendar.YEAR, false);
                txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));

                List<ScaleMeasurement> scaleMeasurementList =
                        OpenScale.getInstance().getScaleDataOfYear(calYears.get(Calendar.YEAR));
                if (!scaleMeasurementList.isEmpty()) {
                    calLastSelected.setTime(scaleMeasurementList.get(0).getDateTime());
                }
                generateGraphs();
            }
        });

        btnRightYear = graphView.findViewById(R.id.btnRightYear);
        btnRightYear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                calYears.roll(Calendar.YEAR, true);
                txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));

                List<ScaleMeasurement> scaleMeasurementList =
                        OpenScale.getInstance().getScaleDataOfYear(calYears.get(Calendar.YEAR));
                if (!scaleMeasurementList.isEmpty()) {
                    calLastSelected.setTime(scaleMeasurementList.get(scaleMeasurementList.size() - 1).getDateTime());
                }
                generateGraphs();
            }
        });

        measurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.NONE);

        popup = new PopupMenu(getContext(), optionMenu);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {

                switch (item.getItemId()) {
                    case R.id.enableMonth:
                        if (item.isChecked()) {
                            item.setChecked(false);
                            prefs.edit().putBoolean("showMonth", false).apply();
                        } else {
                            item.setChecked(true);
                            prefs.edit().putBoolean("showMonth", true).apply();
                        }

                        generateGraphs();
                        return true;
                    case R.id.enableWeek:
                        if (item.isChecked()) {
                            item.setChecked(false);
                            prefs.edit().putBoolean("showWeek", false).apply();
                        } else {
                            item.setChecked(true);
                            prefs.edit().putBoolean("showWeek", true).apply();
                        }

                        generateGraphs();
                        return true;
                    default:
                        return false;
                }
            }
        });
        popup.getMenuInflater().inflate(R.menu.graph_menu, popup.getMenu());

        MenuItem enableMonth = popup.getMenu().findItem(R.id.enableMonth);
        enableMonth.setChecked(prefs.getBoolean("showMonth", true));

        MenuItem enableWeek = popup.getMenu().findItem(R.id.enableWeek);
        enableWeek.setChecked(prefs.getBoolean("showWeek", false));

        showMenu = graphView.findViewById(R.id.showMenu);
        showMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = markedMeasurement.getId();

                Intent intent = new Intent(graphView.getContext(), DataEntryActivity.class);
                intent.putExtra(DataEntryActivity.EXTRA_ID, id);
                intent.putExtra(DataEntryActivity.EXTRA_MODE, DataEntryActivity.VIEW_MEASUREMENT_REQUEST);
                startActivity(intent);
            }
        });

        editMenu = graphView.findViewById(R.id.editMenu);
        editMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = markedMeasurement.getId();

                Intent intent = new Intent(graphView.getContext(), DataEntryActivity.class);
                intent.putExtra(DataEntryActivity.EXTRA_ID, id);
                intent.putExtra(DataEntryActivity.EXTRA_MODE, DataEntryActivity.EDIT_MEASUREMENT_REQUEST);
                startActivity(intent);
            }
        });
        deleteMenu = graphView.findViewById(R.id.deleteMenu);
        deleteMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteMeasurement();
            }
        });

        openScale.registerFragment(this);

        return graphView;
    }

    @Override
    public void onDestroyView() {
        OpenScale.getInstance().unregisterFragment(this);
        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putLong(CAL_YEARS_KEY, calYears.getTimeInMillis());
        outState.putLong(CAL_LAST_SELECTED_KEY, calLastSelected.getTimeInMillis());
    }

    @Override
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList)
    {
        generateGraphs();
    }

    private void addFloatingActionButton(FloatMeasurementView measurementView) {
        FloatingActionButton actionButton = new FloatingActionButton(getContext());

        actionButton.setTag(measurementView.getKey());
        actionButton.setColorFilter(Color.parseColor("#000000"));
        actionButton.setImageDrawable(measurementView.getIcon());
        actionButton.setClickable(true);
        actionButton.setSize(FloatingActionButton.SIZE_MINI);
        RelativeLayout.LayoutParams lay = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lay.setMargins(0,5,20,10);
        actionButton.setLayoutParams(lay);
        actionButton.setOnClickListener(new onClickListenerDiagramLines());

        int color = measurementView.getSettings().isInGraph()
                ? measurementView.getColor() : ColorUtil.COLOR_GRAY;
        actionButton.setBackgroundTintList(ColorStateList.valueOf(color));

        floatingActionBar.addView(actionButton);
    }

    private void generateLineData(int field, List<ScaleMeasurement> scaleMeasurementList)
    {
        chartBottom.clear();

        SimpleDateFormat day_date = new SimpleDateFormat("D", Locale.getDefault());

        if (field == Calendar.DAY_OF_MONTH) {
            day_date = new SimpleDateFormat("dd", Locale.getDefault());

            if (prefs.getBoolean("showWeek", false)) {
                field = Calendar.WEEK_OF_MONTH;
                day_date = new SimpleDateFormat("w", Locale.getDefault());
            }
        } else if (field == Calendar.DAY_OF_YEAR) {
            day_date = new SimpleDateFormat("D", Locale.getDefault());

            if (prefs.getBoolean("averageData", true)) {
                field = Calendar.MONTH;
                day_date = new SimpleDateFormat("MMM", Locale.getDefault());
            }

            if (prefs.getBoolean("showWeek", false)) {
                field = Calendar.WEEK_OF_YEAR;
                day_date = new SimpleDateFormat("w", Locale.getDefault());
            }
        }

        final int finalField = field;
        final SimpleDateFormat mFormat = day_date;

        chartBottom.getXAxis().setValueFormatter(new IAxisValueFormatter() {

            @Override
            public String getFormattedValue(float value, AxisBase axis) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(new Date(0));
                calendar.set(finalField, (int)value);

                return mFormat.format(calendar.getTime());
            }
        });

        Calendar calDays = (Calendar)calLastSelected.clone();

        calDays.setMinimalDaysInFirstWeek(7);
        calDays.set(field, calDays.getMinimum(field));
        int maxDays = calDays.getMaximum(field);

        List<ILineDataSet> dataSets = new ArrayList<>();

        Calendar calDB = Calendar.getInstance();

        floatingActionBar.removeAllViews();

        int regressLineOrder = 1;

        try {
            regressLineOrder = Integer.parseInt(prefs.getString("regressionLineOrder", "1"));
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), getString(R.string.error_value_required) + ":" + e.getMessage(), Toast.LENGTH_LONG).show();
            prefs.edit().putString("regressionLineOrder", "1").apply();
        }

        PolynomialFitter polyFitter = new PolynomialFitter(Math.min(regressLineOrder, 100));

        float maxYValue = 0;

        for (MeasurementView view : measurementViews) {
            if (view instanceof FloatMeasurementView) {
                final FloatMeasurementView measurementView = (FloatMeasurementView) view;

                if (measurementView instanceof BMRMeasurementView) {
                    continue;
                }

                boolean entryIsAverage = false;
                final List<Entry> entries = new ArrayList<>();
                final ArrayList<Float>[] avgBins = new ArrayList[maxDays+1];
                ScaleMeasurement[] indexScaleMeasurement = new ScaleMeasurement[maxDays+1];

                for (ScaleMeasurement measurement : scaleMeasurementList) {
                    measurementView.loadFrom(measurement, null);

                    calDB.setTime(measurement.getDateTime());
                    calDB.setMinimalDaysInFirstWeek(7);

                    if (avgBins[calDB.get(field)] == null) {
                        avgBins[calDB.get(field)] = new ArrayList<>();
                    }

                    if (measurementView.getValue() != 0.0f){
                        avgBins[calDB.get(field)].add(measurementView.getValue());
                        indexScaleMeasurement[calDB.get(field)] = measurement;
                    }
                }

                for (int i=0; i<maxDays+1; i++) {
                    ArrayList avgBin = avgBins[i];

                    if (avgBin == null) {
                        continue;
                    }

                    float sum = 0.0f;

                    for (int j=0; j<avgBin.size(); j++) {
                        sum += (float)avgBin.get(j);
                    }

                    Entry entry = new Entry(i, sum / avgBin.size());
                    Object[] extraData = new Object[2];
                    extraData[0] = indexScaleMeasurement[i];
                    extraData[1] = measurementView;
                    entry.setData(extraData);

                    if (prefs.getBoolean("regressionLine", false) && measurementView instanceof WeightMeasurementView) {
                        polyFitter.addPoint((double)entry.getX(), (double)entry.getY());
                    }

                    if (avgBin.size() > 1) {
                        entryIsAverage = true;// entry is a average calculation
                    }

                    if (entry.getY() > maxYValue) {
                        maxYValue = entry.getY();
                    }

                    entries.add(entry);
                }

                final boolean finalEntryIsAverage = entryIsAverage; // TODO HACK to transfer entryIsAverage to getFormattedValue because entry data is already used for the measurement

                LineDataSet dataSet = new LineDataSet(entries, measurementView.getName().toString());
                dataSet.setLineWidth(1.5f);
                dataSet.setValueTextSize(8.0f);
                dataSet.setColor(measurementView.getColor());
                dataSet.setValueTextColor(ColorUtil.getTextColor(graphView.getContext()));
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

                        if (finalEntryIsAverage) {
                            prefix = "Ø ";
                        }

                        return prefix + mFormat.format(value) + " " + measurementView.getUnit();
                    }
                });

                if (measurementView.isVisible()) {
                    addFloatingActionButton(measurementView);

                    if (measurementView.getSettings().isInGraph()) {
                        dataSets.add(dataSet);
                    }
                }
            }
        }

        if (prefs.getBoolean("goalLine", true)) {
            List<Entry> valuesGoalLine = new Stack<>();

            final ScaleUser user = openScale.getSelectedScaleUser();
            float goalWeight = Converters.fromKilogram(user.getGoalWeight(), user.getScaleUnit());

            valuesGoalLine.add(new Entry(0, goalWeight));
            valuesGoalLine.add(new Entry(maxDays, goalWeight));

            LineDataSet goalLine = new LineDataSet(valuesGoalLine, getString(R.string.label_goal_line));
            goalLine.setLineWidth(1.5f);
            goalLine.setColor(ColorUtil.COLOR_GREEN);
            goalLine.setDrawValues(false);
            goalLine.setDrawCircles(false);
            goalLine.setHighlightEnabled(false);
            goalLine.enableDashedLine(10, 30, 0);

            dataSets.add(goalLine);
        }

        if (prefs.getBoolean("regressionLine", false)) {
            PolynomialFitter.Polynomial polynomial = polyFitter.getBestFit();

            List<Entry> valuesLinearRegression = new Stack<>();

            for (int i = 0; i < maxDays+1; i++) {
                    double y_value = polynomial.getY(i);
                    valuesLinearRegression.add(new Entry((float) i, (float) y_value));
            }

            LineDataSet linearRegressionLine = new LineDataSet(valuesLinearRegression, getString(R.string.label_regression_line));
            linearRegressionLine.setLineWidth(1.5f);
            linearRegressionLine.setColor(ColorUtil.COLOR_VIOLET);
            linearRegressionLine.setDrawValues(false);
            linearRegressionLine.setDrawCircles(false);
            linearRegressionLine.setHighlightEnabled(false);
            linearRegressionLine.enableDashedLine(10, 30, 0);

            dataSets.add(linearRegressionLine);
        }

        LineData data = new LineData(dataSets);
        chartBottom.setData(data);
        chartBottom.animateY(700);
        chartBottom.invalidate();
    }

    private void generateColumnData()
    {
        int[] numOfMonth = openScale.getCountsOfMonth(calYears.get(Calendar.YEAR));

        float[] normNumOfMonth = new float[12];

        int max = 0;
        int min = Integer.MAX_VALUE;

        for (int i=0; i<12; i++) {
            if (numOfMonth[i] > max) {
                max = numOfMonth[i];
            }

            if (numOfMonth[i] < min) {
                min = numOfMonth[i];
            }
        }

        final float heightOffset = 0.2f; // increase month selector minimum height

        for (int i=0; i<12; i++) {
            normNumOfMonth[i] = (numOfMonth[i] - min) / (float)(max - min); // normalize data to [0..1]

            if (normNumOfMonth[i] != 0.0f) {
                normNumOfMonth[i] += heightOffset;
            }
        }

        Calendar calMonths = Calendar.getInstance();
        calMonths.set(Calendar.MONTH, Calendar.JANUARY);

        List<IBarDataSet> dataSets = new ArrayList<>();

        for (int i=0; i<12; i++) {
            List<BarEntry> entries = new ArrayList<>();

            entries.add(new BarEntry(calMonths.get(Calendar.MONTH), normNumOfMonth[i]));

            calMonths.add(Calendar.MONTH, 1);

            BarDataSet set = new BarDataSet(entries, "month "+i);
            set.setColor(ColorUtil.COLORS[i % 4]);
            set.setDrawValues(false);
            dataSets.add(set);
        }

        BarData data = new BarData(dataSets);

        chartTop.setData(data);
        chartTop.setFitBars(true);
        chartTop.invalidate();
    }

    private void generateGraphs() {
        final int selectedYear = calYears.get(Calendar.YEAR);

        int firstYear = selectedYear;
        int lastYear = selectedYear;

        List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleMeasurementList();
        if (!scaleMeasurementList.isEmpty()) {
            Calendar cal = Calendar.getInstance();

            cal.setTime(scaleMeasurementList.get(scaleMeasurementList.size() - 1).getDateTime());
            firstYear = cal.get(Calendar.YEAR);

            cal.setTime(scaleMeasurementList.get(0).getDateTime());
            lastYear = cal.get(Calendar.YEAR);
        }
        btnLeftYear.setEnabled(selectedYear > firstYear);
        btnRightYear.setEnabled(selectedYear < lastYear);

        if (selectedYear == firstYear && selectedYear == lastYear) {
            btnLeftYear.setVisibility(View.GONE);
            btnRightYear.setVisibility(View.GONE);
        } else {
            btnLeftYear.setVisibility(View.VISIBLE);
            btnRightYear.setVisibility(View.VISIBLE);
        }

        // show monthly diagram
        if (prefs.getBoolean("showMonth", true)) {
            chartTop.setVisibility(View.VISIBLE);
            chartBottom.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.7f));

            generateColumnData();
            scaleMeasurementList = openScale.getScaleDataOfMonth(selectedYear, calLastSelected.get(Calendar.MONTH));

            generateLineData(Calendar.DAY_OF_MONTH, scaleMeasurementList);
        // show only yearly diagram and hide monthly diagram
        } else {
            chartTop.setVisibility(View.GONE);
            chartBottom.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f));

            scaleMeasurementList = openScale.getScaleDataOfYear(selectedYear);

            generateLineData(Calendar.DAY_OF_YEAR, scaleMeasurementList);
        }
    }

    private class chartTopValueTouchListener implements OnChartValueSelectedListener {
        @Override
        public void onValueSelected(Entry e, Highlight h) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.MONTH, (int)e.getX());

            calLastSelected = cal;

            List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleDataOfMonth(calYears.get(Calendar.YEAR), calLastSelected.get(Calendar.MONTH));
            generateLineData(Calendar.DAY_OF_MONTH, scaleMeasurementList);

            showMenu.setVisibility(View.GONE);
            editMenu.setVisibility(View.GONE);
            deleteMenu.setVisibility(View.GONE);
        }

        @Override
        public void onNothingSelected() {

        }
    }

    private class chartBottomValueTouchListener implements OnChartValueSelectedListener {
        @Override
        public void onValueSelected(Entry e, Highlight h) {
            Object[] extraData = (Object[])e.getData();

            if (extraData == null) {
                return;
            }

            markedMeasurement = (ScaleMeasurement)extraData[0];
            //MeasurementView measurementView = (MeasurementView)extraData[1];

            showMenu.setVisibility(View.VISIBLE);
            editMenu.setVisibility(View.VISIBLE);
            deleteMenu.setVisibility(View.VISIBLE);
        }

        @Override
        public void onNothingSelected() {
            showMenu.setVisibility(View.GONE);
            editMenu.setVisibility(View.GONE);
            deleteMenu.setVisibility(View.GONE);
        }
    }

    private class onClickListenerDiagramLines implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            FloatingActionButton actionButton = (FloatingActionButton) v;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

            String key = String.valueOf(actionButton.getTag());
            MeasurementViewSettings settings = new MeasurementViewSettings(prefs, key);
            prefs.edit().putBoolean(settings.getInGraphKey(), !settings.isInGraph()).apply();

            generateGraphs();
        }
    }

    private void deleteMeasurement() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(graphView.getContext());
        boolean deleteConfirmationEnable = prefs.getBoolean("deleteConfirmationEnable", true);

        if (deleteConfirmationEnable) {
            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(graphView.getContext());
            deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete));

            deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    doDeleteMeasurement();
                }
            });

            deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            deleteAllDialog.show();
        }
        else {
            doDeleteMeasurement();
        }
    }

    private void doDeleteMeasurement() {
        OpenScale.getInstance().deleteScaleData(markedMeasurement.getId());
        Toast.makeText(graphView.getContext(), getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();

        showMenu.setVisibility(View.GONE);
        editMenu.setVisibility(View.GONE);
        deleteMenu.setVisibility(View.GONE);

        chartTop.invalidate();
        chartBottom.invalidate();
    }
}
