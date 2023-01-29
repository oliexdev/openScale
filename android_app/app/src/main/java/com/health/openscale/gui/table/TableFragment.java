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

import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.Navigation;

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
import java.util.ArrayList;
import java.util.List;

public class TableFragment extends Fragment {
    private View tableView;

    private StickyHeaderTableView tableDataView;

    private List<MeasurementView> measurementViews;
    private List<ScaleMeasurement> scaleMeasurementList;
    private ArrayList<Drawable> iconList;

    public TableFragment() {

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        tableView = inflater.inflate(R.layout.fragment_table, container, false);

        tableDataView = tableView.findViewById(R.id.tableDataView);

        tableDataView.setOnTableCellClickListener(new StickyHeaderTableView.OnTableCellClickListener() {
            @Override
            public void onTableCellClicked(int rowPosition, int columnPosition) {
                if (rowPosition > 0) {
                    TableFragmentDirections.ActionNavTableToNavDataentry action = TableFragmentDirections.actionNavTableToNavDataentry();
                    action.setMeasurementId(scaleMeasurementList.get(rowPosition-1).getId());
                    action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.VIEW);
                    Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
                }
            }
        });

        measurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.FIRST);

        iconList = new ArrayList<>();

        for (MeasurementView measurementView : measurementViews) {
            if (!measurementView.isVisible() || measurementView instanceof UserMeasurementView || measurementView instanceof TimeMeasurementView) {
                continue;
            }

           // measurementView.setUpdateViews(false);

            measurementView.getIcon().setColorFilter(measurementView.getColor(), PorterDuff.Mode.SRC_ATOP);
            iconList.add(measurementView.getIcon());
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
        this.scaleMeasurementList = scaleMeasurementList;

        Object[][] tableData = new Object[scaleMeasurementList.size()+1][iconList.size()];

        // add header icons to the first table data row
        for (int j=0; j<iconList.size(); j++) {
            tableData[0][j] = iconList.get(j);
        }

        int i = 0;
        for (ScaleMeasurement scaleMeasurement : scaleMeasurementList) {
            int j=0;

            ScaleMeasurement prevScaleMeasurement = null;

            if ((i+1) < scaleMeasurementList.size()) {
                prevScaleMeasurement = scaleMeasurementList.get(i+1);
            }

            for (MeasurementView measurementView : measurementViews) {
                if (!measurementView.isVisible() || measurementView instanceof UserMeasurementView || measurementView instanceof TimeMeasurementView) {
                    continue;
                }
                if (measurementView instanceof DateMeasurementView) {
                    String strDateTime = (DateFormat.getDateInstance(DateFormat.SHORT).format(scaleMeasurement.getDateTime()) +
                            " (" + new SimpleDateFormat("EE").format(scaleMeasurement.getDateTime()) + ")\n"+
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(scaleMeasurement.getDateTime()));
                    tableData[i+1][j] = strDateTime;
                } else {
                    measurementView.loadFrom(scaleMeasurement, prevScaleMeasurement);

                    SpannableStringBuilder string = new SpannableStringBuilder();
                    string.append(measurementView.getValueAsString(false));
                    measurementView.appendDiffValue(string, true);

                    tableData[i+1][j] = string.toString();
                }

                j++;
            }

            i++;
        }

        tableDataView.setData(tableData);
    }



}
