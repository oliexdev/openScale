/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.gui.table;

import android.content.res.Configuration;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.measurement.MeasurementEntryFragment;
import com.health.openscale.gui.measurement.MeasurementView;
import com.health.openscale.gui.measurement.UserMeasurementView;
import com.health.openscale.gui.utils.ColorUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

public class TableFragment extends Fragment {
    private View tableView;
    private LinearLayout tableHeaderView;

    private RecyclerView recyclerView;
    private MeasurementsAdapter adapter;
    private LinearLayoutManager layoutManager;

    private List<MeasurementView> measurementViews;

    public TableFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        tableView = inflater.inflate(R.layout.fragment_table, container, false);

        tableHeaderView = tableView.findViewById(R.id.tableHeaderView);
        recyclerView = tableView.findViewById(R.id.tableDataView);

        recyclerView.setHasFixedSize(true);

        layoutManager = new LinearLayoutManager(getContext());
        recyclerView.setLayoutManager(layoutManager);

        recyclerView.addItemDecoration(new DividerItemDecoration(
                recyclerView.getContext(), layoutManager.getOrientation()));

        adapter = new MeasurementsAdapter();
        recyclerView.setAdapter(adapter);

        measurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.FIRST);

        for (MeasurementView measurement : measurementViews) {
            measurement.setUpdateViews(false);
        }

        OpenScale.getInstance().getScaleMeasurementsLiveData().observe(getViewLifecycleOwner(), new Observer<List<ScaleMeasurement>>() {
            @Override
            public void onChanged(List<ScaleMeasurement> scaleMeasurements) {
                updateOnView(scaleMeasurements);
            }
        });

        OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                requireActivity().finish();
            }
        };

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), onBackPressedCallback);


        return tableView;
    }

    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList)
    {
        tableHeaderView.removeAllViews();

        final int iconHeight = pxImageDp(20);
        ArrayList<MeasurementView> visibleMeasurements = new ArrayList<>();

        for (MeasurementView measurement : measurementViews) {
            if (!measurement.isVisible() || measurement instanceof UserMeasurementView) {
                continue;
            }


            ImageView headerIcon = new ImageView(tableView.getContext());
            headerIcon.setImageDrawable(measurement.getIcon());
            headerIcon.setColorFilter(ColorUtil.getTintColor(tableView.getContext()));
            headerIcon.setLayoutParams(new TableRow.LayoutParams(0, iconHeight, 1));
            headerIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

            tableHeaderView.addView(headerIcon);

            visibleMeasurements.add(measurement);
        }

        adapter.setMeasurements(visibleMeasurements, scaleMeasurementList);
    }

    private int pxImageDp(float dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class MeasurementsAdapter extends RecyclerView.Adapter<MeasurementsAdapter.ViewHolder> {
        public static final int VIEW_TYPE_MEASUREMENT = 0;
        public static final int VIEW_TYPE_YEAR = 1;

        public class ViewHolder extends RecyclerView.ViewHolder {
            public LinearLayout measurementView;
            public ViewHolder(LinearLayout view) {
                super(view);
                measurementView = view;
            }
        }

        private List<MeasurementView> visibleMeasurements;
        private List<ScaleMeasurement> scaleMeasurements;

        public void setMeasurements(List<MeasurementView> visibleMeasurements,
                                    List<ScaleMeasurement> scaleMeasurements) {
            this.visibleMeasurements = visibleMeasurements;
            this.scaleMeasurements = new ArrayList<>(scaleMeasurements.size() + 10);

            Calendar calendar = Calendar.getInstance();
            if (!scaleMeasurements.isEmpty()) {
                calendar.setTime(scaleMeasurements.get(0).getDateTime());
            }
            calendar.set(calendar.get(Calendar.YEAR), 0, 1, 0, 0, 0);
            calendar.set(calendar.MILLISECOND, 0);

            // Copy all measurements from input parameter to member variable and insert
            // an extra "null" entry when the year changes.
            Date yearStart = calendar.getTime();
            for (int i = 0; i < scaleMeasurements.size(); ++i) {
                final ScaleMeasurement measurement = scaleMeasurements.get(i);

                if (measurement.getDateTime().before(yearStart)) {
                    this.scaleMeasurements.add(null);

                    Calendar newCalendar = Calendar.getInstance();
                    newCalendar.setTime(measurement.getDateTime());
                    calendar.set(Calendar.YEAR, newCalendar.get(Calendar.YEAR));
                    yearStart = calendar.getTime();
                }

                this.scaleMeasurements.add(measurement);
            }

            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(getContext());
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            final int screenSize = getResources().getConfiguration()
                    .screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
            final boolean isSmallScreen =
                    screenSize != Configuration.SCREENLAYOUT_SIZE_XLARGE
                            && screenSize != Configuration.SCREENLAYOUT_SIZE_LARGE;

            final int count = viewType == VIEW_TYPE_YEAR ? 1 : visibleMeasurements.size();
            for (int i = 0; i < count; ++i) {
                TextView column = new TextView(getContext());
                column.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                if (viewType == VIEW_TYPE_MEASUREMENT) {
                    column.setMinLines(2);
                    column.setGravity(Gravity.CENTER_HORIZONTAL);

                    if (isSmallScreen) {
                        column.setTextSize(COMPLEX_UNIT_DIP, 9);
                    }
                }
                else {
                    column.setPadding(0, 10, 0, 10);
                    column.setGravity(Gravity.CENTER);
                    column.setTextSize(COMPLEX_UNIT_DIP, 16);
                }

                row.addView(column);
            }

            return new ViewHolder(row);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            LinearLayout row = holder.measurementView;

            final ScaleMeasurement measurement = scaleMeasurements.get(position);
            if (measurement == null) {
                ScaleMeasurement nextMeasurement = scaleMeasurements.get(position + 1);
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(nextMeasurement.getDateTime());

                TextView column = (TextView) row.getChildAt(0);
                column.setText(String.format("%d", calendar.get(Calendar.YEAR)));
                return;
            }

            ScaleMeasurement prevMeasurement = null;
            if (position + 1 < scaleMeasurements.size()) {
                prevMeasurement = scaleMeasurements.get(position + 1);
                if (prevMeasurement == null) {
                    prevMeasurement = scaleMeasurements.get(position + 2);
                }
            }

            // Fill view with data
            for (int i = 0; i < visibleMeasurements.size(); ++i) {
                final MeasurementView view = visibleMeasurements.get(i);
                view.loadFrom(measurement, prevMeasurement);

                SpannableStringBuilder string = new SpannableStringBuilder();
                string.append(view.getValueAsString(false));
                view.appendDiffValue(string, true);

                TextView column = (TextView) row.getChildAt(i);
                column.setText(string);
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    TableFragmentDirections.ActionNavTableToNavDataentry action = TableFragmentDirections.actionNavTableToNavDataentry();
                    action.setMeasurementId(measurement.getId());
                    action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.VIEW);
                    Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                }
            });
        }

        @Override
        public int getItemCount() {
            return scaleMeasurements == null ? 0 : scaleMeasurements.size();
        }

        @Override
        public int getItemViewType(int position) {
            return scaleMeasurements.get(position) != null ? VIEW_TYPE_MEASUREMENT : VIEW_TYPE_YEAR;
        }
    }
}
