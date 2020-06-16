/* Copyright (C) 2019  olie.xdev <olie.xdev@googlemail.com>
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
package com.health.openscale.gui.measurement;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.health.openscale.gui.utils.ColorUtil;

import java.util.List;

public class ChartActionBarView extends HorizontalScrollView {

    private LinearLayout actionBarView;
    private List<MeasurementView> measurementViews;
    private View.OnClickListener onActionClickListener;
    private boolean isInGraphKey;

    public ChartActionBarView(Context context) {
        super(context);
        init();
    }

    public ChartActionBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ChartActionBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        actionBarView = new LinearLayout(getContext());
        actionBarView.setOrientation(LinearLayout.HORIZONTAL);
        actionBarView.setBackgroundColor(ColorUtil.COLOR_BLACK);

        measurementViews = MeasurementView.getMeasurementList(
                getContext(), MeasurementView.DateTimeOrder.NONE);

        isInGraphKey = true;
        onActionClickListener = null;

        addView(actionBarView);
        refreshFloatingActionsButtons();
    }

    public void setOnActionClickListener(View.OnClickListener listener) {
        onActionClickListener = listener;
    }

    public void setIsInGraphKey(boolean status) {
        isInGraphKey = status;
        refreshFloatingActionsButtons();
    }

    private void refreshFloatingActionsButtons() {
        actionBarView.removeAllViews();

        for (MeasurementView view : measurementViews) {
            if (view instanceof FloatMeasurementView) {
                final FloatMeasurementView measurementView = (FloatMeasurementView) view;

                if (measurementView.isVisible()) {
                    addActionButton(measurementView);
                }
            }
        }
    }

    private void addActionButton(FloatMeasurementView measurementView) {
        FloatingActionButton actionButton = new FloatingActionButton(getContext());

        actionButton.setTag(measurementView.getKey());
        actionButton.setColorFilter(Color.parseColor("#000000"));
        actionButton.setImageDrawable(measurementView.getIcon());
        actionButton.setClickable(true);
        actionButton.setSize(FloatingActionButton.SIZE_MINI);
        RelativeLayout.LayoutParams lay = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lay.setMargins(0,5,20,10);
        actionButton.setLayoutParams(lay);
        actionButton.setOnClickListener(new onActionClickListener());

        if (isInGraphKey) {
            int color = measurementView.getSettings().isInGraph()
                    ? measurementView.getColor() : ColorUtil.COLOR_GRAY;
            actionButton.setBackgroundTintList(ColorStateList.valueOf(color));

        } else {
            int color = measurementView.getSettings().isInOverviewGraph()
                    ? measurementView.getColor() : ColorUtil.COLOR_GRAY;
            actionButton.setBackgroundTintList(ColorStateList.valueOf(color));
        }

        actionBarView.addView(actionButton);
    }

    private class onActionClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            FloatingActionButton actionButton = (FloatingActionButton) v;

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

            String key = String.valueOf(actionButton.getTag());
            MeasurementViewSettings settings = new MeasurementViewSettings(prefs, key);
            if (isInGraphKey) {
                prefs.edit().putBoolean(settings.getInGraphKey(), !settings.isInGraph()).apply();
            } else {
                prefs.edit().putBoolean(settings.getInOverviewGraphKey(), !settings.isInOverviewGraph()).apply();
            }

            refreshFloatingActionsButtons();

            if (onActionClickListener != null) {
                onActionClickListener.onClick(v);
            }
        }
    }
}
