package com.health.openscale.gui.table;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.core.view.NestedScrollingChild;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.ViewCompat;

import com.health.openscale.R;
import com.health.openscale.gui.utils.ColorUtil;

/**
 * Created by Mitul Varmora on 11/8/2016.
 * StickyHeaderTableView, see https://github.com/MitulVarmora/StickyHeaderTableView
 * MIT License
 * modified 2023 by olie.xdev <olie.xdev@googlemail.com>
 */

public class StickyHeaderTableView extends View implements NestedScrollingChild {
    private final Paint paintStrokeRect = new Paint();
    private final Paint paintHeaderCellFillRect = new Paint();
    private final Paint paintContentCellFillRect = new Paint();
    private final TextPaint paintLabelText = new TextPaint();
    private final Paint paintDrawable = new Paint();

    private final TextPaint paintHeaderText = new TextPaint();
    private final Rect textRectBounds = new Rect();

    private int maxMeasure = 0;

    /**
     * Visible rect size of view which is displayed on screen
     */
    private final Rect visibleContentRect = new Rect(0, 0, 0, 0);
    /**
     * based on scrolling this rect value will update
     */
    private final Rect scrolledRect = new Rect(0, 0, 0, 0);
    /**
     * Actual rect size of canvas drawn content (Which may be larger or smaller than mobile screen)
     */
    private final Rect actualContentRect = new Rect(0, 0, 0, 0);
    // below variables are used for fling animation (Not for scrolling)
    private final DecelerateInterpolator animateInterpolator = new DecelerateInterpolator();
    private NestedScrollingChildHelper nestedScrollingChildHelper;
    private int NESTED_SCROLL_AXIS = ViewCompat.SCROLL_AXIS_NONE;
    private OnTableCellClickListener onTableCellClickListener = null;
    private boolean isScrollingHorizontally = false;
    private boolean isScrollingVertically = false;
    /**
     * This is used to stop fling animation if user has touch intercepted
     */
    private boolean isFlinging = false;
    // Below are configurable variables via xml (also can be used via setter methods)
    private boolean isDisplayLeftHeadersVertically = false;
    private boolean is2DScrollingEnabled;
    private boolean isWrapHeightOfEachRow = false;
    private boolean isWrapWidthOfEachColumn = false;
    private int textLabelColor;
    private int textHeaderColor;
    private int dividerColor;
    private int textLabelSize;
    private int textHeaderSize;
    private int dividerThickness;
    private int headerCellFillColor;
    private int contentCellFillColor;
    private int cellPadding;
    /**
     * Used to identify clicked position for #OnTableCellClickListener
     */
    private Rect[][] rectEachCellBoundData = new Rect[][]{};
    private Object[][] data = null;
    private int maxWidthOfCell = 0;
    private int maxHeightOfCell = 0;
    private SparseIntArray maxHeightSparseIntArray = new SparseIntArray();
    private SparseIntArray maxWidthSparseIntArray = new SparseIntArray();
    /**
     * Used for scroll events
     */
    private GestureDetector gestureDetector;
    private long startTime;
    private long endTime;
    private float totalAnimDx;
    private float totalAnimDy;
    private float lastAnimDx;
    private float lastAnimDy;

    public interface OnTableCellClickListener {
        public void onTableCellClicked(int rowPosition, int columnPosition);
    }

    public StickyHeaderTableView(Context context) {
        this(context, null, 0);
    }

