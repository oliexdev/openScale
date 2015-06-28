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
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;
import com.health.openscale.core.ScaleUser;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lecho.lib.hellocharts.formatter.SimpleLineChartValueFormatter;
import lecho.lib.hellocharts.listener.LineChartOnValueSelectListener;
import lecho.lib.hellocharts.listener.PieChartOnValueSelectListener;
import lecho.lib.hellocharts.model.Axis;
import lecho.lib.hellocharts.model.AxisValue;
import lecho.lib.hellocharts.model.Line;
import lecho.lib.hellocharts.model.LineChartData;
import lecho.lib.hellocharts.model.PieChartData;
import lecho.lib.hellocharts.model.PointValue;
import lecho.lib.hellocharts.model.SliceValue;
import lecho.lib.hellocharts.util.ChartUtils;
import lecho.lib.hellocharts.view.LineChartView;
import lecho.lib.hellocharts.view.PieChartView;

public class OverviewFragment extends Fragment implements FragmentUpdateListener {	
	private View overviewView;

    private TextView txtTitleUser;
    private TextView txtTitleLastMeasurement;
    private TextView txtTitleGoal;
    private TextView txtTitleStatistics;

    private TextView txtWeightLast;
    private TextView txtBMILast;
    private TextView txtWaterLast;
    private TextView txtMuscleLast;
    private TextView txtFatLast;

    private TextView txtGoalWeight;
    private TextView txtGoalDiff;
    private TextView txtGoalDayLeft;

    private TextView txtAvgWeek;
    private TextView txtAvgMonth;

    private TextView txtLabelWeight;
    private TextView txtLabelBMI;
    private TextView txtLabelFat;
    private TextView txtLabelMuscle;
    private TextView txtLabelWater;

    private TextView txtLabelGoalWeight;
    private TextView txtLabelGoalDiff;
    private TextView txtLabelDayLeft;

    private TextView txtLabelAvgWeek;
    private TextView txtLabelAvgMonth;

	private PieChartView pieChartLast;
    private LineChartView lineChartLast;

    private enum lines {WEIGHT, FAT, WATER, MUSCLE}
    private ArrayList<lines> activeLines;

    private SharedPreferences prefs;

    private ScaleData lastScaleData;
    private ScaleUser currentScaleUser;

    private List<ScaleData> scaleDataLastDays;

