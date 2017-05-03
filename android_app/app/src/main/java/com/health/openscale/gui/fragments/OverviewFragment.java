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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.views.BMIMeasurementView;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.HipMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MuscleMeasurementView;
import com.health.openscale.gui.views.WHRMeasurementView;
import com.health.openscale.gui.views.WHtRMeasurementView;
import com.health.openscale.gui.views.WaistMeasurementView;
import com.health.openscale.gui.views.WaterMeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

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

    private TableLayout tableOverviewLayout;

    private ArrayList<MeasurementView> overviewMeasurements;

    private PieChartView pieChartLast;
    private LineChartView lineChartLast;

    private Spinner spinUser;

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

        tableOverviewLayout = (TableLayout)overviewView.findViewById(R.id.tableLayoutMeasurements);

        overviewMeasurements = new ArrayList<>();

        overviewMeasurements.add(new WeightMeasurementView(context));
        overviewMeasurements.add(new BMIMeasurementView(context));
        overviewMeasurements.add(new WaterMeasurementView(context));
        overviewMeasurements.add(new MuscleMeasurementView(context));
        overviewMeasurements.add(new FatMeasurementView(context));
        overviewMeasurements.add(new WaistMeasurementView(context));
        overviewMeasurements.add(new WHtRMeasurementView(context));
        overviewMeasurements.add(new HipMeasurementView(context));
        overviewMeasurements.add(new WHRMeasurementView(context));

        for (MeasurementView measuremt : overviewMeasurements) {
            tableOverviewLayout.addView(measuremt);
        }

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

        OpenScale.getInstance(getContext()).registerFragment(this);

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

        updateUserSelection();
        updateLastPieChart();
        updateLastLineChart(scaleDataList);

        ScaleData[] tupleScaleData = OpenScale.getInstance(context).getTupleScaleData(lastScaleData.getId());
        ScaleData prevScaleData = tupleScaleData[0];

        if (prevScaleData == null) {
            prevScaleData = new ScaleData();
        }

        for (MeasurementView measuremt : overviewMeasurements) {
            measuremt.updatePreferences(prefs);
            measuremt.updateValue(lastScaleData);
            measuremt.updateDiff(lastScaleData, prevScaleData);
        }
    }

    private void updateUserSelection() {

        currentScaleUser =  OpenScale.getInstance(getContext()).getSelectedScaleUser();

        userSelectedData = null;

        spinUserAdapter.clear();
        ArrayList<ScaleUser> scaleUserList = OpenScale.getInstance(getContext()).getScaleUserList();

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
            lastDate.setTime(scaleDataList.get(0).getDateTime());
        }

        scaleDataLastDays = new ArrayList<ScaleData>();

        for (int i=0; i<max_i; i++) {
            ScaleData histData = scaleDataList.get(max_i - i - 1);

            scaleDataLastDays.add(histData);

            valuesWeight.add(new PointValue(i, histData.getConvertedWeight(currentScaleUser.scale_unit)));
            if (histData.getFat() != 0.0f)
                valuesFat.add(new PointValue(i, histData.getFat()));
            if (histData.getWater() != 0.0f)
                valuesWater.add(new PointValue(i, histData.getWater()));
            if (histData.getMuscle() != 0.0f)
                valuesMuscle.add(new PointValue(i, histData.getMuscle()));
            if (histData.getWaist() != 0.0f)
                valuesWaist.add(new PointValue(i, histData.getWaist()));
            if (histData.getHip() != 0.0f)
                valuesHip.add(new PointValue(i, histData.getHip()));

            histDate.setTime(histData.getDateTime());

            long days = 0 - daysBetween(lastDate, histDate);

            if (days == 0 && !scaleDataList.isEmpty()) {
                axisValues.add(new AxisValue(i, DateFormat.getDateInstance(DateFormat.SHORT).format(scaleDataList.get(0).getDateTime()).toCharArray()));
            } else {
                axisValues.add(new AxisValue(i, String.format("%d " + getResources().getString(R.string.label_days), days).toCharArray()));
            }
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

        if(prefs.getBoolean("waistEnable", false)) {
            lines.add(lineWaist);
        }

        if(prefs.getBoolean("hipEnable", false)) {
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

        if (lastScaleData.getFat() == 0) {
            arcValuesLast.add(new SliceValue(1, ChartUtils.COLOR_ORANGE));
        }
        else {
            arcValuesLast.add(new SliceValue(lastScaleData.getFat(), ChartUtils.COLOR_ORANGE));
        }

        if (lastScaleData.getWater() == 0) {
            arcValuesLast.add(new SliceValue(1, ChartUtils.COLOR_BLUE));
        }
        else {
            arcValuesLast.add(new SliceValue(lastScaleData.getWater(), ChartUtils.COLOR_BLUE));
        }

        if (lastScaleData.getMuscle() == 0) {
            arcValuesLast.add(new SliceValue(1, ChartUtils.COLOR_GREEN));
        }
        else {
            arcValuesLast.add(new SliceValue(lastScaleData.getMuscle(), ChartUtils.COLOR_GREEN));
        }

        PieChartData pieChartData = new PieChartData(arcValuesLast);
        pieChartData.setHasLabels(false);
        pieChartData.setHasCenterCircle(true);
        pieChartData.setCenterText1(String.format("%.2f %s", lastScaleData.getConvertedWeight(currentScaleUser.scale_unit), ScaleUser.UNIT_STRING[currentScaleUser.scale_unit]));
        pieChartData.setCenterText2(DateFormat.getDateInstance(DateFormat.MEDIUM).format(lastScaleData.getDateTime()));


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
        startActivityForResult(intent, 1);
	}

    private class PieChartLastTouchListener implements PieChartOnValueSelectListener
    {
        @Override
        public void onValueSelected(int i, SliceValue arcValue) {
            if (lastScaleData == null) {
                return;
            }

            String date_time = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(lastScaleData.getDateTime());

            switch (i) {
                case 0:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_fat) + " " + lastScaleData.getFat() + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                case 1:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_water) + " " + lastScaleData.getWater() + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                case 2:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_muscle) + " " + lastScaleData.getMuscle() + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
            }
        }

        @Override
        public void onValueDeselected() {

        }
    }

    private class LineChartTouchListener implements LineChartOnValueSelectListener {
        @Override
        public void onValueSelected(int lineIndex, int pointIndex, PointValue pointValue) {
            userSelectedData = scaleDataLastDays.get(pointIndex);

            updateOnView( OpenScale.getInstance(getContext()).getScaleDataList());
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

                 ArrayList<ScaleUser> scaleUserList = OpenScale.getInstance(getContext()).getScaleUserList();

                 ScaleUser scaleUser = scaleUserList.get(position);

                 SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                 prefs.edit().putInt("selectedUserId", scaleUser.id).commit();
                 OpenScale.getInstance(getContext()).updateScaleData();
             }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }

}
