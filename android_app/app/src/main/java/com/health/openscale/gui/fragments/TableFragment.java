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
package com.health.openscale.gui.fragments;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.views.MeasurementView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

public class TableFragment extends Fragment implements FragmentUpdateListener {
    private View tableView;
    private LinearLayout tableHeaderView;

    private RecyclerView recyclerView;
    private MeasurementsAdapter adapter;
    private RecyclerView.LayoutManager layoutManager;

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

        adapter = new MeasurementsAdapter();
        recyclerView.setAdapter(adapter);

        measurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.FIRST);

        for (MeasurementView measurement : measurementViews) {
            measurement.setUpdateViews(false);
        }

        OpenScale.getInstance().registerFragment(this);

        return tableView;
    }

    @Override
    public void onDestroyView() {
        OpenScale.getInstance().unregisterFragment(this);
        super.onDestroyView();
    }

    @Override
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList)
    {
        tableHeaderView.removeAllViews();

        ArrayList<MeasurementView> visibleMeasurements = new ArrayList<>();
        for (MeasurementView measurement : measurementViews) {

            if (measurement.isVisible()) {
                ImageView headerIcon = new ImageView(tableView.getContext());
                headerIcon.setImageDrawable(measurement.getIcon());
                headerIcon.setLayoutParams(new TableRow.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.MATCH_PARENT, 1));
                headerIcon.getLayoutParams().width = 0;
                headerIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                headerIcon.getLayoutParams().height = pxImageDp(20);

                tableHeaderView.addView(headerIcon);

                visibleMeasurements.add(measurement);
            }
        }

        adapter.setMeasurements(visibleMeasurements, scaleMeasurementList);
    }

    private int pxImageDp(float dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class MeasurementsAdapter extends RecyclerView.Adapter<MeasurementsAdapter.ViewHolder> {

        public class ViewHolder extends RecyclerView.ViewHolder {
            public LinearLayout measurementView;
            public ViewHolder(LinearLayout view) {
                super(view);
                measurementView = view;
            }
        }

        private List<MeasurementView> visibleMeasurements;
        private List<ScaleMeasurement> scaleMeasurements;

        private Spanned[][] stringCache;

        public void setMeasurements(List<MeasurementView> visibleMeasurements,
                                    List<ScaleMeasurement> scaleMeasurements) {
            this.visibleMeasurements = visibleMeasurements;
            this.scaleMeasurements = scaleMeasurements;

            stringCache = new Spanned[scaleMeasurements.size()][visibleMeasurements.size()];

            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(getContext());
            row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            final int screenSize = getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
            final boolean isSmallScreen =
                    screenSize != Configuration.SCREENLAYOUT_SIZE_XLARGE
                            && screenSize != Configuration.SCREENLAYOUT_SIZE_LARGE;

            for (int i = 0; i < visibleMeasurements.size(); ++i) {
                TextView column = new TextView(getContext());
                LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1);
                layoutParams.width = 0;
                column.setLayoutParams(layoutParams);
                column.setMinLines(2);
                column.setGravity(Gravity.CENTER_HORIZONTAL);

                if (isSmallScreen) {
                    column.setTextSize(COMPLEX_UNIT_DIP, 9);
                }
                row.addView(column);
            }

            return new ViewHolder(row);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            final ScaleMeasurement measurement = scaleMeasurements.get(position);

            // Create entries in stringCache if needed
            if (stringCache[position][0] == null) {
                ScaleMeasurement prevMeasurement = null;
                if (position + 1 < scaleMeasurements.size()) {
                    prevMeasurement = scaleMeasurements.get(position + 1);
                }

                for (int i = 0; i < visibleMeasurements.size(); ++i) {
                    visibleMeasurements.get(i).loadFrom(measurement, prevMeasurement);

                    SpannableStringBuilder string = new SpannableStringBuilder();
                    string.append(visibleMeasurements.get(i).getValueAsString(false));
                    visibleMeasurements.get(i).appendDiffValue(string, true);

                    stringCache[position][i] = string;
                }
            }

            // Fill view with data
            LinearLayout row = holder.measurementView;
            for (int i = 0; i < visibleMeasurements.size(); ++i) {
                TextView column = (TextView) row.getChildAt(i);
                column.setText(stringCache[position][i]);
            }

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(getContext(), DataEntryActivity.class);
                    intent.putExtra(DataEntryActivity.EXTRA_ID, measurement.getId());
                    startActivity(intent);
                }
            });
        }

        @Override
        public int getItemCount() {
            return scaleMeasurements == null ? 0 : scaleMeasurements.size();
        }
    }
}
