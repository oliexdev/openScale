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
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.AxisBase;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.formatter.IAxisValueFormatter;
import com.github.mikephil.charting.formatter.StackedValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.datasets.IBarDataSet;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.gui.activities.DataEntryActivity;
import com.health.openscale.gui.utils.ColorUtil;
import com.health.openscale.gui.views.ChartMeasurementView;
import com.health.openscale.gui.views.FloatMeasurementView;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.MeasurementViewSettings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import androidx.fragment.app.Fragment;

public class GraphFragment extends Fragment implements FragmentUpdateListener {
    private View graphView;
    private ChartMeasurementView chartView;
    private BarChart chartTop;
    private TextView txtYear;
    private Button btnLeftYear;
    private Button btnRightYear;
    private LinearLayout floatingActionBar;
    private PopupMenu popup;
    private FloatingActionButton showMenu;
    private FloatingActionButton editMenu;
    private FloatingActionButton deleteMenu;
    private SharedPreferences prefs;

    private List<MeasurementView> measurementViews;

    private OpenScale openScale;

    private final Calendar calYears;
    private Calendar calLastSelected;

    private ScaleMeasurement markedMeasurement;

    private static final String CAL_YEARS_KEY = "calYears";
    private static final String CAL_LAST_SELECTED_KEY = "calLastSelected";

    public GraphFragment() {
        calYears = Calendar.getInstance();
        calLastSelected = Calendar.getInstance();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        openScale = OpenScale.getInstance();

        if (savedInstanceState == null) {
            List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleMeasurementList();
            if (!scaleMeasurementList.isEmpty()) {
                calYears.setTime(scaleMeasurementList.get(0).getDateTime());
                calLastSelected.setTime(scaleMeasurementList.get(0).getDateTime());
            }
        }
        else {
            calYears.setTimeInMillis(savedInstanceState.getLong(CAL_YEARS_KEY));
            calLastSelected.setTimeInMillis(savedInstanceState.getLong(CAL_LAST_SELECTED_KEY));
        }

        graphView = inflater.inflate(R.layout.fragment_graph, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

        chartView = graphView.findViewById(R.id.chartView);
        chartView.setOnChartValueSelectedListener(new onChartValueSelectedListener());

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
        chartTopxAxis.setTextColor(ColorUtil.getTextColor(graphView.getContext()));
        chartTopxAxis.setValueFormatter(new IAxisValueFormatter() {

            private final SimpleDateFormat mFormat = new SimpleDateFormat("MMM", Locale.getDefault());

            @Override
            public String getFormattedValue(float value, AxisBase axis) {
                Calendar calendar = Calendar.getInstance();
                calendar.set(Calendar.MONTH, (int)value);
                return mFormat.format(calendar.getTime());
            }
        });

        txtYear = graphView.findViewById(R.id.txtYear);
        txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));

