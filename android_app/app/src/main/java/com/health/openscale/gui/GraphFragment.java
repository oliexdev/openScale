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

package com.health.openscale.gui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;
import com.health.openscale.core.ScaleUser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import lecho.lib.hellocharts.gesture.ZoomType;
import lecho.lib.hellocharts.model.Axis;
import lecho.lib.hellocharts.model.AxisValue;
import lecho.lib.hellocharts.model.Column;
import lecho.lib.hellocharts.model.ColumnChartData;
import lecho.lib.hellocharts.model.ColumnValue;
import lecho.lib.hellocharts.model.Line;
import lecho.lib.hellocharts.model.LineChartData;
import lecho.lib.hellocharts.model.PointValue;
import lecho.lib.hellocharts.model.SimpleValueFormatter;
import lecho.lib.hellocharts.model.Viewport;
import lecho.lib.hellocharts.util.Utils;
import lecho.lib.hellocharts.view.ColumnChartView;
import lecho.lib.hellocharts.view.LineChartView;

public class GraphFragment extends Fragment implements FragmentUpdateListener {	
	private View graphView;
	private LineChartView chartTop;
    private ColumnChartView chartBottom;
    private TextView txtYear;
    private SharedPreferences prefs;

    private OpenScale openScale;

    private Calendar yearCal;

    private ArrayList<ScaleData> scaleDataList;

    private enum lines {WEIGHT, FAT, WATER, MUSCLE}
    private ArrayList<lines> activeLines;

