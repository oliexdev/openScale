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

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.PolynomialFitter;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.views.BMRMeasurementView;
import com.health.openscale.gui.views.FloatMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MeasurementViewSettings;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Stack;

import lecho.lib.hellocharts.formatter.SimpleLineChartValueFormatter;
import lecho.lib.hellocharts.listener.ColumnChartOnValueSelectListener;
import lecho.lib.hellocharts.listener.LineChartOnValueSelectListener;
import lecho.lib.hellocharts.model.Axis;
import lecho.lib.hellocharts.model.AxisValue;
import lecho.lib.hellocharts.model.Column;
import lecho.lib.hellocharts.model.ColumnChartData;
import lecho.lib.hellocharts.model.Line;
import lecho.lib.hellocharts.model.LineChartData;
import lecho.lib.hellocharts.model.PointValue;
import lecho.lib.hellocharts.model.SelectedValue;
import lecho.lib.hellocharts.model.SubcolumnValue;
import lecho.lib.hellocharts.model.Viewport;
import lecho.lib.hellocharts.util.ChartUtils;
import lecho.lib.hellocharts.view.ColumnChartView;
import lecho.lib.hellocharts.view.LineChartView;

public class GraphFragment extends Fragment implements FragmentUpdateListener {
    private View graphView;
    private LineChartView chartBottom;
    private ColumnChartView chartTop;
    private Viewport defaultTopViewport;
    private TextView txtYear;
    private Button btnLeftYear;
    private Button btnRightYear;
    private LinearLayout floatingActionBar;
    private PopupMenu popup;
    private SharedPreferences prefs;

    private List<MeasurementView> measurementViews;

    private int textColor;

    private OpenScale openScale;

    private final Calendar calYears;
    private Calendar calLastSelected;

    private static final String CAL_YEARS_KEY = "calYears";
    private static final String CAL_LAST_SELECTED_KEY = "calLastSelected";

    private List<ScaleMeasurement> pointIndexScaleMeasurementList;

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

        chartBottom = graphView.findViewById(R.id.chart_bottom);
        chartTop = graphView.findViewById(R.id.chart_top);

        chartBottom.setOnTouchListener(new chartBottomListener());
        chartBottom.setOnValueTouchListener(new chartBottomValueTouchListener());
        chartTop.setOnValueTouchListener(new chartTopValueTouchListener());

        // HACK: get default text color from hidden text view to set the correct axis colors
        textColor = ((TextView)graphView.findViewById(R.id.colorHack)).getCurrentTextColor();

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

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        MenuItem enableMonth = popup.getMenu().findItem(R.id.enableMonth);
        enableMonth.setChecked(prefs.getBoolean("showMonth", true));

        MenuItem enableWeek = popup.getMenu().findItem(R.id.enableWeek);
        enableWeek.setChecked(prefs.getBoolean("showWeek", false));

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
        actionButton.setSize(android.support.design.widget.FloatingActionButton.SIZE_MINI);
        RelativeLayout.LayoutParams lay = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lay.setMargins(0,5,20,10);
        actionButton.setLayoutParams(lay);
        actionButton.setOnClickListener(new onClickListenerDiagramLines());

        int color = measurementView.getSettings().isInGraph()
                ? measurementView.getColor() : Color.parseColor("#d3d3d3");
        actionButton.setBackgroundTintList(ColorStateList.valueOf(color));

