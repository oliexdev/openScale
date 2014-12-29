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

import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;

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

    private OpenScale openScale;

    private Calendar yearCal;

	public GraphFragment() {
        yearCal = Calendar.getInstance();
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) 
	{
		graphView = inflater.inflate(R.layout.fragment_graph, container, false);
		
		chartTop = (LineChartView) graphView.findViewById(R.id.chart_top);
        chartBottom = (ColumnChartView) graphView.findViewById(R.id.chart_bottom);

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

		return graphView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDBEntries)
	{
        generateColumnData();
	}

    private void generateLineData(Calendar cal)
    {
        ArrayList<ScaleData> scaleDBEntries = openScale.getAllDataOfMonth(yearCal.get(Calendar.YEAR), cal.get(Calendar.MONTH));

        SimpleDateFormat day_date = new SimpleDateFormat("dd", Locale.getDefault());

        cal.set(Calendar.DAY_OF_MONTH, 1);
        int max_days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        List<AxisValue> axisValues = new ArrayList<AxisValue>();

        for (int i=0; i<max_days; i++) {
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

        for(ScaleData scaleEntry: scaleDBEntries)
        {
            calDB.setTime(scaleEntry.date_time);

            valuesWeight.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH), scaleEntry.weight));
            valuesFat.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH), scaleEntry.fat));
            valuesWater.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH), scaleEntry.water));
            valuesMuscle.add(new PointValue(calDB.get(Calendar.DAY_OF_MONTH), scaleEntry.muscle));
        }


        Line lineWeight = new Line(valuesWeight).
                setColor(Utils.COLOR_VIOLET).
                setCubic(true).
                setHasLabels(true).
                setFormatter(new SimpleValueFormatter(1, false, null, null));
        Line lineFat = new Line(valuesFat).
                setColor(Utils.COLOR_ORANGE).
                setCubic(true).
                setHasLabels(true).
                setFormatter(new SimpleValueFormatter(1, false, null, null));
        Line lineWater = new Line(valuesWater).
                setColor(Utils.COLOR_BLUE).
                setCubic(true).
                setHasLabels(true).
                setFormatter(new SimpleValueFormatter(1, false, null, null));
        Line lineMuscle = new Line(valuesMuscle).
                setColor(Utils.COLOR_GREEN).
                setCubic(true).
                setHasLabels(true).
                setFormatter(new SimpleValueFormatter(1, false, null, null));

        lines.add(lineWeight);
        lines.add(lineFat);
        lines.add(lineWater);
        lines.add(lineMuscle);

        LineChartData lineData = new LineChartData(lines);
        lineData.setAxisXBottom(new Axis(axisValues).
                setHasLines(true).
                setTextColor(Color.BLACK).
                setName(getResources().getString(R.string.label_x_axis))
        );

        lineData.setAxisYLeft(new Axis().
                setHasLines(true).
                setMaxLabelChars(3).
                setTextColor(Color.BLACK).
                setName(getResources().getString(R.string.label_y_axis))
        );



        chartTop.setLineChartData(lineData);
        chartTop.setViewportCalculationEnabled(false);

        Viewport v = new Viewport(0, 110, max_days, 0);
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
        chartBottom.setZoomType(ZoomType.HORIZONTAL);
        chartBottom.setOnValueTouchListener(new ValueTouchListener());

        generateLineData(cal);
    }

    private class ValueTouchListener implements ColumnChartView.ColumnChartOnValueTouchListener {
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
}