	public GraphFragment() {
        yearCal = Calendar.getInstance();
    }

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) 
	{
		graphView = inflater.inflate(R.layout.fragment_graph, container, false);
		
		chartTop = (LineChartView) graphView.findViewById(R.id.chart_top);
        chartBottom = (ColumnChartView) graphView.findViewById(R.id.chart_bottom);

        chartTop.setOnValueTouchListener(new ChartTopValueTouchListener());
        chartBottom.setOnValueTouchListener(new ChartBottomValueTouchListener());

        txtYear = (TextView) graphView.findViewById(R.id.txtYear);
        txtYear.setText(Integer.toString(yearCal.get(Calendar.YEAR)));

        graphView.findViewById(R.id.btnLeftYear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                yearCal.roll(Calendar.YEAR, false);
                txtYear.setText(Integer.toString(yearCal.get(Calendar.YEAR)));
                updateOnView(null);
            }
        });

        graphView.findViewById(R.id.btnRightYear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                yearCal.roll(Calendar.YEAR, true);
                txtYear.setText(Integer.toString(yearCal.get(Calendar.YEAR)));
                updateOnView(null);
            }
        });
        openScale = OpenScale.getInstance(graphView.getContext());

        prefs = PreferenceManager.getDefaultSharedPreferences(graphView.getContext());

		return graphView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDataList)
	{
        generateColumnData();
	}

    private void generateLineData(Calendar cal)
    {
        scaleDataList = openScale.getScaleDataOfMonth(yearCal.get(Calendar.YEAR), cal.get(Calendar.MONTH));
        float maxValue = openScale.getMaxValueOfScaleData(yearCal.get(Calendar.YEAR), cal.get(Calendar.MONTH));

        SimpleDateFormat day_date = new SimpleDateFormat("dd", Locale.getDefault());

        cal.set(Calendar.DAY_OF_MONTH, 1);
        int maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        List<AxisValue> axisValues = new ArrayList<AxisValue>();

        for (int i=0; i<maxDays; i++) {
            String day_name = day_date.format(cal.getTime());

            axisValues.add(new AxisValue(i, day_name.toCharArray()));

            cal.add(Calendar.DAY_OF_MONTH, 1);
        }

        List<PointValue> valuesWeight = new ArrayList<PointValue>();
        List<PointValue> valuesFat = new ArrayList<PointValue>();
        List<PointValue> valuesWater = new ArrayList<PointValue>();
        List<PointValue> valuesMuscle = new ArrayList<PointValue>();
        List<Line> lines = new ArrayList<Line>();

        Calendar calDB = Calendar.getInstance();

        for(ScaleData scaleEntry: scaleDataList)
        {
            calDB.setTime(scaleEntry.date_time);

            valuesWeight.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.weight));
            valuesFat.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.fat));
            valuesWater.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.water));
            valuesMuscle.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH)-1, scaleEntry.muscle));
        }


        Line lineWeight = new Line(valuesWeight).
                setColor(Utils.COLOR_VIOLET).
                setCubic(true).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setFormatter(new SimpleValueFormatter(1, false, null, null));
        Line lineFat = new Line(valuesFat).
                setColor(Utils.COLOR_ORANGE).
                setCubic(true).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setFormatter(new SimpleValueFormatter(1, false, null, null));
        Line lineWater = new Line(valuesWater).
                setColor(Utils.COLOR_BLUE).
                setCubic(true).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setFormatter(new SimpleValueFormatter(1, false, null, null));
        Line lineMuscle = new Line(valuesMuscle).
                setColor(Utils.COLOR_GREEN).
                setCubic(true).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setFormatter(new SimpleValueFormatter(1, false, null, null));

        activeLines = new ArrayList<lines>();

        if(prefs.getBoolean("weightEnable", true)) {
            lines.add(lineWeight);
            activeLines.add(GraphFragment.lines.WEIGHT);
        }

        if(prefs.getBoolean("fatEnable", true)) {
            lines.add(lineFat);
            activeLines.add(GraphFragment.lines.FAT);
        }

        if(prefs.getBoolean("waterEnable", true)) {
            lines.add(lineWater);
            activeLines.add(GraphFragment.lines.WATER);
        }

        if(prefs.getBoolean("muscleEnable", true)) {
            lines.add(lineMuscle);
            activeLines.add(GraphFragment.lines.MUSCLE);
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
        chartTop.setViewportCalculationEnabled(false);

        if (maxValue == 0.0) {
            maxValue = 100;
        } else {
            maxValue += 20;
        }

        Viewport v = new Viewport(0, (int)maxValue, maxDays-1, 0);
        chartTop.setMaximumViewport(v);
        chartTop.setCurrentViewport(v, true);

        chartTop.setZoomType(ZoomType.HORIZONTAL);
    }

    private void generateColumnData()
    {
        int[] numOfMonth = openScale.getCountsOfMonth(yearCal.get(Calendar.YEAR));

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.MONTH, Calendar.JANUARY);

        SimpleDateFormat month_date = new SimpleDateFormat("MMM", Locale.getDefault());

        List<AxisValue> axisValues = new ArrayList<AxisValue>();
        List<Column> columns = new ArrayList<Column>();

        for (int i=0; i<12; i++) {
            String month_name = month_date.format(cal.getTime());

            axisValues.add(new AxisValue(i, month_name.toCharArray()));
            List<ColumnValue> values = new ArrayList<ColumnValue>();
            values.add(new ColumnValue(numOfMonth[i], Utils.COLORS[i % Utils.COLORS.length]));

            columns.add(new Column(values).setHasLabelsOnlyForSelected(true));

            cal.add(Calendar.MONTH, 1);
        }

        ColumnChartData columnData = new ColumnChartData(columns);

        columnData.setAxisXBottom(new Axis(axisValues).setHasLines(true).setTextColor(Color.BLACK));

        chartBottom.setColumnChartData(columnData);
        chartBottom.setValueSelectionEnabled(true);
        chartBottom.setZoomEnabled(false);

        generateLineData(cal);
    }

    private class ChartBottomValueTouchListener implements ColumnChartView.ColumnChartOnValueTouchListener {
        @Override
        public void onValueTouched(int selectedLine, int selectedValue, ColumnValue value) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.MONTH, Calendar.JANUARY);
            cal.add(Calendar.MONTH, selectedLine);

            generateLineData(cal);
        }

        @Override
        public void onNothingTouched() {

        }
    }

    private class ChartTopValueTouchListener implements LineChartView.LineChartOnValueTouchListener {
        @Override
        public void onValueTouched(int lineIndex, int pointIndex, PointValue pointValue) {
            ScaleData scaleData = scaleDataList.get(pointIndex);
            lines selectedLine = activeLines.get(lineIndex);

            String date_time = new SimpleDateFormat("dd. MMM yyyy (EE) HH:mm").format(scaleData.date_time);

            switch (selectedLine) {
                case WEIGHT:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_weight) + " " + scaleData.weight + ScaleUser.UNIT_STRING[OpenScale.getInstance(graphView.getContext()).getSelectedScaleUser().scale_unit] + " " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                   break;
                case FAT:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_fat) + " " + scaleData.fat + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                case WATER:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_water) + " " + scaleData.water + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                case MUSCLE:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_muscle) + " " + scaleData.muscle + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
            }
        }

        @Override
        public void onNothingTouched() {

        }
    }
}