    @Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) 
	{
		overviewView = inflater.inflate(R.layout.fragment_overview, container, false);

        txtTitleUser = (TextView) overviewView.findViewById(R.id.txtTitleUser);
        txtTitleLastMeasurement = (TextView) overviewView.findViewById(R.id.txtTitleLastMeasurment);
        txtTitleGoal = (TextView) overviewView.findViewById(R.id.txtTitleGoal);
        txtTitleStatistics = (TextView) overviewView.findViewById(R.id.txtTitleStatistics);

        txtWeightLast = (TextView) overviewView.findViewById(R.id.txtWeightLast);
        txtBMILast = (TextView) overviewView.findViewById(R.id.txtBMILast);
        txtWaterLast = (TextView) overviewView.findViewById(R.id.txtWaterLast);
        txtMuscleLast = (TextView) overviewView.findViewById(R.id.txtMuscleLast);
        txtFatLast = (TextView) overviewView.findViewById(R.id.txtFatLast);

        txtGoalWeight = (TextView) overviewView.findViewById(R.id.txtGoalWeight);
        txtGoalDiff = (TextView) overviewView.findViewById(R.id.txtGoalDiff);
        txtGoalDayLeft = (TextView) overviewView.findViewById(R.id.txtGoalDayLeft);

        txtAvgWeek = (TextView) overviewView.findViewById(R.id.txtAvgWeek);
        txtAvgMonth = (TextView) overviewView.findViewById(R.id.txtAvgMonth);

        txtLabelWeight = (TextView) overviewView.findViewById(R.id.txtLabelWeight);
        txtLabelBMI = (TextView) overviewView.findViewById(R.id.txtLabelBMI);
        txtLabelFat = (TextView) overviewView.findViewById(R.id.txtLabelFat);
        txtLabelMuscle = (TextView) overviewView.findViewById(R.id.txtLabelMuscle);
        txtLabelWater = (TextView) overviewView.findViewById(R.id.txtLabelWater);

        txtLabelGoalWeight = (TextView) overviewView.findViewById(R.id.txtLabelGoalWeight);
        txtLabelGoalDiff = (TextView) overviewView.findViewById(R.id.txtLabelGoalDiff);
        txtLabelDayLeft = (TextView) overviewView.findViewById(R.id.txtLabelDayLeft);

        txtLabelAvgWeek = (TextView) overviewView.findViewById(R.id.txtLabelAvgWeek);
        txtLabelAvgMonth = (TextView) overviewView.findViewById(R.id.txtLabelAvgMonth);

        pieChartLast = (PieChartView) overviewView.findViewById(R.id.pieChartLast);
        lineChartLast = (LineChartView) overviewView.findViewById(R.id.lineChartLast);

        lineChartLast.setOnValueTouchListener(new LineChartTouchListener());

        pieChartLast.setOnValueTouchListener(new PieChartLastTouchListener());
        pieChartLast.setChartRotationEnabled(false);

		overviewView.findViewById(R.id.btnInsertData).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
            	btnOnClickInsertData();
            }
        });

        prefs = PreferenceManager.getDefaultSharedPreferences(overviewView.getContext());

		updateOnView(OpenScale.getInstance(overviewView.getContext()).getScaleDataList());

		return overviewView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDataList)
	{
        currentScaleUser = OpenScale.getInstance(overviewView.getContext()).getSelectedScaleUser();

        if (scaleDataList.isEmpty()) {
            lastScaleData = null;
            return;
        }

        lastScaleData = scaleDataList.get(0);

        txtTitleUser.setText(getResources().getString(R.string.label_title_user).toUpperCase() + " " + currentScaleUser.user_name);
        txtTitleLastMeasurement.setText(getResources().getString(R.string.label_title_last_measurement).toUpperCase());
        txtTitleGoal.setText(getResources().getString(R.string.label_title_goal).toUpperCase());
        txtTitleStatistics.setText(getResources().getString(R.string.label_title_statistics).toUpperCase());

        updateLastPieChart();
		updateLastLineChart(scaleDataList);
        updateLastMeasurement();
        updateGoal(scaleDataList);
        updateStatistics(scaleDataList);
    }

    private void updateLastMeasurement() {
        txtWeightLast.setText(lastScaleData.weight + " " + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit]);
        txtBMILast.setText(String.format("%.1f", currentScaleUser.getBMI(lastScaleData.weight)));
        txtFatLast.setText(lastScaleData.fat + " %");
        txtWaterLast.setText(lastScaleData.water + " %");
        txtMuscleLast.setText(lastScaleData.muscle + " %");
    }

    private void updateGoal(ArrayList<ScaleData> scaleDataList) {
        txtGoalWeight.setText(currentScaleUser.goal_weight + " " + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit]);

        double weight_diff = currentScaleUser.goal_weight - lastScaleData.weight;
        txtGoalDiff.setText(String.format("%.1f " + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit], weight_diff));

        Calendar goalDate = Calendar.getInstance();
        Calendar curDate = Calendar.getInstance();
        goalDate.setTime(currentScaleUser.goal_date);

        long days = daysBetween(curDate, goalDate);
        txtGoalDayLeft.setText(days + " " + getResources().getString(R.string.label_days));

        txtLabelGoalWeight.setText(Html.fromHtml(getResources().getString(R.string.label_goal_weight) + " <br> <font color='grey'><small>BMI " + String.format("%.1f", currentScaleUser.getBMI(currentScaleUser.goal_weight)) + " </small></font>"));
        txtLabelGoalDiff.setText(Html.fromHtml(getResources().getString(R.string.label_weight_difference) + " <br> <font color='grey'><small>BMI " + String.format("%.1f", currentScaleUser.getBMI(lastScaleData.weight) - currentScaleUser.getBMI(currentScaleUser.goal_weight))  + " </small></font>"));
        txtLabelDayLeft.setText(Html.fromHtml(getResources().getString(R.string.label_days_left) + " <br> <font color='grey'><small>" + getResources().getString(R.string.label_goal_date_is) + " " + DateFormat.getDateInstance(DateFormat.LONG).format(currentScaleUser.goal_date) + " </small></font>")); // currentScaleUser.goal_date

        if (scaleDataList.size() > 2) {
            ScaleData diffScaleData = scaleDataList.get(1);

            double diffWeight = lastScaleData.weight - diffScaleData.weight;
            double diffBMI = currentScaleUser.getBMI(lastScaleData.weight) - currentScaleUser.getBMI(diffScaleData.weight);
            double diffFat = lastScaleData.fat - diffScaleData.fat;
            double diffMuscle = lastScaleData.muscle - diffScaleData.muscle;
            double diffWater = lastScaleData.water - diffScaleData.water;

            if (diffWeight > 0.0)
                txtLabelWeight.setText(Html.fromHtml(getResources().getString(R.string.label_weight) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.1f ", diffWeight) + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit] + "</small></font>"));
            else
                txtLabelWeight.setText(Html.fromHtml(getResources().getString(R.string.label_weight) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.1f ", diffWeight) + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit] + "</small></font>"));


            if (diffBMI > 0.0)
                txtLabelBMI.setText(Html.fromHtml(getResources().getString(R.string.label_bmi) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.1f", diffBMI) + "</small></font>"));
            else
                txtLabelBMI.setText(Html.fromHtml(getResources().getString(R.string.label_bmi) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.1f", diffBMI) + "</small></font>"));

            if (diffFat > 0.0)
                txtLabelFat.setText(Html.fromHtml(getResources().getString(R.string.label_fat) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.1f", diffFat) + "%</small></font>"));
            else
                txtLabelFat.setText(Html.fromHtml(getResources().getString(R.string.label_fat) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.1f", diffFat) + "%</small></font>"));

            if (diffMuscle > 0.0)
                txtLabelMuscle.setText(Html.fromHtml(getResources().getString(R.string.label_muscle) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.1f", diffMuscle) + "%</small></font>"));
            else
                txtLabelMuscle.setText(Html.fromHtml(getResources().getString(R.string.label_muscle) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.1f", diffMuscle) + "%</small></font>"));

            if (diffWater > 0.0)
                txtLabelWater.setText(Html.fromHtml(getResources().getString(R.string.label_water) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.1f", diffWater) + "%</small></font>"));
            else
                txtLabelWater.setText(Html.fromHtml(getResources().getString(R.string.label_water) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.1f", diffWater) + "%</small></font>"));
        }
    }

    private void updateStatistics(ArrayList<ScaleData> scaleDataList) {
        Calendar histDate = Calendar.getInstance();
        Calendar weekPastDate = Calendar.getInstance();
        Calendar monthPastDate = Calendar.getInstance();

        weekPastDate.setTime(lastScaleData.date_time);
        weekPastDate.add(Calendar.DATE, -7);

        monthPastDate.setTime(lastScaleData.date_time);
        monthPastDate.add(Calendar.DATE, -30);

        int weekSize = 0;
        float weekAvgWeight = 0;
        float weekAvgBMI = 0;
        float weekAvgFat = 0;
        float weekAvgWater = 0;
        float weekAvgMuscle = 0;

        int monthSize = 0;
        float monthAvgWeight = 0;
        float monthAvgBMI = 0;
        float monthAvgFat = 0;
        float monthAvgWater = 0;
        float monthAvgMuscle = 0;

        for (ScaleData scaleData : scaleDataList)
        {
            histDate.setTime(scaleData.date_time);

            if (weekPastDate.before(histDate)) {
                weekSize++;

                weekAvgWeight += scaleData.weight;
                weekAvgBMI += currentScaleUser.getBMI(scaleData.weight);
                weekAvgFat += scaleData.fat;
                weekAvgWater += scaleData.water;
                weekAvgMuscle += scaleData.muscle;
            }

            if (monthPastDate.before(histDate)) {
                monthSize++;

                monthAvgWeight += scaleData.weight;
                monthAvgBMI += currentScaleUser.getBMI(scaleData.weight);
                monthAvgFat += scaleData.fat;
                monthAvgWater += scaleData.water;
                monthAvgMuscle += scaleData.muscle;
            } else {
                break;
            }
        }

        weekAvgWeight /= weekSize;
        weekAvgBMI /= weekSize;
        weekAvgFat /= weekSize;
        weekAvgWater /= weekSize;
        weekAvgMuscle /= weekSize;

        monthAvgWeight /= monthSize;
        monthAvgBMI /= monthSize;
        monthAvgFat /= monthSize;
        monthAvgWater /= monthSize;
        monthAvgMuscle /= monthSize;

        txtLabelAvgWeek.setText(Html.fromHtml(getResources().getString(R.string.label_last_week) + " <br> <font color='grey'><small> " + String.format("[Ø-"+getResources().getString(R.string.label_weight)+": %.1f" + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit] + "]  [Ø-"+getResources().getString(R.string.label_bmi)+": %.1f]  [Ø-"+getResources().getString(R.string.label_fat)+": %.1f%%]  [Ø-"+getResources().getString(R.string.label_muscle)+": %.1f%%]  [Ø-"+getResources().getString(R.string.label_water)+": %.1f%%]", weekAvgWeight, weekAvgBMI, weekAvgFat, weekAvgMuscle, weekAvgWater) + "</small></font>"));
        txtLabelAvgMonth.setText(Html.fromHtml(getResources().getString(R.string.label_last_month) + " <br> <font color='grey'><small> " + String.format("[Ø-"+getResources().getString(R.string.label_weight)+": %.1f" + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit] + "]  [Ø-"+getResources().getString(R.string.label_bmi)+": %.1f]  [Ø-"+getResources().getString(R.string.label_fat)+": %.1f%%]  [Ø-"+getResources().getString(R.string.label_muscle)+": %.1f%%]  [Ø-"+getResources().getString(R.string.label_water)+": %.1f%%]", monthAvgWeight, monthAvgBMI, monthAvgFat, monthAvgMuscle, monthAvgWater) + "</small></font>"));

        txtAvgWeek.setText(weekSize + " " + getResources().getString(R.string.label_measures));
        txtAvgMonth.setText(monthSize + " " + getResources().getString(R.string.label_measures));
    }

    private void updateLastLineChart(ArrayList<ScaleData> scaleDataList) {
        List<AxisValue> axisValues = new ArrayList<AxisValue>();

        List<PointValue> valuesWeight = new ArrayList<PointValue>();
        List<PointValue> valuesFat = new ArrayList<PointValue>();
        List<PointValue> valuesWater = new ArrayList<PointValue>();
        List<PointValue> valuesMuscle = new ArrayList<PointValue>();
        List<Line> lines = new ArrayList<Line>();

        int max_i = 7;

        if (scaleDataList.size() < 7) {
            max_i = scaleDataList.size();
        }

        Calendar histDate = Calendar.getInstance();
        Calendar lastDate = Calendar.getInstance();

        lastDate.setTime(scaleDataList.get(0).date_time);

        scaleDataLastDays = new ArrayList<ScaleData>();

        for (int i=0; i<max_i; i++) {
            ScaleData histData = scaleDataList.get(max_i - i - 1);

            scaleDataLastDays.add(histData);

            valuesWeight.add(new PointValue(i, histData.weight));
            valuesFat.add(new PointValue(i, histData.fat));
            valuesWater.add(new PointValue(i, histData.water));
            valuesMuscle.add(new PointValue(i, histData.muscle));

            histDate.setTime(histData.date_time);

            long days = 0 - daysBetween(lastDate, histDate);

            if (days == 0) {
                axisValues.add(new AxisValue(i, DateFormat.getDateInstance(DateFormat.SHORT).format(lastScaleData.date_time).toCharArray()));
            } else {
                axisValues.add(new AxisValue(i, String.format("%d " + getResources().getString(R.string.label_days), days).toCharArray()));
            }
        }

        Line lineWeight = new Line(valuesWeight).
                setColor(ChartUtils.COLOR_VIOLET).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));
        Line lineFat = new Line(valuesFat).
                setColor(ChartUtils.COLOR_ORANGE).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));
        Line lineWater = new Line(valuesWater).
                setColor(ChartUtils.COLOR_BLUE).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));
        Line lineMuscle = new Line(valuesMuscle).
                setColor(ChartUtils.COLOR_GREEN).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));

        activeLines = new ArrayList<lines>();

        if(prefs.getBoolean("weightEnable", true)) {
            lines.add(lineWeight);
            activeLines.add(OverviewFragment.lines.WEIGHT);
        }

        if(prefs.getBoolean("fatEnable", true)) {
            lines.add(lineFat);
            activeLines.add(OverviewFragment.lines.FAT);
        }

        if(prefs.getBoolean("waterEnable", true)) {
            lines.add(lineWater);
            activeLines.add(OverviewFragment.lines.WATER);
        }

        if(prefs.getBoolean("muscleEnable", true)) {
            lines.add(lineMuscle);
            activeLines.add(OverviewFragment.lines.MUSCLE);
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



        lineChartLast.setLineChartData(lineData);
        lineChartLast.setViewportCalculationEnabled(true);

        lineChartLast.setZoomEnabled(false);
    }

    private void updateLastPieChart() {

        List<SliceValue> arcValuesLast = new ArrayList<SliceValue>();

        arcValuesLast.add(new SliceValue(lastScaleData.fat, ChartUtils.COLOR_ORANGE));
        arcValuesLast.add(new SliceValue(lastScaleData.water, ChartUtils.COLOR_BLUE));
        arcValuesLast.add(new SliceValue(lastScaleData.muscle, ChartUtils.COLOR_GREEN));

        PieChartData pieChartData = new PieChartData(arcValuesLast);
        pieChartData.setHasLabels(false);
        pieChartData.setHasCenterCircle(true);
        pieChartData.setCenterText1(Float.toString(lastScaleData.weight) + " " + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit]);
        pieChartData.setCenterText2(DateFormat.getDateInstance(DateFormat.MEDIUM).format(lastScaleData.date_time));


        if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_XLARGE ||
                (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_LARGE) {
            pieChartData.setCenterText1FontSize(25);
            pieChartData.setCenterText2FontSize(14);
        } else
        {
            pieChartData.setCenterText1FontSize(20);
            pieChartData.setCenterText2FontSize(12);
            pieChartData.setValueLabelTextSize(12);
        }

        pieChartLast.setPieChartData(pieChartData);
    }

    private long daysBetween(Calendar startDate, Calendar endDate) {
        long end = endDate.getTimeInMillis();
        long start = startDate.getTimeInMillis();
        return TimeUnit.MILLISECONDS.toDays(Math.abs(end - start));
    }

	public void btnOnClickInsertData()
	{
		Intent intent = new Intent(overviewView.getContext(), NewEntryActivity.class);
        startActivityForResult(intent, 1);
	}

    private class PieChartLastTouchListener implements PieChartOnValueSelectListener
    {
        @Override
        public void onValueSelected(int i, SliceValue arcValue) {
            if (lastScaleData == null) {
                return;
            }

            String date_time = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(lastScaleData.date_time);

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
        public void onValueDeselected() {

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
                if (a != null) a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        }
    }

    private class LineChartTouchListener implements LineChartOnValueSelectListener {
        @Override
        public void onValueSelected(int lineIndex, int pointIndex, PointValue pointValue) {
            ScaleData scaleData = scaleDataLastDays.get(pointIndex);
            lines selectedLine = activeLines.get(lineIndex);

            String date_time = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(scaleData.date_time);

            switch (selectedLine) {
                case WEIGHT:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_weight) + " " + scaleData.weight + ScaleUser.UNIT_STRING[OpenScale.getInstance(overviewView.getContext()).getSelectedScaleUser().scale_unit] + " " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
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
        public void onValueDeselected() {

        }
    }
}
