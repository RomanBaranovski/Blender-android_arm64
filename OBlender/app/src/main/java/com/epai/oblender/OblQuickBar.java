package com.epai.oblender;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, draggable, collapsible floating bar that gives quick access to the
 * Ctrl/Shift/Alt/RMB/MMB "hold" toggles plus Undo/Redo, so they don't require
 * opening the full {@link OblSettingFragment} keyboard panel.
 */
public class OblQuickBar extends LinearLayout {

    public interface OblQuickBarListener {
        void onModifierOn(int[] ordinals);

        void onModifierOff(int[] ordinals);

        void onMomentaryKey(int[] ordinals);
    }

    private static final String PREFS_NAME = "oblender_quickbar";
    private static final String PREF_X = "pos_x";
    private static final String PREF_Y = "pos_y";
    private static final String PREF_COLLAPSED = "collapsed";

    // OBLButtonID ordinals (see OBLButtonID.java) - reused, not extended.
    private static final int ORD_SHIFT = 0;
    private static final int ORD_CTRL = 1;
    private static final int ORD_ALT = 2;
    private static final int ORD_MMB = 3;
    private static final int ORD_RMB = 4;
    private static final int ORD_Z = 26;

    // Gaps between the on/key/off steps of the Undo/Redo bracket sequence,
    // in case the native side only samples modifier state once per frame.
    private static final long MODIFIER_SETTLE_DELAY_MS = 40;
    private static final long KEY_RELEASE_DELAY_MS = 40;

    private OblQuickBarListener mListener;
    private boolean mSuppressCallback = false;

    private LinearLayout mButtonContainer;
    private TextView mCollapseToggle;
    private ToggleButton mCtrlBtn;
    private ToggleButton mShiftBtn;
    private ToggleButton mAltBtn;
    private ToggleButton mRmbBtn;
    private ToggleButton mMmbBtn;

    private float mDragStartRawX;
    private float mDragStartRawY;
    private int mDragStartLpX;
    private int mDragStartLpY;

    public OblQuickBar(Context context) {
        super(context);
        init(context);
    }

    public OblQuickBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OblQuickBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setBackgroundColor(ContextCompat.getColor(context, R.color.quickbar_bg));
        LayoutInflater.from(context).inflate(R.layout.oblquickbar, this, true);

        View dragHandle = findViewById(R.id.quickbar_drag_handle);
        mCollapseToggle = findViewById(R.id.quickbar_collapse_toggle);
        mButtonContainer = findViewById(R.id.quickbar_buttons);

        mCtrlBtn = findViewById(R.id.quickbar_btn_ctrl);
        mShiftBtn = findViewById(R.id.quickbar_btn_shift);
        mAltBtn = findViewById(R.id.quickbar_btn_alt);
        mRmbBtn = findViewById(R.id.quickbar_btn_rmb);
        mMmbBtn = findViewById(R.id.quickbar_btn_mmb);
        View undoBtn = findViewById(R.id.quickbar_btn_undo);
        View redoBtn = findViewById(R.id.quickbar_btn_redo);

        setupModifierToggle(mCtrlBtn, ORD_CTRL);
        setupModifierToggle(mShiftBtn, ORD_SHIFT);
        setupModifierToggle(mAltBtn, ORD_ALT);
        setupModifierToggle(mRmbBtn, ORD_RMB);
        setupModifierToggle(mMmbBtn, ORD_MMB);

        undoBtn.setOnClickListener(v -> sendBracketedKey(new int[]{ORD_CTRL}, ORD_Z));
        redoBtn.setOnClickListener(v -> sendBracketedKey(new int[]{ORD_CTRL, ORD_SHIFT}, ORD_Z));

        // See OblSettingFragment#initial() for why this needs to be clickable:
        // a non-clickable View doesn't consume ACTION_HOVER_*, which can cause a
        // stylus's ACTION_DOWN (preceded by ACTION_HOVER_EXIT) to be missed.
        dragHandle.setClickable(true);
        dragHandle.setOnTouchListener(this::onDragTouch);

