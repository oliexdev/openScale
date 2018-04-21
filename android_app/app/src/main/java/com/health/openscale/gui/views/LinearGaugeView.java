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
package com.health.openscale.gui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.health.openscale.R;

import java.util.Locale;


public class LinearGaugeView extends View {

    private static final int COLOR_BLUE = Color.parseColor("#33B5E5");
    private static final int COLOR_GREEN = Color.parseColor("#99CC00");
    private static final int COLOR_RED = Color.parseColor("#FF4444");

    private static final float barHeight = 10;
    private static final float textOffset = 10.0f;
    private final RectF limitRect = new RectF(0, 0, barHeight / 2, barHeight * 2);

    // Pre-created rect to avoid creating object in onDraw
    private final Rect bounds = new Rect();

    private Paint rectPaintLow;
    private Paint rectPaintNormal;
    private Paint rectPaintHigh;
    private Paint textPaint;
    private Paint indicatorPaint;
    private Paint infoTextPaint;

    private float value;
    private float firstLimit = -1.0f;
    private float secondLimit = -1.0f;

    public LinearGaugeView(Context context) {
        super(context);
        init();
    }

    public LinearGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LinearGaugeView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        rectPaintLow = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectPaintLow.setColor(COLOR_BLUE);

        rectPaintNormal = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectPaintNormal.setColor(COLOR_GREEN);

        rectPaintHigh = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectPaintHigh.setColor(COLOR_RED);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.GRAY);
        textPaint.setTextSize(30);

        indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        indicatorPaint.setColor(Color.GRAY);
        indicatorPaint.setTextSize(30);

        infoTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        infoTextPaint.setColor(Color.GRAY);
        infoTextPaint.setTextSize(40);
        infoTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float valueToPosition(float value, float minValue, float maxValue) {
        final float percent = (value - minValue) / (maxValue - minValue) * 100.0f;
        return getWidth() / 100.0f * percent;
    }

    private void drawCenteredText(Canvas canvas, String text, float centerX, float y, Paint paint) {
        final float textWidth = paint.measureText(text);
        float x = Math.max(0.0f, centerX - textWidth / 2.0f);
        x = Math.min(x, getWidth() - textWidth);
        canvas.drawText(text, x, y, paint);
    }

    private String toText(float value) {
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (firstLimit < 0 && secondLimit < 0) {
            float textX = getWidth() / 2.0f;
            float textY = getHeight() / 2.0f;
            canvas.drawText(getResources().getString(R.string.info_no_evaluation_available), textX, textY, infoTextPaint);
            return;
        }

        final boolean hasFirstLimit = firstLimit >= 0;

        // Calculate how much bar to show to the left and right of the "normal" span
        // (or just the second limit if there is no first limit).
        float span = hasFirstLimit ? (secondLimit - firstLimit) / 2.0f : 0.3f * secondLimit;

        // Add some extra margin to avoid having the indicator too far towards an edge
        final float margin = 0.05f * span;

        // Adjust the span if needed to make the value fit inside of it
        if (hasFirstLimit && value - margin < firstLimit - span) {
            span = firstLimit - value + margin;
        } else if (!hasFirstLimit && value - margin < secondLimit - span) {
            span = secondLimit - value + margin;
        } else if (value + margin > secondLimit + span) {
            span = value - secondLimit + margin;
        }

        // Round span to some nice value
        if (span <= 1.0f) {
            span = (float)Math.ceil(span * 10.0) / 10.0f;
        } else if (span <= 10.0f) {
            span = (float)Math.ceil(span);
        } else {
            span = 5.0f * (float)Math.ceil(span / 5.0);
        }

        final float minValue = Math.max(0.0f, (hasFirstLimit ? firstLimit : secondLimit) - span);
        final float maxValue = secondLimit + span;

        final float firstPos = valueToPosition(firstLimit, minValue, maxValue);
        final float secondPos = valueToPosition(secondLimit, minValue, maxValue);
        final float valuePos = valueToPosition(value, minValue, maxValue);

        // Bar
        final float barTop = getHeight() / 2.0f - barHeight / 2.0f;
        final float barBottom = barTop + barHeight;

        if (firstLimit > 0) {
            canvas.drawRect(0, barTop, firstPos, barBottom, rectPaintLow);
            canvas.drawRect(firstPos, barTop, secondPos, barBottom, rectPaintNormal);
        } else {
            canvas.drawRect(0, barTop, secondPos, barBottom, rectPaintNormal);
        }
        canvas.drawRect(secondPos, barTop, getWidth(), barBottom, rectPaintHigh);

        // Limit Lines
        limitRect.offsetTo(0, getHeight() / 2.0f - limitRect.height() / 2.0f);
        canvas.drawRect(limitRect, textPaint);
        if (firstLimit > 0) {
            limitRect.offsetTo(firstPos - limitRect.width() / 2.0f, limitRect.top);
            canvas.drawRect(limitRect, textPaint);
        }
        limitRect.offsetTo(secondPos - limitRect.width() / 2.0f, limitRect.top);
        canvas.drawRect(limitRect, textPaint);
        limitRect.offsetTo(getWidth() - limitRect.width(), limitRect.top);
        canvas.drawRect(limitRect, textPaint);

        // Text
        final float textY = barTop - textOffset;
        canvas.drawText(toText(minValue), 0.0f, textY, textPaint);
        if (firstLimit > 0) {
            drawCenteredText(canvas, toText(firstLimit), firstPos, textY, textPaint);
        }
        drawCenteredText(canvas, toText(secondLimit), secondPos, textY, textPaint);
        drawCenteredText(canvas, toText(maxValue), getWidth(), textY, textPaint);

        // Indicator
        final float indicatorBottom = limitRect.bottom + 15.0f;
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.moveTo(valuePos,  barBottom);
        path.lineTo(valuePos + 10.0f, indicatorBottom);
        path.lineTo(valuePos - 10.0f, indicatorBottom);
        path.close();

        canvas.drawPath(path, indicatorPaint);

        // Value text
        final String valueStr = String.format(Locale.getDefault(), "%.2f", value);
        indicatorPaint.getTextBounds(valueStr, 0, valueStr.length(), bounds);
        drawCenteredText(canvas, valueStr, valuePos,
            indicatorBottom + bounds.height() + 2, indicatorPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        int desiredWidth = 100;
        int desiredHeight = 120;

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int width;
        int height;

        //Measure Width
        if (widthMode == MeasureSpec.EXACTLY) {
            //Must be this size
            width = widthSize;
        } else if (widthMode == MeasureSpec.AT_MOST) {
            //Can't be bigger than...
            width = Math.min(desiredWidth, widthSize);
        } else {
            //Be whatever you want
            width = desiredWidth;
        }

        //Measure Height
        if (heightMode == MeasureSpec.EXACTLY) {
            //Must be this size
            height = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            //Can't be bigger than...
            height = Math.min(desiredHeight, heightSize);
        } else {
            //Be whatever you want
            height = desiredHeight;
        }

        //MUST CALL THIS
        setMeasuredDimension(width, height);
    }

    public void setLimits(float first, float second) {
        firstLimit = first;
        secondLimit = second;
        invalidate();
        requestLayout();
    }

    public void setValue(float value) {
        this.value = value;
        invalidate();
        requestLayout();
    }
}