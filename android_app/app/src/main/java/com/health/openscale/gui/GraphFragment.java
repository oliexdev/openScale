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

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

import lecho.lib.hellocharts.model.Axis;
import lecho.lib.hellocharts.model.AxisValue;
import lecho.lib.hellocharts.model.Line;
import lecho.lib.hellocharts.model.LineChartData;
import lecho.lib.hellocharts.model.PointValue;
import lecho.lib.hellocharts.view.LineChartView;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.health.openscale.R;
import com.health.openscale.core.ScaleData;

public class GraphFragment extends Fragment implements FragmentUpdateListener {	
	private View graphView;
	private LineChartView chartView;
	
	public GraphFragment() {

	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) 
	{
		graphView = inflater.inflate(R.layout.fragment_graph, container, false);
		
		chartView = (LineChartView) graphView.findViewById(R.id.data_chart);
		
		return graphView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDBEntries)
	{		
		if (scaleDBEntries.isEmpty()) {
			LineChartData data = new LineChartData();
			chartView.setLineChartData(data);
			
			return;
		}
		
		List<AxisValue> axisValues = new ArrayList<AxisValue>();
		List<PointValue> valuesWeight = new ArrayList<PointValue>();
		List<PointValue> valuesFat = new ArrayList<PointValue>();
		List<PointValue> valuesWater = new ArrayList<PointValue>();
		List<PointValue> valuesMuscle = new ArrayList<PointValue>();
		List<Line> lines = new ArrayList<Line>();
		
		
		for(ScaleData scaleEntry: scaleDBEntries)
		{
			valuesWeight.add(new PointValue(scaleEntry.date_time.getTime(), scaleEntry.weight));
			valuesFat.add(new PointValue(scaleEntry.date_time.getTime(), scaleEntry.fat));
			valuesWater.add(new PointValue(scaleEntry.date_time.getTime(), scaleEntry.water));
			valuesMuscle.add(new PointValue(scaleEntry.date_time.getTime(), scaleEntry.muscle));
			
			axisValues.add(new AxisValue(scaleEntry.date_time.getTime(), DateFormat.getDateInstance(DateFormat.SHORT).format(scaleEntry.date_time).toCharArray()));
		}
		
	    Line lineWeight = new Line(valuesWeight).setColor(Color.GREEN);
	    Line lineFat = new Line(valuesFat).setColor(Color.RED);
	    Line lineWater = new Line(valuesWater).setColor(Color.BLUE);
	    Line lineMuscle = new Line(valuesMuscle).setColor(Color.GRAY);
	    
	    lines.add(lineWeight);
	    lines.add(lineFat);
	    lines.add(lineWater);
	    lines.add(lineMuscle);
	    
	    lineWeight.setHasLabels(true);
	    lineWeight.setHasLabelsOnlyForSelected(true);
	    lineFat.setHasLabelsOnlyForSelected(true);
	    
	    LineChartData data = new LineChartData();
	    
	    Axis axisX = new Axis(axisValues);
	    Axis axisY = new Axis();
	    
	    axisY.setHasLines(true);
	    
	    axisX.setName("Zeit");
	    axisY.setName("Wert");
	    
	    axisX.setTextColor(Color.BLACK);
	    axisY.setTextColor(Color.BLACK);
	    
	    data.setAxisXBottom(axisX);
	    data.setAxisYLeft(axisY);
	    
	    data.setLines(lines);

	    chartView.setLineChartData(data);
	}
}
