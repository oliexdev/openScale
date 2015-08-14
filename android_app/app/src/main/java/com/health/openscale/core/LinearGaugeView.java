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
package com.health.openscale.core;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import com.health.openscale.R;


public class LinearGaugeView extends View {

    public static final int COLOR_BLUE = Color.parseColor("#33B5E5");
    public static final int COLOR_VIOLET = Color.parseColor("#AA66CC");
    public static final int COLOR_GREEN = Color.parseColor("#99CC00");
    public static final int COLOR_ORANGE = Color.parseColor("#FFBB33");
    public static final int COLOR_RED = Color.parseColor("#FF4444");

    private final float barHeight = 10;
    private final float limitLineHeight = 20;
    private final float lineThickness = 5.0f;
    private final float textOffset = 10.0f;

    private float firstPercent;
    private float firstPos;
    private float secondPercent;
    private float secondPos;
    private float valuePercent;
    private float valuePos;

    private Paint rectPaintLow;
    private Paint rectPaintNormal;
    private Paint rectPaintHigh;
    private Paint textPaint;
    private Paint indicatorPaint;
    private Paint infoTextPaint;

    private float value;
    private int minValue;
    private int maxValue;
    private float firstLimit;
    private float secondLimit;

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
        textPaint.setTextSize(20);

        indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        indicatorPaint.setColor(Color.BLACK);
        indicatorPaint.setTextSize(20);

        infoTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        infoTextPaint.setColor(Color.GRAY);
        infoTextPaint.setTextSize(30);
        infoTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (firstLimit < 0 && secondLimit < 0) {
            float textY=getHeight() / 2.0f;
            float textX=getWidth() / 2.0f;
            canvas.drawText(getResources().getString(R.string.info_no_evaluation_available),textX,textY,infoTextPaint);
            return;
        }

        firstPercent = (firstLimit / (float)maxValue) * 100.0f;
        firstPos = (getWidth() / 100.0f) * firstPercent;

        secondPercent = (secondLimit / (float)maxValue) * 100.0f;
        secondPos = (getWidth() / 100.0f) * secondPercent;

        valuePercent = (value / (float)maxValue) * 100.0f;
        valuePos = (getWidth() / 100.0f) * valuePercent;

        // Bar
        if (firstLimit > 0) {
            canvas.drawRect(0, (getHeight() / 2.0f) - (barHeight / 2.0f), firstPos, (getHeight() / 2.0f) + (barHeight / 2.0f), rectPaintLow);
        } else {
            canvas.drawRect(0, (getHeight() / 2.0f) - (barHeight / 2.0f), firstPos, (getHeight() / 2.0f) + (barHeight / 2.0f), rectPaintNormal);
        }
        canvas.drawRect(firstPos, (getHeight() / 2.0f) - (barHeight / 2.0f), secondPos , (getHeight() / 2.0f) + (barHeight / 2.0f), rectPaintNormal);
        canvas.drawRect(secondPos,(getHeight() / 2.0f) - (barHeight / 2.0f), getWidth() , (getHeight() / 2.0f) + (barHeight / 2.0f), rectPaintHigh);

        // Limit Lines
        canvas.drawRect(0, (getHeight() / 2.0f) - (limitLineHeight / 2.0f), 0+lineThickness, (getHeight() / 2.0f) + (limitLineHeight / 2.0f), textPaint);
        if (firstLimit > 0) {
            canvas.drawRect(firstPos, (getHeight() / 2.0f) - (limitLineHeight / 2.0f), firstPos + lineThickness, (getHeight() / 2.0f) + (limitLineHeight / 2.0f), textPaint);
        }
        canvas.drawRect(secondPos, (getHeight() / 2.0f) - (limitLineHeight / 2.0f), secondPos+lineThickness, (getHeight() / 2.0f) + (limitLineHeight / 2.0f), textPaint);
        canvas.drawRect(getWidth()-lineThickness, (getHeight() / 2.0f) - (limitLineHeight / 2.0f), getWidth(), (getHeight() / 2.0f) + (limitLineHeight / 2.0f), textPaint);

        // Text
        canvas.drawText(Integer.toString(minValue), 0.0f, (getHeight() / 2.0f) - (barHeight / 2.0f) - textOffset, textPaint);
        if (firstLimit > 0) {
            canvas.drawText(Float.toString(firstLimit), firstPos - 5.0f, (getHeight() / 2.0f) - (barHeight / 2.0f) - textOffset, textPaint);
        }
        canvas.drawText(Float.toString(secondLimit), secondPos-5.0f, (getHeight() / 2.0f) - (barHeight / 2.0f) - textOffset, textPaint);
        canvas.drawText(Float.toString(maxValue), getWidth()-40.0f, (getHeight() / 2.0f) - (barHeight / 2.0f)- textOffset, textPaint);

        // Indicator
        Path path = new Path();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.moveTo(valuePos,  (getHeight() / 2.0f) - 10.0f);
        path.lineTo(valuePos + 10.0f, (getHeight() / 2.0f) + 20.0f);
        path.lineTo(valuePos - 10.0f, (getHeight() / 2.0f) + 20.0f);
        path.lineTo(valuePos, (getHeight() / 2.0f) - 10.0f);
        path.close();

        canvas.drawPath(path, indicatorPaint);
        canvas.drawText(String.format("%.2f", value), valuePos-15.0f, (getHeight() / 2.0f) - (barHeight / 2.0f) - textOffset, indicatorPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        int desiredWidth = 100;
        int desiredHeight = 100;

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

    public void setMinMaxValue(int min, int max) {
        minValue = min;
        maxValue = max;
        invalidate();
        requestLayout();
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