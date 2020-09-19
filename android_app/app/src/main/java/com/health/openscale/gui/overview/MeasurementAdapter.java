package com.health.openscale.gui.overview;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.measurement.FloatMeasurementView;
import com.health.openscale.gui.measurement.MeasurementView;
import com.health.openscale.gui.measurement.WeightMeasurementView;
import com.health.openscale.gui.utils.ColorUtil;

import java.util.ArrayList;
import java.util.List;

class MeasurementAdapter extends RecyclerView.Adapter<MeasurementAdapter.ViewHolder> {
    private Context context;
    private ScaleMeasurement scaleMeasurement;
    private ScaleMeasurement prevScaleMeasurement;
    private List<FloatMeasurementView> measurementViewList;

    public MeasurementAdapter(Context aContext, ScaleMeasurement scaleMeasurement, ScaleMeasurement prevScaleMeasurement) {
        context = aContext;
        this.scaleMeasurement = scaleMeasurement;
        this.prevScaleMeasurement = prevScaleMeasurement;
        measurementViewList = new ArrayList<>();
        List<MeasurementView> measurementDefaultViewList = MeasurementView.getMeasurementList(context,  MeasurementView.DateTimeOrder.LAST);

        for (MeasurementView measurementView : measurementDefaultViewList) {
            if (measurementView instanceof FloatMeasurementView && measurementView.isVisible() && !(measurementView instanceof WeightMeasurementView)) {
                measurementViewList.add((FloatMeasurementView)measurementView);
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_measurement, parent, false);

        MeasurementAdapter.ViewHolder viewHolder = new MeasurementAdapter.ViewHolder(view);

        return viewHolder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FloatMeasurementView measurementView = measurementViewList.get(position);

        measurementView.loadFrom(scaleMeasurement, prevScaleMeasurement);

        GradientDrawable iconViewBackground = new GradientDrawable();
        iconViewBackground.setColor(((FloatMeasurementView) measurementView).getColor());
        iconViewBackground.setShape(GradientDrawable.OVAL);
        iconViewBackground.setGradientRadius(holder.iconView.getWidth());
        holder.iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        holder.iconView.setColorFilter(ColorUtil.COLOR_BLACK);
        holder.iconView.setBackground(iconViewBackground);
        holder.iconView.setImageDrawable(measurementView.getIcon());

        SpannableStringBuilder value = new SpannableStringBuilder();
        value.append("◆ ");
        value.setSpan(new ForegroundColorSpan(measurementView.getIndicatorColor()), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        value.append(measurementView.getValueAsString(true));
        measurementView.appendDiffValue(value, true);

        holder.valueView.setText(value);
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
        TextView valueView;
        ImageView iconView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);

            valueView = itemView.findViewById(R.id.valueView);
            iconView = itemView.findViewById(R.id.iconView);
        }
    }
}
