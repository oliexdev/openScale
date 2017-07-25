package com.health.openscale.gui.views.chart;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.support.design.widget.FloatingActionButton;
import android.view.View;

import java.util.Stack;

import lecho.lib.hellocharts.formatter.SimpleLineChartValueFormatter;
import lecho.lib.hellocharts.model.Line;
import lecho.lib.hellocharts.model.PointValue;
import lecho.lib.hellocharts.util.ChartUtils;

import static android.R.attr.lines;
import static com.health.openscale.R.id.diagramFat;
import static com.health.openscale.R.id.diagramWeight;

public class Diagram {
    private final FloatingActionButton button;
    private final SharedPreferences prefs;
    private final boolean enabledDefault;
    private final int color;
    private final String key;

    public Diagram(
            View view,
            View.OnClickListener onClickListener,
            SharedPreferences prefs,
            String key,
            boolean enabledDefault,
            int resourceId,
            int color
    ) {
        this.button = (FloatingActionButton) view.findViewById(resourceId);
        this.button.setOnClickListener(onClickListener);
        this.prefs = prefs;
        this.key = key;
        this.enabledDefault = enabledDefault;
        this.color = color;

        if (!prefs.getBoolean(key, enabledDefault)) {
            button.setVisibility(View.GONE);
        }
    }

    public Line createLine(Stack<PointValue> values) {
            return new Line(values).
                    setColor(this.color).
                    setHasLabels(prefs.getBoolean("labelsEnable", true)).
                    setHasPoints(prefs.getBoolean("pointsEnable", true)).
                    setFormatter(new SimpleLineChartValueFormatter(1));
    }

    public void prepareBackgroundTint() {
        if (showLine()) {
            button.setBackgroundTintList(ColorStateList.valueOf(this.color));
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#d3d3d3")));
        }
    }

    public boolean showLine() {
        return prefs.getBoolean(key, enabledDefault) && prefs.getBoolean(String.valueOf(button.getId()), true);
    }
}
