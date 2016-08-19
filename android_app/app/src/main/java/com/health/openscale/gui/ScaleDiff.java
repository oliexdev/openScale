package com.health.openscale.gui;

import android.text.Html;
import android.widget.TextView;

public class ScaleDiff {
    private static String SYMBOL_UP = "&#x2197;";
    private static String SYMBOL_DOWN = "&#x2198;";

    public static void setDiff(TextView txtLabel,
                        double diff,
                        String labelResource,
                        String format) {

        String symbol;

        if (diff > 0.0) {
            symbol = SYMBOL_UP;
        } else {
            symbol = SYMBOL_DOWN;
        }

        txtLabel.setText(
                Html.fromHtml(
                        labelResource +
                                " <br> <font color='grey'>" +
                                symbol +
                                "<small> " +
                                String.format(format, diff) +
                                "</small></font>"
                )
        );
    }
}

