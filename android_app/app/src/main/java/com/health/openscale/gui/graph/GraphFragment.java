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

package com.health.openscale.gui.graph;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.Navigation;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.formatter.StackedValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.measurement.ChartActionBarView;
import com.health.openscale.gui.measurement.ChartMeasurementView;
import com.health.openscale.gui.measurement.MeasurementEntryFragment;
import com.health.openscale.gui.utils.ColorUtil;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class GraphFragment extends Fragment {
    private View graphView;
    private ChartMeasurementView chartView;
    private ChartActionBarView chartActionBarView;
    private BarChart chartTop;
    private TextView txtYear;
    private Button btnLeftYear;
    private Button btnRightYear;
    private PopupMenu popup;
    private FloatingActionButton showMenu;
    private FloatingActionButton editMenu;
    private FloatingActionButton deleteMenu;
    private SharedPreferences prefs;

    private OpenScale openScale;

    private LocalDate calYears;
    private LocalDate calLastSelected;

    private ScaleMeasurement markedMeasurement;

    private static final String CAL_YEARS_KEY = "calYears";
    private static final String CAL_LAST_SELECTED_KEY = "calLastSelected";

    public GraphFragment() {
        calYears = LocalDate.now();
        calLastSelected = LocalDate.now();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        openScale = OpenScale.getInstance();

        if (savedInstanceState == null) {
            if (!openScale.isScaleMeasurementListEmpty()) {
                calYears = openScale.getLastScaleMeasurement().getDateTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();;
                calLastSelected = openScale.getLastScaleMeasurement().getDateTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
        }
        else {
            calYears = LocalDate.ofEpochDay(savedInstanceState.getLong(CAL_YEARS_KEY));
            calLastSelected = LocalDate.ofEpochDay(savedInstanceState.getLong(CAL_LAST_SELECTED_KEY));
        }

        graphView = inflater.inflate(R.layout.fragment_graph, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        chartView = graphView.findViewById(R.id.chartView);
        chartView.setOnChartValueSelectedListener(new onChartValueSelectedListener());
        chartView.setProgressBar(graphView.findViewById(R.id.progressBar));

        chartTop = graphView.findViewById(R.id.chart_top);
        chartTop.setDoubleTapToZoomEnabled(false);
        chartTop.setDrawGridBackground(false);
        chartTop.getLegend().setEnabled(false);
        chartTop.getAxisLeft().setEnabled(false);
        chartTop.getAxisRight().setEnabled(false);
        chartTop.getDescription().setEnabled(false);
        chartTop.setOnChartValueSelectedListener(new chartTopValueTouchListener());

        XAxis chartTopxAxis = chartTop.getXAxis();
        chartTopxAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        chartTopxAxis.setDrawGridLines(false);
        chartTopxAxis.setTextColor(ColorUtil.getTintColor(graphView.getContext()));
        chartTopxAxis.setValueFormatter(new ValueFormatter() {

            private final SimpleDateFormat mFormat = new SimpleDateFormat("MMM", Locale.getDefault());

            @Override
            public String getAxisLabel(float value, AxisBase axis) {
                Calendar calendar = Calendar.getInstance();
                calendar.set(Calendar.MONTH, (int)value);
                return mFormat.format(calendar.getTime());
            }
        });

        txtYear = graphView.findViewById(R.id.txtYear);
        txtYear.setText(Integer.toString(calYears.getYear()));

        chartActionBarView = graphView.findViewById(R.id.chartActionBar);
        chartActionBarView.setOnActionClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generateGraphs();
            }
        });

        ImageView optionMenu = graphView.findViewById(R.id.optionMenu);
        optionMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                popup.show();
            }
        });

        btnLeftYear = graphView.findViewById(R.id.btnLeftYear);
        btnLeftYear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                calYears = calYears.minusYears(1);
                txtYear.setText(Integer.toString(calYears.getYear()));

                generateGraphs();
            }
        });

        btnRightYear = graphView.findViewById(R.id.btnRightYear);
        btnRightYear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                calYears = calYears.plusYears(1);
                txtYear.setText(Integer.toString(calYears.getYear()));

                generateGraphs();
            }
        });

        popup = new PopupMenu(getContext(), optionMenu);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {

                switch (item.getItemId()) {
                    case R.id.enableChartActionBar:
                        if (item.isChecked()) {
                            item.setChecked(false);
                            prefs.edit().putBoolean("enableGraphChartActionBar", false).apply();
                            chartActionBarView.setVisibility(View.GONE);
                        } else {
                            item.setChecked(true);
                            prefs.edit().putBoolean("enableGraphChartActionBar", true).apply();
                            chartActionBarView.setVisibility(View.VISIBLE);
                        }
                        return true;
                    case R.id.enableMonth:
                        if (item.isChecked()) {
                            item.setChecked(false);
                            prefs.edit().putBoolean("showMonth", false).apply();
                        } else {
                            item.setChecked(true);
                            prefs.edit().putBoolean("showMonth", true).apply();
                        }

                        getActivity().recreate(); // TODO HACK to refresh graph; graph.invalidate and notfiydatachange is not enough!?

                        generateGraphs();
                        return true;
                    case R.id.enableWeek:
                        if (item.isChecked()) {
                            item.setChecked(false);
                            prefs.edit().putBoolean("showWeek", false).apply();
                        } else {
                            item.setChecked(true);
                            prefs.edit().putBoolean("showWeek", true).apply();
                        }

                        getActivity().recreate(); // TODO HACK to refresh graph; graph.invalidate and notfiydatachange is not enough!?

                        generateGraphs();
                        return true;
                    default:
                        return false;
                }
            }
        });
        popup.getMenuInflater().inflate(R.menu.graph_menu, popup.getMenu());

        MenuItem enableMonth = popup.getMenu().findItem(R.id.enableMonth);
        enableMonth.setChecked(prefs.getBoolean("showMonth", true));

        MenuItem enableWeek = popup.getMenu().findItem(R.id.enableWeek);
        enableWeek.setChecked(prefs.getBoolean("showWeek", false));

        MenuItem enableMeasurementBar = popup.getMenu().findItem(R.id.enableChartActionBar);
        enableMeasurementBar.setChecked(prefs.getBoolean("enableGraphChartActionBar", true));

        if (enableMeasurementBar.isChecked()) {
            chartActionBarView.setVisibility(View.VISIBLE);
        } else {
            chartActionBarView.setVisibility(View.GONE);
        }

        showMenu = graphView.findViewById(R.id.showMenu);
        showMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GraphFragmentDirections.ActionNavGraphToNavDataentry action = GraphFragmentDirections.actionNavGraphToNavDataentry();
                action.setMeasurementId(markedMeasurement.getId());
                action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.VIEW);
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
            }
        });

        editMenu = graphView.findViewById(R.id.editMenu);
        editMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GraphFragmentDirections.ActionNavGraphToNavDataentry action = GraphFragmentDirections.actionNavGraphToNavDataentry();
                action.setMeasurementId(markedMeasurement.getId());
                action.setMode(MeasurementEntryFragment.DATA_ENTRY_MODE.EDIT);
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);
            }
        });
        deleteMenu = graphView.findViewById(R.id.deleteMenu);
        deleteMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteMeasurement();
            }
        });

        OpenScale.getInstance().getScaleMeasurementsLiveData().observe(getViewLifecycleOwner(), new Observer<List<ScaleMeasurement>>() {
            @Override
            public void onChanged(List<ScaleMeasurement> scaleMeasurements) {
                chartView.updateMeasurementList(scaleMeasurements);
                generateGraphs();
            }
        });

        OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                requireActivity().finish();
            }
        };

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), onBackPressedCallback);

        return graphView;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putLong(CAL_YEARS_KEY, calYears.toEpochDay());
        outState.putLong(CAL_LAST_SELECTED_KEY, calLastSelected.toEpochDay());
    }

    private void generateColumnData()
    {
        int[] numOfMonth = openScale.getCountsOfMonth(calYears.getYear());

        LocalDate calMonths = LocalDate.of(calYears.getYear(), 1, 1);

        List<IBarDataSet> dataSets = new ArrayList<>();

        for (int i=0; i<12; i++) {
            List<BarEntry> entries = new ArrayList<>();

            entries.add(new BarEntry(calMonths.getMonthValue()-1, numOfMonth[i]));

            calMonths = calMonths.plusMonths(1);

            BarDataSet set = new BarDataSet(entries, "month "+i);
            set.setColor(ColorUtil.COLORS[i % 4]);
            set.setDrawValues(false);
            set.setValueFormatter(new StackedValueFormatter(true, "", 0));
            dataSets.add(set);
        }

        BarData data = new BarData(dataSets);

        chartTop.setData(data);
        chartTop.setFitBars(true);
        chartTop.invalidate();
    }

    private void generateGraphs() {
        final int selectedYear = calYears.getYear();

        int firstYear = selectedYear;
        int lastYear = selectedYear;

        if (!openScale.isScaleMeasurementListEmpty()) {
            Calendar cal = Calendar.getInstance();

            cal.setTime(openScale.getFirstScaleMeasurement().getDateTime());
            firstYear = cal.get(Calendar.YEAR);

            cal.setTime(openScale.getLastScaleMeasurement().getDateTime());
            lastYear = cal.get(Calendar.YEAR);
        }
        btnLeftYear.setEnabled(selectedYear > firstYear);
        btnRightYear.setEnabled(selectedYear < lastYear);

        if (selectedYear == firstYear && selectedYear == lastYear) {
            btnLeftYear.setVisibility(View.GONE);
            btnRightYear.setVisibility(View.GONE);
        } else {
            btnLeftYear.setVisibility(View.VISIBLE);
            btnRightYear.setVisibility(View.VISIBLE);
        }

        // show monthly diagram
        if (prefs.getBoolean("showMonth", true)) {
            chartTop.setVisibility(View.VISIBLE);
            chartView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.7f));

            generateColumnData();

           if (prefs.getBoolean("showWeek", false)) {
                chartView.setViewRange(selectedYear, calLastSelected.getMonthValue(), ChartMeasurementView.ViewMode.WEEK_OF_MONTH);
            } else {
                chartView.setViewRange(selectedYear, calLastSelected.getMonthValue(), ChartMeasurementView.ViewMode.DAY_OF_MONTH);
            }
        } else { // show only yearly diagram and hide monthly diagram
            chartTop.setVisibility(View.GONE);
            chartView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f));

            if (prefs.getBoolean("showWeek", false)) {
                chartView.setViewRange(selectedYear, ChartMeasurementView.ViewMode.WEEK_OF_YEAR);
            } else {
                chartView.setViewRange(selectedYear, ChartMeasurementView.ViewMode.MONTH_OF_YEAR);
            }
        }
        chartView.refreshMeasurementList();
    }

    private class chartTopValueTouchListener implements OnChartValueSelectedListener {
        @Override
        public void onValueSelected(Entry e, Highlight h) {
            calLastSelected = calLastSelected.withMonth((int)e.getX()+1);

            generateGraphs();

            showMenu.setVisibility(View.GONE);
            editMenu.setVisibility(View.GONE);
            deleteMenu.setVisibility(View.GONE);
        }

        @Override
        public void onNothingSelected() {

        }
    }

    private class onChartValueSelectedListener implements OnChartValueSelectedListener {
        @Override
        public void onValueSelected(Entry e, Highlight h) {
            Object[] extraData = (Object[])e.getData();

            if (extraData == null) {
                return;
            }

            markedMeasurement = (ScaleMeasurement)extraData[0];
            //MeasurementView measurementView = (MeasurementView)extraData[1];

            showMenu.setVisibility(View.VISIBLE);
            editMenu.setVisibility(View.VISIBLE);
            deleteMenu.setVisibility(View.VISIBLE);
        }

        @Override
        public void onNothingSelected() {
            showMenu.setVisibility(View.GONE);
            editMenu.setVisibility(View.GONE);
            deleteMenu.setVisibility(View.GONE);
        }
    }

    private void deleteMeasurement() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(graphView.getContext());
        boolean deleteConfirmationEnable = prefs.getBoolean("deleteConfirmationEnable", true);

        if (deleteConfirmationEnable) {
            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(graphView.getContext());
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
        OpenScale.getInstance().deleteScaleMeasurement(markedMeasurement.getId());
        Toast.makeText(graphView.getContext(), getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();

        showMenu.setVisibility(View.GONE);
        editMenu.setVisibility(View.GONE);
        deleteMenu.setVisibility(View.GONE);

        chartTop.invalidate();
        chartView.invalidate();
    }
}
