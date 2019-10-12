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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ProgressBar;
import android.widget.Toast;

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
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.github.mikephil.charting.utils.Utils;
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
    private List<ScaleMeasurement> scaleMeasurementList;
    private List<MeasurementView> measurementViews;
    private ScaleMeasurement firstMeasurement;
    private ScaleMeasurement lastMeasurement;
    private int maxXValue;
    private int minXValue;
    private ViewMode viewMode;
    private boolean isAnimationOn;
    private boolean isInGraphKey;
    private int scrollHistoryCount;
    private ProgressBar progressBar;
    private boolean isRollingChart;

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
        progressBar.setVisibility(VISIBLE);
        isRollingChart = rollingChart;

        if (isRollingChart) {
            ScaleMeasurement lastMeasurement = openScale.getLatestScaleMeasurement(openScale.getSelectedScaleUserId());

            if (lastMeasurement != null) {
                Calendar lastMeasurementCalender = Calendar.getInstance();
                lastMeasurementCalender.setTime(lastMeasurement.getDateTime());

                switch (mode) {
                    case DAY_OF_ALL:
                        lastMeasurementCalender.add(Calendar.DAY_OF_MONTH, -28 * scrollHistoryCount);
                        break;
                    case WEEK_OF_ALL:
                        lastMeasurementCalender.add(Calendar.WEEK_OF_YEAR, -4 * scrollHistoryCount);
                        break;
                    case MONTH_OF_ALL:
                        lastMeasurementCalender.add(Calendar.MONTH, -4 * scrollHistoryCount);
                        break;
                    case YEAR_OF_ALL:
                        lastMeasurementCalender.add(Calendar.YEAR, -4 * scrollHistoryCount);
                        break;
                    default:
                        throw new IllegalArgumentException("view mode not implemented");
                }

                setMeasurementList(openScale.getScaleDataOfStartDate(lastMeasurementCalender.get(Calendar.YEAR), lastMeasurementCalender.get(Calendar.MONTH), lastMeasurementCalender.get(Calendar.DAY_OF_MONTH)));
            }
        } else {
            setMeasurementList(openScale.getScaleMeasurementList());
        }
        setViewMode(mode);

        refresh();

        if (isRollingChart) {
            setRollingChartOn(mode);
        }
    }

    public void setViewRange(int year, final ViewMode mode) {
        progressBar.setVisibility(VISIBLE);
        setMeasurementList(openScale.getScaleDataOfYear(year));
        setViewMode(mode);

        refresh();
    }

    public void setViewRange(int year, int month, final ViewMode mode) {
        progressBar.setVisibility(VISIBLE);
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

    public void setProgressBar(ProgressBar bar) {
        progressBar = bar;
    }

    private void initChart() {
        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        openScale = OpenScale.getInstance();
        scaleMeasurementList = new ArrayList<>();
        measurementViews = MeasurementView.getMeasurementList(getContext(), MeasurementView.DateTimeOrder.NONE);
        firstMeasurement = new ScaleMeasurement();
        lastMeasurement = new ScaleMeasurement();
        maxXValue = 0;
        minXValue = 0;
        isAnimationOn = true;
        isInGraphKey = true;
        scrollHistoryCount = 1;
        progressBar = null;

        setHardwareAccelerationEnabled(true);
        setMarker(new ChartMarkerView(getContext(), R.layout.chart_markerview));
        setDoubleTapToZoomEnabled(false);
        setHighlightPerTapEnabled(true);
        getLegend().setEnabled(prefs.getBoolean("legendEnable", true));
        getLegend().setWordWrapEnabled(true);
        getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        getLegend().setTextColor(ColorUtil.getTextColor(getContext()));
        getDescription().setEnabled(false);
        getAxisLeft().setEnabled(true);
        getAxisRight().setEnabled(true);
        getAxisLeft().setTextColor(ColorUtil.getTextColor(getContext()));
        getAxisRight().setTextColor(ColorUtil.getTextColor(getContext()));
        getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        getXAxis().setTextColor(ColorUtil.getTextColor(getContext()));

        setOnChartGestureListener(new OnChartGestureListener() {
            @Override
            public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {

            }

            @Override
            public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {
                if (isRollingChart) {
                    if (progressBar.getVisibility() == GONE) {
                        if (Math.round(getLowestVisibleX()) == Math.round(getXChartMin())) {
                            scrollHistoryCount++;
                            setViewRange(viewMode, isRollingChart);
                        }
                    }
                }
            }

            @Override
            public void onChartLongPressed(MotionEvent me) {

            }

            @Override
            public void onChartDoubleTapped(MotionEvent me) {

            }

            @Override
            public void onChartSingleTapped(MotionEvent me) {

            }

            @Override
            public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {

            }

            @Override
            public void onChartScale(MotionEvent me, float scaleX, float scaleY) {

            }

            @Override
            public void onChartTranslate(MotionEvent me, float dX, float dY) {

            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        progressBar.setVisibility(GONE);
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

            setAutoScaleMinMaxEnabled(true);
            setCustomViewPortOffsets(); // set custom viewPortOffsets to avoid jitter on translating while auto scale is on

            getXAxis().setGranularity(granularity);
            setVisibleXRangeMaximum(range);

            moveViewToX(getBinNr(lastMeasurement));
        }
    }

    private void setXValueFormat(final ViewMode mode) {
        getXAxis().setValueFormatter(new ValueFormatter() {
            private final SimpleDateFormat xValueFormat = new SimpleDateFormat();
            private final Calendar calendar = Calendar.getInstance();

            @Override
            public String getAxisLabel(float value, AxisBase axis) {
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
        final ScaleMeasurement[] avgMeasurementList = new ScaleMeasurement[ maxXValue + minXValue + 1];

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
        for (int i=binNr-1; i >= 0; i--) {
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

                    if (measurementView.getValue() == 0.0f) {
                        continue;
                    }

                    Entry entry = new Entry();
                    entry.setX(binNr);
                    entry.setY(measurementView.getValue());
                    Object[] extraData = new Object[3];
                    extraData[0] = avgMeasurement;
                    extraData[1] = prevMeasuremnt;
                    extraData[2] = measurementView;
                    entry.setData(extraData);

                    lineEntries.add(entry);
                }

                addMeasurementLine(lineDataSets, lineEntries, measurementView);
            }
        }

        addGoalLine(lineDataSets);

        if (isInGraphKey) {
            addRegressionLine(lineDataSets);
        }

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
        measurementLine.setValueTextSize(10.0f);
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

    private void addRegressionLine(List<ILineDataSet> lineDataSets) {
        if (prefs.getBoolean("regressionLine", false)) {
            int regressLineOrder = 1;

            try {
                regressLineOrder = Integer.parseInt(prefs.getString("regressionLineOrder", "1"));
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), getContext().getString(R.string.error_value_required) + ":" + e.getMessage(), Toast.LENGTH_LONG).show();
                prefs.edit().putString("regressionLineOrder", "1").apply();
            }

            List<ILineDataSet> regressionLineDataSets = new ArrayList<>();

            for (ILineDataSet dataSet : lineDataSets) {
                PolynomialFitter polyFitter = new PolynomialFitter(Math.min(regressLineOrder, 100));

                for (int i=0; i<dataSet.getEntryCount(); i++) {
                    Entry entry = dataSet.getEntryForIndex(i);
                    polyFitter.addPoint((double) entry.getX(), (double) entry.getY());
                }

                PolynomialFitter.Polynomial polynomial = polyFitter.getBestFit();

                List<Entry> valuesLinearRegression = new Stack<>();

                for (int i = minXValue; i < maxXValue + minXValue + 1; i++) {
                    double y_value = polynomial.getY(i);
                    valuesLinearRegression.add(new Entry((float) i, (float) y_value));
                }

                LineDataSet linearRegressionLine = new LineDataSet(valuesLinearRegression, dataSet.getLabel() + "-" + getContext().getString(R.string.label_regression_line));
                linearRegressionLine.setLineWidth(1.5f);
                linearRegressionLine.setColor(dataSet.getColor());
                linearRegressionLine.setAxisDependency(dataSet.getAxisDependency());
                linearRegressionLine.setDrawValues(false);
                linearRegressionLine.setDrawCircles(false);
                linearRegressionLine.setHighlightEnabled(false);
                linearRegressionLine.enableDashedLine(10, 30, 0);

                regressionLineDataSets.add(linearRegressionLine);
            }

            for (ILineDataSet dataSet : regressionLineDataSets) {
                lineDataSets.add(dataSet);
            }
        }
    }
}
