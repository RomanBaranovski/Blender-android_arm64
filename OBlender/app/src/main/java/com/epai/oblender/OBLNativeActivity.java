package com.epai.oblender;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.FragmentTransaction;
import android.app.NativeActivity;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Xml;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.Manifest;
import android.content.Intent;
import androidx.core.app.ActivityCompat;
import android.os.Environment;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.fragment.app.Fragment;

import com.epai.oblfiles.InstallOBLFiles;
import com.epai.oblender.WindowGLSurfaceView.WindowGLSurfaceViewListener;
import com.epai.oblender.input.GodotEditText;
import com.epai.oblender.input.GodotInputHandler;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.content.ClipboardManager;
import android.content.ClipData;

import org.libsdl.app.SDLActivity;

public class OBLNativeActivity extends NativeActivity
        implements WindowGLSurfaceViewListener,GodotRenderView {
    private final String TAG="OBLNativeActivity";
    static {
        System.loadLibrary("blender");
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    Map<Integer, WindowGLSurfaceView> mWindowFragmentMap = new HashMap<>();

    private OblSettingFragment mOblSettingFragment = null;
    private boolean mBooleanLastOblSettingFragmentVisible=false;
    private OblQuickBar mOblQuickBar = null;
    private OblPieMenu mOblPieMenu = null;

    // Diameter of the radial quick-action menu opened by holding the stylus's
    // side button.
    private static final int PIE_MENU_DIAMETER_DP = 280;
    // How long the stylus button must be held (without releasing) before the
    // pie menu opens.
    private static final long PIE_HOLD_DELAY_MS = 220L;
    // Max gap between two stylus-button clicks to count as a double-click.
    private static final long DOUBLE_CLICK_WINDOW_MS = 300L;
    private static final int DOUBLE_CLICK_SLOP_DP = 24;
    // Gaps between the on/key/off steps of a modifier+key sequence, in case
    // the native side only samples modifier state once per frame.
    private static final long MODIFIER_SETTLE_DELAY_MS = 40L;
    private static final long KEY_RELEASE_DELAY_MS = 40L;

    // Pie menu slices, starting at the top and going clockwise, matching
    // OblPieMenu's LABELS. Multi-key entries are sent as modifier(s)-on,
    // key, modifier(s)-off.
    private static final int[][] PIE_SLICE_KEYS = {
            {OBLButtonID.OBLButtonID_G.ordinal()},                                          // Grab
            {OBLButtonID.OBLButtonID_R.ordinal()},                                          // Rotate
            {OBLButtonID.OBLButtonID_S.ordinal()},                                          // Scale
            {OBLButtonID.OBLButtonID_E.ordinal()},                                          // Extrude
            {OBLButtonID.OBLButtonID_X.ordinal()},                                          // Delete
            {OBLButtonID.OBLButtonID_Ctrl.ordinal(), OBLButtonID.OBLButtonID_R.ordinal()},  // Loop Cut
            {OBLButtonID.OBLButtonID_Ctrl.ordinal(), OBLButtonID.OBLButtonID_B.ordinal()},  // Bevel
            {OBLButtonID.OBLButtonID_I.ordinal()},                                          // Inset
    };

    private final Handler mPenHandler = new Handler(Looper.getMainLooper());
    private boolean mPenButtonDown = false;
    private Runnable mPieOpenRunnable = null;
    private float mPieVisualCenterX = 0f;
    private float mPieVisualCenterY = 0f;
    private int mPieDiameterPx = 0;
    private float mClickSlopPx = 0f;
    private long mLastPenClickTime = 0L;
    private float mLastPenClickX = 0f;
    private float mLastPenClickY = 0f;

    private String joinKeys(int[] keys) {
        ArrayList<String> strings = new ArrayList<>();
        for (int key : keys) {
            strings.add(String.valueOf(key));
        }
        return String.join(",", strings);
    }

    private void sendKeysOn(int[] keys) {
        oblSetValueOn(joinKeys(keys));
    }

    private void sendKeysOff(int[] keys) {
        oblSetValueOff(joinKeys(keys));
    }

    private void sendKey(int[] keys) {
        oblSetValue(joinKeys(keys));
    }

    public String getClipboard(boolean selection){
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard.hasPrimaryClip()){
            ClipData clipData = clipboard.getPrimaryClip();
            ClipData.Item item = clipData.getItemAt(0); // 首个数据项
            String text = item.getText().toString();    // 提取文本
            return text;
        }else{
            return "";
        }
    }

    public void putClipboard(String stringText,boolean selection){
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("label", stringText);
        clipboard.setPrimaryClip(clip);
    }

    public void SetValue(int type,int value){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mOblSettingFragment != null) {
                    mOblSettingFragment.SetValue(type,value);
                }
                if (mOblQuickBar != null) {
                    mOblQuickBar.SetValue(type,value);
                }
            }
        });
    }

    public int GetAsyncKeyState(int type) {
        if (mOblSettingFragment == null) {
            if (type==100){
                return 0;
            }else if (type==101){
                return 0;
            }
            return 0;
        }
        return mOblSettingFragment.GetAsyncKeyState(type);
    }

    public void showWindow(int left, int top, int width, int height, int shape_type,String stringInfo) {
        Log.i("OBLNativeActivity", "打开窗体 1" + " " + shape_type + " " + width);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.i("OBLNativeActivity", "打开窗体 2" + " " + shape_type + " " + width);
                if(shape_type==3000){
                    //  打开键盘
                    showKeyboardApp(stringInfo,left,top,width,height);
                }else if (shape_type==4000){
                    //  关闭键盘
                    hideKeyboardApp();
                }
                else if (shape_type == 1001) {
                    //  左上角窗口
                    if (mOblSettingFragment == null) {

                        mOblSettingFragment = new OblSettingFragment(OBLNativeActivity.this);
                        LayoutParams lp = new LayoutParams();

//                        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;//    允许点击窗口外面穿透


                        lp.flags = LayoutParams.FLAG_NOT_FOCUSABLE;
                        lp.flags|= LayoutParams.FLAG_FULLSCREEN;
                        lp.flags|= LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                        lp.flags|= LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                        lp.flags|= LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                        lp.flags|= LayoutParams.FLAG_NOT_TOUCH_MODAL;

                        lp.gravity = Gravity.LEFT | Gravity.BOTTOM;
                        lp.width = 400;
                        lp.height = 400;
                        lp.x = 80;
                        lp.y = 80;
                        getWindowManager().addView(mOblSettingFragment, lp);

                        mOblSettingFragment.setOBLSettingFragmentListener(new OblSettingFragment.OBLSettingFragmentListener() {
                            @Override
                            public void enterKey(int[] keys) {
                                sendKey(keys);
                            }

                            @Override
                            public void enterKeyOff(int[] keys) {
                                sendKeysOff(keys);
                            }

                            @Override
                            public void enterKeyOn(int[] keys) {
                                sendKeysOn(keys);
                            }

                            @Override
                            public void closeFragment() {
                                mOblSettingFragment.setVisibility(View.INVISIBLE);
                            }
                        });
                        hideToolbar();
                    }
                    if (mOblSettingFragment.getVisibility() != View.VISIBLE) {
                        mOblSettingFragment.setVisibility(View.VISIBLE);
                        hideToolbar();
                    }
                } else {
                    if (width >= 0) {
                        if (mWindowFragmentMap.containsKey(shape_type)) {
                            mWindowFragmentMap.get(shape_type).setVisibility(View.VISIBLE);
                        } else {
                            Log.i("OBLNativeActivity", "打开窗体 3" + " " + shape_type + " " + width);
                            WindowGLSurfaceView windowFragment = new WindowGLSurfaceView(OBLNativeActivity.this.getBaseContext());
                            Log.i("OBLNativeActivity", "打开窗体 3 1" + " " + shape_type + " " + width);
                            windowFragment.setListener(OBLNativeActivity.this);
                            Log.i("OBLNativeActivity", "打开窗体 3 2" + " " + shape_type + " " + width + windowFragment);
                            mWindowFragmentMap.put(shape_type, windowFragment);
                            Log.i("OBLNativeActivity", "打开窗体 3 3" + " " + shape_type + " " + width);
                            LayoutParams lp = new LayoutParams();
                            Log.i("OBLNativeActivity", "打开窗体 3 4" + " " + shape_type + " " + width);
                            lp.type = LayoutParams.TYPE_APPLICATION_PANEL;
                            Log.i("OBLNativeActivity", "打开窗体 3 5" + " " + shape_type + " " + width);
                            lp.flags = LayoutParams.FLAG_NOT_FOCUSABLE |
                                    LayoutParams.FLAG_NOT_TOUCHABLE |
                                    LayoutParams.FLAG_NOT_TOUCH_MODAL |
                                    LayoutParams.FLAG_ALT_FOCUSABLE_IM;
                            //   重要修改 ，将窗体修改成这种 FLAG_ALT_FOCUSABLE_IM ，才能在子窗体中弹出键盘，并且键盘在子窗体之上
                            Log.i("OBLNativeActivity", "打开窗体 3 6" + " " + shape_type + " " + width);
                            getWindowManager().addView(windowFragment, lp);
                            Log.i("OBLNativeActivity", "打开窗体 4" + " " + shape_type + " " + width);
                        }
                    } else {
                        Log.i("OBLNativeActivity", "打开窗体 5" + " " + shape_type + " " + width);
//                    WindowWindow windowFragment = mWindowFragmentMap.get(shape_type);
//                    windowFragment.getDialog().show();
//                    mWindowFragmentMap.get(shape_type).setVisibility(View.VISIBLE);
                        Log.i("OBLNativeActivity", "打开窗体 6" + " " + shape_type + " " + width);
                        if (mWindowFragmentMap.containsKey(shape_type)) {
                            mWindowFragmentMap.get(shape_type).setVisibility(View.INVISIBLE);
                            Log.i("OBLNativeActivity", "打开窗体 7" + " " + shape_type + " " + width);
//                        int lastIndex=mWindowFragmentMap.size()-1;
                            Log.i("OBLNativeActivity", "打开窗体 8" + " " + shape_type + " " + width);
//                        if (mWindowFragmentMap.get(lastIndex).getVisibility()==View.VISIBLE)
                            {
                                Log.i("OBLNativeActivity", "打开窗体 9" + " " + shape_type + " " + width);
//                            WindowGLSurfaceView windowGLSurfaceView=mWindowFragmentMap.get(lastIndex);
//                            WindowManager.LayoutParams params=(WindowManager.LayoutParams)windowGLSurfaceView.getLayoutParams();
//                            params.type=LayoutParams.TYPE_APPLICATION;
//                            getWindowManager().updateViewLayout(windowGLSurfaceView,params);
//                            getWindowManager().removeView(windowGLSurfaceView);
//                            mWindowFragmentMap.remove(lastIndex);
//                            windowGLSurfaceView=null;
                                Log.i("OBLNativeActivity", "打开窗体 10" + " " + shape_type + " " + width);
                            }
                        }
                    }
                }
            }
        });
    }

    public void SetCursorPosition(long x,long y){
        Log.i(TAG,"SetCursorPosition "+x+" "+y);
    }

    @Override
    public void updateSurface(SurfaceHolder holder) {
        updateSurface(holder.getSurface());
    }

    @Override
    public void updateSurfaceDestroyed(SurfaceHolder holder) {
        updateSurfaceDestroyed(holder.getSurface());
    }

    @Override
    public void hideWindow(WindowWindow windowWindow) {
        windowWindow.setVisibility(View.INVISIBLE);
    }

    public native String stringFromJNI();

    public native void initial(String stringPath,String stringPython);

    public native void updateSurface(Surface surface);

    public native void updateSurfaceDestroyed(Surface surface);

    public native void oblSetValue(String stringValue);

    public native void oblSetValueOn(String stringValue);

    public native void oblSetValueOff(String stringValue);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setContentView(R.layout.activity_main);
