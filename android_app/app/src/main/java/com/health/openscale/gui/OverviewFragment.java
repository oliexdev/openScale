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

import android.content.Intent;
import android.content.res.Configuration;
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
import java.util.List;

import lecho.lib.hellocharts.model.ArcValue;
import lecho.lib.hellocharts.model.PieChartData;
import lecho.lib.hellocharts.model.SimpleValueFormatter;
import lecho.lib.hellocharts.util.Utils;
import lecho.lib.hellocharts.view.PieChartView;

public class OverviewFragment extends Fragment implements FragmentUpdateListener {	
	private View overviewView;
	private PieChartView pieChart;
	private TextView txtAvgWeight;
	private TextView txtAvgFat;
	private TextView txtAvgWater;
	private TextView txtAvgMuscle;
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) 
	{
		overviewView = inflater.inflate(R.layout.fragment_overview, container, false);
		
		pieChart = (PieChartView) overviewView.findViewById(R.id.data_pie_chart);
		txtAvgWeight = (TextView) overviewView.findViewById(R.id.txtAvgWeight);
		txtAvgFat = (TextView) overviewView.findViewById(R.id.txtAvgFat);
		txtAvgWater = (TextView) overviewView.findViewById(R.id.txtAvgWater);
		txtAvgMuscle = (TextView) overviewView.findViewById(R.id.txtAvgMuscle);
		
		overviewView.findViewById(R.id.btnInsertData).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
            	btnOnClickInsertData();
            }
        });
		
		updateOnView(OpenScale.getInstance(overviewView.getContext()).getScaleDBEntries());
		
		return overviewView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDBEntries)
	{		
		List<ArcValue> arcValues = new ArrayList<ArcValue>();
		
		if (scaleDBEntries.isEmpty()) {
			return;
		}
		
		ScaleData lastEntry = scaleDBEntries.get(0);
		
		arcValues.add(new ArcValue(lastEntry.fat, Utils.COLOR_ORANGE));
		arcValues.add(new ArcValue(lastEntry.water, Utils.COLOR_BLUE));
		arcValues.add(new ArcValue(lastEntry.muscle, Utils.COLOR_GREEN));
		
		PieChartData pieChartData = new PieChartData(arcValues);
		pieChartData.setHasLabels(true);
        pieChartData.setFormatter(new SimpleValueFormatter(1, false, null, " %".toCharArray()));
		pieChartData.setHasCenterCircle(true);
		pieChartData.setCenterText1(Float.toString(lastEntry.weight) + " " + getResources().getString(R.string.weight_unit));
		pieChartData.setCenterText2(new SimpleDateFormat("dd. MMM yyyy (EE)").format(lastEntry.date_time));

        if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_XLARGE ||
           (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_LARGE) {
            pieChartData.setCenterText1FontSize(33);
            pieChartData.setCenterText2FontSize(14);
        } else
        {
            pieChartData.setCenterText1FontSize(12);
            pieChartData.setCenterText2FontSize(8);
            pieChartData.setValueLabelTextSize(8);
        }
		
		pieChart.setPieChartData(pieChartData);
		
		double avgWeight = 0;
		double avgFat = 0;
		double avgWater = 0;
		double avgMuscle = 0;
		
		for (ScaleData scaleData : scaleDBEntries)
		{
			avgWeight += scaleData.weight;
			avgFat += scaleData.fat;
			avgWater += scaleData.water;
			avgMuscle += scaleData.muscle;
		}
		
		avgWeight = avgWeight / scaleDBEntries.size();
		avgFat = avgFat / scaleDBEntries.size();
		avgWater = avgWater / scaleDBEntries.size();
		avgMuscle = avgMuscle / scaleDBEntries.size();
		
		txtAvgWeight.setText(String.format( "%.1f " + getResources().getString(R.string.weight_unit), avgWeight));
		txtAvgFat.setText(String.format( "%.1f %%", avgFat));
		txtAvgWater.setText(String.format( "%.1f %%", avgWater));
		txtAvgMuscle.setText(String.format( "%.1f %%", avgMuscle));
	}
	
	public void btnOnClickInsertData()
	{
		Intent intent = new Intent(overviewView.getContext(), NewEntryActivity.class);
		startActivity(intent);
	}
	
}
