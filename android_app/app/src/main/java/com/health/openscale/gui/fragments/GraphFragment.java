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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.gui.activities.DataEntryActivity;

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
    private FloatingActionButton diagramWeight;
    private FloatingActionButton diagramFat;
    private FloatingActionButton diagramWater;
    private FloatingActionButton diagramMuscle;
    private FloatingActionButton diagramWaist;
    private FloatingActionButton diagramHip;
    private FloatingActionButton enableMonth;
    private SharedPreferences prefs;

    private OpenScale openScale;

    private Calendar calYears;
    private Calendar calLastSelected;

    private ArrayList<ScaleData> scaleDataList;
    private ArrayList<ScaleData> pointIndexScaleDataList;

	public GraphFragment() {
        calYears = Calendar.getInstance();
        calLastSelected = Calendar.getInstance();
    }

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) 
	{
		graphView = inflater.inflate(R.layout.fragment_graph, container, false);
		
		chartBottom = (LineChartView) graphView.findViewById(R.id.chart_bottom);
        chartTop = (ColumnChartView) graphView.findViewById(R.id.chart_top);

        chartBottom.setOnTouchListener(new chartBottomListener());
        chartBottom.setOnValueTouchListener(new chartBottomValueTouchListener());
        chartTop.setOnValueTouchListener(new chartTopValueTouchListener());

        txtYear = (TextView) graphView.findViewById(R.id.txtYear);
        txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));

        diagramWeight = (FloatingActionButton) graphView.findViewById(R.id.diagramWeight);
        diagramFat = (FloatingActionButton) graphView.findViewById(R.id.diagramFat);
        diagramWater = (FloatingActionButton) graphView.findViewById(R.id.diagramWater);
        diagramMuscle = (FloatingActionButton) graphView.findViewById(R.id.diagramMuscle);
        diagramWaist = (FloatingActionButton) graphView.findViewById(R.id.diagramWaist);
        diagramHip = (FloatingActionButton) graphView.findViewById(R.id.diagramHip);

        enableMonth = (FloatingActionButton) graphView.findViewById(R.id.enableMonth);

        diagramWeight.setOnClickListener(new onClickListenerDiagramLines());
        diagramFat.setOnClickListener(new onClickListenerDiagramLines());
        diagramWater.setOnClickListener(new onClickListenerDiagramLines());
        diagramMuscle.setOnClickListener(new onClickListenerDiagramLines());
        diagramWaist.setOnClickListener(new onClickListenerDiagramLines());
        diagramHip.setOnClickListener(new onClickListenerDiagramLines());

        enableMonth.setOnClickListener(new onClickListenerDiagramLines());

        prefs = PreferenceManager.getDefaultSharedPreferences(graphView.getContext());

        if(!prefs.getBoolean("weightEnable", true)) {
            diagramWeight.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("fatEnable", true)) {
            diagramFat.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("waterEnable", true)) {
            diagramWater.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("muscleEnable", true)) {
            diagramMuscle.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("waistEnable", false)) {
            diagramWaist.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("hipEnable", false)) {
            diagramHip.setVisibility(View.GONE);
        }

        graphView.findViewById(R.id.btnLeftYear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                calYears.roll(Calendar.YEAR, false);
                txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));
                updateOnView(null);
            }
        });

        graphView.findViewById(R.id.btnRightYear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                calYears.roll(Calendar.YEAR, true);
                txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));
                updateOnView(null);
            }
        });

        openScale = OpenScale.getInstance(getContext());
        openScale.registerFragment(this);

		return graphView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDataList)
	{
        generateGraphs();
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

    private void generateLineData(int field)
    {
        SimpleDateFormat day_date = new SimpleDateFormat("D", Locale.getDefault());

        if (field == Calendar.DAY_OF_MONTH) {
            day_date = new SimpleDateFormat("dd", Locale.getDefault());
        } else if (field == Calendar.MONTH) {
            day_date = new SimpleDateFormat("MMM", Locale.getDefault());
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

        Stack<PointValue> valuesWeight = new Stack<PointValue>();
        Stack<PointValue> valuesFat = new Stack<PointValue>();
        Stack<PointValue> valuesWater = new Stack<PointValue>();
        Stack<PointValue> valuesMuscle = new Stack<PointValue>();
        Stack<PointValue> valuesWaist = new Stack<PointValue>();
        Stack<PointValue> valuesHip = new Stack<PointValue>();
        List<Line> lines = new ArrayList<Line>();

        Calendar calDB = Calendar.getInstance();

        pointIndexScaleDataList = new ArrayList<>();

        for(ScaleData scaleEntry: scaleDataList)
        {
            calDB.setTime(scaleEntry.getDateTime());

            if (addPointValue(valuesWeight, calDB.get(field), scaleEntry.getConvertedWeight(openScale.getSelectedScaleUser().scale_unit))) {
                pointIndexScaleDataList.add(scaleEntry); // if new point was added, add this point to pointIndexScaleDataList to get the correct point index after selecting an point
            }

            addPointValue(valuesFat, calDB.get(field), scaleEntry.getFat());
            addPointValue(valuesWater, calDB.get(field), scaleEntry.getWater());
            addPointValue(valuesMuscle, calDB.get(field), scaleEntry.getMuscle());
            addPointValue(valuesWaist, calDB.get(field), scaleEntry.getWaist());
            addPointValue(valuesHip, calDB.get(field), scaleEntry.getHip());
        }


        Line lineWeight = new Line(valuesWeight).
                setColor(ChartUtils.COLOR_VIOLET).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setHasPoints(prefs.getBoolean("pointsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));
        Line lineFat = new Line(valuesFat).
                setColor(ChartUtils.COLOR_ORANGE).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setHasPoints(prefs.getBoolean("pointsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));
        Line lineWater = new Line(valuesWater).
                setColor(ChartUtils.COLOR_BLUE).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setHasPoints(prefs.getBoolean("pointsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));
        Line lineMuscle = new Line(valuesMuscle).
                setColor(ChartUtils.COLOR_GREEN).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setHasPoints(prefs.getBoolean("pointsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));
        Line lineWaist = new Line(valuesWaist).
                setColor(Color.MAGENTA).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setHasPoints(prefs.getBoolean("pointsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));
        Line lineHip = new Line(valuesHip).
                setColor(Color.YELLOW).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setHasPoints(prefs.getBoolean("pointsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));


        if(prefs.getBoolean("weightEnable", true) && prefs.getBoolean(String.valueOf(diagramWeight.getId()), true)) {
            lines.add(lineWeight);
            diagramWeight.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_VIOLET));
        } else {
            diagramWeight.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d3d3d3")));
        }

        if(prefs.getBoolean("fatEnable", true) && prefs.getBoolean(String.valueOf(diagramFat.getId()), true)) {
            lines.add(lineFat);
            diagramFat.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_ORANGE));
        } else {
            diagramFat.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d3d3d3")));
        }

        if(prefs.getBoolean("waterEnable", true) && prefs.getBoolean(String.valueOf(diagramWater.getId()), true)) {
            lines.add(lineWater);
            diagramWater.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_BLUE));
        } else {
            diagramWater.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d3d3d3")));
        }

        if(prefs.getBoolean("muscleEnable", true) && prefs.getBoolean(String.valueOf(diagramMuscle.getId()), true)) {
            lines.add(lineMuscle);
            diagramMuscle.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_GREEN));
        } else {
            diagramMuscle.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d3d3d3")));
        }

        if(prefs.getBoolean("waistEnable", false) && prefs.getBoolean(String.valueOf(diagramWaist.getId()), true)) {
            lines.add(lineWaist);
            diagramWaist.setBackgroundTintList(ColorStateList.valueOf(Color.MAGENTA));
        } else {
            diagramWaist.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d3d3d3")));
        }

        if(prefs.getBoolean("hipEnable", false) && prefs.getBoolean(String.valueOf(diagramHip.getId()), true)) {
            lines.add(lineHip);
            diagramHip.setBackgroundTintList(ColorStateList.valueOf(Color.YELLOW));
        } else {
            diagramHip.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d3d3d3")));
        }

        if(prefs.getBoolean(String.valueOf(enableMonth.getId()), true)) {
            enableMonth.setBackgroundTintList(ColorStateList.valueOf(ChartUtils.COLOR_BLUE));
        } else {
            enableMonth.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d3d3d3")));
        }

        if (prefs.getBoolean("goalLine", true)) {
            Stack<PointValue> valuesGoalLine = new Stack<PointValue>();

            float goalWeight = openScale.getSelectedScaleUser().goal_weight;

            valuesGoalLine.push(new PointValue(0, goalWeight));
            valuesGoalLine.push(new PointValue(31, goalWeight));

            Line goalLine = new Line(valuesGoalLine)
                    .setHasPoints(false);

            goalLine.setPathEffect(new DashPathEffect(new float[] {10,30}, 0));

            lines.add(goalLine);
        }

        if (prefs.getBoolean("regressionLine", true)) {

            /*
            // quadratic regression y = ax^2 + bx + c
            double x_value = 0.0;
            double y_value = 0.0;

            double s40 = 0; //sum of x^4
            double s30 = 0; //sum of x^3
            double s20 = 0; //sum of x^2
            double s10 = 0;  //sum of x
            double s00 = scaleDataList.size();
            //sum of x^0 * y^0  ie 1 * number of entries

            double s21 = 0; //sum of x^2*y
            double s11 = 0;  //sum of x*y
            double s01 = 0;   //sum of y

            for(ScaleData scaleEntry: scaleDataList) {
                calDB.setTime(scaleEntry.getDateTime());

                x_value = calDB.get(field);
                y_value = scaleEntry.getConvertedWeight(openScale.getSelectedScaleUser().scale_unit);

                s40 += Math.pow(x_value, 4);
                s30 += Math.pow(x_value, 3);
                s20 += Math.pow(x_value, 2);
                s10 += x_value;

                s21 += Math.pow(x_value, 2) * y_value;
                s11 += x_value * y_value;
                s01 += y_value;
            }

            // solve equations using Cramer's law
            double a = (s21*(s20 * s00 - s10 * s10) -
                        s11*(s30 * s00 - s10 * s20) +
                        s01*(s30 * s10 - s20 * s20))
                        /
                        (s40*(s20 * s00 - s10 * s10) -
                        s30*(s30 * s00 - s10 * s20) +
                        s20*(s30 * s10 - s20 * s20));

            double b = (s40*(s11 * s00 - s01 * s10) -
                        s30*(s21 * s00 - s01 * s20) +
                        s20*(s21 * s10 - s11 * s20))
                        /
                        (s40 * (s20 * s00 - s10 * s10) -
                        s30 * (s30 * s00 - s10 * s20) +
                        s20 * (s30 * s10 - s20 * s20));

            double c = (s40*(s20 * s01 - s10 * s11) -
                        s30*(s30 * s01 - s10 * s21) +
                        s20*(s30 * s11 - s20 * s21))
                        /
                        (s40 * (s20 * s00 - s10 * s10) -
                        s30 * (s30 * s00 - s10 * s20) +
                        s20 * (s30 * s10 - s20 * s20));
            */

            // linear regression y = a + x*b
            double sumx = 0.0;
            double sumy = 0.0;

            double x_value = 0.0;
            double y_value = 0.0;

            for(ScaleData scaleEntry: scaleDataList) {
                calDB.setTime(scaleEntry.getDateTime());

                x_value = calDB.get(field);
                y_value = scaleEntry.getConvertedWeight(openScale.getSelectedScaleUser().scale_unit);

                sumx += x_value;
                sumy += y_value;
            }

            double xbar = sumx / scaleDataList.size();
            double ybar = sumy / scaleDataList.size();

            double xxbar = 0.0;
            double xybar = 0.0;

            for(ScaleData scaleEntry: scaleDataList) {
                calDB.setTime(scaleEntry.getDateTime());

                x_value = calDB.get(field);
                y_value = scaleEntry.getConvertedWeight(openScale.getSelectedScaleUser().scale_unit);

                xxbar += (x_value - xbar) * (x_value - xbar);
                xybar += (y_value - xbar) * (y_value - ybar);
            }

            double b = xybar / xxbar;
            double a = ybar - b * xbar;


            Stack<PointValue> valuesLinearRegression = new Stack<PointValue>();

            for (int i = 0; i < 31; i++) {
                y_value = a + b * i; // linear regression
                //y_value = a * i*i + b * i + c; // quadratic regression

                valuesLinearRegression.push(new PointValue((float) i, (float) y_value));
            }

            Line linearRegressionLine = new Line(valuesLinearRegression)
                    .setColor(ChartUtils.COLOR_VIOLET)
                    .setHasPoints(false);

            linearRegressionLine.setPathEffect(new DashPathEffect(new float[] {10,30}, 0));

            lines.add(linearRegressionLine);
        }

        LineChartData lineData = new LineChartData(lines);
        lineData.setAxisXBottom(new Axis(axisValues).
                setHasLines(true).
                setTextColor(Color.BLACK)
        );

        lineData.setAxisYLeft(new Axis().
                setHasLines(true).
                setMaxLabelChars(5).
                setTextColor(Color.BLACK)
        );

        chartBottom.setLineChartData(lineData);
        defaultTopViewport = new Viewport(0, chartBottom.getCurrentViewport().top+4, axisValues.size()-1, chartBottom.getCurrentViewport().bottom-4);

        chartBottom.setMaximumViewport(defaultTopViewport);
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

        columnData.setAxisXBottom(new Axis(axisValues).setHasLines(true).setTextColor(Color.BLACK));

        chartTop.setColumnChartData(columnData);
        chartTop.setValueSelectionEnabled(true);
        chartTop.setZoomEnabled(false);
        chartTop.selectValue(new SelectedValue(calLastSelected.get(Calendar.MONTH), 0, SelectedValue.SelectedValueType.COLUMN));
    }

    private void generateGraphs() {
        // show monthly diagram
        if (prefs.getBoolean(String.valueOf(enableMonth.getId()), true)) {
            chartTop.setVisibility(View.VISIBLE);
            chartBottom.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.7f));

            generateColumnData();
            scaleDataList = openScale.getScaleDataOfMonth(calYears.get(Calendar.YEAR), calLastSelected.get(Calendar.MONTH));

            generateLineData(Calendar.DAY_OF_MONTH);
        // show only yearly diagram and hide monthly diagram
        } else {
            chartTop.setVisibility(View.GONE);
            chartBottom.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

            scaleDataList = openScale.getScaleDataOfYear(calYears.get(Calendar.YEAR));

            generateLineData(Calendar.MONTH);
        }
    }

    private class chartTopValueTouchListener implements ColumnChartOnValueSelectListener {
        @Override
        public void onValueSelected(int selectedLine, int selectedValue, SubcolumnValue value) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.MONTH, Calendar.JANUARY);
            cal.add(Calendar.MONTH, selectedLine);

            calLastSelected = cal;

            scaleDataList = openScale.getScaleDataOfMonth(calYears.get(Calendar.YEAR), calLastSelected.get(Calendar.MONTH));
            generateLineData(Calendar.DAY_OF_MONTH);
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
            ScaleData scaleData = pointIndexScaleDataList.get(pointIndex);

            long id = scaleData.getId();

            Intent intent = new Intent(graphView.getContext(), DataEntryActivity.class);
            intent.putExtra("id", id);
            startActivityForResult(intent, 1);
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

            if (prefs.getBoolean(String.valueOf(actionButton.getId()), true)) {
                prefs.edit().putBoolean(String.valueOf(actionButton.getId()), false).commit();
            } else {
                prefs.edit().putBoolean(String.valueOf(actionButton.getId()), true).commit();
            }

            generateGraphs();
        }
    }
}
