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
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.EvaluationResult;
import com.health.openscale.core.EvaluationSheet;
import com.health.openscale.core.LinearGaugeView;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.ScaleData;
import com.health.openscale.core.ScaleUser;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.ListIterator;
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
    private TextView txtWHtRLast;
    private TextView txtHipLast;
    private TextView txtWHRLast;

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
    private TextView txtLabelWHtR;
    private TextView txtLabelHip;
    private TextView txtLabelWHR;

    private TextView txtLabelGoalWeight;
    private TextView txtLabelGoalDiff;
    private TextView txtLabelDayLeft;

    private TextView txtLabelAvgWeek;
    private TextView txtLabelAvgMonth;

    private PieChartView pieChartLast;
    private LineChartView lineChartLast;

    private Spinner spinUser;

    private LinearGaugeView linearGaugeWeight;
    private LinearGaugeView linearGaugeBMI;
    private LinearGaugeView linearGaugeFat;
    private LinearGaugeView linearGaugeMuscle;
    private LinearGaugeView linearGaugeWater;
    private LinearGaugeView linearGaugeWaist;
    private LinearGaugeView linearGaugeWHtR;
    private LinearGaugeView linearGaugeHip;
    private LinearGaugeView linearGaugeWHR;

    private SharedPreferences prefs;

    private ScaleData lastScaleData;
    private ScaleData userSelectedData;
    private ScaleUser currentScaleUser;

    private List<ScaleData> scaleDataLastDays;

    private ArrayAdapter<String> spinUserAdapter;

    private Context context;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // retain this fragment otherwise the app crashed in landscape mode for small devices (see "Handling Runtime Changes")
        setRetainInstance(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
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
        txtWHtRLast = (TextView) overviewView.findViewById(R.id.txtWHtRLast);
        txtHipLast = (TextView) overviewView.findViewById(R.id.txtHipLast);
        txtWHRLast = (TextView) overviewView.findViewById(R.id.txtWHRLast);

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
        txtLabelWHtR = (TextView) overviewView.findViewById(R.id.txtLabelWHtR);
        txtLabelHip = (TextView) overviewView.findViewById(R.id.txtLabelHip);
        txtLabelWHR = (TextView) overviewView.findViewById(R.id.txtLabelWHR);

        txtLabelGoalWeight = (TextView) overviewView.findViewById(R.id.txtLabelGoalWeight);
        txtLabelGoalDiff = (TextView) overviewView.findViewById(R.id.txtLabelGoalDiff);
        txtLabelDayLeft = (TextView) overviewView.findViewById(R.id.txtLabelDayLeft);

        txtLabelAvgWeek = (TextView) overviewView.findViewById(R.id.txtLabelAvgWeek);
        txtLabelAvgMonth = (TextView) overviewView.findViewById(R.id.txtLabelAvgMonth);

        pieChartLast = (PieChartView) overviewView.findViewById(R.id.pieChartLast);
        lineChartLast = (LineChartView) overviewView.findViewById(R.id.lineChartLast);

        spinUser = (Spinner) overviewView.findViewById(R.id.spinUser);

        linearGaugeWeight = (LinearGaugeView) overviewView.findViewById(R.id.linearGaugeWeight);
        linearGaugeBMI = (LinearGaugeView) overviewView.findViewById(R.id.linearGaugeBMI);
        linearGaugeFat = (LinearGaugeView) overviewView.findViewById(R.id.linearGaugeFat);
        linearGaugeMuscle = (LinearGaugeView) overviewView.findViewById(R.id.linearGaugeMuscle);
        linearGaugeWater = (LinearGaugeView) overviewView.findViewById(R.id.linearGaugeWater);
        linearGaugeWaist = (LinearGaugeView) overviewView.findViewById(R.id.linearGaugeWaist);
        linearGaugeWHtR = (LinearGaugeView) overviewView.findViewById(R.id.linearGaugeWHtR);
        linearGaugeHip = (LinearGaugeView) overviewView.findViewById(R.id.linearGaugeHip);
        linearGaugeWHR = (LinearGaugeView) overviewView.findViewById(R.id.linearGaugeWHR);

        lineChartLast.setOnValueTouchListener(new LineChartTouchListener());

        pieChartLast.setOnValueTouchListener(new PieChartLastTouchListener());
        pieChartLast.setChartRotationEnabled(false);

        overviewView.findViewById(R.id.btnInsertData).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                btnOnClickInsertData();
            }
        });

        overviewView.findViewById(R.id.tableRowWeight).setOnClickListener(new onClickListenerEvaluation());
        overviewView.findViewById(R.id.tableRowBMI).setOnClickListener(new onClickListenerEvaluation());
        overviewView.findViewById(R.id.tableRowFat).setOnClickListener(new onClickListenerEvaluation());
        overviewView.findViewById(R.id.tableRowMuscle).setOnClickListener(new onClickListenerEvaluation());
        overviewView.findViewById(R.id.tableRowWater).setOnClickListener(new onClickListenerEvaluation());
        overviewView.findViewById(R.id.tableRowWaist).setOnClickListener(new onClickListenerEvaluation());
        overviewView.findViewById(R.id.tableRowWHtR).setOnClickListener(new onClickListenerEvaluation());
        overviewView.findViewById(R.id.tableRowHip).setOnClickListener(new onClickListenerEvaluation());
        overviewView.findViewById(R.id.tableRowWHR).setOnClickListener(new onClickListenerEvaluation());

        userSelectedData = null;

        spinUserAdapter = new ArrayAdapter<>(overviewView.getContext(), R.layout.support_simple_spinner_dropdown_item, new ArrayList<String>());
        spinUser.setAdapter(spinUserAdapter);

        // Set item select listener after spinner is created because otherwise item listener fires a lot!?!?
        spinUser.post(new Runnable() {
            public void run() {
                spinUser.setOnItemSelectedListener(new spinUserSelectionListener());
                updateUserSelection();
            }
        });

        OpenScale.getInstance(overviewView.getContext()).registerFragment(this);

        return overviewView;
    }

    @Override
    public void updateOnView(ArrayList<ScaleData> scaleDataList) {
        if (scaleDataList.isEmpty()) {
            lastScaleData = new ScaleData();
        } else if (userSelectedData != null) {
            lastScaleData = userSelectedData;
        }
        else {
                lastScaleData = scaleDataList.get(0);
        }


        prefs = PreferenceManager.getDefaultSharedPreferences(overviewView.getContext());

        txtTitleUser.setText(getResources().getString(R.string.label_title_user).toUpperCase());
        txtTitleLastMeasurement.setText(getResources().getString(R.string.label_title_last_measurement).toUpperCase());
        txtTitleGoal.setText(getResources().getString(R.string.label_title_goal).toUpperCase());
        txtTitleStatistics.setText(getResources().getString(R.string.label_title_statistics).toUpperCase());

        updateUserSelection();
        updateVisibleRows();
        updateLastPieChart();
        updateLastLineChart(scaleDataList);
        updateLastMeasurement();
        updateGoal(scaleDataList);
        updateStatistics(scaleDataList);
        updateEvaluation();
    }

    private void updateUserSelection() {

        currentScaleUser =  OpenScale.getInstance(overviewView.getContext()).getSelectedScaleUser();

        spinUserAdapter.clear();
        ArrayList<ScaleUser> scaleUserList = OpenScale.getInstance(overviewView.getContext()).getScaleUserList();

        int posUser = 0;
        int pos = 0;

        for(ScaleUser scaleUser :scaleUserList) {
            spinUserAdapter.add(scaleUser.user_name);

            if (scaleUser.id == currentScaleUser.id) {
                posUser = pos;
            }

            pos++;
        }

        spinUser.setSelection(posUser, true);
    }

    private void updateVisibleRows() {
        if(!prefs.getBoolean("fatEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowFat);
            row.setVisibility(View.GONE);
        } else {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowFat);
            row.setVisibility(View.VISIBLE);
        }

        if(!prefs.getBoolean("muscleEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowMuscle);
            row.setVisibility(View.GONE);
        } else {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowMuscle);
            row.setVisibility(View.VISIBLE);
        }

        if(!prefs.getBoolean("waterEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowWater);
            row.setVisibility(View.GONE);
        } else {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowWater);
            row.setVisibility(View.VISIBLE);
        }

        if(!prefs.getBoolean("waistEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowWaist);
            row.setVisibility(View.GONE);

            row = (TableRow)overviewView.findViewById(R.id.tableRowWHtR);
            row.setVisibility(View.GONE);
        } else {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowWaist);
            row.setVisibility(View.VISIBLE);

            row = (TableRow)overviewView.findViewById(R.id.tableRowWHtR);
            row.setVisibility(View.VISIBLE);
        }

        if(!prefs.getBoolean("hipEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowHip);
            row.setVisibility(View.GONE);
        } else {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowHip);
            row.setVisibility(View.VISIBLE);
        }

        if(!prefs.getBoolean("hipEnable", true) || !prefs.getBoolean("waistEnable", true)) {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowWHR);
            row.setVisibility(View.GONE);
        } else {
            TableRow row = (TableRow)overviewView.findViewById(R.id.tableRowWHR);
            row.setVisibility(View.VISIBLE);
        }
    }

    private void updateEvaluation() {
        linearGaugeWeight.setMinMaxValue(30, 300);
        linearGaugeBMI.setMinMaxValue(10, 50);
        linearGaugeFat.setMinMaxValue(10, 40);
        linearGaugeMuscle.setMinMaxValue(10, 80);
        linearGaugeWater.setMinMaxValue(30, 80);
        linearGaugeWaist.setMinMaxValue(30, 200);
        linearGaugeWHtR.setMinMaxValue(0, 1);
        linearGaugeHip.setMinMaxValue(30, 200);
        linearGaugeWHR.setMinMaxValue(0, 1);

        EvaluationSheet evalSheet = new EvaluationSheet(currentScaleUser);

        EvaluationResult sheetWeight = evalSheet.evaluateWeight(lastScaleData.weight);
        EvaluationResult sheetBMI = evalSheet.evaluateBMI(currentScaleUser.getBMI(lastScaleData.weight));
        EvaluationResult sheetFat = evalSheet.evaluateBodyFat(lastScaleData.fat);
        EvaluationResult sheetMuscle = evalSheet.evaluateBodyMuscle(lastScaleData.muscle);
        EvaluationResult sheetWater = evalSheet.evaluateBodyWater(lastScaleData.water);
        EvaluationResult sheetWaist = evalSheet.evaluateWaist(lastScaleData.waist);
        EvaluationResult sheetWHtR = evalSheet.evaluateWHtR(currentScaleUser.getWHtR(lastScaleData.waist));
        EvaluationResult sheetWHR = evalSheet.evaluateWHR(currentScaleUser.getWHR(lastScaleData.waist, lastScaleData.hip));

        updateIndicator((ImageView)overviewView.findViewById(R.id.indicatorWeight), sheetWeight.eval_state);
        updateIndicator((ImageView)overviewView.findViewById(R.id.indicatorBMI), sheetBMI.eval_state);
        updateIndicator((ImageView)overviewView.findViewById(R.id.indicatorFat), sheetFat.eval_state);
        updateIndicator((ImageView)overviewView.findViewById(R.id.indicatorMuscle), sheetMuscle.eval_state);
        updateIndicator((ImageView)overviewView.findViewById(R.id.indicatorWater), sheetWater.eval_state);
        updateIndicator((ImageView) overviewView.findViewById(R.id.indicatorWaist), sheetWaist.eval_state);
        updateIndicator((ImageView)overviewView.findViewById(R.id.indicatorWHtR), sheetWHtR.eval_state);
        updateIndicator((ImageView)overviewView.findViewById(R.id.indicatorHip), EvaluationResult.EVAL_STATE.UNDEFINED);
        updateIndicator((ImageView)overviewView.findViewById(R.id.indicatorWHR), sheetWHR.eval_state);

        linearGaugeWeight.setLimits(sheetWeight.lowLimit, sheetWeight.highLimit);
        linearGaugeBMI.setLimits(sheetBMI.lowLimit, sheetBMI.highLimit);
        linearGaugeFat.setLimits(sheetFat.lowLimit, sheetFat.highLimit);
        linearGaugeMuscle.setLimits(sheetMuscle.lowLimit, sheetMuscle.highLimit);
        linearGaugeWater.setLimits(sheetWater.lowLimit, sheetWater.highLimit);
        linearGaugeWaist.setLimits(sheetWaist.lowLimit, sheetWaist.highLimit);
        linearGaugeWHtR.setLimits(sheetWHtR.lowLimit, sheetWHtR.highLimit);
        linearGaugeHip.setLimits(-1f, -1f);
        linearGaugeWHR.setLimits(sheetWHR.lowLimit, sheetWHR.highLimit);

        linearGaugeWeight.setValue(lastScaleData.weight);
        linearGaugeBMI.setValue(currentScaleUser.getBMI(lastScaleData.weight));
        linearGaugeFat.setValue(lastScaleData.fat);
        linearGaugeMuscle.setValue(lastScaleData.muscle);
        linearGaugeWater.setValue(lastScaleData.water);
        linearGaugeWaist.setValue(lastScaleData.waist);
        linearGaugeWHtR.setValue(currentScaleUser.getWHtR(lastScaleData.waist));
        linearGaugeHip.setValue(lastScaleData.hip);
        linearGaugeWHR.setValue(currentScaleUser.getWHR(lastScaleData.waist, lastScaleData.hip));
    }

    private void updateIndicator(ImageView view, EvaluationResult.EVAL_STATE state) {
        switch(state)
        {
            case LOW:
                view.setBackgroundColor(ChartUtils.COLOR_BLUE);
                break;
            case NORMAL:
                view.setBackgroundColor(ChartUtils.COLOR_GREEN);
                break;
            case HIGH:
                view.setBackgroundColor(ChartUtils.COLOR_RED);
                break;
            case UNDEFINED:
                view.setBackgroundColor(Color.GRAY);
                break;
        }
    }

    private void updateLastMeasurement() {
        txtWeightLast.setText(lastScaleData.weight + " " + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit]);
        txtBMILast.setText(String.format("%.1f", currentScaleUser.getBMI(lastScaleData.weight)));
        txtFatLast.setText(lastScaleData.fat + " %");
        txtWaterLast.setText(lastScaleData.water + " %");
        txtMuscleLast.setText(lastScaleData.muscle + " %");
        txtWaistLast.setText(lastScaleData.waist + " cm");
        txtWHtRLast.setText(String.format("%.2f", currentScaleUser.getWHtR(lastScaleData.waist)));
        txtHipLast.setText(lastScaleData.hip + " cm");
        txtWHRLast.setText(String.format("%.2f", currentScaleUser.getWHR(lastScaleData.waist, lastScaleData.hip)));
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

        ListIterator<ScaleData> scaleDataIterator = scaleDataList.listIterator();

        while(scaleDataIterator.hasNext()) {
            ScaleData scaleData = scaleDataIterator.next();

            if (scaleData.id == lastScaleData.id) {
                if (scaleDataIterator.hasNext()) {
                    ScaleData diffScaleData = scaleDataIterator.next();

                    double diffWeight = lastScaleData.weight - diffScaleData.weight;
                    double diffBMI = currentScaleUser.getBMI(lastScaleData.weight) - currentScaleUser.getBMI(diffScaleData.weight);
                    double diffFat = lastScaleData.fat - diffScaleData.fat;
                    double diffMuscle = lastScaleData.muscle - diffScaleData.muscle;
                    double diffWater = lastScaleData.water - diffScaleData.water;
                    double diffWaist = lastScaleData.waist - diffScaleData.waist;
                    double diffWHtR = currentScaleUser.getWHtR(lastScaleData.waist) - currentScaleUser.getWHtR(diffScaleData.waist);
                    double diffHip = lastScaleData.hip - diffScaleData.hip;
                    double diffWHR = currentScaleUser.getWHR(lastScaleData.waist, lastScaleData.hip) - currentScaleUser.getWHR(diffScaleData.waist, diffScaleData.hip);


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

                    if (diffWHtR > 0.0)
                        txtLabelWHtR.setText(Html.fromHtml(getResources().getString(R.string.label_whtr) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.2f", diffWHtR) + "</small></font>"));
                    else
                        txtLabelWHtR.setText(Html.fromHtml(getResources().getString(R.string.label_whtr) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.2f", diffWHtR) + "</small></font>"));

                    if (diffHip > 0.0)
                        txtLabelHip.setText(Html.fromHtml(getResources().getString(R.string.label_hip) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.1f", diffHip) + "cm</small></font>"));
                    else
                        txtLabelHip.setText(Html.fromHtml(getResources().getString(R.string.label_hip) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.1f", diffHip) + "cm</small></font>"));

                    if (diffWHR > 0.0)
                        txtLabelWHR.setText(Html.fromHtml(getResources().getString(R.string.label_whr) + " <br> <font color='grey'>&#x2197;<small> " + String.format("%.2f", diffWHR) + "</small></font>"));
                    else
                        txtLabelWHR.setText(Html.fromHtml(getResources().getString(R.string.label_whr) + " <br> <font color='grey'>&#x2198;<small> " + String.format("%.2f", diffWHR) + "</small></font>"));
                }
            }
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
        float weekAvgWHtR = 0;
        float weekAvgHip = 0;
        float weekAvgWHR = 0;

        int monthSize = 0;
        float monthAvgWeight = 0;
        float monthAvgBMI = 0;
        float monthAvgFat = 0;
        float monthAvgWater = 0;
        float monthAvgMuscle = 0;
        float monthAvgWaist = 0;
        float monthAvgWHtR = 0;
        float monthAvgHip = 0;
        float monthAvgWHR = 0;

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
                weekAvgWHtR += currentScaleUser.getWHtR(scaleData.waist);
                weekAvgWHR += currentScaleUser.getWHR(scaleData.waist, scaleData.hip);
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
                monthAvgWHtR += currentScaleUser.getWHtR(scaleData.waist);
                monthAvgWHR += currentScaleUser.getWHR(scaleData.waist, scaleData.hip);
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
        weekAvgWHtR /= weekSize;
        weekAvgHip /= weekSize;
        weekAvgWHR /= weekSize;

        monthAvgWeight /= monthSize;
        monthAvgBMI /= monthSize;
        monthAvgFat /= monthSize;
        monthAvgWater /= monthSize;
        monthAvgMuscle /= monthSize;
        monthAvgWaist /= monthSize;
        monthAvgWHtR /= monthSize;
        monthAvgHip /= monthSize;
        monthAvgWHR /= monthSize;

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
            info_week += String.format("Ø-"+getResources().getString(R.string.label_muscle)+": %.1f%% <br>", weekAvgMuscle);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_muscle)+": %.1f%% <br>", monthAvgMuscle);
            lines++;
        }

        if(prefs.getBoolean("waterEnable", true)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_water)+": %.1f%% <br>", weekAvgWater);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_water)+": %.1f%% <br>", monthAvgWater);
            lines++;
        }

        if(prefs.getBoolean("waistEnable", true)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_waist)+": %.1fcm <br>", weekAvgWaist);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_waist)+": %.1fcm <br>", monthAvgWaist);
            lines++;

            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_whtr)+": %.2f <br>", weekAvgWHtR);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_whtr)+": %.2f <br>", monthAvgWHtR);
            lines++;
        }

        if(prefs.getBoolean("hipEnable", true)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_hip)+": %.1fcm <br>", weekAvgHip);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_hip)+": %.1fcm <br>",monthAvgHip);
            lines++;
        }

        if(prefs.getBoolean("hipEnable", true) && prefs.getBoolean("waistEnable", true)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_whr)+": %.2f <br>", weekAvgWHR);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_whr)+": %.2f <br>", monthAvgWHR);
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

        if (!scaleDataList.isEmpty()) {
            lastDate.setTime(scaleDataList.get(0).date_time);
        }

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

            if (days == 0 && !scaleDataList.isEmpty()) {
                axisValues.add(new AxisValue(i, DateFormat.getDateInstance(DateFormat.SHORT).format(scaleDataList.get(0).date_time).toCharArray()));
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

        if(prefs.getBoolean("weightEnable", true)) {
            lines.add(lineWeight);
        }

        if(prefs.getBoolean("fatEnable", true)) {
            lines.add(lineFat);
        }

        if(prefs.getBoolean("waterEnable", true)) {
            lines.add(lineWater);
        }

        if(prefs.getBoolean("muscleEnable", true)) {
            lines.add(lineMuscle);
        }

        if(prefs.getBoolean("waistEnable", true)) {
            lines.add(lineWaist);
        }

        if(prefs.getBoolean("hipEnable", true)) {
            lines.add(lineHip);
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
        } else {
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
            userSelectedData = scaleDataLastDays.get(pointIndex);

            updateOnView( OpenScale.getInstance(overviewView.getContext()).getScaleDataList());
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
                 OpenScale.getInstance(overviewView.getContext()).updateScaleData();
             }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }

    private class onClickListenerEvaluation implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            TableRow row = (TableRow)v;

            TableLayout tableLayout = (TableLayout)row.getParent();
            int index = tableLayout.indexOfChild(row);

            TableRow rowEvaluation = (TableRow)tableLayout.getChildAt(index+1);

            if (rowEvaluation.getVisibility() == View.VISIBLE) {
                rowEvaluation.setVisibility(View.GONE);
            } else {
                rowEvaluation.setVisibility(View.VISIBLE);
            }
        }
    }
}
