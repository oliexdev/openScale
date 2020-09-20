package com.health.openscale.gui.overview;

import android.app.Activity;
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
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.flexbox.AlignItems;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.android.flexbox.JustifyContent;
import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.measurement.MeasurementEntryFragment;
import com.health.openscale.gui.measurement.WeightMeasurementView;

import java.text.DateFormat;
import java.util.List;

class OverviewAdapter extends RecyclerView.Adapter<OverviewAdapter.ViewHolder> {
    private Activity activity;
    private List<ScaleMeasurement> scaleMeasurementList;

    public OverviewAdapter(Activity activity, List<ScaleMeasurement> scaleMeasurementList) {
        this.activity = activity;
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

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverviewFragmentDirections.ActionNavOverviewToNavDataentry action = OverviewFragmentDirections.actionNavOverviewToNavDataentry();
                action.setMeasurementId(scaleMeasurement.getId());
                action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.VIEW);
                Navigation.findNavController(activity, R.id.nav_host_fragment).navigate(action);
            }
        });

        if (!scaleMeasurement.getComment().isEmpty()) {
            holder.commentTextView.setVisibility(View.VISIBLE);
            holder.commentIconView.setVisibility(View.VISIBLE);
            holder.commentTextView.setText(scaleMeasurement.getComment());
        } else {
            holder.commentTextView.setVisibility(View.GONE);
            holder.commentIconView.setVisibility(View.GONE);
        }

        FlexboxLayoutManager layoutManager = new FlexboxLayoutManager(activity);
        layoutManager.setFlexDirection(FlexDirection.ROW);
        layoutManager.setJustifyContent(JustifyContent.SPACE_AROUND);
        layoutManager.setAlignItems(AlignItems.CENTER);
        holder.measurementRecyclerView.setLayoutManager(layoutManager);
        holder.measurementRecyclerView.setHasFixedSize(true);
        holder.measurementRecyclerView.setNestedScrollingEnabled(false);
        holder.measurementRecyclerView.setAdapter(new MeasurementAdapter(activity, scaleMeasurement, prevScaleMeasurement));

        WeightMeasurementView weightMeasurementView = new WeightMeasurementView(activity);
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
        ImageView commentIconView;
        TextView commentTextView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);

            dateView = itemView.findViewById(R.id.dateView);
            weightView = itemView.findViewById(R.id.weightView);
            expandMoreView = itemView.findViewById(R.id.expandMoreView);
            measurementRecyclerView = itemView.findViewById(R.id.measurementRecyclerView);
            commentIconView = itemView.findViewById(R.id.commentIconView);
            commentTextView = itemView.findViewById(R.id.commentTextView);
        }
    }
}
