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

import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.RadarChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LegendEntry;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.RadarData;
import com.github.mikephil.charting.data.RadarDataSet;
import com.github.mikephil.charting.data.RadarEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.IRadarDataSet;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.DateTimeHelpers;
import com.health.openscale.gui.utils.ColorUtil;
import com.health.openscale.gui.views.BMIMeasurementView;
import com.health.openscale.gui.views.BoneMeasurementView;
import com.health.openscale.gui.views.ChartMarkerView;
import com.health.openscale.gui.views.FatMeasurementView;
import com.health.openscale.gui.views.FloatMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MeasurementViewSettings;
import com.health.openscale.gui.views.MuscleMeasurementView;
import com.health.openscale.gui.views.WaterMeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class StatisticsFragment extends Fragment implements FragmentUpdateListener {

    private View statisticsView;

    private TextView txtGoalWeight;
    private TextView txtGoalDiff;
    private TextView txtGoalDayLeft;

    private TextView txtLabelGoalWeight;
    private TextView txtLabelGoalDiff;
    private TextView txtLabelDayLeft;

    private RadarChart radarChartWeek;
    private RadarChart radarChartMonth;

    private ScaleUser currentScaleUser;
    private ScaleMeasurement lastScaleMeasurement;

    private ArrayList <MeasurementView> viewMeasurementsStatistics;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        statisticsView = inflater.inflate(R.layout.fragment_statistics, container, false);

        for (int id : new int[]{R.id.imageGoalWeight, R.id.imageGoalDiff, R.id.imageDayLeft}) {
            ImageView image = statisticsView.findViewById(id);
            image.setColorFilter(ColorUtil.getTextColor(statisticsView.getContext()));
        }

        txtGoalWeight = statisticsView.findViewById(R.id.txtGoalWeight);
        txtGoalWeight.setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));
        txtGoalDiff = statisticsView.findViewById(R.id.txtGoalDiff);
        txtGoalDiff.setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));
        txtGoalDayLeft = statisticsView.findViewById(R.id.txtGoalDayLeft);
        txtGoalDayLeft.setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));

        txtLabelGoalWeight = statisticsView.findViewById(R.id.txtLabelGoalWeight);
        txtLabelGoalWeight.setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));
        txtLabelGoalDiff = statisticsView.findViewById(R.id.txtLabelGoalDiff);
        txtLabelGoalDiff.setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));
        txtLabelDayLeft = statisticsView.findViewById(R.id.txtLabelDayLeft);
        txtLabelDayLeft.setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));

        viewMeasurementsStatistics = new ArrayList<>();

        viewMeasurementsStatistics.add(new WeightMeasurementView(statisticsView.getContext()));
        viewMeasurementsStatistics.add(new WaterMeasurementView(statisticsView.getContext()));
        viewMeasurementsStatistics.add(new MuscleMeasurementView(statisticsView.getContext()));
        viewMeasurementsStatistics.add(new FatMeasurementView(statisticsView.getContext()));
        viewMeasurementsStatistics.add(new BoneMeasurementView(statisticsView.getContext()));
        viewMeasurementsStatistics.add(new BMIMeasurementView(statisticsView.getContext()));

        ArrayList<LegendEntry> legendEntriesWeek = new ArrayList<>();

        for (int i = 0; i< viewMeasurementsStatistics.size(); i++) {
            LegendEntry legendEntry = new LegendEntry();
            legendEntry.label = i + " - " + viewMeasurementsStatistics.get(i).getName().toString();
            legendEntriesWeek.add(legendEntry);
        }

        MarkerView mv = new ChartMarkerView(statisticsView.getContext(), R.layout.chart_markerview);

        radarChartWeek = statisticsView.findViewById(R.id.radarPastWeek);
        radarChartWeek.getXAxis().setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));
        radarChartWeek.getDescription().setEnabled(false);
        radarChartWeek.getYAxis().setEnabled(false);
        radarChartWeek.setExtraTopOffset(10);
        radarChartWeek.setRotationEnabled(false);
        Legend weekLegend = radarChartWeek.getLegend();
        weekLegend.setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));
        weekLegend.setWordWrapEnabled(true);
        weekLegend.setExtra(legendEntriesWeek);
        weekLegend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        mv.setChartView(radarChartWeek);
        radarChartWeek.setMarker(mv);

        radarChartMonth = statisticsView.findViewById(R.id.radarPastMonth);
        radarChartMonth.getXAxis().setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));
        radarChartMonth.getDescription().setEnabled(false);
        radarChartMonth.getYAxis().setEnabled(false);
        radarChartMonth.setExtraTopOffset(10);
        radarChartMonth.setRotationEnabled(false);
        Legend monthLegend = radarChartMonth.getLegend();
        monthLegend.setTextColor(ColorUtil.getTextColor(statisticsView.getContext()));
        monthLegend.setWordWrapEnabled(true);
        monthLegend.setExtra(legendEntriesWeek);
        monthLegend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        mv.setChartView(radarChartMonth);
        radarChartMonth.setMarker(mv);

        OpenScale.getInstance().registerFragment(this);

        return statisticsView;
    }

    @Override
    public void onDestroyView() {
        OpenScale.getInstance().unregisterFragment(this);
        super.onDestroyView();
    }

    @Override
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList) {
        currentScaleUser = OpenScale.getInstance().getSelectedScaleUser();

        if (scaleMeasurementList.isEmpty()) {
            lastScaleMeasurement = new ScaleMeasurement();
            lastScaleMeasurement.setUserId(currentScaleUser.getId());
            lastScaleMeasurement.setWeight(currentScaleUser.getInitialWeight());
        } else {
            lastScaleMeasurement = scaleMeasurementList.get(0);
        }

        updateStatistics(scaleMeasurementList);
        updateGoal();
    }

    private void updateGoal() {
        final Converters.WeightUnit unit = currentScaleUser.getScaleUnit();

        ScaleMeasurement goalScaleMeasurement = new ScaleMeasurement();
        goalScaleMeasurement.setUserId(currentScaleUser.getId());
        goalScaleMeasurement.setWeight(currentScaleUser.getGoalWeight());

        txtGoalWeight.setText(String.format("%.1f %s",
                Converters.fromKilogram(goalScaleMeasurement.getWeight(), unit),
                unit.toString()));

        txtGoalDiff.setText(String.format("%.1f %s",
                Converters.fromKilogram(goalScaleMeasurement.getWeight() - lastScaleMeasurement.getWeight(), unit),
                unit.toString()));

        Calendar goalCalendar = Calendar.getInstance();
        goalCalendar.setTime(currentScaleUser.getGoalDate());
        int days = Math.max(0, DateTimeHelpers.daysBetween(Calendar.getInstance(), goalCalendar));
        txtGoalDayLeft.setText(getResources().getQuantityString(R.plurals.label_days, days, days));

        boolean isBmiEnabled = new MeasurementViewSettings(
                PreferenceManager.getDefaultSharedPreferences(getActivity()), BMIMeasurementView.KEY)
                .isEnabled();
        final float goalBmi = goalScaleMeasurement.getBMI(currentScaleUser.getBodyHeight());

        txtLabelGoalWeight.setText(
                isBmiEnabled
                        ? Html.fromHtml(String.format(
                                "%s<br><font color='grey'><small>%s: %.1f</small></font>",
                                getResources().getString(R.string.label_goal_weight),
                                getResources().getString(R.string.label_bmi),
                                goalBmi))
                        : getResources().getString(R.string.label_goal_weight));

        txtLabelGoalDiff.setText(
                isBmiEnabled
                        ? Html.fromHtml(String.format(
                                "%s<br><font color='grey'><small>%s: %.1f</small></font>",
                                getResources().getString(R.string.label_weight_difference),
                                getResources().getString(R.string.label_bmi),
                                lastScaleMeasurement.getBMI(currentScaleUser.getBodyHeight()) - goalBmi))
                        : getResources().getString(R.string.label_weight_difference));

        txtLabelDayLeft.setText(
                Html.fromHtml(String.format(
                        "%s<br><font color='grey'><small>%s %s</small></font>",
                        getResources().getString(R.string.label_days_left),
                        getResources().getString(R.string.label_goal_date_is),
                        DateFormat.getDateInstance(DateFormat.LONG).format(currentScaleUser.getGoalDate()))));
    }

    private void updateStatistics(List<ScaleMeasurement> scaleMeasurementList) {
        radarChartWeek.clear();
        radarChartMonth.clear();

        Calendar histDate = Calendar.getInstance();
        Calendar weekPastDate = Calendar.getInstance();
        Calendar monthPastDate = Calendar.getInstance();

        weekPastDate.setTime(lastScaleMeasurement.getDateTime());
        weekPastDate.add(Calendar.DATE, -7);

        monthPastDate.setTime(lastScaleMeasurement.getDateTime());
        monthPastDate.add(Calendar.DATE, -30);

        ScaleMeasurement averageWeek = new ScaleMeasurement();
        ScaleMeasurement averageMonth = new ScaleMeasurement();

        ArrayList<RadarEntry> entriesLastMeasurement = new ArrayList<>();
        ArrayList<RadarEntry> entriesAvgWeek = new ArrayList<>();
        ArrayList<RadarEntry> entriesAvgMonth = new ArrayList<>();

        for (ScaleMeasurement measurement : scaleMeasurementList) {
            histDate.setTime(measurement.getDateTime());

            if (weekPastDate.before(histDate)) {
                averageWeek.add(measurement);
            }

            if (monthPastDate.before(histDate)) {
                averageMonth.add(measurement);
            }
        }

        averageWeek.divide(averageWeek.count());
        averageMonth.divide(averageMonth.count());

        for (MeasurementView view : viewMeasurementsStatistics) {
            final FloatMeasurementView measurementView = (FloatMeasurementView) view;

            Object[] extraData = new Object[3];
            extraData[0] = null; // not needed
            extraData[1] = null; // not needed
            extraData[2] = measurementView;

            measurementView.loadFrom(averageMonth, null);
            entriesAvgMonth.add(new RadarEntry(measurementView.getValue(), extraData));

            measurementView.loadFrom(averageWeek, null);
            entriesAvgWeek.add(new RadarEntry(measurementView.getValue(), extraData));

            measurementView.loadFrom(lastScaleMeasurement, null);
            entriesLastMeasurement.add(new RadarEntry(measurementView.getValue(), extraData));
        }

        RadarDataSet setLastMeasurement = new RadarDataSet(entriesLastMeasurement, getString(R.string.label_title_last_measurement));
        setLastMeasurement.setColor(ColorUtil.COLOR_BLUE);
        setLastMeasurement.setFillColor(ColorUtil.COLOR_BLUE);
        setLastMeasurement.setDrawFilled(true);
        setLastMeasurement.setFillAlpha(180);
        setLastMeasurement.setLineWidth(2f);
        setLastMeasurement.setDrawHighlightCircleEnabled(true);
        setLastMeasurement.setDrawHighlightIndicators(false);

        RadarDataSet setAvgWeek = new RadarDataSet(entriesAvgWeek, getString(R.string.label_last_week));
        setAvgWeek.setColor(ColorUtil.COLOR_GREEN);
        setAvgWeek.setFillColor(ColorUtil.COLOR_GREEN);
        setAvgWeek.setDrawFilled(true);
        setAvgWeek.setFillAlpha(180);
        setAvgWeek.setLineWidth(2f);
        setAvgWeek.setDrawHighlightCircleEnabled(true);
        setAvgWeek.setDrawHighlightIndicators(false);

        RadarDataSet setAvgMonth = new RadarDataSet(entriesAvgMonth, getString(R.string.label_last_month));
        setAvgMonth.setColor(ColorUtil.COLOR_GREEN);
        setAvgMonth.setFillColor(ColorUtil.COLOR_GREEN);
        setAvgMonth.setDrawFilled(true);
        setAvgMonth.setFillAlpha(180);
        setAvgMonth.setLineWidth(2f);
        setAvgMonth.setDrawHighlightCircleEnabled(true);
        setAvgMonth.setDrawHighlightIndicators(false);

        ArrayList<IRadarDataSet> setsAvgWeek = new ArrayList<>();
        setsAvgWeek.add(setAvgWeek);
        setsAvgWeek.add(setLastMeasurement);

        ArrayList<IRadarDataSet> setsAvgMonth = new ArrayList<>();
        setsAvgMonth.add(setAvgMonth);
        setsAvgMonth.add(setLastMeasurement);

        RadarData dataAvgWeek = new RadarData(setsAvgWeek);
        dataAvgWeek.setValueTextSize(8f);
        dataAvgWeek.setDrawValues(false);
        dataAvgWeek.setValueFormatter(new ValueFormatter() {
            @Override
            public String getRadarLabel(RadarEntry radarEntry) {
                FloatMeasurementView measurementView = (FloatMeasurementView) radarEntry.getData();

                return measurementView.getValueAsString(true);
            }
        });

        RadarData dataAvgMonth = new RadarData(setsAvgMonth);
        dataAvgMonth.setValueTextSize(8f);
        dataAvgMonth.setDrawValues(false);
        dataAvgMonth.setValueFormatter(new ValueFormatter() {
            @Override
            public String getRadarLabel(RadarEntry radarEntry) {
                FloatMeasurementView measurementView = (FloatMeasurementView) radarEntry.getData();

                return measurementView.getValueAsString(true);
            }
        });

        radarChartWeek.setData(dataAvgWeek);
        radarChartMonth.setData(dataAvgMonth);

        radarChartWeek.animateXY(1000, 1000);
        radarChartMonth.animateXY(1000, 1000);

        radarChartWeek.invalidate();
        radarChartMonth.invalidate();
    }
}
