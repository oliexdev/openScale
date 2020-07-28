/* Copyright (C) 2019  olie.xdev <olie.xdev@googlemail.com>
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

package com.health.openscale.gui.measurement;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.widget.ProgressBar;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.gui.utils.ColorUtil;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Stack;

import timber.log.Timber;

public class ChartMeasurementView extends LineChart {
    public enum ViewMode {
        DAY_OF_MONTH,
        WEEK_OF_MONTH,
        WEEK_OF_YEAR,
        MONTH_OF_YEAR,
        DAY_OF_YEAR,
        DAY_OF_ALL,
        WEEK_OF_ALL,
        MONTH_OF_ALL,
        YEAR_OF_ALL
    }

    private OpenScale openScale;
    private SharedPreferences prefs;
    private List<MeasurementView> measurementViews;
    private ViewMode viewMode;
    private boolean isAnimationOn;
    private boolean isInGraphKey;
    private ProgressBar progressBar;

    public ChartMeasurementView(Context context) {
        super(context);
        initChart();
    }

    public ChartMeasurementView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initChart();
    }

    public ChartMeasurementView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initChart();
    }

    public void setViewRange(final ViewMode mode) {
        viewMode = mode;

        refresh();

        setGranularityAndRange(0, 0);

        moveViewToX(convertDateInShort(openScale.getLastScaleMeasurement().getDateTime()));
        Timber.d("SET VIEW");
    }

    public void setViewRange(int year, final ViewMode mode) {
        viewMode = mode;

        refresh();

        setGranularityAndRange(year, 0);

        Calendar viewModeCalender = Calendar.getInstance();
        viewModeCalender.setTimeInMillis(0);
        viewModeCalender.set(Calendar.YEAR, year);

        int startDate = convertDateInShort(viewModeCalender.getTime());
        /*viewModeCalender.add(Calendar.YEAR, 1);
        int endDate = convertDateInShort(viewModeCalender.getTime());

        setVisibleXRangeMaximum(endDate - startDate);*/

        moveViewToX(startDate);
    }

    public void setViewRange(int year, int month, final ViewMode mode) {
        viewMode = mode;

        refresh();

        setGranularityAndRange(year, month);

        Calendar viewModeCalender = Calendar.getInstance();
        viewModeCalender.setTimeInMillis(0);
        viewModeCalender.set(Calendar.YEAR, year);
        viewModeCalender.set(Calendar.MONTH, month);

        Timber.d("START DATE " + viewModeCalender.getTime());
        int startDate = convertDateInShort(viewModeCalender.getTime());
        viewModeCalender.add(Calendar.MONTH, -1);
        int endDate = convertDateInShort(viewModeCalender.getTime());

        Timber.d("END DATE " + viewModeCalender.getTime());

        Timber.d("DIFF " + (startDate - endDate));

        moveViewToX(startDate);
        setVisibleXRangeMaximum(startDate - endDate);

        Timber.d("SET YEAR/MONTH VIEW");
    }

    private void setGranularityAndRange(int year, int month) {
        Calendar zeroCalendar = new GregorianCalendar();
        zeroCalendar.setTime(new Date(0));
        zeroCalendar.set(Calendar.YEAR, year);
        zeroCalendar.set(Calendar.MONTH, month);

        Calendar deltaCalendar = new GregorianCalendar();
        deltaCalendar.setTime(zeroCalendar.getTime());

        int range = 0;
        int granularity = 0;

        switch (viewMode) {
            case DAY_OF_MONTH:
                Timber.d("DAY OF MONTH");
                deltaCalendar.add(Calendar.MONTH, 1);
                range = convertDateInShort(deltaCalendar.getTime()) - convertDateInShort(zeroCalendar.getTime());
                deltaCalendar.add(Calendar.MONTH, -1);
                deltaCalendar.add(Calendar.DAY_OF_MONTH, 1);
                granularity = convertDateInShort(deltaCalendar.getTime()) - convertDateInShort(zeroCalendar.getTime());
                break;
            case WEEK_OF_MONTH:
                break;
            case WEEK_OF_YEAR:
                break;
            case MONTH_OF_YEAR:
                break;
            case DAY_OF_YEAR:
                break;
            case DAY_OF_ALL:
                deltaCalendar.add(Calendar.DAY_OF_YEAR, 1);
                granularity = convertDateInShort(deltaCalendar.getTime()) - convertDateInShort(zeroCalendar.getTime());
                range = granularity * 14;
                break;
            case WEEK_OF_ALL:
                deltaCalendar.add(Calendar.WEEK_OF_YEAR, 1);
                granularity = convertDateInShort(deltaCalendar.getTime()) - convertDateInShort(zeroCalendar.getTime());
                range = granularity * 4;
                break;
            case MONTH_OF_ALL:
                deltaCalendar.add(Calendar.MONTH, 1);
                granularity = convertDateInShort(deltaCalendar.getTime()) - convertDateInShort(zeroCalendar.getTime());
                range = granularity * 4;
                break;
            case YEAR_OF_ALL:
                deltaCalendar.add(Calendar.YEAR, 1);
                granularity = convertDateInShort(deltaCalendar.getTime()) - convertDateInShort(zeroCalendar.getTime());
                range = granularity * 3;
                break;
            default:
                throw new IllegalArgumentException("view mode not implemented");
        }

        setAutoScaleMinMaxEnabled(true);

        getXAxis().setGranularity(granularity);
        setVisibleXRangeMaximum(range);
    }

    public void setAnimationOn(boolean status) {
        isAnimationOn = status;
    }

    public void setIsInGraphKey(boolean status) {
        isInGraphKey = status;
    }

    public void setProgressBar(ProgressBar bar) {
        progressBar = bar;
    }

    private void initChart() {
        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        openScale = OpenScale.getInstance();
        measurementViews = MeasurementView.getMeasurementList(getContext(), MeasurementView.DateTimeOrder.NONE);
        isAnimationOn = true;
        isInGraphKey = true;
        progressBar = null;

        setHardwareAccelerationEnabled(true);
        setMarker(new ChartMarkerView(getContext(), R.layout.chart_markerview));
        setDoubleTapToZoomEnabled(false);
        setHighlightPerTapEnabled(true);
        getLegend().setEnabled(prefs.getBoolean("legendEnable", true));
        getLegend().setWordWrapEnabled(true);
        getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        getLegend().setTextColor(ColorUtil.getTintColor(getContext()));
        getDescription().setEnabled(false);
        getAxisLeft().setEnabled(true);
        getAxisRight().setEnabled(true);
        getAxisLeft().setTextColor(ColorUtil.getTintColor(getContext()));
        getAxisRight().setTextColor(ColorUtil.getTintColor(getContext()));
        getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        getXAxis().setTextColor(ColorUtil.getTintColor(getContext()));
    }

    private int convertDateInShort(Date date) {
        return (int)(date.getTime() / 1000000L);
    }

    private Date convertShortInDate(int shortDate) {
        return new Date(shortDate * 1000000L);
    }

    private void setXValueFormat(final ViewMode mode) {
        getXAxis().setValueFormatter(new ValueFormatter() {
            private final SimpleDateFormat xValueFormat = new SimpleDateFormat();
            private final Calendar calendar = Calendar.getInstance();

            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                calendar.setTime(convertShortInDate((int)value));

                switch (mode) {
                    case DAY_OF_MONTH:
                        xValueFormat.applyLocalizedPattern("dd");
                        break;
                    case WEEK_OF_MONTH:
                        xValueFormat.applyLocalizedPattern("'W'W");
                        break;
                    case WEEK_OF_YEAR:
                        xValueFormat.applyLocalizedPattern("'W'w");
                        break;
                    case MONTH_OF_YEAR:
                        xValueFormat.applyLocalizedPattern("MMM");
                        break;
                    case DAY_OF_YEAR:
                        xValueFormat.applyLocalizedPattern("D");
                        break;
                    case DAY_OF_ALL:
                        return DateFormat.getDateInstance(DateFormat.SHORT).format(calendar.getTime());
                    case WEEK_OF_ALL:
                        xValueFormat.applyLocalizedPattern("'W'w yyyy");
                        break;
                    case MONTH_OF_ALL:
                        xValueFormat.applyLocalizedPattern("MMM yyyy");
                        break;
                    case YEAR_OF_ALL:
                        xValueFormat.applyLocalizedPattern("yyyy");
                        break;
                    default:
                        throw new IllegalArgumentException("view mode not implemented");
                }

                return xValueFormat.format(calendar.getTime());
            }
        });
    }

    private void refresh() {
        clear();

        List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleMeasurementList();

        Collections.reverse(scaleMeasurementList);

        if (scaleMeasurementList.isEmpty()) {
            return;
        }

        List<ILineDataSet> lineDataSets = new ArrayList<>();

        for (MeasurementView view : measurementViews) {
            if (view instanceof FloatMeasurementView) {
                final FloatMeasurementView measurementView = (FloatMeasurementView) view;

                final List<Entry> lineEntries = new ArrayList<>();

                for (ScaleMeasurement measurement : scaleMeasurementList) {
                   // ScaleMeasurement prevMeasuremnt = openScale.getTupleOfScaleMeasurement(measurement.getId())[0];
                    measurementView.loadFrom(measurement, null);

                    if (measurementView.getValue() == 0.0f) {
                        continue;
                    }

                    Entry entry = new Entry();
                    entry.setX(convertDateInShort(measurement.getDateTime()));
                    entry.setY(measurementView.getValue());
                    Object[] extraData = new Object[3];
                    extraData[0] = measurement;
                    extraData[1] = null;
                    extraData[2] = measurementView;
                    entry.setData(extraData);

                    lineEntries.add(entry);
                }

                addMeasurementLine(lineDataSets, lineEntries, measurementView);
            }
        }

        addTrendLine(lineDataSets);
       // addGoalLine(lineDataSets);
        setXValueFormat(viewMode);

        LineData data = new LineData(lineDataSets);
        setData(data);

        progressBar.setVisibility(GONE);
    }

    private void addMeasurementLine(List<ILineDataSet> lineDataSets, List<Entry> lineEntries, FloatMeasurementView measurementView) {
        LineDataSet measurementLine = new LineDataSet(lineEntries, measurementView.getName().toString());
        measurementLine.setLineWidth(1.5f);
        measurementLine.setValueTextSize(10.0f);
        measurementLine.setColor(measurementView.getColor());
        measurementLine.setValueTextColor(ColorUtil.getTintColor(getContext()));
        measurementLine.setCircleColor(measurementView.getColor());
        measurementLine.setCircleHoleColor(measurementView.getColor());
        measurementLine.setAxisDependency(measurementView.getSettings().isOnRightAxis() ? YAxis.AxisDependency.RIGHT : YAxis.AxisDependency.LEFT);
        measurementLine.setHighlightEnabled(true);
        measurementLine.setDrawHighlightIndicators(true);
        measurementLine.setHighlightLineWidth(1.5f);
        measurementLine.setDrawHorizontalHighlightIndicator(false);
        measurementLine.setHighLightColor(Color.RED);
        measurementLine.setDrawCircles(prefs.getBoolean("pointsEnable", true));
        measurementLine.setDrawValues(prefs.getBoolean("labelsEnable", true));
        if (prefs.getBoolean("trendLine", true)) {
            // show only data point if trend line is enabled
            measurementLine.enableDashedLine(0, 1, 0);
        }
        measurementLine.setValueFormatter(new ValueFormatter() {
            @Override
            public String getPointLabel(Entry entry) {
                String prefix = new String();

                Object[] extraData = (Object[])entry.getData();
                ScaleMeasurement measurement = (ScaleMeasurement)extraData[0];
                ScaleMeasurement prevMeasurement = (ScaleMeasurement)extraData[1];
                FloatMeasurementView measurementView = (FloatMeasurementView)extraData[2];

                measurementView.loadFrom(measurement, prevMeasurement);

                if (measurement.isAverageValue()) {
                    prefix = "Ø ";
                }

                return prefix + measurementView.getValueAsString(true);
            }
        });

        if (measurementView.isVisible()) {
            if (isInGraphKey) {
                if (measurementView.getSettings().isInGraph()) {
                    lineDataSets.add(measurementLine);
                }
            } else {
                if (measurementView.getSettings().isInOverviewGraph()) {
                    lineDataSets.add(measurementLine);
                }
            }
        }
    }

    private void addGoalLine(List<ILineDataSet> lineDataSets) {
        if (prefs.getBoolean("goalLine", true)) {
            List<Entry> valuesGoalLine = new Stack<>();

            ScaleUser user = OpenScale.getInstance().getSelectedScaleUser();
            float goalWeight = Converters.fromKilogram(user.getGoalWeight(), user.getScaleUnit());

            // TODO
           /* valuesGoalLine.add(new Entry(minXValue, goalWeight));
            valuesGoalLine.add(new Entry(maxXValue, goalWeight));*/

            LineDataSet goalLine = new LineDataSet(valuesGoalLine, getContext().getString(R.string.label_goal_line));
            goalLine.setLineWidth(1.5f);
            goalLine.setColor(ColorUtil.COLOR_GREEN);
            goalLine.setAxisDependency(prefs.getBoolean("weightOnRightAxis", true) ? YAxis.AxisDependency.RIGHT : YAxis.AxisDependency.LEFT);
            goalLine.setDrawValues(false);
            goalLine.setDrawCircles(false);
            goalLine.setHighlightEnabled(false);
            goalLine.enableDashedLine(10, 30, 0);

            lineDataSets.add(goalLine);
        }
    }

    private void addTrendLine(List<ILineDataSet> lineDataSets) {

        List<ScaleMeasurement> scaleMeasurementsAsTrendlineList = openScale.getScaleMeasurementsAsTrendline();
        Collections.reverse(scaleMeasurementsAsTrendlineList);

        for (MeasurementView view : measurementViews) {
            if (view instanceof FloatMeasurementView) {
                final FloatMeasurementView measurementView = (FloatMeasurementView) view;

                final List<Entry> lineEntries = new ArrayList<>();

                for (ScaleMeasurement measurement : scaleMeasurementsAsTrendlineList) {
                    // TODO
                   // ScaleMeasurement prevMeasuremnt = openScale.getTupleOfScaleMeasurement(measurement.getId());
                    measurementView.loadFrom(measurement, null);

                    if (measurementView.getValue() == 0.0f) {
                        continue;
                    }

                    Entry entry = new Entry();
                    entry.setX(convertDateInShort(measurement.getDateTime()));
                    entry.setY(measurementView.getValue());
                    Object[] extraData = new Object[3];
                    extraData[0] = measurement;
                    extraData[1] = null;
                    extraData[2] = measurementView;
                    entry.setData(extraData);

                    lineEntries.add(entry);
                }

                addMeasurementLineTrend(lineDataSets, lineEntries, measurementView);
            }
        }


        /*if (!prefs.getBoolean("trendLine", true)) {
            return;
        }

        List<ILineDataSet> trendlineDataSets = new ArrayList<>();

        for (ILineDataSet dataSet : lineDataSets) {
            // we need at least two data points
            if (dataSet.getEntryCount() < 2) {
                continue;
            }

            PolynomialFitter polyFitter = new PolynomialFitter(1);

            List<Entry> valuesTrendLine = new Stack<>();
            valuesTrendLine.add(dataSet.getEntryForIndex(0));
            polyFitter.addPoint((double) valuesTrendLine.get(0).getX(), (double) valuesTrendLine.get(0).getY());

            for (int i = 1; i < dataSet.getEntryCount(); i++) {
                Entry entry = dataSet.getEntryForIndex(i);
                Entry trendPreviousEntry = valuesTrendLine.get(i - 1);
                float trendYValue = (trendPreviousEntry.getY() + 0.1f * (entry.getY() - trendPreviousEntry.getY()));
                polyFitter.addPoint((double) entry.getX(), (double) trendYValue);
                valuesTrendLine.add(new Entry(entry.getX(), trendYValue));
                /*Timber.d("ENTRY X " + entry.getX() + " Y " + entry.getY());
                Timber.d("PREVIOUS X " + trendPreviousEntry.getX() + " Y " + trendPreviousEntry.getY());
                Timber.d("TREND X " + entry.getX() + " Y " + trendYValue);*/
            /*}

            if (isInGraphKey) {
                PolynomialFitter.Polynomial polynomial = polyFitter.getBestFit();

                int x_last = (int) dataSet.getEntryForIndex(dataSet.getEntryCount() - 1).getX();
                for (int i = x_last; i < maxXValue + minXValue + 1; i++) {
                    double y_value = polynomial.getY(i);
                    valuesTrendLine.add(new Entry((float) i, (float) y_value));
                }
            }

            LineDataSet trendLine = new LineDataSet(valuesTrendLine, dataSet.getLabel() + "-" + getContext().getString(R.string.label_trend_line));
            trendLine.setLineWidth(1.5f);
            trendLine.setColor(dataSet.getColor());
            trendLine.setAxisDependency(dataSet.getAxisDependency());
            trendLine.setDrawValues(false);
            trendLine.setDrawCircles(false);
            trendLine.setHighlightEnabled(false);
            //trendLine.enableDashedLine(10, 30, 0);

            trendlineDataSets.add(trendLine);
        }

        for (ILineDataSet dataSet : trendlineDataSets) {
            lineDataSets.add(dataSet);
        }*/
    }

    private void addMeasurementLineTrend(List<ILineDataSet> lineDataSets, List<Entry> lineEntries, FloatMeasurementView measurementView) {
        LineDataSet measurementLine = new LineDataSet(lineEntries, measurementView.getName().toString() + "-" + getContext().getString(R.string.label_trend_line));
        measurementLine.setLineWidth(1.5f);
        measurementLine.setValueTextSize(10.0f);
        measurementLine.setColor(Color.GREEN);
        measurementLine.setValueTextColor(ColorUtil.getTintColor(getContext()));
        measurementLine.setCircleColor(Color.GREEN);
        measurementLine.setCircleHoleColor(Color.GREEN);
        measurementLine.setAxisDependency(measurementView.getSettings().isOnRightAxis() ? YAxis.AxisDependency.RIGHT : YAxis.AxisDependency.LEFT);
        measurementLine.setHighlightEnabled(true);
        measurementLine.setDrawHighlightIndicators(true);
        measurementLine.setHighlightLineWidth(1.5f);
        measurementLine.setDrawHorizontalHighlightIndicator(false);
        measurementLine.setHighLightColor(Color.RED);
        measurementLine.setDrawCircles(prefs.getBoolean("pointsEnable", true));
        measurementLine.setDrawValues(prefs.getBoolean("labelsEnable", true));
        measurementLine.setValueFormatter(new ValueFormatter() {
            @Override
            public String getPointLabel(Entry entry) {
                String prefix = new String();

                Object[] extraData = (Object[])entry.getData();
                ScaleMeasurement measurement = (ScaleMeasurement)extraData[0];
                ScaleMeasurement prevMeasurement = (ScaleMeasurement)extraData[1];
                FloatMeasurementView measurementView = (FloatMeasurementView)extraData[2];

                measurementView.loadFrom(measurement, prevMeasurement);

                if (measurement.isAverageValue()) {
                    prefix = "Ø ";
                }

                return prefix + measurementView.getValueAsString(true);
            }
        });

        if (measurementView.isVisible()) {
            if (isInGraphKey) {
                if (measurementView.getSettings().isInGraph()) {
                    lineDataSets.add(measurementLine);
                }
            } else {
                if (measurementView.getSettings().isInOverviewGraph()) {
                    lineDataSets.add(measurementLine);
                }
            }
        }
    }
}