//        android.app.FragmentTransaction fragmentTransaction=getFragmentManager().beginTransaction();
//        fragmentTransaction.add(fragment,"Fragment");
//        fragmentTransaction.commit();
//        fragment.show(getFilesDir(),"43");

        hideToolbar();

        initialEditText();
        initialQuickBar();
        initialPieMenu();

        // Example of a call to a native method

        AssetManager assetManager=getAssets();
        String strHomePath = getExternalFilesDir("obl").getAbsolutePath()+ File.separator;
        String strConfigPath=getFilesDir().getAbsolutePath()+File.separator;
        Intent intent=getIntent();
        if (intent!=null){
            strHomePath=intent.getStringExtra("HomePath");
            strConfigPath=intent.getStringExtra("ConfigPath");
        }
        initial(strHomePath,strConfigPath);

        //  监听键盘弹出和隐藏
        SoftKeyBoardListener.setListener(this, new SoftKeyBoardListener.OnSoftKeyBoardChangeListener() {
            @Override
            public void keyBoardShow(int height) {
                if (mOblSettingFragment!=null){
                    mBooleanLastOblSettingFragmentVisible=mOblSettingFragment.getVisibility()==View.VISIBLE;
                    if (mBooleanLastOblSettingFragmentVisible){
                        mOblSettingFragment.setVisibility(View.INVISIBLE);
                    }
                }else{
                    mBooleanLastOblSettingFragmentVisible=false;
                }
                if (mOblQuickBar!=null){
                    mOblQuickBar.setVisibility(View.INVISIBLE);
                }
                ScreenUtils.fullScreen(getWindow());
            }

            @Override
            public void keyBoardHide(int height) {
                ScreenUtils.fullScreen(getWindow());
                if (mOblSettingFragment!=null){
                    if (mBooleanLastOblSettingFragmentVisible){
                        mOblSettingFragment.setVisibility(View.VISIBLE);
                    }
                }
                if (mOblQuickBar!=null){
                    mOblQuickBar.setVisibility(View.VISIBLE);
                }
            }
        });

        super.onCreate(savedInstanceState);
    }

    private void hideToolbar() {
        ScreenUtils.fullScreen(getWindow());
    }

    @Override
    protected void onResume() {
        //Hide toolbar
        hideToolbar();

        super.onResume();
        //  恢复运行时资源
    }

    @Override
    protected void onPause() {
        //  保存运行时资源
        super.onPause();
    }


    @Override
    protected void onStart() {
        super.onStart();
    }

    private GodotEditText mGodotEditText=null ;
    private GodotInputHandler inputHandler=null;

    private void initialEditText(){
        if (mGodotEditText==null){
            inputHandler = new GodotInputHandler(this);

            mGodotEditText=new GodotEditText(OBLNativeActivity.this);

            ViewGroup.LayoutParams layoutParams=new LayoutParams();
            layoutParams.height=200;
            layoutParams.width=500;
            mGodotEditText.setLayoutParams(layoutParams);
            mGodotEditText.setBackgroundColor(Color.TRANSPARENT);
            mGodotEditText.setTextColor(Color.valueOf(0.0f,0.0f,1.0f).toArgb());

            LayoutParams lp = new LayoutParams();
            lp.gravity = Gravity.LEFT | Gravity.BOTTOM;
            lp.width = 500;
            lp.height = 200;
            lp.x = 80;
            lp.y = 80;
            getWindowManager().addView(mGodotEditText,lp);

            mGodotEditText.setView(this);

            mGodotEditText.setVisibility(View.GONE);
        }
    }

    private void initialQuickBar() {
        if (mOblQuickBar != null) {
            return;
        }
        mOblQuickBar = new OblQuickBar(this);
        mOblQuickBar.setOblQuickBarListener(new OblQuickBar.OblQuickBarListener() {
            @Override
            public void onModifierOn(int[] ordinals) {
                sendKeysOn(ordinals);
            }

            @Override
            public void onModifierOff(int[] ordinals) {
                sendKeysOff(ordinals);
            }

            @Override
            public void onMomentaryKey(int[] ordinals) {
                sendKey(ordinals);
            }
        });
        getWindowManager().addView(mOblQuickBar, mOblQuickBar.createLayoutParams());
    }

    private void initialPieMenu() {
        if (mOblPieMenu != null) {
            return;
        }
        mOblPieMenu = new OblPieMenu(this);
        float density = getResources().getDisplayMetrics().density;
        mPieDiameterPx = (int) (PIE_MENU_DIAMETER_DP * density);
        mClickSlopPx = DOUBLE_CLICK_SLOP_DP * density;
        WindowManager.LayoutParams lp = mOblPieMenu.createLayoutParams(mPieDiameterPx);
        mOblPieMenu.setVisibility(View.GONE);
        getWindowManager().addView(mOblPieMenu, lp);
    }

    /**
     * Intercepts whole touch gestures started with the stylus's side button
     * held: a sustained hold opens the radial quick-action pie menu (dragging
     * to a wedge and releasing fires that action), while a quick double-click
     * toggles {@link #mOblQuickBar}'s collapsed state. Gestures that don't
     * start with the side button held pass through unchanged.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        boolean stylusButton = (ev.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (stylusButton && mOblPieMenu != null) {
                    mPenButtonDown = true;
                    final float downX = ev.getRawX();
                    final float downY = ev.getRawY();
                    mPieOpenRunnable = () -> openPieMenu(downX, downY);
                    mPenHandler.postDelayed(mPieOpenRunnable, PIE_HOLD_DELAY_MS);
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mPenButtonDown) {
                    if (mOblPieMenu.getVisibility() == View.VISIBLE) {
                        mOblPieMenu.updateSelection(ev.getRawX() - mPieVisualCenterX, ev.getRawY() - mPieVisualCenterY);
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mPenButtonDown) {
                    mPenHandler.removeCallbacks(mPieOpenRunnable);
                    mPenButtonDown = false;
                    if (mOblPieMenu.getVisibility() == View.VISIBLE) {
                        int slice = mOblPieMenu.getSelectedIndex();
                        closePieMenu();
                        if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                            firePieAction(slice);
                        }
                    } else if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                        handleStylusClick(ev.getEventTime(), ev.getRawX(), ev.getRawY());
                    }
                    return true;
                }
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void handleStylusClick(long eventTime, float x, float y) {
        boolean isDoubleClick = mLastPenClickTime != 0L
                && (eventTime - mLastPenClickTime) <= DOUBLE_CLICK_WINDOW_MS
                && Math.hypot(x - mLastPenClickX, y - mLastPenClickY) <= mClickSlopPx;
        if (isDoubleClick) {
            mLastPenClickTime = 0L;
            if (mOblQuickBar != null) {
                mOblQuickBar.toggleCollapsed();
            }
        } else {
            mLastPenClickTime = eventTime;
            mLastPenClickX = x;
            mLastPenClickY = y;
        }
    }

    private void openPieMenu(float centerX, float centerY) {
        if (mOblPieMenu == null) {
            return;
        }
        mOblPieMenu.reset();

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float x = clamp(centerX - mPieDiameterPx / 2f, 0, metrics.widthPixels - mPieDiameterPx);
        float y = clamp(centerY - mPieDiameterPx / 2f, 0, metrics.heightPixels - mPieDiameterPx);
        mPieVisualCenterX = x + mPieDiameterPx / 2f;
        mPieVisualCenterY = y + mPieDiameterPx / 2f;

        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) mOblPieMenu.getLayoutParams();
        lp.x = (int) x;
        lp.y = (int) y;
        getWindowManager().updateViewLayout(mOblPieMenu, lp);
        mOblPieMenu.setVisibility(View.VISIBLE);
    }

    private void closePieMenu() {
        if (mOblPieMenu != null) {
            mOblPieMenu.setVisibility(View.GONE);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void firePieAction(int slice) {
        if (slice < 0 || slice >= PIE_SLICE_KEYS.length) {
            return;
        }
        int[] keys = PIE_SLICE_KEYS[slice];
        if (keys.length == 1) {
            sendKey(keys);
            return;
        }
        int[] modifiers = Arrays.copyOf(keys, keys.length - 1);
        int keyOrdinal = keys[keys.length - 1];
        sendKeysOn(modifiers);
        mPenHandler.postDelayed(() -> {
            sendKey(new int[]{keyOrdinal});
            mPenHandler.postDelayed(() -> sendKeysOff(modifiers), KEY_RELEASE_DELAY_MS);
        }, MODIFIER_SETTLE_DELAY_MS);
    }

    public void showKeyboardApp(String p_existing_text, int p_type, int p_max_input_length, int p_cursor_start, int p_cursor_end) {
        Log.i(TAG,"showKeyboardApp 1 "+p_existing_text+" "+p_type+" "+p_max_input_length+" "+p_cursor_start+" "+p_cursor_start);
        if (mGodotEditText != null) {
            mGodotEditText.showKeyboard(p_existing_text, GodotEditText.VirtualKeyboardType.values()[p_type], p_max_input_length, 0, p_existing_text.length());
        }
        Log.i(TAG,"showKeyboardApp 2");

        InputMethodManager inputMgr = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMgr.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    public void hideKeyboardApp() {
        if (mGodotEditText != null)
            Log.i(TAG,"showKeyboardApp 3");
            mGodotEditText.hideKeyboard();
        Log.i(TAG,"showKeyboardApp 4");
    }

    @Override
    public View getView() {
        return getWindow().getDecorView();
    }

    @Override
    public void initInputDevices() {

    }

    @Override
    public void startRenderer() {

    }

    @Override
    public void onActivityPaused() {

    }

    @Override
    public void onActivityStopped() {

    }

    @Override
    public void onActivityResumed() {

    }

    @Override
    public void onActivityStarted() {

    }

    @Override
    public GodotInputHandler getInputHandler() {
        return inputHandler;
    }

//    @Override
//    public void configurePointerIcon(int pointerType, String imagePath, float hotSpotX, float hotSpotY) {
//
//    }
//
//    @Override
//    public void setPointerIcon(int pointerType) {
//
//    }
//
//    @Override
//    public boolean canCapturePointer() {
//        return false;
//    }
}