        mCollapseToggle.setOnClickListener(v -> setCollapsed(!isCollapsed()));
        setCollapsed(loadCollapsed());
    }

    public void setOblQuickBarListener(OblQuickBarListener listener) {
        mListener = listener;
    }

    /** Toggles between collapsed and expanded, e.g. from a stylus double-click shortcut. */
    public void toggleCollapsed() {
        setCollapsed(!isCollapsed());
    }

    /**
     * Builds the {@link WindowManager.LayoutParams} this bar should be added
     * with, restoring its last persisted position (or a left-edge default).
     */
    public WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;

        SharedPreferences prefs = getPrefs();
        lp.x = prefs.getInt(PREF_X, dpToPx(16));
        lp.y = prefs.getInt(PREF_Y, dpToPx(80));
        return lp;
    }

    private void setupModifierToggle(ToggleButton button, int ordinal) {
        button.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mSuppressCallback || mListener == null) {
                return;
            }
            if (isChecked) {
                mListener.onModifierOn(new int[]{ordinal});
            } else {
                mListener.onModifierOff(new int[]{ordinal});
            }
        });
    }

    /**
     * Mirrors a native-driven modifier state change onto the matching
     * toggle button without re-triggering {@link OblQuickBarListener}.
     * Uses the same {@code type} codes as {@link OblSettingFragment#SetValue}.
     */
    public void SetValue(int type, int value) {
        ToggleButton button = null;
        switch (type) {
            case 0:
                button = mShiftBtn;
                break;
            case 1:
                button = mCtrlBtn;
                break;
            case 2:
                button = mAltBtn;
                break;
            case 3:
                button = mMmbBtn;
                break;
            case 4:
                button = mRmbBtn;
                break;
        }
        if (button == null) {
            return;
        }
        boolean checked = value == 1;
        if (button.isChecked() != checked) {
            mSuppressCallback = true;
            button.setChecked(checked);
            mSuppressCallback = false;
        }
    }

    /**
     * Sends {@code keyOrdinal} bracketed by the given modifiers, e.g. Ctrl+Z
     * for Undo. Modifiers that are already held (toggled on by the user) are
     * left untouched so this doesn't clear an intentional held state.
     */
    private void sendBracketedKey(int[] modifierOrdinals, int keyOrdinal) {
        if (mListener == null) {
            return;
        }
        List<Integer> toToggle = new ArrayList<>();
        for (int ordinal : modifierOrdinals) {
            if (!isModifierHeld(ordinal)) {
                toToggle.add(ordinal);
            }
        }
        int[] toToggleArr = new int[toToggle.size()];
        for (int i = 0; i < toToggleArr.length; i++) {
            toToggleArr[i] = toToggle.get(i);
        }

        if (toToggleArr.length > 0) {
            mListener.onModifierOn(toToggleArr);
        }
        postDelayed(() -> {
            mListener.onMomentaryKey(new int[]{keyOrdinal});
            if (toToggleArr.length > 0) {
                postDelayed(() -> mListener.onModifierOff(toToggleArr), KEY_RELEASE_DELAY_MS);
            }
        }, toToggleArr.length > 0 ? MODIFIER_SETTLE_DELAY_MS : 0);
    }

    private boolean isModifierHeld(int ordinal) {
        if (ordinal == ORD_CTRL) {
            return mCtrlBtn.isChecked();
        }
        if (ordinal == ORD_SHIFT) {
            return mShiftBtn.isChecked();
        }
        if (ordinal == ORD_ALT) {
            return mAltBtn.isChecked();
        }
        return false;
    }

    private boolean onDragTouch(View view, MotionEvent event) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragStartRawX = event.getRawX();
                mDragStartRawY = event.getRawY();
                mDragStartLpX = lp.x;
                mDragStartLpY = lp.y;
                return true;
            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (event.getRawX() - mDragStartRawX);
                int dy = (int) (event.getRawY() - mDragStartRawY);
                lp.x = clamp(mDragStartLpX + dx, 0, maxX());
                lp.y = clamp(mDragStartLpY + dy, 0, maxY());
                getWindowManager().updateViewLayout(this, lp);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                savePosition(lp.x, lp.y);
                return true;
            default:
                return false;
        }
    }

    private int maxX() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.max(0, screenWidth - getWidth());
    }

    private int maxY() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return Math.max(0, screenHeight - getHeight());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void setCollapsed(boolean collapsed) {
        mButtonContainer.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        mCollapseToggle.setText(collapsed
                ? getResources().getString(R.string.quickbar_expand)
                : getResources().getString(R.string.quickbar_collapse));
        mCollapseToggle.setContentDescription(getResources().getString(collapsed
                ? R.string.quickbar_desc_expand
                : R.string.quickbar_desc_collapse));
        saveCollapsed(collapsed);

        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp != null) {
            getWindowManager().updateViewLayout(this, lp);
        }
    }

    private boolean isCollapsed() {
        return mButtonContainer.getVisibility() != View.VISIBLE;
    }

    private WindowManager getWindowManager() {
        return (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
    }

    private SharedPreferences getPrefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void savePosition(int x, int y) {
        getPrefs().edit().putInt(PREF_X, x).putInt(PREF_Y, y).apply();
    }

    private boolean loadCollapsed() {
        return getPrefs().getBoolean(PREF_COLLAPSED, false);
    }

    private void saveCollapsed(boolean collapsed) {
        getPrefs().edit().putBoolean(PREF_COLLAPSED, collapsed).apply();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
