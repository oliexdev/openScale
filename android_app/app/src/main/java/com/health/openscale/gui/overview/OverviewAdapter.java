package com.health.openscale.gui.overview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.measurement.DateMeasurementView;
import com.health.openscale.gui.measurement.MeasurementEntryFragment;
import com.health.openscale.gui.measurement.MeasurementView;
import com.health.openscale.gui.measurement.TimeMeasurementView;
import com.health.openscale.gui.measurement.UserMeasurementView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;

class OverviewAdapter extends RecyclerView.Adapter<OverviewAdapter.ViewHolder> {
    private Activity activity;
    private List<ScaleMeasurement> scaleMeasurementList;

    public OverviewAdapter(Activity activity, List<ScaleMeasurement> scaleMeasurementList) {
        this.activity = activity;
        this.scaleMeasurementList = scaleMeasurementList;
    }

    private void deleteMeasurement(int measurementId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean deleteConfirmationEnable = prefs.getBoolean("deleteConfirmationEnable", true);

        if (deleteConfirmationEnable) {
            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(activity);
            deleteAllDialog.setMessage(activity.getResources().getString(R.string.question_really_delete));

            deleteAllDialog.setPositiveButton(activity.getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    doDeleteMeasurement(measurementId);
                }
            });

            deleteAllDialog.setNegativeButton(activity.getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            deleteAllDialog.show();
        }
        else {
            doDeleteMeasurement(measurementId);
        }
    }

    private void doDeleteMeasurement(int measurementId) {
        OpenScale.getInstance().deleteScaleMeasurement(measurementId);
        Toast.makeText(activity, activity.getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();
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
        ScaleMeasurement prevScaleMeasurement;

        // for the first measurement no previous measurement are available, use standard measurement instead
        if (position == 0) {
            prevScaleMeasurement = new ScaleMeasurement();
        } else {
            prevScaleMeasurement = scaleMeasurementList.get(position - 1);
        }

        holder.showEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverviewFragmentDirections.ActionNavOverviewToNavDataentry action = OverviewFragmentDirections.actionNavOverviewToNavDataentry();
                action.setMeasurementId(scaleMeasurement.getId());
                action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.VIEW);
                Navigation.findNavController(activity, R.id.nav_host_fragment).navigate(action);
            }
        });

        holder.editEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverviewFragmentDirections.ActionNavOverviewToNavDataentry action = OverviewFragmentDirections.actionNavOverviewToNavDataentry();
                action.setMeasurementId(scaleMeasurement.getId());
                action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.EDIT);
                Navigation.findNavController(activity, R.id.nav_host_fragment).navigate(action);
            }
        });
        holder.deleteEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteMeasurement(scaleMeasurement.getId());
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

        holder.dateView.setText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(scaleMeasurement.getDateTime()) +
                " (" + new SimpleDateFormat("EE").format(scaleMeasurement.getDateTime()) + ") "+
                DateFormat.getTimeInstance(DateFormat.SHORT).format(scaleMeasurement.getDateTime()));

        List<MeasurementView> measurementViewList = MeasurementView.getMeasurementList(activity,  MeasurementView.DateTimeOrder.LAST);

        for (MeasurementView measurementView : measurementViewList) {
            if (measurementView instanceof DateMeasurementView || measurementView instanceof TimeMeasurementView || measurementView instanceof UserMeasurementView) {
                measurementView.setVisible(false);
            }
            else if (measurementView.isVisible()) {
                measurementView.loadFrom(scaleMeasurement, prevScaleMeasurement);

                if (measurementView.getSettings().isSticky()) {
                    holder.measurementHighlightViews.addView(measurementView);
                } else{
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
        ImageView showEntry;
        ImageView editEntry;
        ImageView deleteEntry;
        TableLayout measurementHighlightViews;
        ImageView expandMeasurementView;
        TableLayout measurementViews;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);

            dateView = itemView.findViewById(R.id.dateView);
            showEntry = itemView.findViewById(R.id.showEntry);
            editEntry = itemView.findViewById(R.id.editEntry);
            deleteEntry = itemView.findViewById(R.id.deleteEntry);
            measurementHighlightViews = itemView.findViewById(R.id.measurementHighlightViews);
            expandMeasurementView = itemView.findViewById(R.id.expandMoreView);
            measurementViews = itemView.findViewById(R.id.measurementViews);
            measurementViews.setVisibility(View.GONE);
        }
    }
}
