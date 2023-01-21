package com.health.openscale.gui.overview;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import com.health.openscale.R;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.measurement.DateMeasurementView;
import com.health.openscale.gui.measurement.MeasurementEntryFragment;
import com.health.openscale.gui.measurement.MeasurementView;
import com.health.openscale.gui.measurement.TimeMeasurementView;
import com.health.openscale.gui.measurement.UserMeasurementView;

import java.text.DateFormat;
import java.util.List;

class OverviewAdapter extends RecyclerView.Adapter<OverviewAdapter.ViewHolder> {
    private Activity activity;
    private List<ScaleMeasurement> scaleMeasurementList;
    private int maxMeasurementView;


    public OverviewAdapter(Activity activity, List<ScaleMeasurement> scaleMeasurementList) {
        this.activity = activity;
        this.scaleMeasurementList = scaleMeasurementList;
        this.maxMeasurementView = 3;
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
        ScaleMeasurement prevScaleMeasurement = new ScaleMeasurement();

        if (scaleMeasurementList.size() > 2) {
            prevScaleMeasurement = scaleMeasurementList.get(position - 1);
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverviewFragmentDirections.ActionNavOverviewToNavDataentry action = OverviewFragmentDirections.actionNavOverviewToNavDataentry();
                action.setMeasurementId(scaleMeasurement.getId());
                action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.VIEW);
                Navigation.findNavController(activity, R.id.nav_host_fragment).navigate(action);
            }
        });

        holder.expandMeasurementView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TransitionManager.beginDelayedTransition(holder.measurementViews, new AutoTransition());

                if (holder.measurementViews.getVisibility() == View.VISIBLE) {
                    holder.measurementViews.setVisibility(View.GONE);
                    holder.expandMeasurementView.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_expand_more));
                } else {
                    holder.measurementViews.setVisibility(View.VISIBLE);
                    holder.expandMeasurementView.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_expand_less));
                }
            }
        });

        holder.dateView.setText(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(scaleMeasurement.getDateTime()));

        List<MeasurementView> measurementViewList = MeasurementView.getMeasurementList(activity,  MeasurementView.DateTimeOrder.LAST);

        int i = 0;
        for (MeasurementView measurementView : measurementViewList) {
            i++;
            if (measurementView instanceof DateMeasurementView || measurementView instanceof TimeMeasurementView || measurementView instanceof UserMeasurementView) {
                measurementView.setVisible(false);
            }
            else {
                measurementView.loadFrom(scaleMeasurement, prevScaleMeasurement);

                if (i <= maxMeasurementView) {
                    holder.measurementHighlightViews.addView(measurementView);
                } else {
                    holder.measurementViews.addView(measurementView);
                }
            }
        }
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
        TableLayout measurementHighlightViews;
        ImageView expandMeasurementView;
        TableLayout measurementViews;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);

            dateView = itemView.findViewById(R.id.dateView);
            measurementHighlightViews = itemView.findViewById(R.id.measurementHighlightViews);
            expandMeasurementView = itemView.findViewById(R.id.expandMoreView);
            measurementViews = itemView.findViewById(R.id.measurementViews);
            measurementViews.setVisibility(View.GONE);
        }
    }
}
