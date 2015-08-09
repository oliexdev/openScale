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
import android.content.Context;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TableRow;
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
    private TextView txtWaistLast;
    private TextView txtHipLast;

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
    private TextView txtLabelWaist;
    private TextView txtLabelHip;

    private TextView txtLabelGoalWeight;
    private TextView txtLabelGoalDiff;
    private TextView txtLabelDayLeft;

    private TextView txtLabelAvgWeek;
    private TextView txtLabelAvgMonth;

	private PieChartView pieChartLast;
    private LineChartView lineChartLast;

    private Spinner spinUser;

    private enum lines {WEIGHT, FAT, WATER, MUSCLE, WAIST, HIP}
    private ArrayList<lines> activeLines;

    private SharedPreferences prefs;

    private ScaleData lastScaleData;
    private ScaleUser currentScaleUser;

    private List<ScaleData> scaleDataLastDays;

    private Context context;

    @Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) 
	{
		overviewView = inflater.inflate(R.layout.fragment_overview, container, false);

        context = overviewView.getContext();

        txtTitleUser = (TextView) overviewView.findViewById(R.id.txtTitleUser);
        txtTitleLastMeasurement = (TextView) overviewView.findViewById(R.id.txtTitleLastMeasurment);
        txtTitleGoal = (TextView) overviewView.findViewById(R.id.txtTitleGoal);
        txtTitleStatistics = (TextView) overviewView.findViewById(R.id.txtTitleStatistics);

        txtWeightLast = (TextView) overviewView.findViewById(R.id.txtWeightLast);
        txtBMILast = (TextView) overviewView.findViewById(R.id.txtBMILast);
        txtWaterLast = (TextView) overviewView.findViewById(R.id.txtWaterLast);
        txtMuscleLast = (TextView) overviewView.findViewById(R.id.txtMuscleLast);
        txtFatLast = (TextView) overviewView.findViewById(R.id.txtFatLast);
        txtWaistLast = (TextView) overviewView.findViewById(R.id.txtWaistLast);
        txtHipLast = (TextView) overviewView.findViewById(R.id.txtHipLast);

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
        txtLabelWaist = (TextView) overviewView.findViewById(R.id.txtLabelWaist);
        txtLabelHip = (TextView) overviewView.findViewById(R.id.txtLabelHip);


        txtLabelGoalWeight = (TextView) overviewView.findViewById(R.id.txtLabelGoalWeight);
        txtLabelGoalDiff = (TextView) overviewView.findViewById(R.id.txtLabelGoalDiff);
        txtLabelDayLeft = (TextView) overviewView.findViewById(R.id.txtLabelDayLeft);

        txtLabelAvgWeek = (TextView) overviewView.findViewById(R.id.txtLabelAvgWeek);
        txtLabelAvgMonth = (TextView) overviewView.findViewById(R.id.txtLabelAvgMonth);

        pieChartLast = (PieChartView) overviewView.findViewById(R.id.pieChartLast);
        lineChartLast = (LineChartView) overviewView.findViewById(R.id.lineChartLast);

        spinUser = (Spinner) overviewView.findViewById(R.id.spinUser);

        lineChartLast.setOnValueTouchListener(new LineChartTouchListener());

        pieChartLast.setOnValueTouchListener(new PieChartLastTouchListener());
        pieChartLast.setChartRotationEnabled(false);

		overviewView.findViewById(R.id.btnInsertData).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
            	btnOnClickInsertData();
            }
        });

        prefs = PreferenceManager.getDefaultSharedPreferences(overviewView.getContext());
        currentScaleUser = OpenScale.getInstance(overviewView.getContext()).getSelectedScaleUser();

		updateOnView(OpenScale.getInstance(overviewView.getContext()).getScaleDataList());

        if(!prefs.getBoolean("fatEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowFat);
            row.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("muscleEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowMuscle);
            row.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("waterEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowWater);
            row.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("waistEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowWaist);
            row.setVisibility(View.GONE);
        }

        if(!prefs.getBoolean("hipEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowHip);
            row.setVisibility(View.GONE);
        }

        spinUser.setOnItemSelectedListener(new spinUserSelectionListener());

        ArrayList<String> userItems = new ArrayList<>();

        ArrayList<ScaleUser> scaleUserList = OpenScale.getInstance(overviewView.getContext()).getScaleUserList();

        int posUser = 0;
        int pos = 0;

        for (ScaleUser scaleUser : scaleUserList) {
            userItems.add(scaleUser.user_name);

            if (scaleUser.id == currentScaleUser.id) {
                posUser = pos;
            }

            pos++;
        }

        ArrayAdapter<String> spinAdapter = new ArrayAdapter<>(overviewView.getContext(), R.layout.support_simple_spinner_dropdown_item, userItems);

        spinUser.setAdapter(spinAdapter);
        spinUser.setSelection(posUser);

        return overviewView;
	}
	
	@Override
	public void updateOnView(ArrayList<ScaleData> scaleDataList)
	{
        if (scaleDataList.isEmpty()) {
            lastScaleData = null;
            return;
        }

        lastScaleData = scaleDataList.get(0);

        txtTitleUser.setText(getResources().getString(R.string.label_title_user).toUpperCase());
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
        txtWaistLast.setText(lastScaleData.waist + " cm");
        txtHipLast.setText(lastScaleData.hip + " cm");
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

        if (scaleDataList.size() >= 2) {
            ScaleData diffScaleData = scaleDataList.get(1);

            double diffWeight = lastScaleData.weight - diffScaleData.weight;
            double diffBMI = currentScaleUser.getBMI(lastScaleData.weight) - currentScaleUser.getBMI(diffScaleData.weight);
            double diffFat = lastScaleData.fat - diffScaleData.fat;
            double diffMuscle = lastScaleData.muscle - diffScaleData.muscle;
            double diffWater = lastScaleData.water - diffScaleData.water;
            double diffWaist = lastScaleData.waist - diffScaleData.waist;
            double diffHip = lastScaleData.hip - diffScaleData.hip;


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

            if (diffWaist > 0.0)
                txtLabelWaist.setText(Html.fromHtml(getResources().getString(R.string.label_waist) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.1f", diffWaist) + "cm</small></font>"));
            else
                txtLabelWaist.setText(Html.fromHtml(getResources().getString(R.string.label_waist) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.1f", diffWaist) + "cm</small></font>"));

            if (diffHip > 0.0)
                txtLabelHip.setText(Html.fromHtml(getResources().getString(R.string.label_hip) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.1f", diffHip) + "cm</small></font>"));
            else
                txtLabelHip.setText(Html.fromHtml(getResources().getString(R.string.label_hip) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.1f", diffHip) + "cm</small></font>"));
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
        float weekAvgWaist = 0;
        float weekAvgHip = 0;

        int monthSize = 0;
        float monthAvgWeight = 0;
        float monthAvgBMI = 0;
        float monthAvgFat = 0;
        float monthAvgWater = 0;
        float monthAvgMuscle = 0;
        float monthAvgWaist = 0;
        float monthAvgHip = 0;


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
                weekAvgWaist += scaleData.waist;
                weekAvgHip += scaleData.hip;
            }

            if (monthPastDate.before(histDate)) {
                monthSize++;

                monthAvgWeight += scaleData.weight;
                monthAvgBMI += currentScaleUser.getBMI(scaleData.weight);
                monthAvgFat += scaleData.fat;
                monthAvgWater += scaleData.water;
                monthAvgMuscle += scaleData.muscle;
                monthAvgWaist += scaleData.waist;
                monthAvgHip += scaleData.hip;
            } else {
                break;
            }
        }

        weekAvgWeight /= weekSize;
        weekAvgBMI /= weekSize;
        weekAvgFat /= weekSize;
        weekAvgWater /= weekSize;
        weekAvgMuscle /= weekSize;
        weekAvgWaist /= weekSize;
        weekAvgHip /= weekSize;

        monthAvgWeight /= monthSize;
        monthAvgBMI /= monthSize;
        monthAvgFat /= monthSize;
        monthAvgWater /= monthSize;
        monthAvgMuscle /= monthSize;
        monthAvgWaist /= monthSize;
        monthAvgHip /= monthSize;

        String info_week = new String();
        String info_month = new String();

        int lines = 1;

        info_week += String.format("Ø-"+getResources().getString(R.string.label_weight)+": %.1f" + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit] + "<br>", weekAvgWeight);
        info_month += String.format("Ø-"+getResources().getString(R.string.label_weight)+": %.1f" + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit] + "<br>", monthAvgWeight);
        lines++;

        info_week += String.format("Ø-"+getResources().getString(R.string.label_bmi)+": %.1f <br>", weekAvgBMI);
        info_month += String.format("Ø-"+getResources().getString(R.string.label_bmi)+": %.1f <br>", monthAvgBMI);
        lines++;

        if(prefs.getBoolean("fatEnable", true)) {
            info_week += String.format("Ø-"+getResources().getString(R.string.label_fat)+": %.1f%% <br>", weekAvgFat);
            info_month +=  String.format("Ø-"+getResources().getString(R.string.label_fat)+": %.1f%% <br>", monthAvgFat);
            lines++;
        }

        if(prefs.getBoolean("muscleEnable", true)) {
            info_week += String.format("Ø-"+getResources().getString(R.string.label_muscle)+": %.1f%% <br>", weekAvgWater);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_muscle)+": %.1f%% <br>", monthAvgWater);
            lines++;
        }

        if(prefs.getBoolean("waterEnable", true)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_water)+": %.1f%% <br>", weekAvgMuscle);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_water)+": %.1f%% <br>", monthAvgMuscle);
            lines++;
        }

        if(prefs.getBoolean("waistEnable", true)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_waist)+": %.1fcm <br>", weekAvgWaist);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_waist)+": %.1fcm <br>", monthAvgWaist);
            lines++;
        }

        if(prefs.getBoolean("hipEnable", true)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_hip)+": %.1fcm <br>", weekAvgHip);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_hip)+": %.1fcm <br>",monthAvgHip);
            lines++;
        }

        txtLabelAvgWeek.setLines(lines);
        txtLabelAvgMonth.setLines(lines);

        txtLabelAvgWeek.setText(Html.fromHtml(getResources().getString(R.string.label_last_week) + " <br> <font color='grey'><small> " + info_week + "</small></font>"));
        txtLabelAvgMonth.setText(Html.fromHtml(getResources().getString(R.string.label_last_month) + " <br> <font color='grey'><small> " + info_month + "</small></font>"));

        txtAvgWeek.setText(weekSize + " " + getResources().getString(R.string.label_measures));
        txtAvgMonth.setText(monthSize + " " + getResources().getString(R.string.label_measures));
    }

    private void updateLastLineChart(ArrayList<ScaleData> scaleDataList) {
        List<AxisValue> axisValues = new ArrayList<AxisValue>();

        List<PointValue> valuesWeight = new ArrayList<PointValue>();
        List<PointValue> valuesFat = new ArrayList<PointValue>();
        List<PointValue> valuesWater = new ArrayList<PointValue>();
        List<PointValue> valuesMuscle = new ArrayList<PointValue>();
        List<PointValue> valuesWaist = new ArrayList<PointValue>();
        List<PointValue> valuesHip = new ArrayList<PointValue>();
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
            valuesWaist.add(new PointValue(i, histData.waist));
            valuesHip.add(new PointValue(i, histData.hip));

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
        Line lineWaist = new Line(valuesWaist).
                setColor(Color.MAGENTA).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));
        Line lineHip = new Line(valuesHip).
                setColor(Color.YELLOW).
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

        if(prefs.getBoolean("waistEnable", true)) {
            lines.add(lineWaist);
            activeLines.add(OverviewFragment.lines.WAIST);

        }

        if(prefs.getBoolean("hipEnable", true)) {
            lines.add(lineHip);
            activeLines.add(OverviewFragment.lines.HIP);
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

        if (lastScaleData.fat == 0) {
            arcValuesLast.add(new SliceValue(1, ChartUtils.COLOR_ORANGE));
        }
        else {
            arcValuesLast.add(new SliceValue(lastScaleData.fat, ChartUtils.COLOR_ORANGE));
        }

        if (lastScaleData.water == 0) {
            arcValuesLast.add(new SliceValue(1, ChartUtils.COLOR_BLUE));
        }
        else {
            arcValuesLast.add(new SliceValue(lastScaleData.water, ChartUtils.COLOR_BLUE));
        }

        if (lastScaleData.muscle == 0) {
            arcValuesLast.add(new SliceValue(1, ChartUtils.COLOR_GREEN));
        }
        else {
            arcValuesLast.add(new SliceValue(lastScaleData.muscle, ChartUtils.COLOR_GREEN));
        }

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
		Intent intent = new Intent(overviewView.getContext(), DataEntryActivity.class);
        intent.putExtra("mode", DataEntryActivity.ADD_DATA_REQUEST);
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
                case WAIST:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_waist) + " " + scaleData.waist + "cm " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                case HIP:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_hip) + " " + scaleData.hip + "cm " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onValueDeselected() {

        }
    }

    private class spinUserSelectionListener implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
             if (parent.getChildCount() > 0) {
                 ((TextView) parent.getChildAt(0)).setTextColor(Color.GRAY);

                 ArrayList<ScaleUser> scaleUserList = OpenScale.getInstance(overviewView.getContext()).getScaleUserList();

                 ScaleUser scaleUser = scaleUserList.get(position);

                 SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                 prefs.edit().putInt("selectedUserId", scaleUser.id).commit();
             }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }
}
