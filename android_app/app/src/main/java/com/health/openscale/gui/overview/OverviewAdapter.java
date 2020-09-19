package com.health.openscale.gui.overview;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.flexbox.AlignItems;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.android.flexbox.JustifyContent;
import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.measurement.WeightMeasurementView;

import java.text.DateFormat;
import java.util.List;

class OverviewAdapter extends RecyclerView.Adapter<OverviewAdapter.ViewHolder> {
    private Context context;
    private List<ScaleMeasurement> scaleMeasurementList;

    public OverviewAdapter(Context aContext, List<ScaleMeasurement> scaleMeasurementList) {
        this.context = aContext;
        this.scaleMeasurementList = scaleMeasurementList;
    }

    @Override
    public OverviewAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_overview, parent, false);

        ViewHolder viewHolder = new ViewHolder(view);

        return viewHolder;
    }

    @Override
    public void onBindViewHolder(@NonNull OverviewAdapter.ViewHolder holder, int position) {
        ScaleMeasurement scaleMeasurement = scaleMeasurementList.get(position);
        ScaleMeasurement prevScaleMeasurement = scaleMeasurementList.get(position-1);

        holder.expandMoreView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder.measurementRecyclerView.getVisibility() == View.GONE) {
                    holder.measurementRecyclerView.setVisibility(View.VISIBLE);
                } else {
                    holder.measurementRecyclerView.setVisibility(View.GONE);
                }
            }
        });

        FlexboxLayoutManager layoutManager = new FlexboxLayoutManager(context);
        layoutManager.setFlexDirection(FlexDirection.ROW);
        layoutManager.setJustifyContent(JustifyContent.SPACE_AROUND);
        layoutManager.setAlignItems(AlignItems.CENTER);
        holder.measurementRecyclerView.setLayoutManager(layoutManager);
        holder.measurementRecyclerView.setHasFixedSize(true);
        holder.measurementRecyclerView.setNestedScrollingEnabled(false);
        holder.measurementRecyclerView.setAdapter(new MeasurementAdapter(context, scaleMeasurement, prevScaleMeasurement));

        WeightMeasurementView weightMeasurementView = new WeightMeasurementView(context);
        weightMeasurementView.loadFrom(scaleMeasurement, prevScaleMeasurement);
        SpannableStringBuilder weightValue = new SpannableStringBuilder();
        weightValue.append("◆ ");
        weightValue.setSpan(new ForegroundColorSpan(weightMeasurementView.getIndicatorColor()), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        weightValue.append(weightMeasurementView.getValueAsString(true));
        int start = weightValue.length();
        weightMeasurementView.appendDiffValue(weightValue, true);
        weightValue.setSpan(new RelativeSizeSpan(0.9f), start, weightValue.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        holder.weightView.setText(weightValue);
        holder.dateView.setText(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(scaleMeasurement.getDateTime()));
    }

    @Override
    public long getItemId(int position) {
        return scaleMeasurementList.get(position).getId();
    }

    @Override
    public int getItemCount() {
        return scaleMeasurementList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView dateView;
        TextView weightView;
        ImageView expandMoreView;
        RecyclerView measurementRecyclerView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);

            dateView = itemView.findViewById(R.id.dateView);
            weightView = itemView.findViewById(R.id.weightView);
            expandMoreView = itemView.findViewById(R.id.expandMoreView);
            measurementRecyclerView = itemView.findViewById(R.id.measurementRecyclerView);
        }
    }
}
