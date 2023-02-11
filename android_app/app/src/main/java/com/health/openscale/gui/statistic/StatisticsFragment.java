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

package com.health.openscale.gui.statistic;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcel;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.CompositeDateValidator;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.DateValidatorPointForward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.datepicker.MaterialPickerOnPositiveButtonClickListener;
import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class StatisticsFragment extends Fragment {
    private RecyclerView compareRecyclerView;
    private TextView diffDateTextView;
    private TextView countMeasurementTextView;
    private ImageView datePickerView;
    private StatisticAdapter statisticAdapter;
    private List<ScaleMeasurement> scaleMeasurementList;
    private SharedPreferences prefs;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View statisticsView = inflater.inflate(R.layout.fragment_statistics, container, false);

        prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        compareRecyclerView = statisticsView.findViewById(R.id.compareRecyclerView);
        diffDateTextView = statisticsView.findViewById(R.id.diffDateTextView);
        countMeasurementTextView = statisticsView.findViewById(R.id.countMeasurementTextView);
        datePickerView = statisticsView.findViewById(R.id.datePickerView);

        datePickerView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                List<CalendarConstraints.DateValidator> dateValidatorList = new ArrayList<>();

                CalendarConstraints.DateValidator selectedDateValidator = new CalendarConstraints.DateValidator() {
                    @Override
                    public boolean isValid(long date) {
                        Calendar dateCalendar = Calendar.getInstance();
                        dateCalendar.setTime(new Date(date));

                        List<ScaleMeasurement> dateScaleMeasurementList = OpenScale.getInstance().getScaleMeasurementOfDay(dateCalendar.get(Calendar.YEAR), dateCalendar.get(Calendar.MONTH), dateCalendar.get(Calendar.DAY_OF_MONTH));

                        if (!dateScaleMeasurementList.isEmpty()) {
                            return true;
                        }

                        return false;
                    }

                    @Override
                    public int describeContents() {
                        return 0;
                    }

                    @Override
                    public void writeToParcel(@NonNull Parcel parcel, int i) {

                    }
                };
                dateValidatorList.add(DateValidatorPointForward.from(scaleMeasurementList.get(scaleMeasurementList.size()-1).getDateTime().getTime()));
                dateValidatorList.add(DateValidatorPointBackward.before(scaleMeasurementList.get(0).getDateTime().getTime()));
                dateValidatorList.add(selectedDateValidator);

                CalendarConstraints constraintsBuilderRange = new CalendarConstraints.Builder().setValidator(CompositeDateValidator.allOf(dateValidatorList)).build();

                MaterialDatePicker<Pair<Long, Long>> materialDate = MaterialDatePicker.Builder.dateRangePicker().setCalendarConstraints(constraintsBuilderRange).build();
                materialDate.show(getActivity().getSupportFragmentManager(), "MATERIAL_DATE_PICKER");

                materialDate.addOnPositiveButtonClickListener(new MaterialPickerOnPositiveButtonClickListener<Pair<Long, Long>>() {
                    @Override public void onPositiveButtonClick(Pair<Long,Long> selection) {
                        Calendar startCalendar = Calendar.getInstance();
                        startCalendar.setTime(new Date(selection.first));
                        Calendar endCalendar = Calendar.getInstance();
                        endCalendar.setTime(new Date(selection.second));

                        setDiffDateText(startCalendar.getTime(), endCalendar.getTime());

                        List<ScaleMeasurement> rangeScaleMeasurementList = OpenScale.getInstance().getScaleMeasurementOfRangeDates(startCalendar.get(Calendar.YEAR), startCalendar.get(Calendar.MONTH), startCalendar.get(Calendar.DAY_OF_MONTH),
                                endCalendar.get(Calendar.YEAR), endCalendar.get(Calendar.MONTH), endCalendar.get(Calendar.DAY_OF_MONTH));

                        prefs.edit().putLong("statistic_range_start_date", startCalendar.getTime().getTime()).commit();

                        updateStatistic(rangeScaleMeasurementList);
                    }
                });
            }
        });

        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        compareRecyclerView.setLayoutManager(layoutManager);

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

        return statisticsView;
    }

    public void updateStatistic(List<ScaleMeasurement> rangeScaleMeasurementList) {
        compareRecyclerView.setVisibility(View.VISIBLE);
        statisticAdapter = new StatisticAdapter(getActivity(), rangeScaleMeasurementList);
        compareRecyclerView.setAdapter(statisticAdapter);

        countMeasurementTextView.setText(rangeScaleMeasurementList.size() + " " + getResources().getString(R.string.label_measurements));

        ScaleMeasurement firstMeasurement;
        ScaleMeasurement lastMeasurement;

        if (rangeScaleMeasurementList.isEmpty()) {
            firstMeasurement = new ScaleMeasurement();
            lastMeasurement = new ScaleMeasurement();
        } else if (rangeScaleMeasurementList.size() == 1) {
            firstMeasurement = rangeScaleMeasurementList.get(0);
            lastMeasurement = rangeScaleMeasurementList.get(0);
        } else {
            firstMeasurement = rangeScaleMeasurementList.get(rangeScaleMeasurementList.size() - 1);
            lastMeasurement = rangeScaleMeasurementList.get(0);
        }

        setDiffDateText(firstMeasurement.getDateTime(), lastMeasurement.getDateTime());
    }

    public void setDiffDateText(Date firstDate, Date secondDate) {
        String diffDateText = DateFormat.getDateInstance(DateFormat.MEDIUM).format(firstDate) + " - " +
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(secondDate);

        diffDateTextView.setText(diffDateText);
    }

    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList) {
        this.scaleMeasurementList = scaleMeasurementList;

        Long prefDate = prefs.getLong("statistic_range_start_date", 0);

        if (prefDate != 0) {
            Calendar startCalendar = Calendar.getInstance();
            startCalendar.setTime(new Date(prefDate));
            Calendar endCalendar = Calendar.getInstance();
            endCalendar.setTime(new Date());

            setDiffDateText(startCalendar.getTime(), endCalendar.getTime());

            List<ScaleMeasurement> rangeScaleMeasurementList = OpenScale.getInstance().getScaleMeasurementOfRangeDates(startCalendar.get(Calendar.YEAR), startCalendar.get(Calendar.MONTH), startCalendar.get(Calendar.DAY_OF_MONTH),
                    endCalendar.get(Calendar.YEAR), endCalendar.get(Calendar.MONTH), endCalendar.get(Calendar.DAY_OF_MONTH));

            updateStatistic(rangeScaleMeasurementList);
        } else {
            updateStatistic(scaleMeasurementList);
        }

    }
}
