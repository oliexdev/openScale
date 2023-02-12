package com.health.openscale.gui.statistic;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.measurement.FloatMeasurementView;
import com.health.openscale.gui.measurement.MeasurementView;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

class StatisticAdapter extends RecyclerView.Adapter<StatisticAdapter.ViewHolder> {
    private Activity activity;
    private List<ScaleMeasurement> scaleMeasurementList;
    private ScaleMeasurement firstMeasurement;
    private ScaleMeasurement lastMeasurement;
    private List<FloatMeasurementView> measurementViewList;

    public StatisticAdapter(Activity activity, List<ScaleMeasurement> scaleMeasurementList) {
        this.activity = activity;
        this.scaleMeasurementList = scaleMeasurementList;

        if (scaleMeasurementList.isEmpty()) {
            this.firstMeasurement = new ScaleMeasurement();
            this.lastMeasurement = new ScaleMeasurement();
        } else if (scaleMeasurementList.size() == 1) {
            this.firstMeasurement = scaleMeasurementList.get(0);
            this.lastMeasurement = scaleMeasurementList.get(0);
        } else {
            this.firstMeasurement = scaleMeasurementList.get(scaleMeasurementList.size()-1);
            this.lastMeasurement = scaleMeasurementList.get(0);
        }

        List<MeasurementView> fullMeasurementViewList = MeasurementView.getMeasurementList(activity,  MeasurementView.DateTimeOrder.LAST);
        measurementViewList = new ArrayList<>();

        for (MeasurementView measurementView : fullMeasurementViewList) {
            if (measurementView instanceof FloatMeasurementView && measurementView.isVisible()) {
                measurementViewList.add((FloatMeasurementView)measurementView);
            }
        }
    }

    @Override
    public StatisticAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_statistic, parent, false);

        ViewHolder viewHolder = new ViewHolder(view);

        return viewHolder;
    }

    @Override
    public void onBindViewHolder(@NonNull StatisticAdapter.ViewHolder holder, int position) {
        FloatMeasurementView measurementView = measurementViewList.get(position);
        List<Entry> lineEntries = new ArrayList<>();

        Collections.reverse(scaleMeasurementList);

        int i=0;
        float sumValue = 0;
        float maxValue = Float.MIN_VALUE;
        float minValue = Float.MAX_VALUE;
        for (ScaleMeasurement scaleMeasurement : scaleMeasurementList) {
            measurementView.loadFrom(scaleMeasurement, null);

            float value = measurementView.getValue();

            sumValue += value;
            if (value > maxValue) {
                maxValue = value;
            }
            if (value < minValue) {
                minValue = value;
            }

            lineEntries.add(new Entry(i, value));
            i++;
        }

        Collections.reverse(scaleMeasurementList);

        LineDataSet lineDataSet = new LineDataSet(lineEntries, holder.measurementName.getText().toString());
        lineDataSet.setColor(measurementView.getColor());
        lineDataSet.setDrawCircles(false);
        lineDataSet.setFillColor(measurementView.getColor());
        lineDataSet.setDrawFilled(true);
        lineDataSet.setDrawValues(false);
        lineDataSet.setHighlightEnabled(false);

        List<ILineDataSet> dataSets = new ArrayList<>();
        dataSets.add(lineDataSet);

        LineData data = new LineData(dataSets);
        holder.diffChartView.setData(data);
        holder.diffChartView.invalidate();

        measurementView.loadFrom(lastMeasurement, firstMeasurement);

        holder.measurementName.setText(measurementView.getName());
        SpannableStringBuilder statisticValueText = new SpannableStringBuilder();
        statisticValueText.append(activity.getResources().getString(R.string.label_abbr_min) + " " + measurementView.formatValue(minValue != Float.MAX_VALUE ? minValue : 0, true) + "\n");
        statisticValueText.append(activity.getResources().getString(R.string.label_abbr_max) + " " + measurementView.formatValue(maxValue != Float.MIN_VALUE ? maxValue : 0, true) + "\n");
        statisticValueText.append(activity.getResources().getString(R.string.label_abbr_avg) + " " + measurementView.formatValue(sumValue != 0 ? sumValue / scaleMeasurementList.size() : 0, true) + "\n");
        statisticValueText.setSpan(new RelativeSizeSpan(0.8f), 0, statisticValueText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        holder.statisticValueView.setText(statisticValueText);
        SpannableStringBuilder endValueText = new SpannableStringBuilder();
        measurementView.appendDiffValue(endValueText, true );
        endValueText.append("\n");
        endValueText.append(measurementView.getValueAsString(true));
        holder.endValueView.setText(endValueText);
        holder.iconView.setImageDrawable(measurementView.getIcon());
        holder.iconView.setBackgroundTintList(ColorStateList.valueOf(measurementView.getColor()));

        measurementView.loadFrom(firstMeasurement, null);
        holder.startValueView.setText(measurementView.getValueAsString(true));
    }

    private int convertDateToInt(Date date) {
        LocalDate localDate = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return (int)localDate.toEpochDay();
    }

    @Override
    public long getItemId(int position) {
        return measurementViewList.get(position).getId();
    }

    @Override
    public int getItemCount() {
        return measurementViewList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView measurementName;
        TextView statisticValueView;
        TextView startValueView;
        FloatingActionButton iconView;
        LineChart diffChartView;
        TextView endValueView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);

            measurementName = itemView.findViewById(R.id.measurementName);
            statisticValueView = itemView.findViewById(R.id.statisticValueView);
            startValueView = itemView.findViewById(R.id.startValueView);
            iconView = itemView.findViewById(R.id.iconView);
            diffChartView = itemView.findViewById(R.id.diffChartView);
            endValueView = itemView.findViewById(R.id.endValueView);

            diffChartView.getLegend().setEnabled(false);
            diffChartView.getDescription().setEnabled(false);
            diffChartView.getAxisRight().setDrawLabels(false);
            diffChartView.getAxisRight().setDrawGridLines(false);
            diffChartView.getAxisRight().setDrawAxisLine(false);
            diffChartView.getAxisLeft().setDrawGridLines(false);
            diffChartView.getAxisLeft().setDrawLabels(false);
            diffChartView.getAxisLeft().setDrawAxisLine(false);
            diffChartView.getXAxis().setDrawGridLines(false);
            diffChartView.getXAxis().setDrawLabels(false);
            diffChartView.getXAxis().setDrawAxisLine(false);
            diffChartView.setMinOffset(0);
        }
    }
}
