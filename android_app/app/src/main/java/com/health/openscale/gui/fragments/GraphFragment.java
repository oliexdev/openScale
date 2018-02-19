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

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.utils.PolynomialFitter;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.views.FloatMeasurementView;
import com.health.openscale.gui.views.MeasurementView;

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
import lecho.lib.hellocharts.model.ValueShape;
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
    private ImageView optionMenu;
    private PopupMenu popup;
    private SharedPreferences prefs;

    private List<MeasurementView> measurementViews;

    private int textColor;

    private OpenScale openScale;

    private Calendar calYears;
    private Calendar calLastSelected;

    private static String CAL_YEARS_KEY = "calYears";
    private static String CAL_LAST_SELECTED_KEY = "calLastSelected";

    private List<ScaleMeasurement> pointIndexScaleMeasurementList;

    public GraphFragment() {
        calYears = Calendar.getInstance();
        calLastSelected = Calendar.getInstance();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        openScale = OpenScale.getInstance(getContext());

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

        chartBottom = (LineChartView) graphView.findViewById(R.id.chart_bottom);
        chartTop = (ColumnChartView) graphView.findViewById(R.id.chart_top);

        chartBottom.setOnTouchListener(new chartBottomListener());
        chartBottom.setOnValueTouchListener(new chartBottomValueTouchListener());
        chartTop.setOnValueTouchListener(new chartTopValueTouchListener());

        // HACK: get default text color from hidden text view to set the correct axis colors
        textColor = ((TextView)graphView.findViewById(R.id.colorHack)).getCurrentTextColor();

        txtYear = (TextView) graphView.findViewById(R.id.txtYear);
        txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));

        floatingActionBar = (LinearLayout) graphView.findViewById(R.id.floatingActionBar);

        optionMenu = (ImageView) graphView.findViewById(R.id.optionMenu);
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
                        OpenScale.getInstance(getContext()).getScaleDataOfYear(calYears.get(Calendar.YEAR));
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
                        OpenScale.getInstance(getContext()).getScaleDataOfYear(calYears.get(Calendar.YEAR));
                if (!scaleMeasurementList.isEmpty()) {
                    calLastSelected.setTime(scaleMeasurementList.get(scaleMeasurementList.size() - 1).getDateTime());
                }
                generateGraphs();
            }
        });

        measurementViews = MeasurementView.getMeasurementList(getContext());

        popup = new PopupMenu(getContext(), optionMenu);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {


                switch (item.getItemId()) {
                    case R.id.enableMonth:
                        if (item.isChecked()) {
                            item.setChecked(false);
                            prefs.edit().putBoolean("showMonth", false).commit();
                        } else {
                            item.setChecked(true);
                            prefs.edit().putBoolean("showMonth", true).commit();
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

        openScale.registerFragment(this);

        return graphView;
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

        actionButton.setTag("actionButton" + measurementView.getName());
        actionButton.setImageDrawable(measurementView.getIcon());
        actionButton.setClickable(true);
        actionButton.setSize(android.support.design.widget.FloatingActionButton.SIZE_MINI);
        RelativeLayout.LayoutParams lay = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lay.setMargins(0,5,20,10);
        actionButton.setLayoutParams(lay);
        actionButton.setOnClickListener(new onClickListenerDiagramLines());

        if (prefs.getBoolean(String.valueOf("actionButton" + measurementView.getName()), true)) {
            actionButton.setBackgroundTintList(ColorStateList.valueOf(measurementView.getColor()));
        } else {
            actionButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d3d3d3")));
        }

        floatingActionBar.addView(actionButton);
    }

    /**
     * Add a point to a point value stack.
     *
     * Average y value of point if x value is already on the stack and option "averageData" is enabled.
     *
     * @param pointValues stack of point values
     * @param value_x x value of the point
     * @param value_y y value of the point
     * @return true if a new point was added otherwise false if point was average added to an existing point
     */
    private boolean addPointValue(Stack<PointValue> pointValues, float value_x, float value_y) {
        if (prefs.getBoolean("averageData", true) && !pointValues.isEmpty() && pointValues.peek().getX() == value_x) {
            pointValues.push(new PointValue(value_x, (pointValues.pop().getY() + value_y) / 2.0f));
        } else {
            if (value_y != 0.0f) { // don't show zero values
                pointValues.add(new PointValue(value_x, value_y));
                return true;
            }
        }

        return false;
    }

    private void generateLineData(int field, List<ScaleMeasurement> scaleMeasurementList)
    {
        SimpleDateFormat day_date = new SimpleDateFormat("D", Locale.getDefault());

        if (field == Calendar.DAY_OF_MONTH) {
            day_date = new SimpleDateFormat("dd", Locale.getDefault());
        } else if (field == Calendar.DAY_OF_YEAR) {
            day_date = new SimpleDateFormat("D", Locale.getDefault());

            if (prefs.getBoolean("averageData", true)) {
                field = Calendar.MONTH;
                day_date = new SimpleDateFormat("MMM", Locale.getDefault());
            }
        }

        Calendar calDays = (Calendar)calLastSelected.clone();

        calDays.set(field, calDays.getActualMinimum(field));
        int maxDays = calDays.getActualMaximum(field);

        List<AxisValue> axisValues = new ArrayList<AxisValue>();

        for (int i=0; i<maxDays+1; i++) {
            String day_name = day_date.format(calDays.getTime());

            axisValues.add(new AxisValue(i+calDays.getActualMinimum(field), day_name.toCharArray()));

            calDays.add(field, 1);
        }

        List<Line> diagramLineList = new ArrayList<Line>();

        Calendar calDB = Calendar.getInstance();

        pointIndexScaleMeasurementList = new ArrayList<>();

        floatingActionBar.removeAllViews();

        for (MeasurementView view : measurementViews) {
            if (view instanceof FloatMeasurementView) {
                FloatMeasurementView measurementView = (FloatMeasurementView) view;

                if (measurementView.getName().equals(getString(R.string.label_bmr))) {
                    continue;
                }

                measurementView.updatePreferences(prefs);
                Stack<PointValue> valuesStack = new Stack<PointValue>();

                for (ScaleMeasurement measurement : scaleMeasurementList) {
                    measurementView.loadFrom(measurement, null);

                    calDB.setTime(measurement.getDateTime());

                    if (addPointValue(valuesStack, calDB.get(field), measurementView.getValue())) {
                        pointIndexScaleMeasurementList.add(measurement); // if new point was added, add this point to pointIndexScaleDataList to get the correct point index after selecting an point
                    }
                }

                Line diagramLine = new Line(valuesStack).
                        setColor(measurementView.getColor()).
                        setHasLabels(prefs.getBoolean("labelsEnable", true)).
                        setHasPoints(prefs.getBoolean("pointsEnable", true)).
                        setFormatter(new SimpleLineChartValueFormatter(1));

                if (measurementView.isVisible()) {
                    addFloatingActionButton(measurementView);

                    if (prefs.getBoolean(String.valueOf("actionButton" + measurementView.getName()), true)) {
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

        defaultTopViewport = new Viewport(calDays.getActualMinimum(field), chartBottom.getCurrentViewport().top, maxDays+1, chartBottom.getCurrentViewport().bottom);

        if (prefs.getBoolean("goalLine", true)) {
            Stack<PointValue> valuesGoalLine = new Stack<PointValue>();

            float goalWeight = openScale.getSelectedScaleUser().getGoalWeight();

            valuesGoalLine.push(new PointValue(0, goalWeight));
            valuesGoalLine.push(new PointValue(maxDays, goalWeight));

            Line goalLine = new Line(valuesGoalLine)
                    .setHasPoints(false);

            goalLine.setPathEffect(new DashPathEffect(new float[] {10,30}, 0));

            diagramLineList.add(goalLine);
        }

        if (prefs.getBoolean("regressionLine", false)) {
            PolynomialFitter polyFitter = new PolynomialFitter(Integer.parseInt(prefs.getString("regressionLineOrder", "1")));

            Stack<PointValue> valuesWeight = new Stack<PointValue>();

            for (ScaleMeasurement measurement : scaleMeasurementList) {
                addPointValue(valuesWeight, calDB.get(field), measurement.getConvertedWeight(openScale.getSelectedScaleUser().getScaleUnit()));
                polyFitter.addPoint(calDB.get(field), measurement.getConvertedWeight(openScale.getSelectedScaleUser().getScaleUnit()));
            }

            PolynomialFitter.Polynomial polynom = polyFitter.getBestFit();

            Stack<PointValue> valuesLinearRegression = new Stack<PointValue>();

            if (!valuesWeight.isEmpty()) {
                for (int i = (int)valuesWeight.peek().getX(); i <= maxDays; i++) {
                    double y_value = polynom.getY(i);
                    valuesLinearRegression.push(new PointValue((float) i, (float) y_value));
                }
            }
            Line linearRegressionLine = new Line(valuesLinearRegression)
                    .setColor(ChartUtils.COLOR_VIOLET)
                    .setHasPoints(false);

            linearRegressionLine.setPathEffect(new DashPathEffect(new float[] {10,30}, 0));

            diagramLineList.add(linearRegressionLine);
        }

        if( prefs.getBoolean("meanLables",false) && (scaleMeasurementList.size()!=0)){

            Stack<PointValue> valuesMean = new Stack<>();

            ScaleMeasurement lastScaleEntry = scaleMeasurementList.get(0);
            calDB.setTime(lastScaleEntry.getDateTime());
            int maxDay;
            int fieldMean;

            if (field==Calendar.MONTH)
            {
                fieldMean = Calendar.DAY_OF_YEAR;
            }
            else
            {
                fieldMean = field;
            }

            maxDay = calDB.get(fieldMean);
            int numberOfMeanPoints = maxDay/7;
            float[] meanSums = new float[numberOfMeanPoints];
            int[]   meanSamples = new int[numberOfMeanPoints];


            for (ScaleMeasurement scaleEntry: scaleMeasurementList) {
                calDB.setTime(scaleEntry.getDateTime());
                int index = (maxDay - calDB.get(fieldMean))/7;
                if(index<numberOfMeanPoints) { // do not add Mean Value if there are less than 7 days left
                    meanSums[index] += scaleEntry.getConvertedWeight(openScale.getSelectedScaleUser().getScaleUnit());
                    meanSamples[index] +=1;
                }
            }

            float scaleFactor = 1.0f;
            float scaleOffset = 0.0f;

            if(field==Calendar.MONTH){
                scaleFactor = 12.0f /calDB.getActualMaximum(Calendar.DAY_OF_YEAR);
                scaleOffset = -1.0f;
            }

            for(int i=0;i<numberOfMeanPoints;i++){
                float actMean = meanSums[i]/(meanSamples[i]+0.0f);

                PointValue actValue = new PointValue((maxDay-i*7)*scaleFactor+scaleOffset,actMean);
                actValue.setLabel(String.format ("Ø  %.2f(%d)", actMean,meanSamples[i]));
                valuesMean.add(actValue);

                if(defaultTopViewport.left > actValue.getX()) {
                    defaultTopViewport.left = actValue.getX();
                }

                if(defaultTopViewport.bottom > actValue.getY()) {
                    defaultTopViewport.bottom = actValue.getY();
                }

                if(defaultTopViewport.right < actValue.getX()) {
                    defaultTopViewport.right = actValue.getX();
                }

                if(defaultTopViewport.top < actValue.getY()) {
                    defaultTopViewport.top = actValue.getY();
                }

            }

            Line meanLine = new Line(valuesMean)
                    .setColor(ChartUtils.COLOR_VIOLET)
                    .setStrokeWidth(0)
                    .setHasPoints(true)
                    .setHasLabels(true)
                    .setShape(ValueShape.DIAMOND)
                    .setHasLines(false)
                    .setFilled(false);

            diagramLineList.add(meanLine);
        }

        chartBottom.setLineChartData(lineData);
        chartBottom.setCurrentViewport(defaultTopViewport);
    }

    private void generateColumnData()
    {
        int[] numOfMonth = openScale.getCountsOfMonth(calYears.get(Calendar.YEAR));

        Calendar calMonths = Calendar.getInstance();
        calMonths.set(Calendar.MONTH, Calendar.JANUARY);

        SimpleDateFormat month_date = new SimpleDateFormat("MMM", Locale.getDefault());

        List<AxisValue> axisValues = new ArrayList<AxisValue>();
        List<Column> columns = new ArrayList<Column>();

        for (int i=0; i<12; i++) {
            String month_name = month_date.format(calMonths.getTime());

            axisValues.add(new AxisValue(i, month_name.toCharArray()));
            List<SubcolumnValue> values = new ArrayList<SubcolumnValue>();
            values.add(new SubcolumnValue(numOfMonth[i], ChartUtils.COLORS[i % ChartUtils.COLORS.length]));

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
            chartBottom.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

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

            List<ScaleMeasurement> scaleMeasurementList =
                    openScale.getScaleDataOfMonth(calYears.get(Calendar.YEAR), calLastSelected.get(Calendar.MONTH));
            generateLineData(Calendar.DAY_OF_MONTH, scaleMeasurementList);
        }

        @Override
        public void onValueDeselected() {

        }
    }

    private class chartBottomListener implements View.OnTouchListener {
        final GestureDetector gestureDetector = new GestureDetector(new GestureDetector.SimpleOnGestureListener() {
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

            if(pointValue.getLabelAsChars()!=null){
                return; // we have a label so we do not make a lookup in the  MeasurementList
            }

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

            if (prefs.getBoolean(String.valueOf(actionButton.getTag()), true)) {
                prefs.edit().putBoolean(String.valueOf(actionButton.getTag()), false).commit();
            } else {
                prefs.edit().putBoolean(String.valueOf(actionButton.getTag()), true).commit();
            }

            generateGraphs();
        }
    }
}
