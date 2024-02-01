package com.android.server.display;

import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManagerInternal;
import android.os.SystemProperties;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import com.android.server.LocalServices;
import java.io.PrintWriter;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class XpColorFade {
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.xp.colorfade", false);
    private static final String TAG = "XpColorFade";
    private final Context mContext;
    private final String mDeviceName;
    private int mDisplayHeight;
    private final int mDisplayId;
    private int mDisplayLayerStack;
    private int mDisplayWidth;
    private WindowManager.LayoutParams mLayoutParams;
    private View mView;
    private final WindowManager mWindowManager;
    private boolean mIsViewVisible = true;
    private int mAction = -1;
    private boolean mPrepared = false;
    private final PowerManagerInternal mPowerManagerInternal = (PowerManagerInternal) LocalServices.getService(PowerManagerInternal.class);

    public XpColorFade(Context context, String deviceName, int displayId) {
        this.mDisplayId = displayId;
        this.mDeviceName = deviceName;
        this.mContext = context;
        this.mWindowManager = (WindowManager) context.getSystemService("window");
        DisplayInfo displayInfo = ((DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class)).getDisplayInfo(this.mDisplayId);
        this.mDisplayLayerStack = displayInfo.layerStack;
        this.mDisplayWidth = displayInfo.getNaturalWidth();
        this.mDisplayHeight = displayInfo.getNaturalHeight();
        if (PowerManagerInternal.IS_HAS_PASSENGER) {
            this.mDisplayWidth >>= 1;
        }
        initLayoutParams();
    }

    private void initLayoutParams() {
        this.mLayoutParams = new WindowManager.LayoutParams();
        WindowManager.LayoutParams layoutParams = this.mLayoutParams;
        layoutParams.width = this.mDisplayWidth;
        layoutParams.height = this.mDisplayHeight;
        layoutParams.gravity = 51;
        layoutParams.x = 0;
        layoutParams.y = 0;
        layoutParams.displayId = this.mDisplayId;
        layoutParams.systemUiVisibility = 512;
        if (PowerManagerInternal.IS_HAS_PASSENGER) {
            this.mLayoutParams.systemUiVisibility &= -513;
            if ("xp_mt_psg".startsWith(this.mDeviceName)) {
                WindowManager.LayoutParams layoutParams2 = this.mLayoutParams;
                layoutParams2.x = this.mDisplayWidth;
                layoutParams2.y = 0;
            }
        }
        WindowManager.LayoutParams layoutParams3 = this.mLayoutParams;
        layoutParams3.type = 2042;
        layoutParams3.flags = 1032;
    }

    public void show() {
        if (DEBUG) {
            Slog.d(TAG, "show, device: " + this.mDeviceName + ", prepared: " + this.mPrepared);
        }
        if (this.mPrepared) {
            return;
        }
        this.mPrepared = true;
        createSurface();
    }

    public void dismiss(boolean isUserActivity) {
        if (DEBUG) {
            Slog.d(TAG, "dismiss, isUserActivity: " + isUserActivity + ", mPrepared: " + this.mPrepared + ", device: " + this.mDeviceName);
        }
        if (this.mPrepared) {
            if (isUserActivity) {
                if (this.mIsViewVisible) {
                    if (DEBUG) {
                        Slog.d(TAG, "View is Invisible");
                    }
                    setViewVisible(this.mView, 0.0f);
                    this.mIsViewVisible = false;
                    return;
                }
                return;
            }
            this.mAction = -1;
            destroySurface();
            this.mPrepared = false;
        }
    }

    private void createSurface() {
        if (this.mView == null) {
            this.mView = new View(this.mContext);
            this.mView.setBackgroundColor(-16777216);
            this.mView.setOnTouchListener(new View.OnTouchListener() { // from class: com.android.server.display.-$$Lambda$XpColorFade$n8vTBnDbw_VPdbs9CjnBBF4I7eQ
                @Override // android.view.View.OnTouchListener
                public final boolean onTouch(View view, MotionEvent motionEvent) {
                    return XpColorFade.this.lambda$createSurface$0$XpColorFade(view, motionEvent);
                }
            });
        }
        if (!this.mIsViewVisible) {
            this.mLayoutParams.alpha = 1.0f;
            this.mIsViewVisible = true;
        }
        try {
            if (DEBUG) {
                Slog.d(TAG, "addView");
            }
            this.mWindowManager.addView(this.mView, this.mLayoutParams);
        } catch (Exception e) {
            Slog.w(TAG, "addView exception: " + e.getMessage());
        }
    }

    public /* synthetic */ boolean lambda$createSurface$0$XpColorFade(View v, MotionEvent event) {
        if (DEBUG) {
            Slog.d(TAG, "mDeviceName: " + this.mDeviceName + ", action: " + event.getAction() + ", event.getDevice: " + event.getDeviceName());
        }
        if (this.mDeviceName.equals(event.getDeviceName())) {
            int action = event.getAction();
            if (action == this.mAction) {
                return true;
            }
            this.mAction = action;
            int action2 = event.getAction();
            if (action2 == 0) {
                this.mPowerManagerInternal.userSetBackLightOn(this.mDeviceName, 2);
            } else if (action2 == 1 || action2 == 3) {
                boolean isScreenOn = this.mPowerManagerInternal.isScreenOn(this.mDeviceName);
                if (DEBUG) {
                    Slog.d(TAG, "isScreenOn: " + isScreenOn);
                }
                if (!isScreenOn) {
                    return true;
                }
                dismiss(false);
            }
            return true;
        }
        return false;
    }

    private void setViewVisible(View view, float alpha) {
        if (view != null) {
            WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) view.getLayoutParams();
            layoutParams.alpha = alpha;
            if (DEBUG) {
                Slog.d(TAG, "updateView, alpha: " + alpha);
            }
            try {
                this.mWindowManager.updateViewLayout(view, layoutParams);
            } catch (Exception ex) {
                Slog.w(TAG, "updateView exception: " + ex.getMessage());
            }
        }
    }

    private void destroySurface() {
        View view = this.mView;
        if (view != null) {
            try {
                this.mWindowManager.removeViewImmediate(view);
            } catch (Exception e) {
                Slog.w(TAG, "removeView exception: " + e.getMessage());
            }
        }
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Xp Color Fade State:");
        pw.println("  mPrepared=" + this.mPrepared);
        pw.println("  mDeviceName=" + this.mDeviceName);
        pw.println("  mDisplayLayerStack=" + this.mDisplayLayerStack);
        pw.println("  mDisplayWidth=" + this.mDisplayWidth);
        pw.println("  mDisplayHeight=" + this.mDisplayHeight);
    }
}
