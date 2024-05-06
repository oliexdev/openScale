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

import android.content.DialogInterface;
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
import androidx.appcompat.app.AlertDialog;
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
                    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity(), R.style.AppTheme_Dialog);
                    builder.setTitle(R.string.label_time_period)
                            .setItems(R.array.range_options_entries, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    switch (which) {
                                        case 0: // all days
                                            setAllDaysRange();
                                            break;
                                        case 1: // last 7 days
                                            setLast7DaysRange();
                                            break;
                                        case 2: // last 30 days
                                            setLast30DaysRange();
                                            break;
                                        case 3: // set reference day
                                            MaterialDatePicker materialDatePicker = MaterialDatePicker.Builder.datePicker().setCalendarConstraints(getCalendarConstraints()).build();
                                            materialDatePicker.show(getActivity().getSupportFragmentManager(), "MATERIAL_DATE_PICKER");

                                            materialDatePicker.addOnPositiveButtonClickListener(new MaterialPickerOnPositiveButtonClickListener<Long>() {
                                                @Override
                                                public void onPositiveButtonClick(Long selection) {
                                                    setReferenceDay(new Date(selection));
                                                }
                                            });
                                            break;
                                        case 4: // custom range
                                            MaterialDatePicker<Pair<Long, Long>> materialDateRangePicker = MaterialDatePicker.Builder.dateRangePicker().setCalendarConstraints(getCalendarConstraints()).build();
                                            materialDateRangePicker.show(getActivity().getSupportFragmentManager(), "MATERIAL_DATE_RANGE_PICKER");

                                            materialDateRangePicker.addOnPositiveButtonClickListener(new MaterialPickerOnPositiveButtonClickListener<Pair<Long, Long>>() {
                                                @Override public void onPositiveButtonClick(Pair<Long,Long> selection) {
                                                    setCustomRange(new Date(selection.first), new Date(selection.second));
                                                }
                                            });
                                            break;
                                    }
                                }
                            });
                    builder.create();
                    builder.show();
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
    }

    public void setDiffDateText(Date firstDate, Date secondDate) {
        String diffDateText = DateFormat.getDateInstance(DateFormat.MEDIUM).format(firstDate) + " - " +
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(secondDate);

        diffDateTextView.setText(diffDateText);
    }

    public void updateOnView(List<ScaleMeasurement> scaleMeasurementList) {
        this.scaleMeasurementList = scaleMeasurementList;

        Long prefStartDate = prefs.getLong("statistic_range_start_date", -1);
        Long prefEndDate = prefs.getLong("statistic_range_end_date", -1);

        if (prefStartDate == -1) {
            setAllDaysRange();
        } else if (prefStartDate == -7) {
            setLast7DaysRange();
        } else if (prefStartDate == -30) {
            setLast30DaysRange();
        } else if (prefEndDate == -1 && prefStartDate > 0) {
            setReferenceDay(new Date(prefStartDate));
        }else if (prefEndDate > 0 && prefStartDate > 0) {
            setCustomRange(new Date(prefStartDate), new Date(prefEndDate));
        }
    }

    private void setAllDaysRange() {
        diffDateTextView.setText(getResources().getString(R.string.label_time_period_all_days));
        prefs.edit().putLong("statistic_range_start_date", -1).commit();
        updateStatistic(scaleMeasurementList);
    }

    private void setLast7DaysRange() {
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTime(new Date());
        startCalendar.add(Calendar.DAY_OF_YEAR, -7);

        prefs.edit().putLong("statistic_range_start_date", -7).commit();
        diffDateTextView.setText(getResources().getString(R.string.label_time_period_last_7_days));

        List<ScaleMeasurement> rangeScaleMeasurementList = OpenScale.getInstance().getScaleMeasurementOfStartDate(startCalendar.get(Calendar.YEAR), startCalendar.get(Calendar.MONTH), startCalendar.get(Calendar.DAY_OF_MONTH));

        updateStatistic(rangeScaleMeasurementList);
    }

    private void setLast30DaysRange() {
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTime(new Date());
        startCalendar.add(Calendar.DAY_OF_YEAR, -30);

        prefs.edit().putLong("statistic_range_start_date", -30).commit();
        diffDateTextView.setText(getResources().getString(R.string.label_time_period_last_30_days));

        List<ScaleMeasurement> rangeScaleMeasurementList = OpenScale.getInstance().getScaleMeasurementOfStartDate(startCalendar.get(Calendar.YEAR), startCalendar.get(Calendar.MONTH), startCalendar.get(Calendar.DAY_OF_MONTH));

        updateStatistic(rangeScaleMeasurementList);
    }

    private void setReferenceDay(Date selectionDate) {
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTime(selectionDate);
        List<ScaleMeasurement> rangeScaleMeasurementList = OpenScale.getInstance().getScaleMeasurementOfStartDate(startCalendar.get(Calendar.YEAR), startCalendar.get(Calendar.MONTH), startCalendar.get(Calendar.DAY_OF_MONTH));

        prefs.edit().putLong("statistic_range_start_date", startCalendar.getTime().getTime()).commit();
        prefs.edit().putLong("statistic_range_end_date", -1).commit();

        diffDateTextView.setText(DateFormat.getDateInstance(DateFormat.MEDIUM).format(startCalendar.getTime()));

        updateStatistic(rangeScaleMeasurementList);
    }

    private void setCustomRange(Date begin, Date end) {
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTime(begin);
        Calendar endCalendar = Calendar.getInstance();
        endCalendar.setTime(end);

        setDiffDateText(startCalendar.getTime(), endCalendar.getTime());

        List<ScaleMeasurement> rangeScaleMeasurementList = OpenScale.getInstance().getScaleMeasurementOfRangeDates(startCalendar.get(Calendar.YEAR), startCalendar.get(Calendar.MONTH), startCalendar.get(Calendar.DAY_OF_MONTH),
                endCalendar.get(Calendar.YEAR), endCalendar.get(Calendar.MONTH), endCalendar.get(Calendar.DAY_OF_MONTH));

        prefs.edit().putLong("statistic_range_start_date", startCalendar.getTime().getTime()).commit();
        prefs.edit().putLong("statistic_range_end_date", endCalendar.getTime().getTime()).commit();

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

        updateStatistic(rangeScaleMeasurementList);
    }

    private final CalendarConstraints getCalendarConstraints() {
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

        return constraintsBuilderRange;
    }
}
