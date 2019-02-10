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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.utils.ColorUtil;
import com.health.openscale.gui.views.ChartActionBarView;
import com.health.openscale.gui.views.ChartMeasurementView;
import com.health.openscale.gui.views.MeasurementView;

import java.util.ArrayList;
import java.util.List;

import androidx.fragment.app.Fragment;

public class OverviewFragment extends Fragment implements FragmentUpdateListener {
    private View overviewView;

    private TextView txtTitleUser;
    private TextView txtTitleLastMeasurement;

    private List<MeasurementView> lastMeasurementViews;

    private ChartMeasurementView chartView;
    private ChartActionBarView chartActionBarView;

    private Spinner spinUser;

    private PopupMenu rangePopupMenu;

    private ImageView showEntry;
    private ImageView editEntry;
    private ImageView deleteEntry;

    private ScaleUser currentScaleUser;

    private ArrayAdapter<String> spinUserAdapter;

    private SharedPreferences prefs;

    private ScaleMeasurement markedMeasurement;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        overviewView = inflater.inflate(R.layout.fragment_overview, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(overviewView.getContext());

        txtTitleUser = overviewView.findViewById(R.id.txtTitleUser);
        txtTitleLastMeasurement = overviewView.findViewById(R.id.txtTitleLastMeasurement);

        chartView = overviewView.findViewById(R.id.chartView);
        chartView.setOnChartValueSelectedListener(new onChartSelectedListener());
        chartView.setAnimationOn(false);
        chartView.setIsInGraphKey(false);

        chartActionBarView = overviewView.findViewById(R.id.chartActionBar);
        chartActionBarView.setIsInGraphKey(false);
        chartActionBarView.setOnActionClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateChartView();
            }
        });

        spinUser = overviewView.findViewById(R.id.spinUser);

        ImageView optionMenu = overviewView.findViewById(R.id.rangeOptionMenu);
        optionMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rangePopupMenu.show();
            }
        });

        rangePopupMenu = new PopupMenu(getContext(), optionMenu);
        rangePopupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {

                switch (item.getItemId()) {
                    case R.id.enableChartActionBar:
                        if (item.isChecked()) {
                            item.setChecked(false);
                            prefs.edit().putBoolean("enableOverviewChartActionBar", false).apply();
                            chartActionBarView.setVisibility(View.GONE);
                        } else {
                            item.setChecked(true);
                            prefs.edit().putBoolean("enableOverviewChartActionBar", true).apply();
                            chartActionBarView.setVisibility(View.VISIBLE);
                        }
                        return true;
                    case R.id.enableRollingChart:
                        if (item.isChecked()) {
                            item.setChecked(false);
                            prefs.edit().putBoolean("enableRollingChart", false).apply();
                        } else {
                            item.setChecked(true);
                            prefs.edit().putBoolean("enableRollingChart", true).apply();
                        }

                        getActivity().recreate(); // TODO HACK to refresh graph; graph.invalidate and notfiydatachange is not enough!?

                        return true;
                }


                item.setChecked(true);

                prefs.edit().putInt("selectRange", item.getItemId()).commit();

                if (prefs.getBoolean("enableRollingChart", true)) {
                    getActivity().recreate(); // TODO HACK to refresh graph; if rolling chart is enabled then graph.invalidate and notfiydatachange is not enough!?
                } else {
                    updateChartView();
                }

                return true;
            }
        });
        rangePopupMenu.getMenuInflater().inflate(R.menu.overview_menu, rangePopupMenu.getMenu());
        rangePopupMenu.getMenu().findItem(prefs.getInt("selectRange", R.id.menu_range_day)).setChecked(true);
        rangePopupMenu.getMenu().findItem(R.id.enableRollingChart).setChecked(prefs.getBoolean("enableRollingChart", true));

        MenuItem enableMeasurementBar = rangePopupMenu.getMenu().findItem(R.id.enableChartActionBar);
        enableMeasurementBar.setChecked(prefs.getBoolean("enableOverviewChartActionBar", false));

        if (enableMeasurementBar.isChecked()) {
            chartActionBarView.setVisibility(View.VISIBLE);
        } else {
            chartActionBarView.setVisibility(View.GONE);
        }

        lastMeasurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.NONE);

        TableLayout tableOverviewLayout = overviewView.findViewById(R.id.tableLayoutMeasurements);

        for (MeasurementView measurement : lastMeasurementViews) {
            tableOverviewLayout.addView(measurement);
        }

        spinUserAdapter = new ArrayAdapter<>(overviewView.getContext(), R.layout.spinner_item, new ArrayList<String>());
        spinUser.setAdapter(spinUserAdapter);

        // Set item select listener after spinner is created because otherwise item listener fires a lot!?!?
        spinUser.post(new Runnable() {
            public void run() {
                spinUser.setOnItemSelectedListener(new spinUserSelectionListener());
                updateUserSelection();
            }
        });

        showEntry = overviewView.findViewById(R.id.showEntry);
        showEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = markedMeasurement.getId();

                Intent intent = new Intent(overviewView.getContext(), DataEntryActivity.class);
                intent.putExtra(DataEntryActivity.EXTRA_ID, id);
                intent.putExtra(DataEntryActivity.EXTRA_MODE, DataEntryActivity.VIEW_MEASUREMENT_REQUEST);
                startActivity(intent);
            }
        });

        editEntry = overviewView.findViewById(R.id.editEntry);
        editEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = markedMeasurement.getId();

                Intent intent = new Intent(overviewView.getContext(), DataEntryActivity.class);
                intent.putExtra(DataEntryActivity.EXTRA_ID, id);
                intent.putExtra(DataEntryActivity.EXTRA_MODE, DataEntryActivity.EDIT_MEASUREMENT_REQUEST);
                startActivity(intent);
            }
        });
        deleteEntry = overviewView.findViewById(R.id.deleteEntry);
        deleteEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteMeasurement();
            }
        });

        showEntry.setEnabled(false);
        editEntry.setEnabled(false);
        deleteEntry.setEnabled(false);

        txtTitleLastMeasurement.setText(getResources().getString(R.string.label_title_last_measurement).toUpperCase());

        OpenScale.getInstance().registerFragment(this);

        chartView.animateY(700);

        return overviewView;
    }

    @Override
    public void onDestroyView() {
        OpenScale.getInstance().unregisterFragment(this);
        super.onDestroyView();
    }

    @Override
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList) {
        if (scaleMeasurementList.isEmpty()) {
            markedMeasurement = new ScaleMeasurement();
        } else {
            markedMeasurement = scaleMeasurementList.get(0);
        }

        updateUserSelection();
        updateMesurementViews(markedMeasurement);
        updateChartView();
    }

    private void updateChartView() {
        boolean enableRollingChart = prefs.getBoolean("enableRollingChart", true);

        switch (prefs.getInt("selectRange", R.id.menu_range_day)) {
            case R.id.menu_range_day:
                chartView.setViewRange(ChartMeasurementView.ViewMode.DAY_OF_ALL, enableRollingChart);
                break;
            case R.id.menu_range_week:
                chartView.setViewRange(ChartMeasurementView.ViewMode.WEEK_OF_ALL, enableRollingChart);
                break;
            case R.id.menu_range_month:
                chartView.setViewRange(ChartMeasurementView.ViewMode.MONTH_OF_ALL, enableRollingChart);
                break;
            case R.id.menu_range_year:
                chartView.setViewRange(ChartMeasurementView.ViewMode.YEAR_OF_ALL, enableRollingChart);
                break;
        }
    }

    private void updateMesurementViews(ScaleMeasurement selectedMeasurement) {
        ScaleMeasurement[] tupleScaleData = OpenScale.getInstance().getTupleScaleData(selectedMeasurement.getId());
        ScaleMeasurement prevScaleMeasurement = tupleScaleData[0];

        for (MeasurementView measurement : lastMeasurementViews) {
            measurement.loadFrom(selectedMeasurement, prevScaleMeasurement);
        }
    }

    private void updateUserSelection() {

        currentScaleUser = OpenScale.getInstance().getSelectedScaleUser();

        spinUserAdapter.clear();
        List<ScaleUser> scaleUserList = OpenScale.getInstance().getScaleUserList();

        int posUser = 0;

        for (ScaleUser scaleUser : scaleUserList) {
            spinUserAdapter.add(scaleUser.getUserName());

            if (scaleUser.getId() == currentScaleUser.getId()) {
                posUser = spinUserAdapter.getCount() - 1;
            }
        }

        spinUser.setSelection(posUser, true);

        // Hide user selector when there is only one user
        int visibility = spinUserAdapter.getCount() < 2 ? View.GONE : View.VISIBLE;
        txtTitleUser.setVisibility(visibility);
        spinUser.setVisibility(visibility);
    }

    private class onChartSelectedListener implements OnChartValueSelectedListener {

        @Override
        public void onValueSelected(Entry e, Highlight h) {
            Object[] extraData = (Object[])e.getData();

            markedMeasurement = (ScaleMeasurement)extraData[0];
            //MeasurementView measurementView = (MeasurementView)extraData[1];

            showEntry.setEnabled(true);
            editEntry.setEnabled(true);
            deleteEntry.setEnabled(true);

            showEntry.setColorFilter(ColorUtil.COLOR_BLUE);
            editEntry.setColorFilter(ColorUtil.COLOR_GREEN);
            deleteEntry.setColorFilter(ColorUtil.COLOR_RED);

            updateMesurementViews(markedMeasurement);
        }

        @Override
        public void onNothingSelected() {
            showEntry.setEnabled(false);
            editEntry.setEnabled(false);
            deleteEntry.setEnabled(false);

            showEntry.setColorFilter(ColorUtil.COLOR_GRAY);
            editEntry.setColorFilter(ColorUtil.COLOR_GRAY);
            deleteEntry.setColorFilter(ColorUtil.COLOR_GRAY);
        }
    }

    private class spinUserSelectionListener implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
             if (parent.getChildCount() > 0) {
                 ((TextView) parent.getChildAt(0)).setTextColor(Color.GRAY);

                 OpenScale openScale = OpenScale.getInstance();

                 List<ScaleUser> scaleUserList = openScale.getScaleUserList();
                 ScaleUser scaleUser = scaleUserList.get(position);

                 openScale.selectScaleUser(scaleUser.getId());
                 openScale.updateScaleData();
             }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }

    private void deleteMeasurement() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(overviewView.getContext());
        boolean deleteConfirmationEnable = prefs.getBoolean("deleteConfirmationEnable", true);

        if (deleteConfirmationEnable) {
            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(overviewView.getContext());
            deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete));

            deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    doDeleteMeasurement();
                }
            });

            deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            deleteAllDialog.show();
        }
        else {
            doDeleteMeasurement();
        }
    }

    private void doDeleteMeasurement() {
        OpenScale.getInstance().deleteScaleData(markedMeasurement.getId());
        Toast.makeText(overviewView.getContext(), getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();

        deleteEntry.setVisibility(View.GONE);
        editEntry.setVisibility(View.GONE);
        deleteEntry.setVisibility(View.GONE);

        updateOnView(OpenScale.getInstance().getScaleMeasurementList());
    }
}