    public StickyHeaderTableView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StickyHeaderTableView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final int defaultTextSize = (int) dpToPixels(getContext(), 14);

        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.StickyHeaderTableView, defStyleAttr, defStyleAttr);

        if (a != null) {
            try {
                textLabelColor = a.getColor(
                        R.styleable.StickyHeaderTableView_shtv_textLabelColor, Color.BLACK);
                textHeaderColor = a.getColor(
                        R.styleable.StickyHeaderTableView_shtv_textHeaderColor, Color.BLACK);
                dividerColor = a.getColor(
                        R.styleable.StickyHeaderTableView_shtv_dividerColor, Color.BLACK);

                textLabelSize = a.getDimensionPixelSize(
                        R.styleable.StickyHeaderTableView_shtv_textLabelSize, defaultTextSize);
                textHeaderSize = a.getDimensionPixelSize(
                        R.styleable.StickyHeaderTableView_shtv_textHeaderSize, defaultTextSize);
                dividerThickness = a.getDimensionPixelSize(R.styleable.StickyHeaderTableView_shtv_dividerThickness, 0);
                cellPadding = a.getDimensionPixelSize(R.styleable.StickyHeaderTableView_shtv_cellPadding, 0);

                is2DScrollingEnabled = a.getBoolean(R.styleable.StickyHeaderTableView_shtv_is2DScrollEnabled, false);
                isDisplayLeftHeadersVertically = a.getBoolean(R.styleable.StickyHeaderTableView_shtv_isDisplayLeftHeadersVertically, false);
                isWrapHeightOfEachRow = a.getBoolean(R.styleable.StickyHeaderTableView_shtv_isWrapHeightOfEachRow, false);
                isWrapWidthOfEachColumn = a.getBoolean(R.styleable.StickyHeaderTableView_shtv_isWrapWidthOfEachColumn, false);

                headerCellFillColor = a.getColor(
                        R.styleable.StickyHeaderTableView_shtv_headerCellFillColor, Color.TRANSPARENT);

                contentCellFillColor = a.getColor(
                        R.styleable.StickyHeaderTableView_shtv_contentCellFillColor, Color.TRANSPARENT);

            } catch (Exception e) {
                textLabelColor = Color.BLACK;
                textHeaderColor = Color.BLACK;
                dividerColor = Color.BLACK;
                textLabelSize = defaultTextSize;
                textHeaderSize = defaultTextSize;
                dividerThickness = 0;
                cellPadding = 0;
                is2DScrollingEnabled = false;
                headerCellFillColor = Color.TRANSPARENT;
                contentCellFillColor = Color.TRANSPARENT;
            } finally {
                a.recycle();
            }
        } else {
            textLabelColor = Color.BLACK;
            textHeaderColor = Color.BLACK;
            dividerColor = Color.BLACK;
            textLabelSize = defaultTextSize;
            textHeaderSize = defaultTextSize;
            dividerThickness = 0;
            cellPadding = 0;
            is2DScrollingEnabled = false;
            headerCellFillColor = Color.TRANSPARENT;
            contentCellFillColor = Color.TRANSPARENT;
        }

        setupPaint();
        setupScrolling();
    }

    private float dpToPixels(Context context, float dpValue) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpValue, metrics);
    }

    private void setupPaint() {
        paintStrokeRect.setStyle(Paint.Style.STROKE);
        paintStrokeRect.setColor(dividerColor);
        paintStrokeRect.setStrokeWidth(dividerThickness);

        paintHeaderCellFillRect.setStyle(Paint.Style.FILL);
        paintHeaderCellFillRect.setColor(headerCellFillColor);

        paintContentCellFillRect.setStyle(Paint.Style.FILL);
        paintContentCellFillRect.setColor(contentCellFillColor);

        paintLabelText.setStyle(Paint.Style.FILL);
        paintLabelText.setColor(textLabelColor);
        paintLabelText.setTextSize(textLabelSize);
        paintLabelText.setTextAlign(Paint.Align.LEFT);

        paintHeaderText.setStyle(Paint.Style.FILL);
        paintHeaderText.setColor(textHeaderColor);
        paintHeaderText.setTextSize(textHeaderSize);
        paintHeaderText.setTextAlign(Paint.Align.LEFT);
    }

    private void setupScrolling() {

        nestedScrollingChildHelper = new NestedScrollingChildHelper(this);

        GestureDetector.SimpleOnGestureListener simpleOnGestureListener = new GestureDetector.SimpleOnGestureListener() {

            public boolean onDown(MotionEvent e) {
                if (isNestedScrollingEnabled()) {
                    startNestedScroll(NESTED_SCROLL_AXIS);
                }
                if (isFlinging) {
                    isFlinging = false;
                }
                return true;
            }

            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (isNestedScrollingEnabled()) {
                    dispatchNestedPreFling(velocityX, velocityY);
                }

                if (!canScrollHorizontally() && !canScrollVertically()) {
                    return false;
                }

                final float distanceTimeFactor = 0.4f;
                totalAnimDx = (distanceTimeFactor * velocityX / 2);
                totalAnimDy = (distanceTimeFactor * velocityY / 2);
                lastAnimDx = 0;
                lastAnimDy = 0;
                startTime = System.currentTimeMillis();
                endTime = startTime + (long) (1000 * distanceTimeFactor);

                float deltaY = e2.getY() - e1.getY();
                float deltaX = e2.getX() - e1.getX();

                if (!is2DScrollingEnabled) {
                    if (Math.abs(deltaX) > Math.abs(deltaY)) {
                        isScrollingHorizontally = true;
                    } else {
                        isScrollingVertically = true;
                    }
                }
                isFlinging = true;

                if (onFlingAnimateStep()) {
                    if (isNestedScrollingEnabled()) {
                        dispatchNestedFling(-velocityX, -velocityY, true);
                    }
                    return true;
                } else {
                    if (isNestedScrollingEnabled()) {
                        dispatchNestedFling(-velocityX, -velocityY, false);
                    }
                    return false;
                }

            }

            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {

                if (isNestedScrollingEnabled()) {
                    dispatchNestedPreScroll((int) distanceX, (int) distanceY, null, null);
                }

                boolean isScrolled;

                if (is2DScrollingEnabled) {
                    isScrolled = scroll2D(distanceX, distanceY);
                } else {

                    if (isScrollingHorizontally) {
                        isScrolled = scrollHorizontal(distanceX);
                    } else if (isScrollingVertically) {
                        isScrolled = scrollVertical(distanceY);
                    } else {

                        float deltaY = e2.getY() - e1.getY();
                        float deltaX = e2.getX() - e1.getX();

                        if (Math.abs(deltaX) > Math.abs(deltaY)) {
                            // if deltaX > 0 : the user made a sliding right gesture
                            // else : the user made a sliding left gesture
                            isScrollingHorizontally = true;
                            isScrolled = scrollHorizontal(distanceX);
                        } else {
                            // if deltaY > 0 : the user made a sliding down gesture
                            // else : the user made a sliding up gesture
                            isScrollingVertically = true;
                            isScrolled = scrollVertical(distanceY);
                        }
                    }
                }

                // Fix scrolling (if any parent view is scrollable in layout hierarchy,
                // than this will disallow intercepting touch event)
                if (getParent() != null && isScrolled) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }

                if (isScrolled) {
                    if (isNestedScrollingEnabled()) {
                        dispatchNestedScroll((int) distanceX, (int) distanceY, 0, 0, null);
                    }
                } else {
                    if (isNestedScrollingEnabled()) {
                        dispatchNestedScroll(0, 0, (int) distanceX, (int) distanceY, null);
                    }
                }

                return isScrolled;
            }

            public boolean onSingleTapUp(MotionEvent e) {

                if (onTableCellClickListener != null) {

                    final float x = e.getX();
                    final float y = e.getY();

                    boolean isEndLoop = false;

                    for (int i = 0; i < rectEachCellBoundData.length; i++) {

                        if (rectEachCellBoundData[i][0].top <= y && rectEachCellBoundData[i][0].bottom >= y) {

                            for (int j = 0; j < rectEachCellBoundData[0].length; j++) {

                                if (rectEachCellBoundData[i][j].left <= x && rectEachCellBoundData[i][j].right >= x) {
                                    isEndLoop = true;
                                    onTableCellClickListener.onTableCellClicked(i, j);
                                    break;
                                }
                            }
                        }
                        if (isEndLoop) {
                            break;
                        }
                    }
                }

                return super.onSingleTapUp(e);
            }

            public void onLongPress(MotionEvent e) {
                super.onLongPress(e);
            }

            public boolean onDoubleTapEvent(MotionEvent e) {
                return super.onDoubleTapEvent(e);
            }

        };
        gestureDetector = new GestureDetector(getContext(), simpleOnGestureListener);
    }

    /**
     * This will start fling animation
     *
     * @return true if fling animation consumed
     */
    private boolean onFlingAnimateStep() {

        boolean isScrolled = false;

        long curTime = System.currentTimeMillis();
        float percentTime = (float) (curTime - startTime) / (float) (endTime - startTime);
        float percentDistance = animateInterpolator.getInterpolation(percentTime);
        float curDx = percentDistance * totalAnimDx;
        float curDy = percentDistance * totalAnimDy;

        float distanceX = curDx - lastAnimDx;
        float distanceY = curDy - lastAnimDy;
        lastAnimDx = curDx;
        lastAnimDy = curDy;

        if (is2DScrollingEnabled) {
            isScrolled = scroll2D(-distanceX, -distanceY);
        } else if (isScrollingHorizontally) {
            isScrolled = scrollHorizontal(-distanceX);
        } else if (isScrollingVertically) {
            isScrolled = scrollVertical(-distanceY);
        }

        // This will stop fling animation if user has touch intercepted
        if (!isFlinging) {
            return false;
        }

        if (percentTime < 1.0f) {
            // fling animation running
            post(this::onFlingAnimateStep);
        } else {
            // fling animation ended
            isFlinging = false;
            isScrollingVertically = false;
            isScrollingHorizontally = false;
        }
        return isScrolled;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int desiredWidth = 0;
        int desiredHeight = 0;

        if (data != null) {
            updateMaxWidthHeightOfCell();
            if (isWrapHeightOfEachRow) {

                for (int i = 0; i < maxHeightSparseIntArray.size(); i++) {
                    desiredHeight = desiredHeight + maxHeightSparseIntArray.get(i, 0);
                }
                desiredHeight = desiredHeight + (dividerThickness / 2);
            } else {
                desiredHeight = maxHeightOfCell * data.length + (dividerThickness / 2);
            }

            if (isWrapWidthOfEachColumn) {

                for (int i = 0; i < maxWidthSparseIntArray.size(); i++) {
                    desiredWidth = desiredWidth + maxWidthSparseIntArray.get(i, 0);
                }
                desiredWidth = desiredWidth + (dividerThickness / 2);

            } else {
                desiredWidth = maxWidthOfCell * data[0].length + (dividerThickness / 2);
            }

            scrolledRect.set(0, 0, desiredWidth, desiredHeight);
            actualContentRect.set(0, 0, desiredWidth, desiredHeight);
        }

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

    /**
     * Calculate and update max width height of cell<br/>
     * Required for onMeasure() method
     */
    private void updateMaxWidthHeightOfCell() {
        // call only once otherwise it is very cpu time consuming
        if (maxMeasure > 0) {
            return;
        }
        maxMeasure++;

        maxWidthOfCell = 0;
        maxHeightOfCell = 0;
        maxHeightSparseIntArray.clear();
        maxWidthSparseIntArray.clear();

        final int doubleCellPadding = cellPadding + cellPadding;

        for (int i = 0; i < data.length; i++) {

            for (int j = 0; j < data[0].length; j++) {

                if (i == 0 && j == 0) {
//                    data[0][0] = "xx";

                    if (data[i][j] instanceof String) {
                        String str = (String)data[i][j];
                        paintHeaderText.getTextBounds(str, 0, str.length(), textRectBounds);
                    } else if (data[i][j] instanceof Drawable) {
                        Drawable icon = (Drawable) data[i][j];
                        textRectBounds.set(0,0, icon.getIntrinsicWidth() + 30, icon.getIntrinsicHeight());
                    }

                    if (maxWidthOfCell < textRectBounds.width()) {
                        maxWidthOfCell = textRectBounds.width();
                    }
                    if (maxHeightOfCell < textRectBounds.height()) {
                        maxHeightOfCell = textRectBounds.height();
                    }

                    if (maxWidthSparseIntArray.get(j, 0) < textRectBounds.width()) {
                        maxWidthSparseIntArray.put(j, textRectBounds.width());
                    }
                    if (maxHeightSparseIntArray.get(i, 0) < textRectBounds.height()) {
                        maxHeightSparseIntArray.put(i, textRectBounds.height());
                    }
                } else if (i == 0) {
                    // Top headers cells

                    if (data[i][j] instanceof String) {
                        String str = (String)data[i][j];
                        paintHeaderText.getTextBounds(str, 0, str.length(), textRectBounds);
                    } else if (data[i][j] instanceof Drawable) {
                        Drawable icon = (Drawable) data[i][j];
                        textRectBounds.set(0,0,icon.getIntrinsicWidth() + 30, icon.getIntrinsicHeight());
                    }
                    if (maxWidthOfCell < textRectBounds.width()) {
                        maxWidthOfCell = textRectBounds.width();
                    }
                    if (maxHeightOfCell < textRectBounds.height()) {
                        maxHeightOfCell = textRectBounds.height();
                    }

                    if (maxWidthSparseIntArray.get(j, 0) < textRectBounds.width()) {
                        maxWidthSparseIntArray.put(j, textRectBounds.width());
                    }
                    if (maxHeightSparseIntArray.get(i, 0) < textRectBounds.height()) {
                        maxHeightSparseIntArray.put(i, textRectBounds.height());
                    }
                } else if (j == 0) {
                    // Left headers cells
                    if (data[i][j] instanceof String) {
                        String str = (String)data[i][j];
                        if (str.indexOf("\n") != -1) {
                            String[] split = str.split("\n");

                            if (split[0].length() >= split[1].length()) {
                                str = split[0];
                            } else {
                                str = split[1];
                            }
                        }
                        paintHeaderText.getTextBounds(str, 0, str.length(), textRectBounds);
                    } else if (data[i][j] instanceof Drawable) {
                        Drawable icon = (Drawable) data[i][j];
                        textRectBounds.set(0,0,icon.getIntrinsicWidth(), icon.getIntrinsicHeight() / 2);
                    }

                    if (isDisplayLeftHeadersVertically) {

                        if (maxWidthOfCell < textRectBounds.height()) {
                            maxWidthOfCell = textRectBounds.height();
                        }
                        if (maxHeightOfCell < textRectBounds.width()) {
                            maxHeightOfCell = textRectBounds.width();
                        }

                        if (maxWidthSparseIntArray.get(j, 0) < textRectBounds.height()) {
                            maxWidthSparseIntArray.put(j, textRectBounds.height());
                        }
                        if (maxHeightSparseIntArray.get(i, 0) < textRectBounds.width()) {
                            maxHeightSparseIntArray.put(i, textRectBounds.width());
                        }

                    } else {

                        if (maxWidthOfCell < textRectBounds.width()) {
                            maxWidthOfCell = textRectBounds.width();
                        }
                        if (maxHeightOfCell < textRectBounds.height()) {
                            maxHeightOfCell = textRectBounds.height();
                        }

                        if (maxWidthSparseIntArray.get(j, 0) < textRectBounds.width()) {
                            maxWidthSparseIntArray.put(j, textRectBounds.width());
                        }
                        if (maxHeightSparseIntArray.get(i, 0) < textRectBounds.height()) {
                            maxHeightSparseIntArray.put(i, textRectBounds.height());
                        }
                    }
                } else {
                    // Other content cells
                    if (data[i][j] instanceof String) {
                        String str = (String)data[i][j];

                        if (str.indexOf("\n") != -1) {
                            String[] split = str.split("\n");

                            // split.length == 1 handle the case when str has only 1 \n and it is at the end.
                            if (split.length == 1 || split[0].length() >= split[1].length()) {
                                str = split[0];
                            } else {
                                str = split[1];
                            }
                        }
                        paintLabelText.getTextBounds(str, 0, str.length(), textRectBounds);
                    } else if (data[i][j] instanceof Drawable) {
                        Drawable icon = (Drawable) data[i][j];
                        textRectBounds.set(0,0,icon.getIntrinsicWidth(), icon.getIntrinsicHeight() / 2);
                    }

                    if (maxWidthOfCell < textRectBounds.width()) {
                        maxWidthOfCell = textRectBounds.width();
                    }
                    if (maxHeightOfCell < textRectBounds.height()) {
                        maxHeightOfCell = textRectBounds.height();
                    }

                    if (maxWidthSparseIntArray.get(j, 0) < textRectBounds.width()) {
                        maxWidthSparseIntArray.put(j, textRectBounds.width());
                    }
                    if (maxHeightSparseIntArray.get(i, 0) < textRectBounds.height()) {
                        maxHeightSparseIntArray.put(i, textRectBounds.height());
                    }
                }
            }
        }
        maxWidthOfCell = maxWidthOfCell + doubleCellPadding;
        maxHeightOfCell = maxHeightOfCell + doubleCellPadding;

        for (int i = 0; i < maxHeightSparseIntArray.size(); i++) {
            maxHeightSparseIntArray.put(i, maxHeightSparseIntArray.get(i, 0) + doubleCellPadding);
        }

        for (int i = 0; i < maxWidthSparseIntArray.size(); i++) {
            maxWidthSparseIntArray.put(i, maxWidthSparseIntArray.get(i, 0) + doubleCellPadding);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);

        visibleContentRect.set(0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (data == null) {
            return;
        }


        int cellLeftX;
        int cellTopY = scrolledRect.top;
        int cellRightX;
        int cellBottomY = scrolledRect.top + getHeightOfRow(0);
        int halfDividerThickness = dividerThickness / 2;

        float drawTextX;
        float drawTextY;
        String textToDraw;
        Drawable iconToDraw;

        // *************************** Calculate each cells to draw **************************
        // This is top-left most cell (0,0)
        updateRectPointData(0, 0, halfDividerThickness, halfDividerThickness, getWidthOfColumn(0), getHeightOfRow(0));

        for (int i = 0; i < data.length; i++) {
            cellRightX = scrolledRect.left;
            int heightOfRowI = getHeightOfRow(i);
            if (i == 0) {
                cellTopY = halfDividerThickness;
                for (int j = 0; j < data[i].length; j++) {
                    cellLeftX = cellRightX - halfDividerThickness;
                    cellRightX += getWidthOfColumn(j);
                    if (j != 0) {
                        // This are top header cells (0,*)
                        updateRectPointData(i, j, cellLeftX, cellTopY, cellRightX, heightOfRowI);
                    }
                }
                cellBottomY = scrolledRect.top + getHeightOfRow(i);
            } else {
                // These are content cells
                for (int j = 0; j < data[0].length; j++) {
                    cellLeftX = cellRightX - halfDividerThickness;
                    cellRightX += getWidthOfColumn(j);
                    if (j != 0) {
                        updateRectPointData(i, j, cellLeftX, cellTopY, cellRightX, cellBottomY);
                    }
                }

                // This are left header cells (*,0)
                cellRightX = 0;
                cellLeftX = cellRightX + halfDividerThickness;
                cellRightX += getWidthOfColumn(0);
                updateRectPointData(i, 0, cellLeftX, cellTopY, cellRightX, cellBottomY);
            }
            cellTopY = cellBottomY - halfDividerThickness;
            cellBottomY = cellBottomY + getHeightOfRow(i + 1);
        }

        // ******************** Draw contents & left headers ********************
        boolean isLeftVisible;
        boolean isTopVisible;
        boolean isRightVisible;
        boolean isBottomVisible;

        for (int i = 1; i < data.length; i++) {
            isTopVisible = rectEachCellBoundData[i][0].top >= rectEachCellBoundData[0][0].bottom
                    && rectEachCellBoundData[i][0].top <= visibleContentRect.bottom;
            isBottomVisible = rectEachCellBoundData[i][0].bottom >= rectEachCellBoundData[0][0].bottom
                    && rectEachCellBoundData[i][0].bottom <= visibleContentRect.bottom;

            if (isTopVisible || isBottomVisible) {

                // ******************** Draw contents ********************
                for (int j = 1; j < data[i].length; j++) {
                    isLeftVisible = rectEachCellBoundData[i][j].left >= rectEachCellBoundData[i][0].right
                            && rectEachCellBoundData[i][j].left <= visibleContentRect.right;
                    isRightVisible = rectEachCellBoundData[i][j].right >= rectEachCellBoundData[i][0].right
                            && rectEachCellBoundData[i][j].right <= visibleContentRect.right;

                    if (isLeftVisible || isRightVisible) {
                        canvas.drawRect(rectEachCellBoundData[i][j].left, rectEachCellBoundData[i][j].top, rectEachCellBoundData[i][j].right, rectEachCellBoundData[i][j].bottom, paintContentCellFillRect);
                        if (dividerThickness != 0) {
                            canvas.drawRect(rectEachCellBoundData[i][j].left, rectEachCellBoundData[i][j].top, rectEachCellBoundData[i][j].right, rectEachCellBoundData[i][j].bottom, paintStrokeRect);
                        }

                        textToDraw = (String)data[i][j];
                      //  paintLabelText.getTextBounds(textToDraw, 0, textToDraw.length(), textRectBounds);

                        drawTextX = rectEachCellBoundData[i][j].right - getWidthOfColumn(j) + getCellPadding();
                        drawTextY = rectEachCellBoundData[i][j].bottom - (getHeightOfRow(i)) + (textRectBounds.height() / 2f);

                        StaticLayout staticLayout = StaticLayout.Builder.obtain(textToDraw, 0, textToDraw.length(), paintLabelText, getWidthOfColumn(j)).build();

                        canvas.save();
                        canvas.translate(drawTextX, drawTextY);
                        staticLayout.draw(canvas);
                        canvas.restore();

                        //canvas.drawText(textToDraw, 0, textToDraw.length(), drawTextX, drawTextY, paintLabelText);
                    }
                }

                // ******************** Draw left header (*,0) ********************
                canvas.drawRect(rectEachCellBoundData[i][0].left, rectEachCellBoundData[i][0].top, rectEachCellBoundData[i][0].right, rectEachCellBoundData[i][0].bottom, paintHeaderCellFillRect);
                if (dividerThickness != 0) {
                    canvas.drawRect(rectEachCellBoundData[i][0].left, rectEachCellBoundData[i][0].top, rectEachCellBoundData[i][0].right, rectEachCellBoundData[i][0].bottom, paintStrokeRect);
                }

                textToDraw = (String)data[i][0];
               // paintHeaderText.getTextBounds(textToDraw, 0, textToDraw.length(), textRectBounds);

                if (isDisplayLeftHeadersVertically) {
                    drawTextX = rectEachCellBoundData[i][0].right - (getWidthOfColumn(0)) + (textRectBounds.height());
                    drawTextY = rectEachCellBoundData[i][0].bottom - getCellPadding();

                    StaticLayout staticLayout = StaticLayout.Builder.obtain(textToDraw, 0, textToDraw.length(), paintHeaderText, getHeightOfRow(i)).build();

                    canvas.save();
                    canvas.translate(drawTextX, drawTextY);
                    canvas.rotate(-90);
                    staticLayout.draw(canvas);
                    //canvas.drawText(textToDraw, 0, textToDraw.length(), drawTextX, drawTextY, paintHeaderText);
                    canvas.restore();
                } else {
                    drawTextX = rectEachCellBoundData[i][0].right - getWidthOfColumn(0) + getCellPadding();
                    drawTextY = rectEachCellBoundData[i][0].bottom - (getHeightOfRow(i)) + (textRectBounds.height() / 2f);

                    StaticLayout staticLayout = StaticLayout.Builder.obtain(textToDraw, 0, textToDraw.length(), paintLabelText, getWidthOfColumn(0)).build();

                    canvas.save();
                    canvas.translate(drawTextX, drawTextY);
                    staticLayout.draw(canvas);
                    canvas.restore();

                    //canvas.drawText(textToDraw, 0, textToDraw.length(), drawTextX, drawTextY, paintHeaderText);
                }
            }
        }

        // ******************** Draw top headers (0,*) ********************
        for (int j = 1; j < data[0].length; j++) {
            isLeftVisible = rectEachCellBoundData[0][j].left >= rectEachCellBoundData[0][0].right
                    && rectEachCellBoundData[0][j].left <= visibleContentRect.right;
            isRightVisible = rectEachCellBoundData[0][j].right >= rectEachCellBoundData[0][0].right
                    && rectEachCellBoundData[0][j].right <= visibleContentRect.right;

            if (isLeftVisible || isRightVisible) {
                canvas.drawRect(rectEachCellBoundData[0][j].left, rectEachCellBoundData[0][j].top, rectEachCellBoundData[0][j].right, rectEachCellBoundData[0][j].bottom, paintHeaderCellFillRect);
                if (dividerThickness != 0) {
                    canvas.drawRect(rectEachCellBoundData[0][j].left, rectEachCellBoundData[0][j].top, rectEachCellBoundData[0][j].right, rectEachCellBoundData[0][j].bottom, paintStrokeRect);
                }

                if (data[0][j] instanceof String) {
                    textToDraw = (String)data[0][j];
                   // paintHeaderText.getTextBounds(textToDraw, 0, textToDraw.length(), textRectBounds);

                    drawTextX = rectEachCellBoundData[0][j].right - (getWidthOfColumn(j) / 2f) - (textRectBounds.width() / 2f);
                    drawTextY = rectEachCellBoundData[0][j].bottom - (getHeightOfRow(0) / 2f) + (textRectBounds.height() / 2f);

                    canvas.drawText(textToDraw, 0, textToDraw.length(), drawTextX, drawTextY, paintHeaderText);
                } else if (data[0][j] instanceof Drawable) {
                    iconToDraw = (Drawable) data[0][j];

                    drawTextX = rectEachCellBoundData[0][j].right - (getWidthOfColumn(j) / 2f) - (iconToDraw.getIntrinsicWidth() / 2f);
                    //drawTextY = rectEachCellBoundData[0][j].bottom - (getHeightOfRow(0) / 2f) + (iconToDraw.getIntrinsicHeight() / 2f);

                    iconToDraw.setBounds((int)drawTextX, 25, (int)drawTextX + iconToDraw.getIntrinsicWidth(), 25 + iconToDraw.getIntrinsicHeight());

                    // draw circle with the tinted icon color and tint the icon with black
                    paintDrawable.setColorFilter(iconToDraw.getColorFilter());
                    iconToDraw.setColorFilter(ColorUtil.COLOR_BLACK, PorterDuff.Mode.SRC_ATOP);
                    canvas.drawOval((int)drawTextX-25, 10, drawTextX+ iconToDraw.getIntrinsicWidth()+25, 45 + iconToDraw.getIntrinsicHeight(), paintDrawable);

                    iconToDraw.draw(canvas);

                    // save the tinted icon color back to the icon
                    iconToDraw.setColorFilter(paintDrawable.getColorFilter());
                }
            }
        }

        // ******************** Draw top-left most cell (0,0) ********************
        canvas.drawRect(rectEachCellBoundData[0][0].left, rectEachCellBoundData[0][0].top, rectEachCellBoundData[0][0].right, rectEachCellBoundData[0][0].bottom, paintHeaderCellFillRect);

        if (dividerThickness != 0) {
            canvas.drawRect(rectEachCellBoundData[0][0].left, rectEachCellBoundData[0][0].top, rectEachCellBoundData[0][0].right, rectEachCellBoundData[0][0].bottom, paintStrokeRect);
        }

        if (data[0][0] instanceof String) {
            textToDraw = (String)data[0][0];

           // paintHeaderText.getTextBounds(textToDraw, 0, textToDraw.length(), textRectBounds);

            drawTextX = getWidthOfColumn(0) - (getWidthOfColumn(0) / 2f) - (textRectBounds.width()/ 2f);
            drawTextY = getHeightOfRow(0) - (getHeightOfRow(0) / 2f) + (textRectBounds.height() / 2f);

            canvas.drawText(textToDraw, 0, textToDraw.length(), drawTextX, drawTextY, paintHeaderText);
        } else if (data[0][0] instanceof Drawable) {
            iconToDraw = (Drawable) data[0][0];

            drawTextX = getWidthOfColumn(0) - (getWidthOfColumn(0) / 2f) - (iconToDraw.getIntrinsicWidth()/ 2f);
            //drawTextY = getHeightOfRow(0) - (getHeightOfRow(0) / 2f) + (iconToDraw.getIntrinsicHeight() / 2f);
            iconToDraw.setBounds((int)drawTextX, 25, (int)drawTextX + iconToDraw.getIntrinsicWidth(), 25 + iconToDraw.getIntrinsicHeight());

            // draw circle with the tinted icon color and tint the icon with black
            paintDrawable.setColorFilter(iconToDraw.getColorFilter());
            iconToDraw.setColorFilter(ColorUtil.COLOR_BLACK, PorterDuff.Mode.SRC_ATOP);
            canvas.drawOval((int)drawTextX-25, 10, drawTextX+ iconToDraw.getIntrinsicWidth()+25, 45 + iconToDraw.getIntrinsicHeight(), paintDrawable);

            iconToDraw.draw(canvas);

            // save the tinted icon color back to the icon
            iconToDraw.setColorFilter(paintDrawable.getColorFilter());
        }


        // ******************** Draw whole view border same as cell border ********************
        if (dividerThickness != 0) {
            canvas.drawRect(visibleContentRect.left, visibleContentRect.top, visibleContentRect.right - halfDividerThickness, visibleContentRect.bottom - halfDividerThickness, paintStrokeRect);
        }
    }

    private int getWidthOfColumn(int key) {
        if (isWrapWidthOfEachColumn) {
            return maxWidthSparseIntArray.get(key, 0);
        } else {
            return maxWidthOfCell;
        }
    }

    private int getHeightOfRow(int key) {
        if (isWrapHeightOfEachRow) {
            return maxHeightSparseIntArray.get(key, 0);
        } else {
            return maxHeightOfCell;
        }
    }

    /**
     * This will update cell bound rect data, which is used for handling cell click event
     *
     * @param i           row position
     * @param j           column position
     * @param cellLeftX   leftX
     * @param cellTopY    topY
     * @param cellRightX  rightX
     * @param cellBottomY bottomY
     */
    private void updateRectPointData(int i, int j, int cellLeftX, int cellTopY, int cellRightX, int cellBottomY) {
        if (rectEachCellBoundData[i][j] == null) {
            rectEachCellBoundData[i][j] = new Rect(cellLeftX, cellTopY, cellRightX, cellBottomY);
        } else {
            rectEachCellBoundData[i][j].left = cellLeftX;
            rectEachCellBoundData[i][j].top = cellTopY;
            rectEachCellBoundData[i][j].right = cellRightX;
            rectEachCellBoundData[i][j].bottom = cellBottomY;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isScrollingHorizontally = false;
                isScrollingVertically = false;
                break;
        }

        return gestureDetector.onTouchEvent(event);
        //return true;
    }

    private void updateLayoutChanges() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            if (!isInLayout()) {
                requestLayout();
            } else {
                invalidate();
            }
        } else {
            requestLayout();
        }
    }

    /**
     * Check if content width is bigger than  view width
     *
     * @return true if content width is bigger than view width
     */
    public boolean canScrollHorizontally() {
        return actualContentRect.right > visibleContentRect.right;
    }

    /**
     * Check if content height is bigger than  view height
     *
     * @return true if content height is bigger than view height
     */
    public boolean canScrollVertically() {
        return actualContentRect.bottom > visibleContentRect.bottom;
    }

    /**
     * Scroll horizontally
     *
     * @param distanceX distance to scroll
     * @return true if horizontally scrolled, false otherwise
     */
    public boolean scrollHorizontal(float distanceX) {

        if (!canScrollHorizontally() || distanceX == 0) {
            return false;
        }

        int newScrolledLeft = scrolledRect.left - (int) distanceX;
        int newScrolledRight = scrolledRect.right - (int) distanceX;

        if (newScrolledLeft > 0) {
            newScrolledLeft = 0;
            newScrolledRight = actualContentRect.right;
        } else if (newScrolledLeft < -(actualContentRect.right - visibleContentRect.right)) {
            newScrolledLeft = -(actualContentRect.right - visibleContentRect.right);
            newScrolledRight = visibleContentRect.right;
        }

        if (scrolledRect.left == newScrolledLeft) {
            return false;
        }
        scrolledRect.set(newScrolledLeft, scrolledRect.top, newScrolledRight, scrolledRect.bottom);
        invalidate();
        return true;
    }

    /**
     * Scroll vertically
     *
     * @param distanceY distance to scroll
     * @return true if vertically scrolled, false otherwise
     */
    public boolean scrollVertical(float distanceY) {

        if (!canScrollVertically() || distanceY == 0) {
            return false;
        }

        int newScrolledTop = scrolledRect.top - (int) distanceY;
        int newScrolledBottom = scrolledRect.bottom - (int) distanceY;

        if (newScrolledTop > 0) {
            newScrolledTop = 0;
            newScrolledBottom = actualContentRect.bottom;
        } else if (newScrolledTop < -(actualContentRect.bottom - visibleContentRect.bottom)) {
            newScrolledTop = -(actualContentRect.bottom - visibleContentRect.bottom);
            newScrolledBottom = visibleContentRect.bottom;
        }

        if (scrolledRect.top == newScrolledTop) {
            return false;
        }
        scrolledRect.set(scrolledRect.left, newScrolledTop, scrolledRect.right, newScrolledBottom);
        invalidate();
        return true;
    }

    /**
     * Scroll vertically & horizontal both side
     *
     * @param distanceX distance to scroll
     * @param distanceY distance to scroll
     * @return true if scrolled, false otherwise
     */
    public boolean scroll2D(float distanceX, float distanceY) {

        boolean isScrollHappened = false;
        int newScrolledLeft;
        int newScrolledTop;
        int newScrolledRight;
        int newScrolledBottom;

        if (canScrollHorizontally()) {
            newScrolledLeft = scrolledRect.left - (int) distanceX;
            newScrolledRight = scrolledRect.right - (int) distanceX;

            if (newScrolledLeft > 0) {
                newScrolledLeft = 0;
            }
            if (newScrolledLeft < -(actualContentRect.right - visibleContentRect.right)) {
                newScrolledLeft = -(actualContentRect.right - visibleContentRect.right);
            }
            isScrollHappened = true;
        } else {
            newScrolledLeft = scrolledRect.left;
            newScrolledRight = scrolledRect.right;
        }

        if (canScrollVertically()) {
            newScrolledTop = scrolledRect.top - (int) distanceY;
            newScrolledBottom = scrolledRect.bottom - (int) distanceY;

            if (newScrolledTop > 0) {
                newScrolledTop = 0;
            }
            if (newScrolledTop < -(actualContentRect.bottom - visibleContentRect.bottom)) {
                newScrolledTop = -(actualContentRect.bottom - visibleContentRect.bottom);
            }
            isScrollHappened = true;
        } else {
            newScrolledTop = scrolledRect.top;
            newScrolledBottom = scrolledRect.bottom;
        }

        if (!isScrollHappened) {
            return false;
        }

        scrolledRect.set(newScrolledLeft, newScrolledTop, newScrolledRight, newScrolledBottom);
        invalidate();
        return true;
    }

    /**
     * @return true if content are scrollable from top to bottom side
     */
    public boolean canScrollTop() {
        return scrolledRect.top < visibleContentRect.top;
    }

    /**
     * @return true if content are scrollable from bottom to top side
     */
    public boolean canScrollBottom() {
        return scrolledRect.bottom > visibleContentRect.bottom;
    }

    /**
     * @return true if content are scrollable from left to right side
     */
    public boolean canScrollRight() {
        return scrolledRect.right > visibleContentRect.right;
    }

    /**
     * @return true if content are scrollable from right to left side
     */
    public boolean canScrollLeft() {
        return scrolledRect.left < visibleContentRect.left;
    }


    // *************************** implemented NestedScrollChild methods *******************************************

    @Override
    public boolean isNestedScrollingEnabled() {
        return nestedScrollingChildHelper.isNestedScrollingEnabled();
    }

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        nestedScrollingChildHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public boolean hasNestedScrollingParent() {
        return nestedScrollingChildHelper.hasNestedScrollingParent();
    }

    /**
     * default Nested scroll axis is ViewCompat.SCROLL_AXIS_NONE <br/>
     * Nested scroll axis must be one of the <br/>ViewCompat.SCROLL_AXIS_NONE <br/>or ViewCompat.SCROLL_AXIS_HORIZONTAL <br/>or ViewCompat.SCROLL_AXIS_VERTICAL
     *
     * @param nestedScrollAxis value of nested scroll direction
     */
    public void setNestedScrollAxis(int nestedScrollAxis) {
        switch (nestedScrollAxis) {

            case ViewCompat.SCROLL_AXIS_HORIZONTAL:
                NESTED_SCROLL_AXIS = ViewCompat.SCROLL_AXIS_HORIZONTAL;
                break;
            case ViewCompat.SCROLL_AXIS_VERTICAL:
                NESTED_SCROLL_AXIS = ViewCompat.SCROLL_AXIS_VERTICAL;
                break;
            default:
                NESTED_SCROLL_AXIS = ViewCompat.SCROLL_AXIS_NONE;
                break;
        }
    }

    @Override
    public boolean startNestedScroll(int axes) {
        return nestedScrollingChildHelper.startNestedScroll(axes);
    }

    @Override
    public void stopNestedScroll() {
        nestedScrollingChildHelper.stopNestedScroll();
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int[] offsetInWindow) {
        return nestedScrollingChildHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed, int[] offsetInWindow) {
        return nestedScrollingChildHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
        return nestedScrollingChildHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return nestedScrollingChildHelper.dispatchNestedPreFling(velocityX, velocityY);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        nestedScrollingChildHelper.onDetachedFromWindow();
    }

    // *************************** Getter/Setter methods *******************************************

    /**
     * @return data which is previously set by setData(data) method. otherwise null.
     */
    public Object[][] getData() {
        return data;
    }

    /**
     * Set you table content data
     *
     * @param data table content data
     */
    public void setData(Object[][] data) {
        this.data = data;
        rectEachCellBoundData = new Rect[data.length][data[0].length];
        updateLayoutChanges();
    }

    /**
     * set the cell click event
     *
     * @param onTableCellClickListener tableCellClickListener
     */
    public void setOnTableCellClickListener(OnTableCellClickListener onTableCellClickListener) {
        this.onTableCellClickListener = onTableCellClickListener;
    }

    /**
     * enable or disable 2 directional scroll
     *
     * @param is2DScrollingEnabled true if you wants to enable 2 directional scroll
     */
    public void setIs2DScrollingEnabled(boolean is2DScrollingEnabled) {
        this.is2DScrollingEnabled = is2DScrollingEnabled;
    }

    /**
     * Check whether is 2 directional scroll is enabled or not
     *
     * @return true if 2 directional scroll is enabled
     */
    public boolean is2DScrollingEnabled() {
        return is2DScrollingEnabled;
    }

    /**
     * @return text color of the content cells
     */
    public int getTextLabelColor() {
        return textLabelColor;
    }

    /**
     * Set text color  for content cells
     *
     * @param textLabelColor color
     */
    public void setTextLabelColor(int textLabelColor) {
        this.textLabelColor = textLabelColor;
        invalidate();
    }

    /**
     * @return text color of the header cells
     */
    public int getTextHeaderColor() {
        return textHeaderColor;
    }

    /**
     * Set text color  for header cells
     *
     * @param textHeaderColor color
     */
    public void setTextHeaderColor(int textHeaderColor) {
        this.textHeaderColor = textHeaderColor;
        invalidate();
    }

    /**
     * @return color of the cell divider or cell border
     */
    public int getDividerColor() {
        return dividerColor;
    }

    /**
     * Set divider or border color  for cell
     *
     * @param dividerColor color
     */
    public void setDividerColor(int dividerColor) {
        this.dividerColor = dividerColor;
        invalidate();
    }

    /**
     * @return text size in pixels of content cells
     */
    public int getTextLabelSize() {
        return textLabelSize;
    }

    /**
     * Set text size in pixels for content cells<br/>
     * You can use {@link DisplayMatrixHelper#dpToPixels(Context, float)} method to convert dp to pixel
     *
     * @param textLabelSize text size in pixels
     */
    public void setTextLabelSize(int textLabelSize) {
        this.textLabelSize = textLabelSize;
        updateLayoutChanges();
    }

    /**
     * @return text header size in pixels of header cells
     */
    public int getTextHeaderSize() {
        return textHeaderSize;
    }

    /**
     * Set text header size in pixels for header cells<br/>
     * You can use {@link DisplayMatrixHelper#dpToPixels(Context, float)} method to convert dp to pixel
     *
     * @param textHeaderSize text header size in pixels
     */
    public void setTextHeaderSize(int textHeaderSize) {
        this.textHeaderSize = textHeaderSize;
        updateLayoutChanges();
    }

    /**
     * @return divider thickness in pixels
     */
    public int getDividerThickness() {
        return dividerThickness;
    }

    /**
     * Set divider thickness size in pixels for all cells<br/>
     * You can use {@link DisplayMatrixHelper#dpToPixels(Context, float)} method to convert dp to pixel
     *
     * @param dividerThickness divider thickness size in pixels
     */
    public void setDividerThickness(int dividerThickness) {
        this.dividerThickness = dividerThickness;
        invalidate();
    }

    /**
     * @return header cell's fill color
     */
    public int getHeaderCellFillColor() {
        return headerCellFillColor;
    }

    /**
     * Set header cell fill color
     *
     * @param headerCellFillColor color to fill in header cell
     */
    public void setHeaderCellFillColor(int headerCellFillColor) {
        this.headerCellFillColor = headerCellFillColor;
        invalidate();
    }

    /**
     * @return content cell's fill color
     */
    public int getContentCellFillColor() {
        return contentCellFillColor;
    }

    /**
     * Set content cell fill color
     *
     * @param contentCellFillColor color to fill in content cell
     */
    public void setContentCellFillColor(int contentCellFillColor) {
        this.contentCellFillColor = contentCellFillColor;
        invalidate();
    }

    /**
     * @return cell padding in pixels
     */
    public int getCellPadding() {
        return cellPadding;
    }

    /**
     * Set padding for all cell of table<br/>
     * You can use {@link DisplayMatrixHelper#dpToPixels(Context, float)} method to convert dp to pixel
     *
     * @param cellPadding cell padding in pixels
     */
    public void setCellPadding(int cellPadding) {
        this.cellPadding = cellPadding;
        updateLayoutChanges();
    }

    /**
     * @return true if left header cell text are displayed vertically enabled
     */
    public boolean isDisplayLeftHeadersVertically() {
        return isDisplayLeftHeadersVertically;
    }

    /**
     * Set left header text display vertically or horizontal
     *
     * @param displayLeftHeadersVertically true if you wants to set left header text display vertically
     */
    public void setDisplayLeftHeadersVertically(boolean displayLeftHeadersVertically) {
        isDisplayLeftHeadersVertically = displayLeftHeadersVertically;
        updateLayoutChanges();
    }

    /**
     * @return true if you settled true for wrap height of each row
     */
    public boolean isWrapHeightOfEachRow() {
        return isWrapHeightOfEachRow;
    }

    /**
     * Set whether height of each row should wrap or not
     *
     * @param wrapHeightOfEachRow pass true if you wants to set each row should wrap the height
     */
    public void setWrapHeightOfEachRow(boolean wrapHeightOfEachRow) {
        isWrapHeightOfEachRow = wrapHeightOfEachRow;
        updateLayoutChanges();
    }

    /**
     * @return true if you settled true for wrap width of each column
     */
    public boolean isWrapWidthOfEachColumn() {
        return isWrapWidthOfEachColumn;
    }

    /**
     * Set whether width of each column should wrap or not
     *
     * @param wrapWidthOfEachColumn pass true if you wants to set each column should wrap the width
     */
    public void setWrapWidthOfEachColumn(boolean wrapWidthOfEachColumn) {
        isWrapWidthOfEachColumn = wrapWidthOfEachColumn;
        updateLayoutChanges();
    }

    /**
     * @return the Rect object which is visible area on screen
     */
    public Rect getVisibleContentRect() {
        return visibleContentRect;
    }

    /**
     * @return the Rect object which is last scrolled area from actual content rectangle
     */
    public Rect getScrolledRect() {
        return scrolledRect;
    }

    /**
     * @return the Rect object which is actual content area
     */
    public Rect getActualContentRect() {
        return actualContentRect;
    }
}
