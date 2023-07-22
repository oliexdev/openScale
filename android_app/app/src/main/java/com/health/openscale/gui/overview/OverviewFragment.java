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
package com.health.openscale.gui.overview;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.ChangeScroll;
import androidx.transition.TransitionManager;

import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.DateTimeHelpers;
import com.health.openscale.gui.measurement.ChartActionBarView;
import com.health.openscale.gui.measurement.ChartMeasurementView;
import com.health.openscale.gui.measurement.WeightMeasurementView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class OverviewFragment extends Fragment {
    private View overviewView;

    private TextView txtTitleUser;

    private RecyclerView recyclerView;
    private OverviewAdapter overviewAdapter;
    private ChartMeasurementView chartView;
    private ChartActionBarView chartActionBarView;

    private Spinner spinUser;

    private PopupMenu rangePopupMenu;

    private LinearLayout rowGoal;
    private TextView differenceWeightView;
    private TextView initialWeightView;
    private TextView goalWeightView;

    private ScaleUser currentScaleUser;

    private ArrayAdapter<String> spinUserAdapter;

    private SharedPreferences prefs;

    private List<ScaleMeasurement> scaleMeasurementList;
    private ScaleMeasurement markedMeasurement;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        overviewView = inflater.inflate(R.layout.fragment_overview, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(overviewView.getContext());

        rowGoal = overviewView.findViewById(R.id.rowGoal);
        differenceWeightView = overviewView.findViewById(R.id.differenceWeightView);
        initialWeightView = overviewView.findViewById(R.id.initialWeightView);
        goalWeightView = overviewView.findViewById(R.id.goalWeightView);

        chartView = overviewView.findViewById(R.id.chartView);
        chartView.setOnChartValueSelectedListener(new onChartSelectedListener());
        chartView.setProgressBar(overviewView.findViewById(R.id.progressBar));
        chartView.setIsInGraphKey(false);
        chartView.getLegend().setEnabled(false);

        String yAxisVisibility = prefs.getString("overviewAxis", "Hidden");

        if (!yAxisVisibility.equals("Right") && !yAxisVisibility.equals("Both")) {
            chartView.getAxisRight().setDrawLabels(false);
            chartView.getAxisRight().setDrawGridLines(false);
            chartView.getAxisRight().setDrawAxisLine(false);
        }

        if (!yAxisVisibility.equals("Left") && !yAxisVisibility.equals("Both")) {
            chartView.getAxisLeft().setDrawGridLines(false);
            chartView.getAxisLeft().setDrawLabels(false);
            chartView.getAxisLeft().setDrawAxisLine(false);
        }

        if (yAxisVisibility.equals("Hidden")) {
            chartView.getXAxis().setDrawGridLines(false);
        }

        chartActionBarView = overviewView.findViewById(R.id.chartActionBar);
        chartActionBarView.setIsInGraphKey(false);
        chartActionBarView.setOnActionClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chartView.refreshMeasurementList();
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
                    case R.id.menu_range_day:
                        prefs.edit().putInt("selectRangeMode", ChartMeasurementView.ViewMode.DAY_OF_ALL.ordinal()).commit();
                        break;
                    case R.id.menu_range_week:
                        prefs.edit().putInt("selectRangeMode", ChartMeasurementView.ViewMode.WEEK_OF_ALL.ordinal()).commit();
                        break;
                    case R.id.menu_range_month:
                        prefs.edit().putInt("selectRangeMode", ChartMeasurementView.ViewMode.MONTH_OF_ALL.ordinal()).commit();
                        break;
                    case R.id.menu_range_year:
                        prefs.edit().putInt("selectRangeMode", ChartMeasurementView.ViewMode.YEAR_OF_ALL.ordinal()).commit();
                }

                item.setChecked(true);

                getActivity().recreate(); // TODO HACK to refresh graph; graph.invalidate and notfiydatachange is not enough!?

                return true;
            }
        });
        rangePopupMenu.getMenuInflater().inflate(R.menu.overview_menu, rangePopupMenu.getMenu());
        ChartMeasurementView.ViewMode selectedRangePos = ChartMeasurementView.ViewMode.values()[prefs.getInt("selectRangeMode", ChartMeasurementView.ViewMode.DAY_OF_ALL.ordinal())];

        switch (selectedRangePos) {
            case DAY_OF_ALL:
                rangePopupMenu.getMenu().findItem(R.id.menu_range_day).setChecked(true);
                break;
            case WEEK_OF_ALL:
                rangePopupMenu.getMenu().findItem(R.id.menu_range_week).setChecked(true);
                break;
            case MONTH_OF_ALL:
                rangePopupMenu.getMenu().findItem(R.id.menu_range_month).setChecked(true);
                break;
            case YEAR_OF_ALL:
                rangePopupMenu.getMenu().findItem(R.id.menu_range_year).setChecked(true);
                break;
        }

        MenuItem enableMeasurementBar = rangePopupMenu.getMenu().findItem(R.id.enableChartActionBar);
        enableMeasurementBar.setChecked(prefs.getBoolean("enableOverviewChartActionBar", false));

        if (enableMeasurementBar.isChecked()) {
            chartActionBarView.setVisibility(View.VISIBLE);
        } else {
            chartActionBarView.setVisibility(View.GONE);
        }

        recyclerView = overviewView.findViewById(R.id.recyclerView);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setInitialPrefetchItemCount(5);
        layoutManager.setReverseLayout(true);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);

        spinUserAdapter = new ArrayAdapter<>(overviewView.getContext(), R.layout.spinner_item, new ArrayList<String>());
        spinUser.setAdapter(spinUserAdapter);

        // Set item select listener after spinner is created because otherwise item listener fires a lot!?!?
        spinUser.post(new Runnable() {
            public void run() {
                spinUser.setOnItemSelectedListener(new spinUserSelectionListener());
                updateUserSelection();
            }
        });

        chartView.animateY(700);

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

        return overviewView;
    }

    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList) {
        this.scaleMeasurementList = scaleMeasurementList;

        overviewAdapter = new OverviewAdapter(getActivity(), scaleMeasurementList);
        recyclerView.setAdapter(overviewAdapter);

        updateUserSelection();
        chartView.updateMeasurementList(scaleMeasurementList);
        updateChartView();
    }

    private void updateChartView() {
        ChartMeasurementView.ViewMode selectedRangeMode = ChartMeasurementView.ViewMode.values()[prefs.getInt("selectRangeMode", ChartMeasurementView.ViewMode.DAY_OF_ALL.ordinal())];
        chartView.setViewRange(selectedRangeMode);
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
        spinUser.setVisibility(visibility);

        if (currentScaleUser.isGoalEnabled()) {
            rowGoal.setVisibility(View.VISIBLE);

            WeightMeasurementView weightMeasurementView = new WeightMeasurementView(getContext());
            ScaleMeasurement initialWeightMeasurement = OpenScale.getInstance().getLastScaleMeasurement();

            if (initialWeightMeasurement == null) {
                initialWeightMeasurement = new ScaleMeasurement();
            }

            initialWeightMeasurement.setWeight(initialWeightMeasurement.getWeight());
            weightMeasurementView.loadFrom(initialWeightMeasurement, null);

            SpannableStringBuilder initialWeightValue = new SpannableStringBuilder();
            initialWeightValue.append(getResources().getString(R.string.label_weight));
            initialWeightValue.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, initialWeightValue.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            initialWeightValue.append("\n");
            initialWeightValue.append(weightMeasurementView.getValueAsString(true));
            initialWeightValue.append(("\n"));
            int start = initialWeightValue.length();
            initialWeightValue.append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(initialWeightMeasurement.getDateTime()));
            initialWeightValue.setSpan(new RelativeSizeSpan(0.8f), start, initialWeightValue.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            initialWeightView.setText(initialWeightValue);

            ScaleMeasurement goalWeightMeasurement = new ScaleMeasurement();
            goalWeightMeasurement.setWeight(currentScaleUser.getGoalWeight());
            weightMeasurementView.loadFrom(goalWeightMeasurement, null);

            SpannableStringBuilder goalWeightValue = new SpannableStringBuilder();
            goalWeightValue.append(getResources().getString(R.string.label_goal_weight));
            goalWeightValue.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, goalWeightValue.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            goalWeightValue.append("\n");
            goalWeightValue.append(weightMeasurementView.getValueAsString(true));
            goalWeightValue.append(("\n"));
            start = goalWeightValue.length();
            goalWeightValue.append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(currentScaleUser.getGoalDate()));
            goalWeightValue.setSpan(new RelativeSizeSpan(0.8f), start, goalWeightValue.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            goalWeightView.setText(goalWeightValue);

            ScaleMeasurement differenceWeightMeasurement = new ScaleMeasurement();
            if (initialWeightMeasurement.getWeight() > goalWeightMeasurement.getWeight()) {
                differenceWeightMeasurement.setWeight(initialWeightMeasurement.getWeight() - goalWeightMeasurement.getWeight());
            } else {
                differenceWeightMeasurement.setWeight(goalWeightMeasurement.getWeight() - initialWeightMeasurement.getWeight());
            }
            weightMeasurementView.loadFrom(differenceWeightMeasurement, null);

            Calendar initialCalendar = Calendar.getInstance();
            initialCalendar.setTime(initialWeightMeasurement.getDateTime());
            Calendar goalCalendar = Calendar.getInstance();
            goalCalendar.setTime(currentScaleUser.getGoalDate());
            int daysBetween = Math.max(0, DateTimeHelpers.daysBetween(initialCalendar, goalCalendar));

            SpannableStringBuilder differenceWeightValue = new SpannableStringBuilder();
            differenceWeightValue.append(getResources().getString(R.string.label_weight_difference));
            differenceWeightValue.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, differenceWeightValue.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            differenceWeightValue.append("\n");
            differenceWeightValue.append(weightMeasurementView.getValueAsString(true));
            differenceWeightValue.append(("\n"));
            start = differenceWeightValue.length();
            differenceWeightValue.append(daysBetween + " " + getString(R.string.label_days_left));
            differenceWeightValue.setSpan(new RelativeSizeSpan(0.8f), start, differenceWeightValue.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            differenceWeightView.setText(differenceWeightValue);
        } else {
            rowGoal.setVisibility(View.GONE);
        }
    }

    private class onChartSelectedListener implements OnChartValueSelectedListener {

        @Override
        public void onValueSelected(Entry e, Highlight h) {
            Object[] extraData = (Object[])e.getData();

            markedMeasurement = (ScaleMeasurement)extraData[0];
            //MeasurementView measurementView = (MeasurementView)extraData[1];

            if (scaleMeasurementList.contains(markedMeasurement)) {
                TransitionManager.beginDelayedTransition(recyclerView, new ChangeScroll());
                recyclerView.scrollToPosition(scaleMeasurementList.indexOf(markedMeasurement));
            }
        }

        @Override
        public void onNothingSelected() {
            // empty
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
                 updateOnView(openScale.getScaleMeasurementList());
             }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }
}
