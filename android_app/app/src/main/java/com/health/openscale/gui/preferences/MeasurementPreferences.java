/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*  Copyright (C) 2018 Erik Johansson <erik@ejohansson.se>
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
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.gui.measurement.MeasurementView;
import com.health.openscale.gui.measurement.WeightMeasurementView;

import java.util.ArrayList;
import java.util.List;

public class MeasurementPreferences extends PreferenceFragmentCompat {
    private static final String PREFERENCE_KEY_DELETE_ALL = "deleteAll";
    private static final String PREFERENCE_KEY_RESET_ORDER = "resetOrder";
    private static final String PREFERENCE_KEY_MEASUREMENTS = "measurements";

    private PreferenceCategory measurementCategory;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.measurement_preferences, rootKey);

        setHasOptionsMenu(true);

        Preference deleteAll = findPreference(PREFERENCE_KEY_DELETE_ALL);
        deleteAll.setOnPreferenceClickListener(new onClickListenerDeleteAll());

        measurementCategory = (PreferenceCategory) findPreference(PREFERENCE_KEY_MEASUREMENTS);

        Preference resetOrder = findPreference(PREFERENCE_KEY_RESET_ORDER);
        resetOrder.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                PreferenceManager.getDefaultSharedPreferences(getActivity()).edit()
                        .remove(MeasurementView.PREF_MEASUREMENT_ORDER).apply();
                updateMeasurementPreferences();
                return true;
            }
        });

        updateMeasurementPreferences();
    }

    private void updateMeasurementPreferences() {
        measurementCategory.removeAll();

        List<MeasurementView> measurementViews = MeasurementView.getMeasurementList(
                getActivity(), MeasurementView.DateTimeOrder.NONE);

        for (MeasurementView measurement : measurementViews) {
            Preference preference = new MeasurementOrderPreference(
                    getActivity(), measurementCategory, measurement);

            measurementCategory.addPreference(preference);
        }
    }

    private class onClickListenerDeleteAll implements Preference.OnPreferenceClickListener {
        @Override
        public boolean onPreferenceClick(Preference preference) {

            AlertDialog.Builder deleteAllDialog = new AlertDialog.Builder(getActivity());

            deleteAllDialog.setMessage(getResources().getString(R.string.question_really_delete_all));

            deleteAllDialog.setPositiveButton(getResources().getString(R.string.label_yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    OpenScale openScale = OpenScale.getInstance();
                    int selectedUserId = openScale.getSelectedScaleUserId();

                    openScale.clearScaleMeasurements(selectedUserId);

                    Toast.makeText(getActivity().getApplicationContext(), getResources().getString(R.string.info_data_all_deleted), Toast.LENGTH_SHORT).show();
                }
            });

            deleteAllDialog.setNegativeButton(getResources().getString(R.string.label_no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            deleteAllDialog.show();

            return false;
        }
    }

    private class MeasurementOrderPreference extends Preference
            implements GestureDetector.OnGestureListener {
        PreferenceGroup parentGroup;
        MeasurementView measurement;

        GestureDetector gestureDetector;

        View boundView;
        ImageView iconView;
        TextView textView;
        TextView summaryView;
        Switch switchView;
        ImageView reorderView;
        ImageView settingsView;

        MeasurementOrderPreference(Context context, PreferenceGroup parent, MeasurementView measurementView) {
            super(context);
            parentGroup = parent;
            measurement = measurementView;

            gestureDetector = new GestureDetector(getContext(), this);
            gestureDetector.setIsLongpressEnabled(true);

            setLayoutResource(R.layout.preference_measurement_order);
        }

        @Override
        public PreferenceGroup getParent() {
            return parentGroup;
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            boundView = holder.itemView;

            textView = (TextView)holder.findViewById(R.id.textView);
            summaryView = (TextView)holder.findViewById(R.id.summaryView);
            iconView = (ImageView)holder.findViewById(R.id.iconView);
            switchView = (Switch)holder.findViewById(R.id.switchView);
            reorderView = (ImageView)holder.findViewById(R.id.reorderView);
            settingsView = (ImageView)holder.findViewById(R.id.settingsView);

            textView.setText(measurement.getName());
            summaryView.setText(measurement.getPreferenceSummary());
            Drawable icon = measurement.getIcon();
            icon.setColorFilter(measurement.getForegroundColor(), PorterDuff.Mode.SRC_IN);
            iconView.setImageDrawable(icon);

            switchView.setChecked(measurement.getSettings().isEnabledIgnoringDependencies());

            setKey(measurement.getSettings().getEnabledKey());
            setDefaultValue(measurement.getSettings().isEnabledIgnoringDependencies());
            setPersistent(true);

            setEnableView(measurement.getSettings().areDependenciesEnabled() && switchView.isChecked());

            if (measurement instanceof WeightMeasurementView) {
                switchView.setVisibility(View.INVISIBLE);
            } else {
                switchView.setVisibility(View.VISIBLE);
            }

            switchView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (buttonView.isPressed()) {
                        persistBoolean(isChecked);
                        setEnableView(isChecked);

                        for (int i = 0; i < getParent().getPreferenceCount(); ++i) {
                            MeasurementOrderPreference preference = (MeasurementOrderPreference) getParent().getPreference(i);
                            preference.setEnabled(preference.measurement.getSettings().areDependenciesEnabled());
                        }
                    }
                }
            });

            boundView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return gestureDetector.onTouchEvent(event);
                }
            });

            boundView.setOnDragListener(new onDragListener());
        }

        private void setEnableView(boolean status) {
            if(status) {
                textView.setEnabled(true);
                summaryView.setEnabled(true);
                reorderView.setEnabled(true);
                settingsView.setEnabled(true);
            } else {
                textView.setEnabled(false);
                summaryView.setEnabled(false);
                reorderView.setEnabled(false);
                settingsView.setEnabled(false);
            }
        }

        @Override
        public boolean onDown(MotionEvent e) {
            return isEnabled();
        }

        @Override
        public void onShowPress(MotionEvent e) {
            boundView.setPressed(true);
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            boundView.setPressed(false);

            if (!measurement.hasExtraPreferences()) {
                if (switchView.getVisibility() == View.VISIBLE) {
                    switchView.toggle();
                }
                return true;
            }

            // Must be enabled to show extra preferences screen
            if (!measurement.getSettings().isEnabled()) {
                return true;
            }

            // HACK to pass an object using navigation controller
            MeasurementDetailPreferences.setMeasurementView(measurement);

            NavDirections action = MeasurementPreferencesDirections.actionNavMeasurementPreferencesToNavMeasurementDetailPreferences();
            Navigation.findNavController(getActivity(), R.id.nav_host_fragment).navigate(action);

            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            return false;
        }

        @Override
        public void onLongPress(MotionEvent event) {
            int x = Math.round(event.getX());
            int y = Math.round(event.getY());

            boundView.startDrag(null, new dragShadowBuilder(boundView, x, y), this, 0);
        }

        private class dragShadowBuilder extends View.DragShadowBuilder {
            private int x;
            private int y;
            public dragShadowBuilder(View view, int x, int y) {
                super(view);
                this.x = x;
                this.y = y;
            }

            @Override
            public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
                super.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint);
                outShadowTouchPoint.set(x, y);
            }
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            return false;
        }

        private class onDragListener implements View.OnDragListener {
            @Override
            public boolean onDrag(View view, DragEvent event) {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DROP:
                        MeasurementOrderPreference draggedPref = (MeasurementOrderPreference) event.getLocalState();

                        ArrayList<MeasurementView> measurementViews = new ArrayList<>();
                        for (int i = 0; i < measurementCategory.getPreferenceCount(); i++) {
                            MeasurementOrderPreference pref = (MeasurementOrderPreference) measurementCategory.getPreference(i);

                            if (pref != draggedPref) {
                                measurementViews.add(pref.measurement);
                            }

                            if (pref.boundView == view) {
                                measurementViews.add(draggedPref.measurement);
                            }
                        }

                        measurementCategory.removeAll();

                        for (MeasurementView measurement : measurementViews) {
                            Preference preference = new MeasurementOrderPreference(
                                    getActivity(), measurementCategory, measurement);

                            measurementCategory.addPreference(preference);
                        }

                        MeasurementView.saveMeasurementViewsOrder(getContext(), measurementViews);
                        break;
                }
                return true;
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }
}
