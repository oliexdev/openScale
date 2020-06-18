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
package com.health.openscale.gui.preferences;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class UserSettingsFragment extends Fragment {
    public enum USER_SETTING_MODE {ADD, EDIT};

    private USER_SETTING_MODE mode = USER_SETTING_MODE.ADD;

    private Date birthday = new Date();
    private Date goal_date = new Date();

    private EditText txtUserName;
    private EditText txtBodyHeight;
    private EditText txtBirthday;
    private EditText txtInitialWeight;
    private EditText txtGoalWeight;
    private EditText txtGoalDate;
    private RadioGroup radioScaleUnit;
    private RadioGroup radioGender;
    private CheckBox assistedWeighing;
    private RadioGroup radioMeasurementUnit;
    private Spinner spinnerActivityLevel;
    private Spinner spinnerLeftAmputationLevel;
    private Spinner spinnerRightAmputationLevel;

    private final DateFormat dateFormat = DateFormat.getDateInstance();

    private Context context;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_usersettings, container, false);
        context = getContext();

        setHasOptionsMenu(true);

        if (getArguments() != null) {
            mode = UserSettingsFragmentArgs.fromBundle(getArguments()).getMode();
        } else {
            mode = USER_SETTING_MODE.ADD;
        }

        txtUserName = root.findViewById(R.id.txtUserName);
        txtBodyHeight = root.findViewById(R.id.txtBodyHeight);
        radioScaleUnit = root.findViewById(R.id.groupScaleUnit);
        radioGender = root.findViewById(R.id.groupGender);
        assistedWeighing = root.findViewById(R.id.asisstedWeighing);
        radioMeasurementUnit = root.findViewById(R.id.groupMeasureUnit);
        spinnerActivityLevel = root.findViewById(R.id.spinnerActivityLevel);
        spinnerLeftAmputationLevel = root.findViewById(R.id.spinnerLeftAmputationLevel);
        spinnerRightAmputationLevel = root.findViewById(R.id.spinnerRightAmputationLevel);
        txtInitialWeight = root.findViewById(R.id.txtInitialWeight);
        txtGoalWeight = root.findViewById(R.id.txtGoalWeight);

        txtBirthday = root.findViewById(R.id.txtBirthday);
        txtGoalDate = root.findViewById(R.id.txtGoalDate);

        txtBodyHeight.setHint(getResources().getString(R.string.info_enter_value_in) + " " + Converters.MeasureUnit.CM.toString());
        txtInitialWeight.setHint(getResources().getString(R.string.info_enter_value_in) + " " + Converters.WeightUnit.KG.toString());
        txtGoalWeight.setHint(getResources().getString(R.string.info_enter_value_in) + " " + Converters.WeightUnit.KG.toString());

        Calendar birthdayCal = Calendar.getInstance();
        birthdayCal.setTime(birthday);
        birthdayCal.add(Calendar.YEAR, -20);
        birthday = birthdayCal.getTime();

        Calendar goalCal = Calendar.getInstance();
        goalCal.setTime(goal_date);
        goalCal.add(Calendar.MONTH, 6);
        goal_date = goalCal.getTime();

        txtBirthday.setText(dateFormat.format(birthday));
        txtGoalDate.setText(dateFormat.format(goal_date));

        txtBirthday.setOnClickListener(new View.OnClickListener() {
             @Override
             public void onClick(View v) {
                 Calendar cal = Calendar.getInstance();
                 cal.setTime(birthday);
                 DatePickerDialog datePicker = new DatePickerDialog(
                     context, birthdayPickerListener, cal.get(Calendar.YEAR),
                     cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
                 datePicker.show();
             }
        });

        txtGoalDate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(goal_date);
                DatePickerDialog datePicker = new DatePickerDialog(
                    context, goalDatePickerListener, cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
                datePicker.show();
            }
        });

        radioScaleUnit.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                Converters.WeightUnit scale_unit = Converters.WeightUnit.KG;

                switch (checkedId) {
                    case R.id.btnRadioKG:
                        scale_unit = Converters.WeightUnit.KG;
                        break;
                    case R.id.btnRadioLB:
                        scale_unit = Converters.WeightUnit.LB;
                        break;
                    case R.id.btnRadioST:
                        scale_unit = Converters.WeightUnit.ST;
                        break;
                }

                txtInitialWeight.setHint(getResources().getString(R.string.info_enter_value_in) + " " + scale_unit.toString());
                txtGoalWeight.setHint(getResources().getString(R.string.info_enter_value_in) + " " + scale_unit.toString());
            }
        });

        radioMeasurementUnit.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                Converters.MeasureUnit measure_unit = Converters.MeasureUnit.CM;

                switch (radioMeasurementUnit.getCheckedRadioButtonId()) {
                    case R.id.btnRadioCM:
                        measure_unit = Converters.MeasureUnit.CM;
                        break;
                    case R.id.btnRadioINCH:
                        measure_unit = Converters.MeasureUnit.INCH;
                        break;
                }

                txtBodyHeight.setHint(getResources().getString(R.string.info_enter_value_in) + " " + measure_unit.toString());
            }
        });

        return root;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.userentry_menu, menu);

        // Apply a tint to all icons in the toolbar
        for (int i = 0; i < menu.size(); ++i) {
            MenuItem item = menu.getItem(i);
            final Drawable drawable = item.getIcon();
            if (drawable == null) {
                continue;
            }

            final Drawable wrapped = DrawableCompat.wrap(drawable.mutate());

            if (item.getItemId() == R.id.saveButton) {
                DrawableCompat.setTint(wrapped, Color.parseColor("#FFFFFF"));
            } else if (item.getItemId() == R.id.deleteButton) {
                DrawableCompat.setTint(wrapped, Color.parseColor("#FF4444"));
            }

            item.setIcon(wrapped);
        }

        MenuItem deleteButton = menu.findItem(R.id.deleteButton);

        switch (mode)  {
            case ADD:
                deleteButton.setVisible(false);
                break;
            case EDIT:
                editMode();
                deleteButton.setVisible(true);
                break;
        }

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.saveButton:
                if (saveUserData()) {
                    if (getActivity().findViewById(R.id.nav_host_fragment) != null){
                        Navigation.findNavController(getActivity(), R.id.nav_host_fragment).getPreviousBackStackEntry().getSavedStateHandle().set("update", true);
                        Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigateUp();
                    } else {
                         getActivity().finish();
                    }
                }
                return true;

            case R.id.deleteButton:
                deleteUser();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void editMode()
    {
        int id = UserSettingsFragmentArgs.fromBundle(getArguments()).getUserId();

        OpenScale openScale = OpenScale.getInstance();

        ScaleUser scaleUser = openScale.getScaleUser(id);

        birthday = scaleUser.getBirthday();
        goal_date = scaleUser.getGoalDate();

        txtUserName.setText(scaleUser.getUserName());
        txtBodyHeight.setText(Float.toString(Math.round(Converters.fromCentimeter(scaleUser.getBodyHeight(), scaleUser.getMeasureUnit()) * 100.0f) / 100.0f));
        txtBodyHeight.setHint(getResources().getString(R.string.info_enter_value_in) + " " + scaleUser.getMeasureUnit().toString());
        txtBirthday.setText(dateFormat.format(birthday));
        txtGoalDate.setText(dateFormat.format(goal_date));
        txtInitialWeight.setText(Float.toString(Math.round(Converters.fromKilogram(scaleUser.getInitialWeight(), scaleUser.getScaleUnit())*100.0f)/100.0f));
        txtGoalWeight.setText(Float.toString(Math.round(Converters.fromKilogram(scaleUser.getGoalWeight(), scaleUser.getScaleUnit())*100.0f)/100.0f));
        txtInitialWeight.setHint(getResources().getString(R.string.info_enter_value_in) + " " + scaleUser.getScaleUnit().toString());
        txtGoalWeight.setHint(getResources().getString(R.string.info_enter_value_in) + " " + scaleUser.getScaleUnit().toString());

        switch (scaleUser.getMeasureUnit()) {
            case CM:
                radioMeasurementUnit.check(R.id.btnRadioCM);
                break;
            case INCH:
                radioMeasurementUnit.check(R.id.btnRadioINCH);
                break;
        }

        switch (scaleUser.getScaleUnit())
        {
            case KG:
                radioScaleUnit.check(R.id.btnRadioKG);
                break;
            case LB:
                radioScaleUnit.check(R.id.btnRadioLB);
                break;
            case ST:
                radioScaleUnit.check(R.id.btnRadioST);
                break;
        }

        switch (scaleUser.getGender())
        {
            case MALE:
                radioGender.check(R.id.btnRadioMale);
                break;
            case FEMALE:
                radioGender.check(R.id.btnRadioWoman);
                break;
        }

        assistedWeighing.setChecked(scaleUser.isAssistedWeighing());

        spinnerActivityLevel.setSelection(scaleUser.getActivityLevel().toInt());
        spinnerLeftAmputationLevel.setSelection(scaleUser.getLeftAmputationLevel().toInt());
        spinnerRightAmputationLevel.setSelection(scaleUser.getRightAmputationLevel().toInt());
    }

    private boolean validateInput()
    {
        boolean validate = true;

        if (txtUserName.getText().toString().length() == 0) {
            txtUserName.setError(getResources().getString(R.string.error_user_name_required));
            validate = false;
        }

        if (txtBodyHeight.getText().toString().length() == 0) {
            txtBodyHeight.setError(getResources().getString(R.string.error_height_required));
            validate = false;
        }

        if (txtInitialWeight.getText().toString().length() == 0) {
            txtInitialWeight.setError(getResources().getString(R.string.error_initial_weight_required));
            validate = false;
        }

        if (txtGoalWeight.getText().toString().length() == 0) {
            txtGoalWeight.setError(getResources().getString(R.string.error_goal_weight_required));
            validate = false;
        }

        return validate;
    }

   private final DatePickerDialog.OnDateSetListener birthdayPickerListener = new DatePickerDialog.OnDateSetListener() {
        @Override
        public void onDateSet(DatePicker view, int selectedYear, int selectedMonth, int selectedDay) {
            Calendar cal = Calendar.getInstance();
            cal.set(selectedYear, selectedMonth, selectedDay, 0, 0, 0);
            cal.set(Calendar.MILLISECOND, 0);
            birthday = cal.getTime();
            txtBirthday.setText(dateFormat.format(birthday));
           }
        };

    private final DatePickerDialog.OnDateSetListener goalDatePickerListener = new DatePickerDialog.OnDateSetListener() {
        @Override
        public void onDateSet(DatePicker view, int selectedYear, int selectedMonth, int selectedDay) {
            Calendar cal = Calendar.getInstance();
            cal.set(selectedYear, selectedMonth, selectedDay, 0, 0, 0);
            cal.set(Calendar.MILLISECOND, 0);
            goal_date = cal.getTime();
            txtGoalDate.setText(dateFormat.format(goal_date));
        }
    };

    private void deleteUser() {
        AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(context);

        deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete_user));

        deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                int userId = UserSettingsFragmentArgs.fromBundle(getArguments()).getUserId();

                OpenScale openScale = OpenScale.getInstance();
                boolean isSelected = openScale.getSelectedScaleUserId() == userId;

                openScale.clearScaleMeasurements(userId);
                openScale.deleteScaleUser(userId);

                if (isSelected) {
                    List<ScaleUser> scaleUser = openScale.getScaleUserList();

                    int lastUserId = -1;
                    if (!scaleUser.isEmpty()) {
                        lastUserId = scaleUser.get(0).getId();
                    }

                    openScale.selectScaleUser(lastUserId);
                }

                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).getPreviousBackStackEntry().getSavedStateHandle().set("update", true);
                Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigateUp();
            }
        });

        deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
            }
        });

        deleteAllDialog.show();
    }

    private boolean saveUserData() {
        try {
            if (validateInput()) {
                OpenScale openScale = OpenScale.getInstance();

                String name = txtUserName.getText().toString();
                float body_height = Float.valueOf(txtBodyHeight.getText().toString());
                float initial_weight = Float.valueOf(txtInitialWeight.getText().toString());
                float goal_weight = Float.valueOf(txtGoalWeight.getText().toString());

                Converters.MeasureUnit measure_unit = Converters.MeasureUnit.CM;

                switch (radioMeasurementUnit.getCheckedRadioButtonId()) {
                    case R.id.btnRadioCM:
                        measure_unit = Converters.MeasureUnit.CM;
                        break;
                    case R.id.btnRadioINCH:
                        measure_unit = Converters.MeasureUnit.INCH;
                        break;
                }

                Converters.WeightUnit scale_unit = Converters.WeightUnit.KG;

                switch (radioScaleUnit.getCheckedRadioButtonId()) {
                    case R.id.btnRadioKG:
                        scale_unit = Converters.WeightUnit.KG;
                        break;
                    case R.id.btnRadioLB:
                        scale_unit = Converters.WeightUnit.LB;
                        break;
                    case R.id.btnRadioST:
                        scale_unit = Converters.WeightUnit.ST;
                        break;
                }

                Converters.Gender gender = Converters.Gender.MALE;

                switch (radioGender.getCheckedRadioButtonId()) {
                    case R.id.btnRadioMale:
                        gender = Converters.Gender.MALE;
                        break;
                    case R.id.btnRadioWoman:
                        gender = Converters.Gender.FEMALE;
                        break;
                }

                final ScaleUser scaleUser = new ScaleUser();

                scaleUser.setUserName(name);
                scaleUser.setBirthday(birthday);
                scaleUser.setBodyHeight(Converters.toCentimeter(body_height, measure_unit));
                scaleUser.setScaleUnit(scale_unit);
                scaleUser.setMeasureUnit(measure_unit);
                scaleUser.setActivityLevel(Converters.fromActivityLevelInt(spinnerActivityLevel.getSelectedItemPosition()));
                scaleUser.setLeftAmputationLevel(Converters.fromAmputationLevelInt(spinnerLeftAmputationLevel.getSelectedItemPosition()));
                scaleUser.setRightAmputationLevel(Converters.fromAmputationLevelInt(spinnerRightAmputationLevel.getSelectedItemPosition()));
                scaleUser.setGender(gender);
                scaleUser.setAssistedWeighing(assistedWeighing.isChecked());
                scaleUser.setInitialWeight(Converters.toKilogram(initial_weight, scale_unit));
                scaleUser.setGoalWeight(Converters.toKilogram(goal_weight, scale_unit));
                scaleUser.setGoalDate(goal_date);

                switch (mode) {
                    case ADD:
                        int id = openScale.addScaleUser(scaleUser);
                        scaleUser.setId(id);
                        break;
                    case EDIT:
                        scaleUser.setId(UserSettingsFragmentArgs.fromBundle(getArguments()).getUserId());
                        openScale.updateScaleUser(scaleUser);
                        break;
                }

                openScale.selectScaleUser(scaleUser.getId());

                return true;
            }
        } catch (NumberFormatException ex) {
            Toast.makeText(context, getResources().getString(R.string.error_value_range) + "(" + ex.getMessage() + ")", Toast.LENGTH_SHORT).show();
        }

        return false;
    }
}
