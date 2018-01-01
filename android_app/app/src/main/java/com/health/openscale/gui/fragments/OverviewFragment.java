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
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.DateTimeHelpers;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.views.BMIMeasurementView;
import com.health.openscale.gui.views.BMRMeasurementView;
import com.health.openscale.gui.views.BoneMeasurementView;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.HipMeasurementView;
import com.health.openscale.gui.views.LBWMeasurementView;
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
    private View userLineSeparator;

    private TextView txtTitleUser;
    private TextView txtTitleLastMeasurement;

    private TableLayout tableOverviewLayout;

    private ArrayList<MeasurementView> overviewMeasurements;

    private PieChartView pieChartLast;
    private LineChartView lineChartLast;

    private Spinner spinUser;

    private SharedPreferences prefs;

    private ScaleMeasurement lastScaleMeasurement;
    private ScaleMeasurement userSelectedData;
    private ScaleUser currentScaleUser;

    private List<ScaleMeasurement> scaleMeasurementLastDays;

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
        userLineSeparator = overviewView.findViewById(R.id.userLineSeparator);

        context = overviewView.getContext();

        txtTitleUser = (TextView) overviewView.findViewById(R.id.txtTitleUser);
        txtTitleLastMeasurement = (TextView) overviewView.findViewById(R.id.txtTitleLastMeasurment);

        tableOverviewLayout = (TableLayout)overviewView.findViewById(R.id.tableLayoutMeasurements);

        overviewMeasurements = new ArrayList<>();

        overviewMeasurements.add(new WeightMeasurementView(context));
        overviewMeasurements.add(new BMIMeasurementView(context));
        overviewMeasurements.add(new WaterMeasurementView(context));
        overviewMeasurements.add(new MuscleMeasurementView(context));
        overviewMeasurements.add(new LBWMeasurementView(context));
        overviewMeasurements.add(new FatMeasurementView(context));
        overviewMeasurements.add(new BoneMeasurementView(context));
        overviewMeasurements.add(new WaistMeasurementView(context));
        overviewMeasurements.add(new WHtRMeasurementView(context));
        overviewMeasurements.add(new HipMeasurementView(context));
        overviewMeasurements.add(new WHRMeasurementView(context));
        overviewMeasurements.add(new BMRMeasurementView(context));

        for (MeasurementView measurement : overviewMeasurements) {
            tableOverviewLayout.addView(measurement);
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
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList) {
        if (scaleMeasurementList.isEmpty()) {
            lastScaleMeasurement = new ScaleMeasurement();
        } else if (userSelectedData != null) {
            lastScaleMeasurement = userSelectedData;
        }
        else {
            lastScaleMeasurement = scaleMeasurementList.get(0);
        }


        prefs = PreferenceManager.getDefaultSharedPreferences(overviewView.getContext());

        txtTitleUser.setText(getResources().getString(R.string.label_title_user).toUpperCase());
        txtTitleLastMeasurement.setText(getResources().getString(R.string.label_title_last_measurement).toUpperCase());

        updateUserSelection();
        updateLastPieChart();
        updateLastLineChart(scaleMeasurementList);

        ScaleMeasurement[] tupleScaleData = OpenScale.getInstance(context).getTupleScaleData(lastScaleMeasurement.getId());
        ScaleMeasurement prevScaleMeasurement = tupleScaleData[0];

        if (prevScaleMeasurement == null) {
            prevScaleMeasurement = new ScaleMeasurement();
        }

        for (MeasurementView measurement : overviewMeasurements) {
            measurement.updatePreferences(prefs);
            measurement.updateValue(lastScaleMeasurement);
            measurement.updateDiff(lastScaleMeasurement, prevScaleMeasurement);
        }
    }

    private void updateUserSelection() {

        currentScaleUser = OpenScale.getInstance(getContext()).getSelectedScaleUser();

        userSelectedData = null;

        spinUserAdapter.clear();
        List<ScaleUser> scaleUserList = OpenScale.getInstance(getContext()).getScaleUserList();

        int posUser = 0;

        for (ScaleUser scaleUser : scaleUserList) {
            spinUserAdapter.add(scaleUser.getUserName());

            if (scaleUser.getId() == currentScaleUser.getId()) {
                posUser = spinUserAdapter.getCount() - 1;
            }
        }

        spinUser.setSelection(posUser, true);

        // Hide user selector when there is only one user
        int visibility = spinUserAdapter.getCount() < 2 ? View.GONE : View.VISIBLE;
        txtTitleUser.setVisibility(visibility);
        spinUser.setVisibility(visibility);
        userLineSeparator.setVisibility(visibility);
    }


    private void updateLastLineChart(List<ScaleMeasurement> scaleMeasurementList) {
        List<AxisValue> axisValues = new ArrayList<AxisValue>();

        List<PointValue> valuesWeight = new ArrayList<PointValue>();
        List<PointValue> valuesFat = new ArrayList<PointValue>();
        List<PointValue> valuesWater = new ArrayList<PointValue>();
        List<PointValue> valuesMuscle = new ArrayList<PointValue>();
        List<PointValue> valuesLBW = new ArrayList<PointValue>();
        List<PointValue> valuesWaist = new ArrayList<PointValue>();
        List<PointValue> valuesHip = new ArrayList<PointValue>();
        List<PointValue> valuesBone = new ArrayList<PointValue>();
        List<Line> lines = new ArrayList<Line>();

        int max_i = 7;

        if (scaleMeasurementList.size() < 7) {
            max_i = scaleMeasurementList.size();
        }

        final Calendar now = Calendar.getInstance();
        Calendar histCalendar = Calendar.getInstance();

        scaleMeasurementLastDays = new ArrayList<ScaleMeasurement>();

        for (int i=0; i<max_i; i++) {
            ScaleMeasurement histData = scaleMeasurementList.get(max_i - i - 1);

            scaleMeasurementLastDays.add(histData);

            valuesWeight.add(new PointValue(i, histData.getConvertedWeight(currentScaleUser.getScaleUnit())));
            if (histData.getFat() != 0.0f)
                valuesFat.add(new PointValue(i, histData.getFat()));
            if (histData.getWater() != 0.0f)
                valuesWater.add(new PointValue(i, histData.getWater()));
            if (histData.getMuscle() != 0.0f)
                valuesMuscle.add(new PointValue(i, histData.getMuscle()));
            if (histData.getLbw() != 0.0f)
                valuesLBW.add(new PointValue(i, histData.getLbw()));
            if (histData.getWaist() != 0.0f)
                valuesWaist.add(new PointValue(i, histData.getWaist()));
            if (histData.getHip() != 0.0f)
                valuesHip.add(new PointValue(i, histData.getHip()));
            if (histData.getBone() != 0.0f)
                valuesBone.add(new PointValue(i, histData.getBone()));

            histCalendar.setTime(histData.getDateTime());
            int days = DateTimeHelpers.daysBetween(now, histCalendar);
            String label = getResources().getQuantityString(R.plurals.label_days, Math.abs(days), days);
            axisValues.add(new AxisValue(i, label.toCharArray()));
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
        Line lineLBW = new Line(valuesLBW).
                setColor(Color.parseColor("#cc0099")).
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
        Line lineBone = new Line(valuesBone).
                setColor(Color.parseColor("#00cc9e")).
                setHasLabels(prefs.getBoolean("labelsEnable", true)).
                setHasPoints(prefs.getBoolean("pointsEnable", true)).
                setFormatter(new SimpleLineChartValueFormatter(1));

        if (prefs.getBoolean("weightEnable", true)) {
            lines.add(lineWeight);
        }

        if (prefs.getBoolean("fatEnable", true)) {
            lines.add(lineFat);
        }

        if (prefs.getBoolean("waterEnable", true)) {
            lines.add(lineWater);
        }

        if (prefs.getBoolean("muscleEnable", true)) {
            lines.add(lineMuscle);
        }

        if (prefs.getBoolean("lbwEnable", false)) {
            lines.add(lineLBW);
        }

        if (prefs.getBoolean("waistEnable", false)) {
            lines.add(lineWaist);
        }

        if (prefs.getBoolean("hipEnable", false)) {
            lines.add(lineHip);
        }

        if (prefs.getBoolean("boneEnable", false)) {
            lines.add(lineBone);
        }

        LineChartData lineData = new LineChartData(lines);
        lineData.setAxisXBottom(new Axis(axisValues).
                        setHasLines(true).
                        setTextColor(Color.BLACK)
        );

        lineData.setAxisYLeft(new Axis().
                        setHasLines(true).
                        setMaxLabelChars(5).
                        setTextColor(Color.BLACK)
        );

        lineChartLast.setLineChartData(lineData);
        lineChartLast.setViewportCalculationEnabled(true);

        lineChartLast.setZoomEnabled(false);
    }

    private void updateLastPieChart() {

        List<SliceValue> arcValuesLast = new ArrayList<SliceValue>();

        if (lastScaleMeasurement.getFat() == 0) {
            arcValuesLast.add(new SliceValue(1, ChartUtils.COLOR_ORANGE));
        }
        else {
            arcValuesLast.add(new SliceValue(lastScaleMeasurement.getFat(), ChartUtils.COLOR_ORANGE));
        }

        if (lastScaleMeasurement.getWater() == 0) {
            arcValuesLast.add(new SliceValue(1, ChartUtils.COLOR_BLUE));
        }
        else {
            arcValuesLast.add(new SliceValue(lastScaleMeasurement.getWater(), ChartUtils.COLOR_BLUE));
        }

        if (lastScaleMeasurement.getMuscle() == 0) {
            arcValuesLast.add(new SliceValue(1, ChartUtils.COLOR_GREEN));
        }
        else {
            arcValuesLast.add(new SliceValue(lastScaleMeasurement.getMuscle(), ChartUtils.COLOR_GREEN));
        }

        PieChartData pieChartData = new PieChartData(arcValuesLast);
        pieChartData.setHasLabels(false);
        pieChartData.setHasCenterCircle(true);
        pieChartData.setCenterText1(String.format("%.2f %s", lastScaleMeasurement.getConvertedWeight(currentScaleUser.getScaleUnit()), ScaleUser.UNIT_STRING[currentScaleUser.getScaleUnit()]));
        pieChartData.setCenterText2(DateFormat.getDateInstance(DateFormat.MEDIUM).format(lastScaleMeasurement.getDateTime()));


        if ((getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_XLARGE ||
            (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) == Configuration.SCREENLAYOUT_SIZE_LARGE) {
            pieChartData.setCenterText1FontSize(20);
            pieChartData.setCenterText2FontSize(14);
        } else {
            pieChartData.setCenterText1FontSize(15);
            pieChartData.setCenterText2FontSize(12);
            pieChartData.setValueLabelTextSize(12);
        }

        pieChartLast.setPieChartData(pieChartData);
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
            if (lastScaleMeasurement == null) {
                return;
            }

            String date_time = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(lastScaleMeasurement.getDateTime());

            switch (i) {
                case 0:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_fat) + " " + lastScaleMeasurement.getFat() + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                case 1:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_water) + " " + lastScaleMeasurement.getWater() + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
                    break;
                case 2:
                    Toast.makeText(getActivity(), getResources().getString(R.string.info_your_muscle) + " " + lastScaleMeasurement.getMuscle() + "% " + getResources().getString(R.string.info_on_date) + " " + date_time, Toast.LENGTH_SHORT).show();
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
            userSelectedData = scaleMeasurementLastDays.get(pointIndex);

            updateOnView(OpenScale.getInstance(getContext()).getScaleMeasurementList());
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

                 List<ScaleUser> scaleUserList = OpenScale.getInstance(getContext()).getScaleUserList();

                 ScaleUser scaleUser = scaleUserList.get(position);

                 SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                 prefs.edit().putInt("selectedUserId", scaleUser.getId()).commit();
                 OpenScale.getInstance(getContext()).updateScaleData();
             }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }

}