        floatingActionBar.addView(actionButton);
    }

    private void generateLineData(int field, List<ScaleMeasurement> scaleMeasurementList)
    {
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

        Calendar calDays = (Calendar)calLastSelected.clone();

        calDays.setMinimalDaysInFirstWeek(7);
        calDays.set(field, calDays.getMinimum(field));
        int maxDays = calDays.getMaximum(field);

        List<AxisValue> axisValues = new ArrayList<>();

        for (int i=0; i<calDays.getMaximum(field)+1; i++) {
            String day_name = day_date.format(calDays.getTime());

            AxisValue  xAxisValue = new AxisValue(i + calDays.getActualMinimum(field));
            xAxisValue.setLabel(day_name);

            axisValues.add(xAxisValue);

            calDays.add(field, 1);
        }

        List<Line> diagramLineList = new ArrayList<>();

        Calendar calDB = Calendar.getInstance();

        pointIndexScaleMeasurementList = new ArrayList<>();

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
                FloatMeasurementView measurementView = (FloatMeasurementView) view;

                if (measurementView instanceof BMRMeasurementView) {
                    continue;
                }

                Stack<PointValue> valuesStack = new Stack<>();
                ArrayList<Float>[] avgBins = new ArrayList[maxDays+1];
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

                    PointValue avgValue  = new PointValue(i, sum / avgBin.size());

                    if (prefs.getBoolean("regressionLine", false) && measurementView instanceof WeightMeasurementView) {
                        polyFitter.addPoint((double)avgValue.getX(), (double)avgValue.getY());
                    }

                    if (avgBin.size() > 1) {
                        avgValue.setLabel(String.format("Ø %.2f", avgValue.getY()));
                    }

                    if (avgValue.getY() > maxYValue) {
                        maxYValue = avgValue.getY();
                    }

                    valuesStack.push(avgValue);
                    pointIndexScaleMeasurementList.add(indexScaleMeasurement[i]);
                }

                Line diagramLine = new Line(valuesStack).
                        setColor(measurementView.getColor()).
                        setHasLabels(prefs.getBoolean("labelsEnable", true)).
                        setHasPoints(prefs.getBoolean("pointsEnable", true)).
                        setFormatter(new SimpleLineChartValueFormatter(1));

                if (measurementView.isVisible()) {
                    addFloatingActionButton(measurementView);

                    if (measurementView.getSettings().isInGraph()) {
                        diagramLineList.add(diagramLine);
                    }
                }
            }
        }

        LineChartData lineData = new LineChartData(diagramLineList);
        lineData.setAxisXBottom(new Axis(axisValues).
                setHasLines(true).
                setTextColor(textColor)
        );

        lineData.setAxisYLeft(new Axis().
                setHasLines(true).
                setMaxLabelChars(5).
                setTextColor(textColor)
        );

        chartBottom.setLineChartData(lineData);

        defaultTopViewport = new Viewport(calDays.getActualMinimum(field), chartBottom.getCurrentViewport().top, calDays.getMaximum(field)+1, chartBottom.getCurrentViewport().bottom);

        if (prefs.getBoolean("goalLine", true)) {
            Stack<PointValue> valuesGoalLine = new Stack<>();

            final ScaleUser user = openScale.getSelectedScaleUser();
            float goalWeight = Converters.fromKilogram(user.getGoalWeight(), user.getScaleUnit());

            valuesGoalLine.push(new PointValue(0, goalWeight));
            valuesGoalLine.push(new PointValue(maxDays, goalWeight));

            Line goalLine = new Line(valuesGoalLine)
                    .setHasPoints(false);

            goalLine.setPathEffect(new DashPathEffect(new float[] {10,30}, 0));

            diagramLineList.add(goalLine);
        }

        if (prefs.getBoolean("regressionLine", false)) {
            PolynomialFitter.Polynomial polynomial = polyFitter.getBestFit();

            Stack<PointValue> valuesLinearRegression = new Stack<>();

            for (int i = 0; i < maxDays; i++) {
                    double y_value = polynomial.getY(i);
                    valuesLinearRegression.push(new PointValue((float) i, (float) y_value));
            }

            Line linearRegressionLine = new Line(valuesLinearRegression)
                    .setColor(ChartUtils.COLOR_VIOLET)
                    .setHasPoints(false)
                    .setCubic(true);

            linearRegressionLine.setPathEffect(new DashPathEffect(new float[]{10, 30}, 0));

            diagramLineList.add(linearRegressionLine);
        }

        chartBottom.setLineChartData(lineData);

        chartBottom.setCurrentViewport(defaultTopViewport);
        chartBottom.setMaximumViewport(new Viewport(0, maxYValue + (maxYValue / 100) * 20, calDays.getMaximum(field)+1, 0));
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

        SimpleDateFormat month_date = new SimpleDateFormat("MMM", Locale.getDefault());

        List<AxisValue> axisValues = new ArrayList<>();
        List<Column> columns = new ArrayList<>();

        for (int i=0; i<12; i++) {
            String month_name = month_date.format(calMonths.getTime());

            axisValues.add(new AxisValue(i, month_name.toCharArray()));
            List<SubcolumnValue> values = new ArrayList<>();
            values.add(new SubcolumnValue(normNumOfMonth[i], ChartUtils.COLORS[i % ChartUtils.COLORS.length]).setLabel(Integer.toString(numOfMonth[i])));

            columns.add(new Column(values).setHasLabelsOnlyForSelected(true));

            calMonths.add(Calendar.MONTH, 1);
        }

        ColumnChartData columnData = new ColumnChartData(columns);

        columnData.setAxisXBottom(new Axis(axisValues).setHasLines(true).setTextColor(textColor));

        chartTop.setColumnChartData(columnData);
        chartTop.setValueSelectionEnabled(true);
        chartTop.setZoomEnabled(false);
        chartTop.selectValue(new SelectedValue(calLastSelected.get(Calendar.MONTH), 0, SelectedValue.SelectedValueType.COLUMN));
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

    private class chartTopValueTouchListener implements ColumnChartOnValueSelectListener {
        @Override
        public void onValueSelected(int selectedLine, int selectedValue, SubcolumnValue value) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.MONTH, Calendar.JANUARY);
            cal.add(Calendar.MONTH, selectedLine);

            calLastSelected = cal;

            List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleDataOfMonth(calYears.get(Calendar.YEAR), calLastSelected.get(Calendar.MONTH));
            generateLineData(Calendar.DAY_OF_MONTH, scaleMeasurementList);
        }

        @Override
        public void onValueDeselected() {

        }
    }

    private class chartBottomListener implements View.OnTouchListener {
        final GestureDetector gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            public void onLongPress(MotionEvent e) {
                chartBottom.setCurrentViewport(defaultTopViewport);
            }
        });

        @Override
        public boolean onTouch (View v, MotionEvent event) {
            return gestureDetector.onTouchEvent(event);
        }
    }

    private class chartBottomValueTouchListener implements LineChartOnValueSelectListener {
        @Override
        public void onValueSelected(int lineIndex, int pointIndex, PointValue pointValue) {
            ScaleMeasurement scaleMeasurement = pointIndexScaleMeasurementList.get(pointIndex);

            int id = scaleMeasurement.getId();

            Intent intent = new Intent(graphView.getContext(), DataEntryActivity.class);
            intent.putExtra(DataEntryActivity.EXTRA_ID, id);
            startActivity(intent);
        }

        @Override
        public void onValueDeselected() {

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
}