        floatingActionBar = graphView.findViewById(R.id.floatingActionBar);

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
                calYears.roll(Calendar.YEAR, false);
                txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));

                List<ScaleMeasurement> scaleMeasurementList =
                        OpenScale.getInstance().getScaleDataOfYear(calYears.get(Calendar.YEAR));
                if (!scaleMeasurementList.isEmpty()) {
                    calLastSelected.setTime(scaleMeasurementList.get(0).getDateTime());
                }
                generateGraphs();
            }
        });

        btnRightYear = graphView.findViewById(R.id.btnRightYear);
        btnRightYear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                calYears.roll(Calendar.YEAR, true);
                txtYear.setText(Integer.toString(calYears.get(Calendar.YEAR)));

                List<ScaleMeasurement> scaleMeasurementList =
                        OpenScale.getInstance().getScaleDataOfYear(calYears.get(Calendar.YEAR));
                if (!scaleMeasurementList.isEmpty()) {
                    calLastSelected.setTime(scaleMeasurementList.get(scaleMeasurementList.size() - 1).getDateTime());
                }
                generateGraphs();
            }
        });

        measurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.NONE);

        popup = new PopupMenu(getContext(), optionMenu);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {

                switch (item.getItemId()) {
                    case R.id.enableMonth:
                        if (item.isChecked()) {
                            item.setChecked(false);
                            prefs.edit().putBoolean("showMonth", false).apply();
                        } else {
                            item.setChecked(true);
                            prefs.edit().putBoolean("showMonth", true).apply();
                        }

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

        showMenu = graphView.findViewById(R.id.showMenu);
        showMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = markedMeasurement.getId();

                Intent intent = new Intent(graphView.getContext(), DataEntryActivity.class);
                intent.putExtra(DataEntryActivity.EXTRA_ID, id);
                intent.putExtra(DataEntryActivity.EXTRA_MODE, DataEntryActivity.VIEW_MEASUREMENT_REQUEST);
                startActivity(intent);
            }
        });

        editMenu = graphView.findViewById(R.id.editMenu);
        editMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = markedMeasurement.getId();

                Intent intent = new Intent(graphView.getContext(), DataEntryActivity.class);
                intent.putExtra(DataEntryActivity.EXTRA_ID, id);
                intent.putExtra(DataEntryActivity.EXTRA_MODE, DataEntryActivity.EDIT_MEASUREMENT_REQUEST);
                startActivity(intent);
            }
        });
        deleteMenu = graphView.findViewById(R.id.deleteMenu);
        deleteMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteMeasurement();
            }
        });

        openScale.registerFragment(this);

        return graphView;
    }

    @Override
    public void onDestroyView() {
        OpenScale.getInstance().unregisterFragment(this);
        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putLong(CAL_YEARS_KEY, calYears.getTimeInMillis());
        outState.putLong(CAL_LAST_SELECTED_KEY, calLastSelected.getTimeInMillis());
    }

    @Override
    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList)
    {
        generateGraphs();
    }

    private void addFloatingActionButton(FloatMeasurementView measurementView) {
        FloatingActionButton actionButton = new FloatingActionButton(getContext());

        actionButton.setTag(measurementView.getKey());
        actionButton.setColorFilter(Color.parseColor("#000000"));
        actionButton.setImageDrawable(measurementView.getIcon());
        actionButton.setClickable(true);
        actionButton.setSize(FloatingActionButton.SIZE_MINI);
        RelativeLayout.LayoutParams lay = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lay.setMargins(0,5,20,10);
        actionButton.setLayoutParams(lay);
        actionButton.setOnClickListener(new onClickListenerDiagramLines());

        int color = measurementView.getSettings().isInGraph()
                ? measurementView.getColor() : ColorUtil.COLOR_GRAY;
        actionButton.setBackgroundTintList(ColorStateList.valueOf(color));

        floatingActionBar.addView(actionButton);
    }

    private void refreshFloatingActionsButtons() {
        floatingActionBar.removeAllViews();

        for (MeasurementView view : measurementViews) {
            if (view instanceof FloatMeasurementView) {
                final FloatMeasurementView measurementView = (FloatMeasurementView) view;

                if (measurementView.isVisible()) {
                    addFloatingActionButton(measurementView);
                }
            }
        }
    }
    private void generateColumnData()
    {
        int[] numOfMonth = openScale.getCountsOfMonth(calYears.get(Calendar.YEAR));

        Calendar calMonths = Calendar.getInstance();
        calMonths.set(Calendar.MONTH, Calendar.JANUARY);

        List<IBarDataSet> dataSets = new ArrayList<>();

        for (int i=0; i<12; i++) {
            List<BarEntry> entries = new ArrayList<>();

            entries.add(new BarEntry(calMonths.get(Calendar.MONTH), numOfMonth[i]));

            calMonths.add(Calendar.MONTH, 1);

            BarDataSet set = new BarDataSet(entries, "month "+i);
            set.setColor(ColorUtil.COLORS[i % 4]);
            set.setDrawValues(true);
            set.setValueFormatter(new StackedValueFormatter(true, "", 0));
            dataSets.add(set);
        }

        BarData data = new BarData(dataSets);

        chartTop.setData(data);
        chartTop.setFitBars(true);
        chartTop.invalidate();
    }

    private void generateGraphs() {
        final int selectedYear = calYears.get(Calendar.YEAR);

        int firstYear = selectedYear;
        int lastYear = selectedYear;

        List<ScaleMeasurement> scaleMeasurementList = openScale.getScaleMeasurementList();
        if (!scaleMeasurementList.isEmpty()) {
            Calendar cal = Calendar.getInstance();

            cal.setTime(scaleMeasurementList.get(scaleMeasurementList.size() - 1).getDateTime());
            firstYear = cal.get(Calendar.YEAR);

            cal.setTime(scaleMeasurementList.get(0).getDateTime());
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
                chartView.setViewRange(selectedYear, calLastSelected.get(Calendar.MONTH), ChartMeasurementView.ViewMode.WEEK_OF_MONTH);
            } else {
                chartView.setViewRange(selectedYear, calLastSelected.get(Calendar.MONTH), ChartMeasurementView.ViewMode.DAY_OF_MONTH);
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

        refreshFloatingActionsButtons();
    }

    private class chartTopValueTouchListener implements OnChartValueSelectedListener {
        @Override
        public void onValueSelected(Entry e, Highlight h) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.MONTH, (int)e.getX());

            calLastSelected = cal;

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

    private class onClickListenerDiagramLines implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            FloatingActionButton actionButton = (FloatingActionButton) v;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

            String key = String.valueOf(actionButton.getTag());
            MeasurementViewSettings settings = new MeasurementViewSettings(prefs, key);
            prefs.edit().putBoolean(settings.getInGraphKey(), !settings.isInGraph()).apply();

            generateGraphs();
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
        OpenScale.getInstance().deleteScaleData(markedMeasurement.getId());
        Toast.makeText(graphView.getContext(), getResources().getString(R.string.info_data_deleted), Toast.LENGTH_SHORT).show();

        showMenu.setVisibility(View.GONE);
        editMenu.setVisibility(View.GONE);
        deleteMenu.setVisibility(View.GONE);

        chartTop.invalidate();
        chartView.invalidate();
    }
}
