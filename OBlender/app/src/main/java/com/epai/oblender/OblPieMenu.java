package com.epai.oblender;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Purely visual 8-slice radial menu shown while the stylus's side button is
 * held. All touch handling (which slice is highlighted, when an action
 * fires) is driven imperatively by {@link OBLNativeActivity}; this view is
 * added with {@code FLAG_NOT_TOUCHABLE} and never receives input itself.
 */
public class OblPieMenu extends View {

    /** Number of selectable wedges around the circle. */
    public static final int SLICE_COUNT = 8;

    // Slice order starts at the top (12 o'clock) and goes clockwise, matching
    // sliceForOffset()'s angle convention.
    private static final String[] LABELS = {
            "Grab", "Rotate", "Scale", "Extrude", "Delete", "Loop Cut", "Bevel", "Inset"
    };

    private static final float SLICE_SWEEP_DEG = 360f / SLICE_COUNT;
    private static final float DEAD_ZONE_FRACTION = 0.25f;
    private static final float LABEL_RADIUS_FRACTION = 0.62f;

    private final Paint mWedgePaint;
    private final Paint mTextPaint;
    private final int mNormalColor;
    private final int mActiveColor;
    private final int mCenterColor;
    private final RectF mBounds = new RectF();
    private int mSelectedIndex = -1;

    public OblPieMenu(Context context) {
        this(context, null);
    }

    public OblPieMenu(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        mNormalColor = ContextCompat.getColor(context, R.color.quickbar_bg);
        mActiveColor = ContextCompat.getColor(context, R.color.quickbar_btn_active);
        mCenterColor = ContextCompat.getColor(context, R.color.quickbar_btn_normal);

        mWedgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mWedgePaint.setStyle(Paint.Style.FILL);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setColor(ContextCompat.getColor(context, android.R.color.white));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTextSize(getResources().getDisplayMetrics().density * 14f);
    }

    /**
     * Highlights the wedge containing the point {@code (dx, dy)} relative to
     * the menu's center, or clears the highlight if it falls in the center
     * dead zone. No-op if the highlighted slice doesn't change.
     */
    public void updateSelection(float dx, float dy) {
        int index = sliceForOffset(dx, dy, getWidth() / 2f);
        if (index != mSelectedIndex) {
            mSelectedIndex = index;
            invalidate();
        }
    }

    /** Returns the currently highlighted slice (0..SLICE_COUNT-1), or -1 if none (cancel). */
    public int getSelectedIndex() {
        return mSelectedIndex;
    }

    /** Clears any highlight; call before showing the menu for a new gesture. */
    public void reset() {
        mSelectedIndex = -1;
    }

    /**
     * Maps a touch offset from the pie menu's center to a wedge index
     * (0..SLICE_COUNT-1, starting at the top and going clockwise), or -1 if
     * the offset falls within the center dead zone (cancel).
     */
    public static int sliceForOffset(float dx, float dy, float radius) {
        if (Math.hypot(dx, dy) < radius * DEAD_ZONE_FRACTION) {
            return -1;
        }
        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
        double normalized = ((angleDeg + 90f + SLICE_SWEEP_DEG / 2f) % 360f + 360f) % 360f;
        return (int) (normalized / SLICE_SWEEP_DEG) % SLICE_COUNT;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy);
        mBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        for (int i = 0; i < SLICE_COUNT; i++) {
            mWedgePaint.setColor(i == mSelectedIndex ? mActiveColor : mNormalColor);
            float startAngle = -90f + i * SLICE_SWEEP_DEG - SLICE_SWEEP_DEG / 2f;
            canvas.drawArc(mBounds, startAngle, SLICE_SWEEP_DEG, true, mWedgePaint);
        }

        mWedgePaint.setColor(mCenterColor);
        canvas.drawCircle(cx, cy, radius * DEAD_ZONE_FRACTION, mWedgePaint);

        float labelRadius = radius * LABEL_RADIUS_FRACTION;
        float textOffset = (mTextPaint.descent() + mTextPaint.ascent()) / 2f;
        for (int i = 0; i < SLICE_COUNT; i++) {
            double labelAngle = Math.toRadians(-90f + i * SLICE_SWEEP_DEG);
            float lx = cx + (float) (Math.cos(labelAngle) * labelRadius);
            float ly = cy + (float) (Math.sin(labelAngle) * labelRadius);
            canvas.drawText(LABELS[i], lx, ly - textOffset, mTextPaint);
        }
    }

    /**
     * Builds the {@link WindowManager.LayoutParams} this overlay should be
     * added with. It starts at the top-left corner; {@link OBLNativeActivity}
     * repositions it via {@code updateViewLayout} before making it visible.
     */
    public WindowManager.LayoutParams createLayoutParams(int diameterPx) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        lp.width = diameterPx;
        lp.height = diameterPx;
        return lp;
    }
}
