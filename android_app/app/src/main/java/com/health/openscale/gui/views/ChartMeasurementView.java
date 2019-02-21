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

package com.health.openscale.gui.views;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.widget.Toast;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;
import com.github.mikephil.charting.formatter.IValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.utils.ViewPortHandler;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.core.utils.PolynomialFitter;
import com.health.openscale.gui.utils.ColorUtil;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Stack;

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
    private ScaleUser user;
    private List<ScaleMeasurement> scaleMeasurementList;
    private List<MeasurementView> measurementViews;
    private ScaleMeasurement firstMeasurement;
    private ScaleMeasurement lastMeasurement;
    private int maxXValue;
    private int minXValue;
    private ViewMode viewMode;
    private boolean isAnimationOn;
    private boolean isInGraphKey;

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

    public void setViewRange(final ViewMode mode, boolean rollingChart) {
        setMeasurementList(openScale.getScaleMeasurementList());
        setViewMode(mode);

        refresh();

        if (rollingChart) {
            setRollingChartOn(mode);
        }
    }

    public void setViewRange(int year, final ViewMode mode) {
        setMeasurementList(openScale.getScaleDataOfYear(year));
        setViewMode(mode);

        refresh();
    }

    public void setViewRange(int year, int month, final ViewMode mode) {
        setMeasurementList(openScale.getScaleDataOfMonth(year, month));
        setViewMode(mode);

        refresh();
    }

    public void setAnimationOn(boolean status) {
        isAnimationOn = status;
    }

    public void setIsInGraphKey(boolean status) {
        isInGraphKey = status;
    }

    private void initChart() {
        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        openScale = OpenScale.getInstance();
        user = openScale.getSelectedScaleUser();
        scaleMeasurementList = new ArrayList<>();
        measurementViews = MeasurementView.getMeasurementList(getContext(), MeasurementView.DateTimeOrder.NONE);
        firstMeasurement = new ScaleMeasurement();
        lastMeasurement = new ScaleMeasurement();
        maxXValue = 0;
        minXValue = 0;
        isAnimationOn = true;
        isInGraphKey = true;

        setMarker(new ChartMarkerView(getContext(), R.layout.chart_markerview));
        setDoubleTapToZoomEnabled(false);
        setHighlightPerTapEnabled(true);
        getLegend().setEnabled(prefs.getBoolean("legendEnable", true));
        getLegend().setWordWrapEnabled(true);
        getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        getLegend().setTextColor(ColorUtil.getTextColor(getContext()));
        getDescription().setEnabled(false);
        getAxisLeft().setEnabled(prefs.getBoolean("yaxisEnable", false));
        getAxisRight().setEnabled(prefs.getBoolean("yaxisEnable", false));
        getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        getXAxis().setTextColor(ColorUtil.getTextColor(getContext()));
    }

    private void setViewMode(final ViewMode mode) {
        viewMode = mode;
        Calendar viewModeCalender = Calendar.getInstance();
        viewModeCalender.setTime(lastMeasurement.getDateTime());

        switch (mode) {
            case DAY_OF_MONTH:
                minXValue = viewModeCalender.getMinimum(Calendar.DAY_OF_MONTH);
                maxXValue = viewModeCalender.getMaximum(Calendar.DAY_OF_MONTH);
                break;
            case WEEK_OF_MONTH:
                minXValue = 1;
                maxXValue = viewModeCalender.getActualMaximum(Calendar.WEEK_OF_MONTH);
                break;
            case WEEK_OF_YEAR:
                minXValue = viewModeCalender.getActualMinimum(Calendar.WEEK_OF_YEAR);
                maxXValue = viewModeCalender.getActualMaximum(Calendar.WEEK_OF_YEAR);
                break;
            case MONTH_OF_YEAR:
                minXValue = viewModeCalender.getActualMinimum(Calendar.MONTH);
                maxXValue = viewModeCalender.getActualMaximum(Calendar.MONTH);
                break;
            case DAY_OF_YEAR:
                minXValue = viewModeCalender.getActualMinimum(Calendar.DAY_OF_YEAR);
                maxXValue = viewModeCalender.getActualMaximum(Calendar.DAY_OF_YEAR);
                break;
            case DAY_OF_ALL:
            case WEEK_OF_ALL:
            case MONTH_OF_ALL:
            case YEAR_OF_ALL:
                minXValue = convertDateInShort(firstMeasurement.getDateTime());
                maxXValue = convertDateInShort(lastMeasurement.getDateTime());
                break;
            default:
                throw new IllegalArgumentException("view mode not implemented");
        }

        setXValueFormat(mode);
    }

    private int convertDateInShort(Date date) {
        Calendar shortDate = Calendar.getInstance();
        shortDate.setTime(new Date(0));

        Calendar dateCalendar = Calendar.getInstance();
        dateCalendar.setTime(date);

        switch (viewMode) {
            case DAY_OF_ALL:
                shortDate.set(Calendar.DAY_OF_MONTH, dateCalendar.get(Calendar.DAY_OF_MONTH));
                shortDate.set(Calendar.MONTH, dateCalendar.get(Calendar.MONTH));
                shortDate.set(Calendar.YEAR, dateCalendar.get(Calendar.YEAR));
                break;
            case WEEK_OF_ALL:
                shortDate.set(Calendar.WEEK_OF_YEAR, dateCalendar.get(Calendar.WEEK_OF_YEAR));
                shortDate.set(Calendar.YEAR, dateCalendar.get(Calendar.YEAR));
                break;
            case MONTH_OF_ALL:
                shortDate.set(Calendar.MONTH, dateCalendar.get(Calendar.MONTH));
                shortDate.set(Calendar.YEAR, dateCalendar.get(Calendar.YEAR));
                break;
            case YEAR_OF_ALL:
                shortDate.set(Calendar.YEAR, dateCalendar.get(Calendar.YEAR));
                break;
            default:
                throw new IllegalArgumentException("view mode not implemented");

        }

        return (int)(shortDate.getTime().getTime() / 1000000L);
    }

    private Date convertShortInDate(int shortDate) {
        return new Date(shortDate * 1000000L);
    }

    private void setMeasurementList(List<ScaleMeasurement> measurementList) {
        scaleMeasurementList = measurementList;

        if (!measurementList.isEmpty()) {
            lastMeasurement = measurementList.get(0);
            Collections.reverse(measurementList);
            firstMeasurement = measurementList.get(0);
            Collections.reverse(measurementList);
        }
    }

    private void setRollingChartOn(ViewMode mode) {
        if (!scaleMeasurementList.isEmpty()) {
            Calendar zeroCalendar = Calendar.getInstance();
            zeroCalendar.setTime(new Date(0));

            Calendar lastCalendar = Calendar.getInstance();
            lastCalendar.setTime(lastMeasurement.getDateTime());

            Calendar deltaCalendar = Calendar.getInstance();

            int range = 0;
            int granularity = 0;

            switch (mode) {
                case DAY_OF_ALL:
                    zeroCalendar.set(Calendar.DAY_OF_MONTH, lastCalendar.get(Calendar.DAY_OF_MONTH));
                    zeroCalendar.set(Calendar.MONTH, lastCalendar.get(Calendar.MONTH));
                    zeroCalendar.set(Calendar.YEAR, lastCalendar.get(Calendar.YEAR));
                    deltaCalendar.setTime(zeroCalendar.getTime());
                    deltaCalendar.add(Calendar.DAY_OF_MONTH, -1);
                    granularity = convertDateInShort(zeroCalendar.getTime()) - convertDateInShort(deltaCalendar.getTime());
                    range = granularity * 14;
                    break;
                case WEEK_OF_ALL:
                    zeroCalendar.set(Calendar.WEEK_OF_YEAR, lastCalendar.get(Calendar.WEEK_OF_YEAR));
                    zeroCalendar.set(Calendar.YEAR, lastCalendar.get(Calendar.YEAR));
                    deltaCalendar.setTime(zeroCalendar.getTime());
                    deltaCalendar.add(Calendar.WEEK_OF_YEAR, -1);
                    granularity = convertDateInShort(zeroCalendar.getTime()) - convertDateInShort(deltaCalendar.getTime());
                    range = granularity * 4;
                    break;
                case MONTH_OF_ALL:
                    zeroCalendar.set(Calendar.MONTH, lastCalendar.get(Calendar.MONTH));
                    zeroCalendar.set(Calendar.YEAR, lastCalendar.get(Calendar.YEAR));
                    deltaCalendar.setTime(zeroCalendar.getTime());
                    deltaCalendar.add(Calendar.MONTH, -1);
                    granularity = convertDateInShort(zeroCalendar.getTime()) - convertDateInShort(deltaCalendar.getTime());
                    range = granularity * 4;
                    break;
                case YEAR_OF_ALL:
                    zeroCalendar.set(Calendar.YEAR, lastCalendar.get(Calendar.YEAR));
                    deltaCalendar.add(Calendar.YEAR, -1);
                    granularity = convertDateInShort(zeroCalendar.getTime()) - convertDateInShort(deltaCalendar.getTime());
                    range = granularity * 3;
                    break;
                default:
                    throw new IllegalArgumentException("view mode not implemented");
            }

            getXAxis().setGranularity(granularity);
            setVisibleXRangeMaximum(range);
            moveViewToX(getBinNr(lastMeasurement));
        }
    }

    private void setXValueFormat(final ViewMode mode) {
        getXAxis().setValueFormatter(new IAxisValueFormatter() {
            private final SimpleDateFormat xValueFormat = new SimpleDateFormat();
            private final Calendar calendar = Calendar.getInstance();

            @Override
            public String getFormattedValue(float value, AxisBase axis) {
                calendar.setTime(new Date(0));

                switch (mode) {
                    case DAY_OF_MONTH:
                        calendar.set(Calendar.DAY_OF_MONTH, (int)value);
                        xValueFormat.applyLocalizedPattern("dd");
                        break;
                    case WEEK_OF_MONTH:
                        calendar.set(Calendar.WEEK_OF_MONTH, (int)value);
                        xValueFormat.applyLocalizedPattern("'W'W");
                        break;
                    case WEEK_OF_YEAR:
                        calendar.set(Calendar.WEEK_OF_YEAR, (int)value);
                        xValueFormat.applyLocalizedPattern("'W'w");
                        break;
                    case MONTH_OF_YEAR:
                        calendar.set(Calendar.MONTH, (int)value);
                        xValueFormat.applyLocalizedPattern("MMM");
                        break;
                    case DAY_OF_YEAR:
                        calendar.set(Calendar.DAY_OF_YEAR, (int)value);
                        xValueFormat.applyLocalizedPattern("D");
                        break;
                    case DAY_OF_ALL:
                        calendar.setTime(convertShortInDate((int)value));
                        return DateFormat.getDateInstance(DateFormat.SHORT).format(calendar.getTime());
                    case WEEK_OF_ALL:
                        calendar.setTime(convertShortInDate((int)value));
                        xValueFormat.applyLocalizedPattern("'W'w yyyy");
                        return xValueFormat.format(calendar.getTime());
                    case MONTH_OF_ALL:
                        calendar.setTime(convertShortInDate((int)value));
                        xValueFormat.applyLocalizedPattern("MMM yyyy");
                        return xValueFormat.format(calendar.getTime());
                    case YEAR_OF_ALL:
                        calendar.setTime(convertShortInDate((int)value));
                        xValueFormat.applyLocalizedPattern("yyyy");
                        return xValueFormat.format(calendar.getTime());
                    default:
                        throw new IllegalArgumentException("view mode not implemented");
                }

                return xValueFormat.format(calendar.getTime());
            }
        });
    }

    private ScaleMeasurement[] averageScaleMeasurementList(List<ScaleMeasurement> measurementList) {
        final ScaleMeasurement[] avgMeasurementList = new ScaleMeasurement[minXValue + maxXValue + 1];

        for (ScaleMeasurement measurement : measurementList) {
            int binNr = getBinNr(measurement);

            if (avgMeasurementList[binNr] == null) {
                avgMeasurementList[binNr] = measurement.clone();
            } else {
                avgMeasurementList[binNr].add(measurement);
            }
        }

        for (ScaleMeasurement avgMeasurement : avgMeasurementList) {
            if (avgMeasurement == null) {
                continue;
            }

            int binNr = getBinNr(avgMeasurement);
            avgMeasurement.divide(avgMeasurementList[binNr].count());
        }

        return avgMeasurementList;
    }

    private ScaleMeasurement getPreviousMeasurment(ScaleMeasurement[] masurementList, int binNr) {
        for (int i=binNr-1; i > 0; i--) {
            if (masurementList[i] != null) {
                return masurementList[i];
            }
        }

        return null;
    }

    private int getBinNr(ScaleMeasurement measurement) {
        Calendar measurementCalendar = Calendar.getInstance();
        measurementCalendar.setTime(measurement.getDateTime());

        switch (viewMode) {
            case DAY_OF_MONTH:
                return measurementCalendar.get(Calendar.DAY_OF_MONTH);
            case WEEK_OF_MONTH:
                return measurementCalendar.get(Calendar.WEEK_OF_MONTH);
            case WEEK_OF_YEAR:
                return measurementCalendar.get(Calendar.WEEK_OF_YEAR);
            case MONTH_OF_YEAR:
                return measurementCalendar.get(Calendar.MONTH);
            case DAY_OF_YEAR:
                return measurementCalendar.get(Calendar.DAY_OF_YEAR);
            case DAY_OF_ALL:
            case WEEK_OF_ALL:
            case MONTH_OF_ALL:
            case YEAR_OF_ALL:
                return convertDateInShort(measurement.getDateTime());
            default:
                throw new IllegalArgumentException("view mode not implemented");
        }
    }

    private void refresh() {
        clear();

        if (scaleMeasurementList.isEmpty()) {
            return;
        }

        List<ILineDataSet> lineDataSets = new ArrayList<>();

        ScaleMeasurement[] avgMeasurementList = averageScaleMeasurementList(scaleMeasurementList);

        for (MeasurementView view : measurementViews) {
            if (view instanceof FloatMeasurementView) {
                final FloatMeasurementView measurementView = (FloatMeasurementView) view;

                final List<Entry> lineEntries = new ArrayList<>();

                for (ScaleMeasurement avgMeasurement : avgMeasurementList) {
                    if (avgMeasurement == null) {
                        continue;
                    }

                    int binNr = getBinNr(avgMeasurement);

                    ScaleMeasurement prevMeasuremnt = getPreviousMeasurment(avgMeasurementList, binNr);
                    measurementView.loadFrom(avgMeasurement, prevMeasuremnt);

                    Entry entry = new Entry();
                    entry.setX(binNr);
                    entry.setY(measurementView.getValue());
                    Object[] extraData = new Object[3];
                    extraData[0] = avgMeasurement;
                    extraData[1] = prevMeasuremnt;
                    extraData[2] = measurementView;
                    entry.setData(extraData);

                    lineEntries.add(entry);

                    /*if (prefs.getBoolean("regressionLine", false) && measurementView instanceof WeightMeasurementView) {
                        polyFitter.addPoint((double)entry.getX(), (double)entry.getY());
                    }*/
                }

                addMeasurementLine(lineDataSets, lineEntries, measurementView);
            }
        }

        addGoalLine(lineDataSets);
        addRegressionLine(lineDataSets);

        LineData data = new LineData(lineDataSets);
        setData(data);

        getXAxis().setAxisMinimum(minXValue);
        getXAxis().setAxisMaximum(maxXValue);
        if (isAnimationOn) {
            animateY(700);
        }
        notifyDataSetChanged();
        invalidate();
    }

    private void addMeasurementLine(List<ILineDataSet> lineDataSets, List<Entry> lineEntries, FloatMeasurementView measurementView) {
        LineDataSet measurementLine = new LineDataSet(lineEntries, measurementView.getName().toString());
        measurementLine.setLineWidth(1.5f);
        measurementLine.setValueTextSize(8.0f);
        measurementLine.setColor(measurementView.getColor());
        measurementLine.setValueTextColor(ColorUtil.getTextColor(getContext()));
        measurementLine.setCircleColor(measurementView.getColor());
        measurementLine.setAxisDependency(measurementView.getSettings().isOnRightAxis() ? YAxis.AxisDependency.RIGHT : YAxis.AxisDependency.LEFT);
        measurementLine.setHighlightEnabled(true);
        measurementLine.setDrawHighlightIndicators(true);
        measurementLine.setHighlightLineWidth(1.5f);
        measurementLine.setDrawHorizontalHighlightIndicator(false);
        measurementLine.setHighLightColor(Color.RED);
        measurementLine.setDrawCircles(prefs.getBoolean("pointsEnable", true));
        measurementLine.setDrawValues(prefs.getBoolean("labelsEnable", true));
        measurementLine.setValueFormatter(new IValueFormatter() {
            @Override
            public String getFormattedValue(float value, Entry entry, int dataSetIndex, ViewPortHandler viewPortHandler) {
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

            float goalWeight = Converters.fromKilogram(user.getGoalWeight(), user.getScaleUnit());

            valuesGoalLine.add(new Entry(minXValue, goalWeight));
            valuesGoalLine.add(new Entry(maxXValue, goalWeight));

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

    // TODO replace with sliding average
    private void addRegressionLine(List<ILineDataSet> lineDataSets) {
        if (prefs.getBoolean("regressionLine", false)) {
            int regressLineOrder = 1;

            try {
                regressLineOrder = Integer.parseInt(prefs.getString("regressionLineOrder", "1"));
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), getContext().getString(R.string.error_value_required) + ":" + e.getMessage(), Toast.LENGTH_LONG).show();
                prefs.edit().putString("regressionLineOrder", "1").apply();
            }

            PolynomialFitter polyFitter = new PolynomialFitter(Math.min(regressLineOrder, 100));

            PolynomialFitter.Polynomial polynomial = polyFitter.getBestFit();

            List<Entry> valuesLinearRegression = new Stack<>();

            for (int i = minXValue; i < maxXValue; i++) {
                double y_value = polynomial.getY(i);
                valuesLinearRegression.add(new Entry((float) i, (float) y_value));
            }

            LineDataSet linearRegressionLine = new LineDataSet(valuesLinearRegression, getContext().getString(R.string.label_regression_line));
            linearRegressionLine.setLineWidth(1.5f);
            linearRegressionLine.setColor(ColorUtil.COLOR_VIOLET);
            linearRegressionLine.setAxisDependency(prefs.getBoolean("weightOnRightAxis", true) ? YAxis.AxisDependency.RIGHT : YAxis.AxisDependency.LEFT);
            linearRegressionLine.setDrawValues(false);
            linearRegressionLine.setDrawCircles(false);
            linearRegressionLine.setHighlightEnabled(false);
            linearRegressionLine.enableDashedLine(10, 30, 0);

            lineDataSets.add(linearRegressionLine);
        }
    }
}
