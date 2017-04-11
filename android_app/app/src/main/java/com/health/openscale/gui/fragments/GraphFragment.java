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

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.Fragment;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
	private LineChartView chartTop;
    private ColumnChartView chartBottom;
    private Viewport defaultTopViewport;
    private TextView txtYear;
    private FloatingActionButton diagramWeight;
    private FloatingActionButton diagramFat;
    private FloatingActionButton diagramWater;
    private FloatingActionButton diagramMuscle;
    private FloatingActionButton diagramWaist;
    private FloatingActionButton diagramHip;
    private SharedPreferences prefs;

    private OpenScale openScale;

    private Calendar calYears;
    private Calendar calLastSelected;

    private ArrayList<ScaleData> scaleDataList;

	public GraphFragment() {
        calYears = Calendar.getInstance();
        calLastSelected = Calendar.getInstance();
    }

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) 
	{
		graphView = inflater.inflate(R.layout.fragment_graph, container, false);
		
		chartTop = (LineChartView) graphView.findViewById(R.id.chart_top);
        chartBottom = (ColumnChartView) graphView.findViewById(R.id.chart_bottom);

        chartTop.setOnTouchListener(new ChartTopListener());
        chartTop.setOnValueTouchListener(new ChartTopValueTouchListener());
        chartBottom.setOnValueTouchListener(new ChartBottomValueTouchListener());

        txtYear = (TextView) graphView.findViewById(R.id.txtYear);
        txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));

        diagramWeight = (FloatingActionButton) graphView.findViewById(R.id.diagramWeight);
        diagramFat = (FloatingActionButton) graphView.findViewById(R.id.diagramFat);
        diagramWater = (FloatingActionButton) graphView.findViewById(R.id.diagramWater);
        diagramMuscle = (FloatingActionButton) graphView.findViewById(R.id.diagramMuscle);
        diagramWaist = (FloatingActionButton) graphView.findViewById(R.id.diagramWaist);
        diagramHip = (FloatingActionButton) graphView.findViewById(R.id.diagramHip);

        diagramWeight.setOnClickListener(new onClickListenerDiagramLines());
        diagramFat.setOnClickListener(new onClickListenerDiagramLines());
        diagramWater.setOnClickListener(new onClickListenerDiagramLines());
        diagramMuscle.setOnClickListener(new onClickListenerDiagramLines());
        diagramWaist.setOnClickListener(new onClickListenerDiagramLines());
        diagramHip.setOnClickListener(new onClickListenerDiagramLines());

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

        openScale = OpenScale.getInstance(graphView.getContext());
        openScale.registerFragment(this);

		return graphView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDataList)
	{
        generateColumnData();
	}

    private void generateLineData(Calendar calMonth)
    {
        scaleDataList = openScale.getScaleDataOfMonth(calYears.get(Calendar.YEAR), calMonth.get(Calendar.MONTH));

        SimpleDateFormat day_date = new SimpleDateFormat("dd", Locale.getDefault());

        Calendar calDays = (Calendar)calMonth.clone();

        calDays.set(Calendar.DAY_OF_MONTH, 1);
        int maxDays = calDays.getActualMaximum(Calendar.DAY_OF_MONTH);

        List<AxisValue> axisValues = new ArrayList<AxisValue>();

        for (int i=0; i<maxDays; i++) {
            String day_name = day_date.format(calDays.getTime());

            axisValues.add(new AxisValue(i, day_name.toCharArray()));

            calDays.add(Calendar.DAY_OF_MONTH, 1);
        }

        List<PointValue> valuesWeight = new ArrayList<PointValue>();
        List<PointValue> valuesFat = new ArrayList<PointValue>();
        List<PointValue> valuesWater = new ArrayList<PointValue>();
        List<PointValue> valuesMuscle = new ArrayList<PointValue>();
        List<PointValue> valuesWaist = new ArrayList<PointValue>();
        List<PointValue> valuesHip = new ArrayList<PointValue>();
        List<Line> lines = new ArrayList<Line>();

        Calendar calDB = Calendar.getInstance();

        for(ScaleData scaleEntry: scaleDataList)
        {
            calDB.setTime(scaleEntry.date_time);

            valuesWeight.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.weight));
            valuesFat.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.fat));
            valuesWater.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.water));
            valuesMuscle.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.muscle));
            valuesWaist.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.waist));
            valuesHip.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.hip));

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

        LineChartData lineData = new LineChartData(lines);
        lineData.setAxisXBottom(new Axis(axisValues).
                setHasLines(true).
                setTextColor(Color.BLACK)
        );

        lineData.setAxisYLeft(new Axis().
                setHasLines(true).
                setMaxLabelChars(3).
                setTextColor(Color.BLACK)
        );



        chartTop.setLineChartData(lineData);

        defaultTopViewport = new Viewport(0, chartTop.getCurrentViewport().top+4, maxDays-1, chartTop.getCurrentViewport().bottom-4);

        chartTop.setMaximumViewport(defaultTopViewport);
        chartTop.setCurrentViewport(defaultTopViewport);
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

        chartBottom.setColumnChartData(columnData);
        chartBottom.setValueSelectionEnabled(true);
        chartBottom.setZoomEnabled(false);
        chartBottom.selectValue(new SelectedValue(calLastSelected.get(Calendar.MONTH), 0, SelectedValue.SelectedValueType.COLUMN));


        generateLineData(calLastSelected);
    }

    private class ChartBottomValueTouchListener implements ColumnChartOnValueSelectListener {
        @Override
        public void onValueSelected(int selectedLine, int selectedValue, SubcolumnValue value) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.MONTH, Calendar.JANUARY);
            cal.add(Calendar.MONTH, selectedLine);

            calLastSelected = cal;

            generateLineData(cal);
        }

        @Override
        public void onValueDeselected() {

        }
    }

    private class ChartTopListener implements View.OnTouchListener {
        final GestureDetector gestureDetector = new GestureDetector(new GestureDetector.SimpleOnGestureListener() {
            public void onLongPress(MotionEvent e) {
                chartTop.setCurrentViewport(defaultTopViewport);
            }
        });

        @Override
        public boolean onTouch (View v, MotionEvent event) {
            return gestureDetector.onTouchEvent(event);
        }
    }

    private class ChartTopValueTouchListener implements LineChartOnValueSelectListener {
        @Override
        public void onValueSelected(int lineIndex, int pointIndex, PointValue pointValue) {
            ScaleData scaleData = scaleDataList.get(pointIndex);

            long id = scaleData.id;

            Intent intent = new Intent(graphView.getContext(), DataEntryActivity.class);
            intent.putExtra("id", id);
            startActivityForResult(intent, 1);
        }

        @Override
        public void onValueDeselected() {

        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if(isVisibleToUser) {
            Activity a = getActivity();
            if(a != null) a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
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

            generateColumnData();
        }
    }
}
