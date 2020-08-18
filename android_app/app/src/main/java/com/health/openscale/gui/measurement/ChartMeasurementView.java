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
import android.graphics.RectF;
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
import com.github.mikephil.charting.utils.Utils;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;
import com.health.openscale.gui.utils.ColorUtil;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Stack;

import timber.log.Timber;

import static java.time.temporal.ChronoUnit.DAYS;

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
    private List<ScaleMeasurement> scaleMeasurementList;
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

        setGranularityAndRange(1980, 1);
        setXValueFormat(viewMode);

        if (openScale.getLastScaleMeasurement() != null) {
            moveViewToX(convertDateToInt(openScale.getLastScaleMeasurement().getDateTime()));
        }
    }

    public void setViewRange(int year, final ViewMode mode) {
        viewMode = mode;

        setGranularityAndRange(year, 1);
        setXValueFormat(viewMode);

        LocalDate startDate = LocalDate.of(year, 1, 1);

        moveViewToX(convertDateToInt(startDate));
    }

    public void setViewRange(int year, int month, final ViewMode mode) {
        viewMode = mode;

        setGranularityAndRange(year, month);
        setXValueFormat(viewMode);

        LocalDate startDate = LocalDate.of(year, month, 1);

        moveViewToX(convertDateToInt(startDate));
    }

    private void setGranularityAndRange(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = LocalDate.of(year, month, 1);

        int range = 0;
        int granularity = 0;

        switch (viewMode) {
            case DAY_OF_MONTH:
                endDate = startDate.plusMonths(1);
                range = (int)DAYS.between(startDate, endDate);
                granularity = 1;
                break;
            case WEEK_OF_MONTH:
                endDate = startDate.plusMonths(1);
                range = (int)DAYS.between(startDate, endDate);
                granularity = 7;
                break;
            case WEEK_OF_YEAR:
                endDate = startDate.plusYears(1);
                range = (int)DAYS.between(startDate, endDate);
                granularity = 7;
                break;
            case MONTH_OF_YEAR:
                endDate = startDate.plusYears(1);
                range = (int)DAYS.between(startDate, endDate);
                granularity = 30;
                break;
            case DAY_OF_YEAR:
                endDate = startDate.plusYears(1);
                range = (int)DAYS.between(startDate, endDate);
                granularity = 1;
                break;
            case DAY_OF_ALL:
                endDate = startDate.plusMonths(1);
                range = (int)DAYS.between(startDate, endDate);
                granularity = 1;
                break;
            case WEEK_OF_ALL:
                endDate = startDate.plusMonths(1);
                range = (int)DAYS.between(startDate, endDate);
                granularity = 7;
                break;
            case MONTH_OF_ALL:
                endDate = startDate.plusMonths(3);
                range = (int)DAYS.between(startDate, endDate);
                granularity = 30;
                break;
            case YEAR_OF_ALL:
                endDate = startDate.plusYears(1);
                range = (int)DAYS.between(startDate, endDate);
                granularity = 365;
                break;
            default:
                throw new IllegalArgumentException("view mode not implemented");
        }

        Timber.d("GRANUALITY " + granularity);
        Timber.d("RANGE " + range);
        getXAxis().setGranularity(granularity);
        setVisibleXRangeMaximum(range);
        setCustomViewPortOffsets(); // set custom viewPortOffsets to avoid jitter on translating while auto scale is on
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
        setAutoScaleMinMaxEnabled(true);
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
        getXAxis().setGranularityEnabled(true);
    }

    private int convertDateToInt(LocalDate date) {
        return (int)date.toEpochDay();
    }

    private int convertDateToInt(Date date) {
        LocalDate localDate = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return (int)localDate.toEpochDay();
    }

    private LocalDate convertIntToDate(int shortDate) {
        return LocalDate.ofEpochDay(shortDate);
    }

    private void setXValueFormat(final ViewMode mode) {
        getXAxis().setValueFormatter(new ValueFormatter() {

            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                DateTimeFormatter formatter;

                switch (mode) {
                    case DAY_OF_MONTH:
                        formatter = DateTimeFormatter.ofPattern("dd");
                        break;
                    case WEEK_OF_MONTH:
                        formatter = DateTimeFormatter.ofPattern("'W'W");
                        break;
                    case WEEK_OF_YEAR:
                        formatter = DateTimeFormatter.ofPattern("'W'w");
                        break;
                    case MONTH_OF_YEAR:
                        formatter = DateTimeFormatter.ofPattern("MMM");
                        break;
                    case DAY_OF_YEAR:
                        formatter = DateTimeFormatter.ofPattern("D");
                        break;
                    case DAY_OF_ALL:
                        formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT);
                        break;
                    case WEEK_OF_ALL:
                        formatter = DateTimeFormatter.ofPattern("'W'w yyyy");
                        break;
                    case MONTH_OF_ALL:
                        formatter = DateTimeFormatter.ofPattern("MMM yyyy");
                        break;
                    case YEAR_OF_ALL:
                        formatter = DateTimeFormatter.ofPattern("yyyy");
                        break;
                    default:
                        throw new IllegalArgumentException("view mode not implemented");
                }

                return formatter.format(convertIntToDate((int)value));
            }
        });
    }

    private void setCustomViewPortOffsets() {
        float offsetLeft = 0f, offsetRight = 0f, offsetTop = 0f, offsetBottom = 0f;

        RectF mOffsetsBuffer = new RectF();
        calculateLegendOffsets(mOffsetsBuffer);

        offsetLeft += mOffsetsBuffer.left;
        offsetTop += mOffsetsBuffer.top;
        offsetRight += mOffsetsBuffer.right;
        offsetBottom += Math.max(70f, mOffsetsBuffer.bottom);

        // offsets for y-labels
        if (mAxisLeft.needsOffset()) {
            offsetLeft += mAxisLeft.getRequiredWidthSpace(mAxisRendererLeft
                    .getPaintAxisLabels());
        }

        if (mAxisRight.needsOffset()) {
            offsetRight += mAxisRight.getRequiredWidthSpace(mAxisRendererRight
                    .getPaintAxisLabels());
        }

        if (mXAxis.isEnabled() && mXAxis.isDrawLabelsEnabled()) {

            float xLabelHeight = mXAxis.mLabelRotatedHeight + mXAxis.getYOffset();

            // offsets for x-labels
            if (mXAxis.getPosition() == XAxis.XAxisPosition.BOTTOM) {

                offsetBottom += xLabelHeight;

            } else if (mXAxis.getPosition() == XAxis.XAxisPosition.TOP) {

                offsetTop += xLabelHeight;

            } else if (mXAxis.getPosition() == XAxis.XAxisPosition.BOTH_SIDED) {

                offsetBottom += xLabelHeight;
                offsetTop += xLabelHeight;
            }
        }

        offsetTop += getExtraTopOffset();
        offsetRight += getExtraRightOffset();
        offsetBottom += getExtraBottomOffset();
        offsetLeft += getExtraLeftOffset();

        float minOffset = Utils.convertDpToPixel(mMinOffset);

        setViewPortOffsets(
                Math.max(minOffset, offsetLeft),
                Math.max(minOffset, offsetTop),
                Math.max(minOffset, offsetRight),
                Math.max(minOffset, offsetBottom));
    }

    public void updateMeasurementList(final List<ScaleMeasurement> scaleMeasurementList) {
        clear();

        if (scaleMeasurementList.isEmpty()) {
            progressBar.setVisibility(GONE);
            return;
        }

        Collections.reverse(scaleMeasurementList);

        this.scaleMeasurementList = scaleMeasurementList;
        refreshMeasurementList();
    }

    public void refreshMeasurementList() {
        if (scaleMeasurementList == null) {
            progressBar.setVisibility(GONE);
            return;
        }

        progressBar.setVisibility(VISIBLE);

        List<ILineDataSet> lineDataSets;
        lineDataSets = new ArrayList<>();

        for (MeasurementView view : measurementViews) {
            if (view instanceof FloatMeasurementView && view.isVisible()) {
                final FloatMeasurementView measurementView = (FloatMeasurementView) view;

                final List<Entry> lineEntries = new ArrayList<>();

                for (int i=0; i<scaleMeasurementList.size(); i++) {
                    ScaleMeasurement measurement = scaleMeasurementList.get(i);
                    float value = measurementView.getMeasurementValue(measurement);

                    if (value == 0.0f) {
                        continue;
                    }

                    Entry entry = new Entry();
                    entry.setX(convertDateToInt(measurement.getDateTime()));
                    entry.setY(value);
                    Object[] extraData = new Object[3];
                    extraData[0] = measurement;
                    extraData[1] = (i == 0) ? null : scaleMeasurementList.get(i-1);
                    extraData[2] = measurementView;
                    entry.setData(extraData);

                    lineEntries.add(entry);
                }

                addMeasurementLine(lineDataSets, lineEntries, measurementView);
            }
        }

        addTrendLine(lineDataSets);
       // addGoalLine(lineDataSets);

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

    private List<ScaleMeasurement> getScaleMeasurementsAsTrendline(List<ScaleMeasurement> measurementList) {
        List<ScaleMeasurement> trendlineList = new ArrayList<>();

       /* ScaleMeasurement a = new ScaleMeasurement();
        a.setWeight(173.2f);
        ScaleMeasurement b = new ScaleMeasurement();
        b.setWeight(171.5f);

        ScaleMeasurement entry = b.clone();
        ScaleMeasurement trendPreviousEntry = a.clone();
        ScaleMeasurement tempScaleMeasurement = entry.clone();

        tempScaleMeasurement.subtract(a);
        tempScaleMeasurement.multiply(0.1f);
        trendPreviousEntry.add(tempScaleMeasurement);*/

        trendlineList.add(measurementList.get(0));

        for (int i = 1; i < measurementList.size(); i++) {
            ScaleMeasurement entry = measurementList.get(i).clone();
            ScaleMeasurement trendPreviousEntry = trendlineList.get(i - 1).clone();

            entry.subtract(trendPreviousEntry);
            entry.multiply(0.1f);
            entry.add(trendPreviousEntry);

            trendlineList.add(entry);
            // Timber.d("TREND LINE " + entry.getWeight() + " DATE " + entry.getDateTime());
        }

        return trendlineList;
    }

    private void addTrendLine(List<ILineDataSet> lineDataSets) {

        List<ScaleMeasurement> scaleMeasurementsAsTrendlineList = getScaleMeasurementsAsTrendline(scaleMeasurementList);

        for (MeasurementView view : measurementViews) {
            if (view instanceof FloatMeasurementView && view.isVisible()) {
                final FloatMeasurementView measurementView = (FloatMeasurementView) view;

                final List<Entry> lineEntries = new ArrayList<>();

                for (int i=0; i<scaleMeasurementsAsTrendlineList.size(); i++) {
                    ScaleMeasurement measurement = scaleMeasurementsAsTrendlineList.get(i);
                    float value = measurementView.getMeasurementValue(measurement);

                    if (value == 0.0f) {
                        continue;
                    }

                    Entry entry = new Entry();
                    entry.setX(convertDateToInt(measurement.getDateTime()));
                    entry.setY(value);
                    Object[] extraData = new Object[3];
                    extraData[0] = measurement;
                    extraData[1] = (i == 0) ? null : scaleMeasurementsAsTrendlineList.get(i-1);
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
        measurementLine.setDrawCircles(false);//prefs.getBoolean("pointsEnable", true));
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
