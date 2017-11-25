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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.datatypes.ScaleUser;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class StatisticsFragment extends Fragment implements FragmentUpdateListener {

    private View statisticsView;

    private TextView txtTitleGoal;
    private TextView txtTitleStatistics;

    private TextView txtGoalWeight;
    private TextView txtGoalDiff;
    private TextView txtGoalDayLeft;

    private TextView txtAvgWeek;
    private TextView txtAvgMonth;

    private TextView txtLabelGoalWeight;
    private TextView txtLabelGoalDiff;
    private TextView txtLabelDayLeft;

    private TextView txtLabelAvgWeek;
    private TextView txtLabelAvgMonth;

    private SharedPreferences prefs;
    private ScaleUser currentScaleUser;
    private ScaleData lastScaleData;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        statisticsView = inflater.inflate(R.layout.fragment_statistics, container, false);

        txtTitleGoal = (TextView) statisticsView.findViewById(R.id.txtTitleGoal);
        txtTitleStatistics = (TextView) statisticsView.findViewById(R.id.txtTitleStatistics);

        txtGoalWeight = (TextView) statisticsView.findViewById(R.id.txtGoalWeight);
        txtGoalDiff = (TextView) statisticsView.findViewById(R.id.txtGoalDiff);
        txtGoalDayLeft = (TextView) statisticsView.findViewById(R.id.txtGoalDayLeft);

        txtAvgWeek = (TextView) statisticsView.findViewById(R.id.txtAvgWeek);
        txtAvgMonth = (TextView) statisticsView.findViewById(R.id.txtAvgMonth);

        txtLabelGoalWeight = (TextView) statisticsView.findViewById(R.id.txtLabelGoalWeight);
        txtLabelGoalDiff = (TextView) statisticsView.findViewById(R.id.txtLabelGoalDiff);
        txtLabelDayLeft = (TextView) statisticsView.findViewById(R.id.txtLabelDayLeft);

        txtLabelAvgWeek = (TextView) statisticsView.findViewById(R.id.txtLabelAvgWeek);
        txtLabelAvgMonth = (TextView) statisticsView.findViewById(R.id.txtLabelAvgMonth);

        OpenScale.getInstance(getContext()).registerFragment(this);

        return statisticsView;
    }

    @Override
    public void updateOnView(ArrayList<ScaleData> scaleDataList) {
        if (scaleDataList.isEmpty()) {
            lastScaleData = new ScaleData();
        } else {
            lastScaleData = scaleDataList.get(0);
        }

        txtTitleGoal.setText(getResources().getString(R.string.label_title_goal).toUpperCase());
        txtTitleStatistics.setText(getResources().getString(R.string.label_title_statistics).toUpperCase());

        prefs = PreferenceManager.getDefaultSharedPreferences(statisticsView.getContext());
        currentScaleUser =  OpenScale.getInstance(getContext()).getSelectedScaleUser();

        updateStatistics(scaleDataList);
        updateGoal(scaleDataList);
    }

    private void updateGoal(ArrayList<ScaleData> scaleDataList) {
        ScaleData goalScaleData = new ScaleData();
        goalScaleData.setConvertedWeight(currentScaleUser.goal_weight, currentScaleUser.scale_unit);

        txtGoalWeight.setText(goalScaleData.getConvertedWeight(currentScaleUser.scale_unit) + " " + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit]);

        double weight_diff = goalScaleData.getConvertedWeight(currentScaleUser.scale_unit) - lastScaleData.getConvertedWeight(currentScaleUser.scale_unit);
        txtGoalDiff.setText(String.format("%.1f " + ScaleUser.UNIT_STRING[currentScaleUser.scale_unit], weight_diff));

        Calendar goalDate = Calendar.getInstance();
        Calendar curDate = Calendar.getInstance();
        goalDate.setTime(currentScaleUser.goal_date);

        long days = daysBetween(curDate, goalDate);
        txtGoalDayLeft.setText(days + " " + getResources().getString(R.string.label_days));

        lastScaleData.setUserId(currentScaleUser.id);

        ScaleData goalData = new ScaleData();
        goalData.setConvertedWeight(currentScaleUser.goal_weight, currentScaleUser.scale_unit);
        goalData.setUserId(currentScaleUser.id);

        txtLabelGoalWeight.setText(
                Html.fromHtml(
                        getResources().getString(R.string.label_goal_weight) +
                                " <br> <font color='grey'><small>" +
                                getResources().getString(R.string.label_bmi) +
                                ": " +
                                String.format("%.1f", goalData.getBMI(currentScaleUser.body_height)) +
                                " </small></font>"
                )
        );
        txtLabelGoalDiff.setText(
                Html.fromHtml(
                        getResources().getString(R.string.label_weight_difference) +
                                " <br> <font color='grey'><small>" +
                                getResources().getString(R.string.label_bmi) +
                                ": " +
                                String.format("%.1f", lastScaleData.getBMI(currentScaleUser.body_height) - goalData.getBMI(currentScaleUser.body_height))  +
                                " </small></font>"
                )
        );
        txtLabelDayLeft.setText(
                Html.fromHtml(
                        getResources().getString(R.string.label_days_left) +
                                " <br> <font color='grey'><small>" +
                                getResources().getString(R.string.label_goal_date_is) +
                                " "
                                + DateFormat.getDateInstance(DateFormat.LONG).format(currentScaleUser.goal_date) +
                                " </small></font>"
                )
        ); // currentScaleUser.goal_date
    }

    private void updateStatistics(ArrayList<ScaleData> scaleDataList) {
        Calendar histDate = Calendar.getInstance();
        Calendar weekPastDate = Calendar.getInstance();
        Calendar monthPastDate = Calendar.getInstance();

        weekPastDate.setTime(lastScaleData.getDateTime());
        weekPastDate.add(Calendar.DATE, -7);

        monthPastDate.setTime(lastScaleData.getDateTime());
        monthPastDate.add(Calendar.DATE, -30);

        int weekSize = 0;
        float weekAvgWeight = 0;
        float weekAvgBMI = 0;
        float weekAvgFat = 0;
        float weekAvgWater = 0;
        float weekAvgMuscle = 0;
        float weekAvgLBW = 0;
        float weekAvgWaist = 0;
        float weekAvgBone = 0;
        float weekAvgWHtR = 0;
        float weekAvgHip = 0;
        float weekAvgWHR = 0;

        int monthSize = 0;
        float monthAvgWeight = 0;
        float monthAvgBMI = 0;
        float monthAvgFat = 0;
        float monthAvgWater = 0;
        float monthAvgMuscle = 0;
        float monthAvgLBW = 0;
        float monthAvgWaist = 0;
        float monthAvgBone = 0;
        float monthAvgWHtR = 0;
        float monthAvgHip = 0;
        float monthAvgWHR = 0;

        for (ScaleData scaleData : scaleDataList)
        {
            histDate.setTime(scaleData.getDateTime());

            if (weekPastDate.before(histDate)) {
                weekSize++;

                weekAvgWeight += scaleData.getConvertedWeight(currentScaleUser.scale_unit);
                weekAvgBMI += scaleData.getBMI(currentScaleUser.body_height);
                weekAvgFat += scaleData.getFat();
                weekAvgWater += scaleData.getWater();
                weekAvgMuscle += scaleData.getMuscle();
                weekAvgLBW += scaleData.getLBW();
                weekAvgBone += scaleData.getBone();
                weekAvgWaist += scaleData.getWaist();
                weekAvgHip += scaleData.getHip();
                weekAvgWHtR += scaleData.getWHtR(currentScaleUser.body_height);
                weekAvgWHR += scaleData.getWHR();
            }

            if (monthPastDate.before(histDate)) {
                monthSize++;

                monthAvgWeight += scaleData.getConvertedWeight(currentScaleUser.scale_unit);
                monthAvgBMI += scaleData.getBMI(currentScaleUser.body_height);
                monthAvgFat += scaleData.getFat();
                monthAvgWater += scaleData.getWater();
                monthAvgMuscle += scaleData.getMuscle();
                monthAvgLBW += scaleData.getLBW();
                monthAvgBone += scaleData.getBone();
                monthAvgWaist += scaleData.getWaist();
                monthAvgHip += scaleData.getHip();
                monthAvgWHtR += scaleData.getWHtR(currentScaleUser.body_height);
                monthAvgWHR += scaleData.getWHR();
            } else {
                break;
            }
        }

        weekAvgWeight /= weekSize;
        weekAvgBMI /= weekSize;
        weekAvgFat /= weekSize;
        weekAvgWater /= weekSize;
        weekAvgMuscle /= weekSize;
        weekAvgLBW /= weekSize;
        weekAvgWaist /= weekSize;
        weekAvgBone /= weekSize;
        weekAvgWHtR /= weekSize;
        weekAvgHip /= weekSize;
        weekAvgWHR /= weekSize;

        monthAvgWeight /= monthSize;
        monthAvgBMI /= monthSize;
        monthAvgFat /= monthSize;
        monthAvgWater /= monthSize;
        monthAvgMuscle /= monthSize;
        monthAvgLBW /= monthSize;
        monthAvgBone /= monthSize;
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

        if (prefs.getBoolean("fatEnable", true)) {
            info_week += String.format("Ø-"+getResources().getString(R.string.label_fat)+": %.1f%% <br>", weekAvgFat);
            info_month +=  String.format("Ø-"+getResources().getString(R.string.label_fat)+": %.1f%% <br>", monthAvgFat);
            lines++;
        }

        if (prefs.getBoolean("muscleEnable", true)) {
            info_week += String.format("Ø-"+getResources().getString(R.string.label_muscle)+": %.1f%% <br>", weekAvgMuscle);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_muscle)+": %.1f%% <br>", monthAvgMuscle);
            lines++;
        }

        if (prefs.getBoolean("lbwEnable", false)) {
            info_week += String.format("Ø-"+getResources().getString(R.string.label_lbw)+": %.1fkg <br>", weekAvgLBW);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_lbw)+": %.1fkg <br>", monthAvgLBW);
            lines++;
        }

        if (prefs.getBoolean("waterEnable", true)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_water)+": %.1f%% <br>", weekAvgWater);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_water)+": %.1f%% <br>", monthAvgWater);
            lines++;
        }

        if (prefs.getBoolean("boneEnable", false)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_bone)+": %.1fkg <br>", weekAvgBone);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_bone)+": %.1fkg <br>",monthAvgBone);
            lines++;
        }


        if (prefs.getBoolean("waistEnable", false)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_waist)+": %.1fcm <br>", weekAvgWaist);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_waist)+": %.1fcm <br>", monthAvgWaist);
            lines++;

            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_whtr)+": %.2f <br>", weekAvgWHtR);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_whtr)+": %.2f <br>", monthAvgWHtR);
            lines++;
        }

        if (prefs.getBoolean("hipEnable", false)) {
            info_week +=  String.format("Ø-"+getResources().getString(R.string.label_hip)+": %.1fcm <br>", weekAvgHip);
            info_month += String.format("Ø-"+getResources().getString(R.string.label_hip)+": %.1fcm <br>",monthAvgHip);
            lines++;
        }

        if (prefs.getBoolean("hipEnable", false) && prefs.getBoolean("waistEnable", false)) {
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

    private long daysBetween(Calendar startDate, Calendar endDate) {
        long end = endDate.getTimeInMillis();
        long start = startDate.getTimeInMillis();
        return TimeUnit.MILLISECONDS.toDays(Math.abs(end - start));
    }
}
