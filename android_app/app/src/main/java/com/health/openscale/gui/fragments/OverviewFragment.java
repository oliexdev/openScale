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
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.DateTimeHelpers;
import com.health.openscale.gui.views.BMRMeasurementView;
import com.health.openscale.gui.views.FloatMeasurementView;
import com.health.openscale.gui.views.MeasurementView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Stack;

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
import lecho.lib.hellocharts.view.LineChartView;
import lecho.lib.hellocharts.view.PieChartView;

public class OverviewFragment extends Fragment implements FragmentUpdateListener {

    private View overviewView;
    private View userLineSeparator;

    private TextView txtTitleUser;
    private TextView txtTitleLastMeasurement;

    private List<MeasurementView> measurementViews;

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

        txtTitleUser = overviewView.findViewById(R.id.txtTitleUser);
        txtTitleLastMeasurement = overviewView.findViewById(R.id.txtTitleLastMeasurement);

        pieChartLast = overviewView.findViewById(R.id.pieChartLast);
        lineChartLast = overviewView.findViewById(R.id.lineChartLast);

        spinUser = overviewView.findViewById(R.id.spinUser);

        lineChartLast.setOnValueTouchListener(new LineChartTouchListener());

        pieChartLast.setOnValueTouchListener(new PieChartLastTouchListener());
        pieChartLast.setChartRotationEnabled(false);

        measurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.NONE);

        TableLayout tableOverviewLayout = overviewView.findViewById(R.id.tableLayoutMeasurements);

        for (MeasurementView measurement : measurementViews) {
            tableOverviewLayout.addView(measurement);
        }

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

        txtTitleUser.setText(getResources().getString(R.string.label_title_user).toUpperCase());
        txtTitleLastMeasurement.setText(getResources().getString(R.string.label_title_last_measurement).toUpperCase());

        prefs = PreferenceManager.getDefaultSharedPreferences(overviewView.getContext());

        OpenScale.getInstance().registerFragment(this);

        return overviewView;
    }

    @Override
    public void onDestroyView() {
        OpenScale.getInstance().unregisterFragment(this);
        super.onDestroyView();
    }

    @Override
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList) {
        if (scaleMeasurementList.isEmpty()) {
            lastScaleMeasurement = new ScaleMeasurement();
        } else if (userSelectedData != null) {
            lastScaleMeasurement = userSelectedData;
        } else {
            lastScaleMeasurement = scaleMeasurementList.get(0);
        }

        ScaleMeasurement[] tupleScaleData = OpenScale.getInstance().getTupleScaleData(lastScaleMeasurement.getId());
        ScaleMeasurement prevScaleMeasurement = tupleScaleData[0];

        updateUserSelection();
        updateLastPieChart();
        updateLastLineChart(scaleMeasurementList);

        for (MeasurementView measurement : measurementViews) {
            measurement.loadFrom(lastScaleMeasurement, prevScaleMeasurement);
        }
    }

    private void updateUserSelection() {

        currentScaleUser = OpenScale.getInstance().getSelectedScaleUser();

        userSelectedData = null;

        spinUserAdapter.clear();
        List<ScaleUser> scaleUserList = OpenScale.getInstance().getScaleUserList();

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
        final Calendar now = Calendar.getInstance();
        Calendar histCalendar = Calendar.getInstance();

        scaleMeasurementLastDays = new ArrayList<>();
        List<AxisValue> axisValues = new ArrayList<>();

        int max_i = Math.min(7, scaleMeasurementList.size());
        for (int i = 0; i < max_i; ++i) {
            ScaleMeasurement measurement = scaleMeasurementList.get(max_i - i - 1);
            scaleMeasurementLastDays.add(measurement);

            histCalendar.setTime(measurement.getDateTime());
            int days = DateTimeHelpers.daysBetween(now, histCalendar);
            String label = getResources().getQuantityString(R.plurals.label_days, Math.abs(days), days);
            axisValues.add(new AxisValue(i, label.toCharArray()));
        }

        List<Line> diagramLineList = new ArrayList<>();

        for (MeasurementView view : measurementViews) {
            if (!view.isVisible()
                    || !view.getSettings().isInOverviewGraph()
                    || !(view instanceof FloatMeasurementView)) {
                continue;
            }

            FloatMeasurementView measurementView = (FloatMeasurementView) view;
            Stack<PointValue> valuesStack = new Stack<>();

            for (int i = 0; i < max_i; ++i) {
                ScaleMeasurement measurement = scaleMeasurementList.get(max_i - i - 1);
                measurementView.loadFrom(measurement, null);

                if (measurementView.getValue() != 0.0f) {
                    valuesStack.push(new PointValue(i, measurementView.getValue()));
                }
            }

            diagramLineList.add(new Line(valuesStack).
                    setColor(measurementView.getColor()).
                    setHasLabels(prefs.getBoolean("labelsEnable", true)).
                    setHasPoints(prefs.getBoolean("pointsEnable", true)).
                    setFormatter(new SimpleLineChartValueFormatter(1)));
        }

        LineChartData lineData = new LineChartData(diagramLineList);
        lineData.setAxisXBottom(new Axis(axisValues).
                        setHasLines(true).
                        setTextColor(txtTitleLastMeasurement.getCurrentTextColor())
        );

        lineData.setAxisYLeft(new Axis().
                        setHasLines(true).
                        setMaxLabelChars(5).
                        setTextColor(txtTitleLastMeasurement.getCurrentTextColor())
        );

        lineChartLast.setLineChartData(lineData);
        lineChartLast.setViewportCalculationEnabled(true);

        lineChartLast.setZoomEnabled(false);
    }

    private void updateLastPieChart() {
        List<SliceValue> arcValuesLast = new ArrayList<>();

        for (MeasurementView view : measurementViews) {
            if (!view.isVisible()
                || !(view instanceof FloatMeasurementView)
                || view instanceof BMRMeasurementView) {
                continue;
            }

            FloatMeasurementView measurementView = (FloatMeasurementView) view;
            measurementView.loadFrom(lastScaleMeasurement, null);

            if (measurementView.getValue() != 0) {
                arcValuesLast.add(new SliceValue(measurementView.getValue(), measurementView.getColor()));
            }
        }

        final Converters.WeightUnit unit = currentScaleUser.getScaleUnit();
        PieChartData pieChartData = new PieChartData(arcValuesLast);
        pieChartData.setHasLabels(false);
        pieChartData.setHasCenterCircle(true);
        pieChartData.setCenterText1(String.format("%.2f %s", Converters.fromKilogram(lastScaleMeasurement.getWeight(), unit), unit.toString()));
        pieChartData.setCenterText2(DateFormat.getDateInstance(DateFormat.MEDIUM).format(lastScaleMeasurement.getDateTime()));
        pieChartData.setCenterText1Color(txtTitleLastMeasurement.getCurrentTextColor());
        pieChartData.setCenterText2Color(txtTitleLastMeasurement.getCurrentTextColor());

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

    private class PieChartLastTouchListener implements PieChartOnValueSelectListener
    {
        @Override
        public void onValueSelected(int i, SliceValue arcValue) {
            if (lastScaleMeasurement == null) {
                return;
            }

            for (MeasurementView view : measurementViews) {
                if (view instanceof FloatMeasurementView) {
                    FloatMeasurementView measurementView = (FloatMeasurementView) view;

                    if (measurementView.getColor() == arcValue.getColor()) {
                        Toast.makeText(getActivity(), String.format("%s: %s",
                                measurementView.getName(), measurementView.getValueAsString(true)),
                                Toast.LENGTH_SHORT).show();
                        break;
                    }
                }
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

            updateOnView(OpenScale.getInstance().getScaleMeasurementList());
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

                 OpenScale openScale = OpenScale.getInstance();

                 List<ScaleUser> scaleUserList = openScale.getScaleUserList();
                 ScaleUser scaleUser = scaleUserList.get(position);

                 openScale.selectScaleUser(scaleUser.getId());
                 openScale.updateScaleData();
             }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }

}
