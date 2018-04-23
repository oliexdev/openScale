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
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ListAdapter;
import android.widget.Switch;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.OpenScale;
import com.health.openscale.gui.views.MeasurementView;
import com.health.openscale.gui.views.WeightMeasurementView;

import java.util.ArrayList;
import java.util.List;

public class MeasurementPreferences extends PreferenceFragment {
    private static final String PREFERENCE_KEY_DELETE_ALL = "deleteAll";
    private static final String PREFERENCE_KEY_RESET_ORDER = "resetOrder";
    private static final String PREFERENCE_KEY_MEASUREMENTS = "measurements";

    private PreferenceCategory measurementCategory;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.measurement_preferences);

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
            preference.setKey(measurement.getSettings().getEnabledKey());
            preference.setDefaultValue(measurement.getSettings().isEnabledIgnoringDependencies());
            preference.setPersistent(true);
            preference.setEnabled(measurement.getSettings().areDependenciesEnabled());

            Drawable icon = measurement.getIcon();
            icon.setColorFilter(measurement.getForegroundColor(), PorterDuff.Mode.SRC_IN);
            preference.setIcon(icon);

            preference.setTitle(measurement.getName());
            preference.setSummary(measurement.getPreferenceSummary());

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

                    openScale.clearScaleData(selectedUserId);

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
        Switch measurementSwitch;

        MeasurementOrderPreference(Context context, PreferenceGroup parent, MeasurementView measurementView) {
            super(context);
            parentGroup = parent;
            measurement = measurementView;

            gestureDetector = new GestureDetector(getContext(), this);
            gestureDetector.setIsLongpressEnabled(true);

            setWidgetLayoutResource(R.layout.measurement_preferences_widget_layout);
        }

        public PreferenceGroup getParent() {
            return parentGroup;
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            boundView = view;

            measurementSwitch = view.findViewById(R.id.measurement_switch);
            if (measurement instanceof WeightMeasurementView) {
                measurementSwitch.setVisibility(View.INVISIBLE);
            }
            else {
                measurementSwitch.setChecked(measurement.getSettings().isEnabledIgnoringDependencies());
                measurementSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        persistBoolean(isChecked);
                        for (int i = 0; i < getParent().getPreferenceCount(); ++i) {
                            MeasurementOrderPreference preference =
                                    (MeasurementOrderPreference) getParent().getPreference(i);
                            preference.setEnabled(preference.measurement.getSettings().areDependenciesEnabled());
                        }
                    }
                });
            }

            if (!measurement.hasExtraPreferences()) {
                view.findViewById(R.id.measurement_switch_separator).setVisibility(View.GONE);
            }

            TypedValue outValue = new TypedValue();
            getActivity().getTheme().resolveAttribute(R.attr.selectableItemBackground, outValue, true);
            boundView.setBackgroundResource(outValue.resourceId);

            view.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return gestureDetector.onTouchEvent(event);
                }
            });
            view.setOnDragListener(new onDragListener());
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
                if (measurementSwitch.getVisibility() == View.VISIBLE) {
                    measurementSwitch.toggle();
                }
                return true;
            }

            // Must be enabled to show extra preferences screen
            if (!measurement.getSettings().isEnabled()) {
                return true;
            }

            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getActivity());

            // Register as an observer so that the loop to getItem() below will find the new
            // preference screen added at the end. The add is done on another thread so we must
            // wait for it to complete.
            final ListAdapter adapter = getPreferenceScreen().getRootAdapter();
            adapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    adapter.unregisterDataSetObserver(this);

                    // Simulate a click to have the preference screen open
                    for (int i = adapter.getCount() - 1; i >= 0; --i) {
                        if (adapter.getItem(i) == screen) {
                            getPreferenceScreen().onItemClick(null, null, i, 0);
                            break;
                        }
                    }

                    // Remove the preference when the dialog is dismissed
                    Dialog dialog = screen.getDialog();
                    dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            screen.onDismiss(dialog);
                            getPreferenceScreen().removePreference(screen);
                            setSummary(measurement.getPreferenceSummary());
                        }
                    });
                }
            });

            getPreferenceScreen().addPreference(screen);
            measurement.prepareExtraPreferencesScreen(screen);

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
            Drawable background = null;
            // background may be set to null, thus the extra boolean
            boolean hasBackground = false;

            private MeasurementOrderPreference castLocalState(DragEvent event) {
                return (MeasurementOrderPreference) event.getLocalState();
            }

            private boolean isDraggedView(View view, DragEvent event) {
                return castLocalState(event).boundView == view;
            }

            private void setTemporaryBackgroundColor(View view, int color) {
                if (!hasBackground) {
                    background = view.getBackground();
                    hasBackground = true;
                    view.setBackgroundColor(color);
                }
            }

            private void restoreBackground(View view) {
                if (hasBackground) {
                    view.setBackground(background);
                    background = null;
                    hasBackground = false;
                }
            }

            @Override
            public boolean onDrag(View view, DragEvent event) {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        break;
                    case DragEvent.ACTION_DRAG_ENTERED:
                        if (!isDraggedView(view, event)) {
                            setTemporaryBackgroundColor(view, Color.LTGRAY);
                        }
                        break;
                    case DragEvent.ACTION_DRAG_EXITED:
                        if (!isDraggedView(view, event)) {
                            restoreBackground(view);
                        }
                        break;
                    case DragEvent.ACTION_DROP:
                        MeasurementOrderPreference draggedPref = castLocalState(event);
                        PreferenceGroup group = draggedPref.getParent();

                        ArrayList<MeasurementOrderPreference> preferences = new ArrayList<>();
                        for (int i = 0; i < group.getPreferenceCount(); ++i) {
                            MeasurementOrderPreference pref = (MeasurementOrderPreference) group.getPreference(i);
                            // Add all preferences except the dragged one
                            if (pref != draggedPref) {
                                preferences.add(pref);
                            }
                            // When we find the view that is the drop target use add(index, ...).
                            // This will add the dragged preference before the drop if dragged upwards,
                            // and after if dragged downwards.
                            if (pref.boundView == view) {
                                preferences.add(i, draggedPref);
                            }
                        }

                        ArrayList<MeasurementView> measurementViews = new ArrayList<>();
                        // Re-add all preferences in the new order
                        group.removeAll();
                        for (MeasurementOrderPreference p : preferences) {
                            p.setOrder(DEFAULT_ORDER);
                            group.addPreference(p);
                            measurementViews.add(p.measurement);
                        }
                        MeasurementView.saveMeasurementViewsOrder(getContext(), measurementViews);
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        restoreBackground(view);
                        break;
                }
                return true;
            }
        }
    }
}
