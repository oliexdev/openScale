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

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
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
import java.util.List;

import lecho.lib.hellocharts.model.ArcValue;
import lecho.lib.hellocharts.model.PieChartData;
import lecho.lib.hellocharts.model.SimpleValueFormatter;
import lecho.lib.hellocharts.util.Utils;
import lecho.lib.hellocharts.view.PieChartView;

public class OverviewFragment extends Fragment implements FragmentUpdateListener {	
	private View overviewView;

    private TextView txtOverviewTitle;
	private PieChartView pieChart;
	private TextView txtAvgWeight;
	private TextView txtAvgFat;
	private TextView txtAvgWater;
	private TextView txtAvgMuscle;

    private ScaleData lastScaleData;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) 
	{
		overviewView = inflater.inflate(R.layout.fragment_overview, container, false);

        txtOverviewTitle = (TextView) overviewView.findViewById(R.id.txtOverviewTitle);
		pieChart = (PieChartView) overviewView.findViewById(R.id.pieChart);
		txtAvgWeight = (TextView) overviewView.findViewById(R.id.txtAvgWeight);
		txtAvgFat = (TextView) overviewView.findViewById(R.id.txtAvgFat);
		txtAvgWater = (TextView) overviewView.findViewById(R.id.txtAvgWater);
		txtAvgMuscle = (TextView) overviewView.findViewById(R.id.txtAvgMuscle);

        pieChart.setOnValueTouchListener(new PieChartTouchListener());
        pieChart.setChartRotationEnabled(false);

		overviewView.findViewById(R.id.btnInsertData).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
            	btnOnClickInsertData();
            }
        });
		
		updateOnView(OpenScale.getInstance(overviewView.getContext()).getScaleDataList());

		return overviewView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDataList)
	{
        ScaleUser scaleUser = OpenScale.getInstance(overviewView.getContext()).getSelectedScaleUser();

        txtOverviewTitle.setText(getResources().getString(R.string.label_overview_title_start) + " " + scaleUser.user_name + " " + getResources().getString(R.string.label_overview_title_end));

		List<ArcValue> arcValues = new ArrayList<ArcValue>();

		if (scaleDataList.isEmpty()) {
            lastScaleData = null;
            return;
        }

        lastScaleData = scaleDataList.get(0);
		
		arcValues.add(new ArcValue(lastScaleData.fat, Utils.COLOR_ORANGE));
		arcValues.add(new ArcValue(lastScaleData.water, Utils.COLOR_BLUE));
		arcValues.add(new ArcValue(lastScaleData.muscle, Utils.COLOR_GREEN));
		
		PieChartData pieChartData = new PieChartData(arcValues);
		pieChartData.setHasLabels(true);
        pieChartData.setFormatter(new SimpleValueFormatter(1, false, null, " %".toCharArray()));
		pieChartData.setHasCenterCircle(true);
		pieChartData.setCenterText1(Float.toString(lastScaleData.weight) + " " + ScaleUser.UNIT_STRING[scaleUser.scale_unit]);
		pieChartData.setCenterText2(new SimpleDateFormat("dd. MMM yyyy (EE)").format(lastScaleData.date_time));

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
		
		for (ScaleData scaleData : scaleDataList)
		{
			avgWeight += scaleData.weight;
			avgFat += scaleData.fat;
			avgWater += scaleData.water;
			avgMuscle += scaleData.muscle;
		}
		
		avgWeight = avgWeight / scaleDataList.size();
		avgFat = avgFat / scaleDataList.size();
		avgWater = avgWater / scaleDataList.size();
		avgMuscle = avgMuscle / scaleDataList.size();
		
		txtAvgWeight.setText(String.format( "%.1f " + ScaleUser.UNIT_STRING[scaleUser.scale_unit], avgWeight));
		txtAvgFat.setText(String.format( "%.1f %%", avgFat));
		txtAvgWater.setText(String.format( "%.1f %%", avgWater));
		txtAvgMuscle.setText(String.format( "%.1f %%", avgMuscle));
	}

	public void btnOnClickInsertData()
	{
		Intent intent = new Intent(overviewView.getContext(), NewEntryActivity.class);
        startActivityForResult(intent, 1);
	}

    private class PieChartTouchListener implements PieChartView.PieChartOnValueTouchListener
    {
        @Override
        public void onValueTouched(int i, ArcValue arcValue)
        {
            if (lastScaleData == null) {
                return;
            }


            String date_time = new SimpleDateFormat("dd. MMM yyyy (EE) HH:mm").format(lastScaleData.date_time);

            switch (i) {
                case 0:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_fat) + " " + lastScaleData.fat + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                case 1:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_water) + " " + lastScaleData.water + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                case 2:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_muscle) + " " + lastScaleData.muscle + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
            }
        }

        @Override
        public void onNothingTouched()
        {

        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        updateOnView(OpenScale.getInstance(overviewView.getContext()).getScaleDataList());
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser) {
             if ((getActivity().getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_XLARGE &&
                (getActivity().getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) != Configuration.SCREENLAYOUT_SIZE_LARGE) {
                Activity a = getActivity();
                if (a != null) a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        }
    }
}
