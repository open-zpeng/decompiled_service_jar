package com.android.server.policy;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.view.DisplayInfo;
import android.view.IDisplayFoldListener;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class DisplayFoldController {
    private static final String TAG = "DisplayFoldController";
    private final int mDisplayId;
    private final DisplayManagerInternal mDisplayManagerInternal;
    private String mFocusedApp;
    private Boolean mFolded;
    private final Rect mFoldedArea;
    private final Handler mHandler;
    private final WindowManagerInternal mWindowManagerInternal;
    private Rect mOverrideFoldedArea = new Rect();
    private final DisplayInfo mNonOverrideDisplayInfo = new DisplayInfo();
    private final RemoteCallbackList<IDisplayFoldListener> mListeners = new RemoteCallbackList<>();
    private final DisplayFoldDurationLogger mDurationLogger = new DisplayFoldDurationLogger();

    DisplayFoldController(WindowManagerInternal windowManagerInternal, DisplayManagerInternal displayManagerInternal, int displayId, Rect foldedArea, Handler handler) {
        this.mWindowManagerInternal = windowManagerInternal;
        this.mDisplayManagerInternal = displayManagerInternal;
        this.mDisplayId = displayId;
        this.mFoldedArea = new Rect(foldedArea);
        this.mHandler = handler;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishedGoingToSleep() {
        this.mDurationLogger.onFinishedGoingToSleep();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void finishedWakingUp() {
        this.mDurationLogger.onFinishedWakingUp(this.mFolded);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void requestDeviceFolded(final boolean folded) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.policy.-$$Lambda$DisplayFoldController$NTSuhIo_Cno_Oi2ZijeIvJCcvfc
            @Override // java.lang.Runnable
            public final void run() {
                DisplayFoldController.this.lambda$requestDeviceFolded$0$DisplayFoldController(folded);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: setDeviceFolded */
    public void lambda$requestDeviceFolded$0$DisplayFoldController(boolean folded) {
        Rect foldedArea;
        Boolean bool = this.mFolded;
        if (bool != null && bool.booleanValue() == folded) {
            return;
        }
        if (folded) {
            if (!this.mOverrideFoldedArea.isEmpty()) {
                foldedArea = this.mOverrideFoldedArea;
            } else {
                Rect foldedArea2 = this.mFoldedArea;
                if (!foldedArea2.isEmpty()) {
                    foldedArea = this.mFoldedArea;
                } else {
                    return;
                }
            }
            this.mDisplayManagerInternal.getNonOverrideDisplayInfo(this.mDisplayId, this.mNonOverrideDisplayInfo);
            int dx = ((this.mNonOverrideDisplayInfo.logicalWidth - foldedArea.width()) / 2) - foldedArea.left;
            int dy = ((this.mNonOverrideDisplayInfo.logicalHeight - foldedArea.height()) / 2) - foldedArea.top;
            this.mDisplayManagerInternal.setDisplayScalingDisabled(this.mDisplayId, true);
            this.mWindowManagerInternal.setForcedDisplaySize(this.mDisplayId, foldedArea.width(), foldedArea.height());
            this.mDisplayManagerInternal.setDisplayOffsets(this.mDisplayId, -dx, -dy);
        } else {
            this.mDisplayManagerInternal.setDisplayScalingDisabled(this.mDisplayId, false);
            this.mWindowManagerInternal.clearForcedDisplaySize(this.mDisplayId);
            this.mDisplayManagerInternal.setDisplayOffsets(this.mDisplayId, 0, 0);
        }
        this.mDurationLogger.setDeviceFolded(folded);
        this.mDurationLogger.logFocusedAppWithFoldState(folded, this.mFocusedApp);
        this.mFolded = Boolean.valueOf(folded);
        int n = this.mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                this.mListeners.getBroadcastItem(i).onDisplayFoldChanged(this.mDisplayId, folded);
            } catch (RemoteException e) {
            }
        }
        this.mListeners.finishBroadcast();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerDisplayFoldListener(final IDisplayFoldListener listener) {
        this.mListeners.register(listener);
        if (this.mFolded == null) {
            return;
        }
        this.mHandler.post(new Runnable() { // from class: com.android.server.policy.-$$Lambda$DisplayFoldController$aUVA2gXih47E319JuwXnHTqEGHI
            @Override // java.lang.Runnable
            public final void run() {
                DisplayFoldController.this.lambda$registerDisplayFoldListener$1$DisplayFoldController(listener);
            }
        });
    }

    public /* synthetic */ void lambda$registerDisplayFoldListener$1$DisplayFoldController(IDisplayFoldListener listener) {
        try {
            listener.onDisplayFoldChanged(this.mDisplayId, this.mFolded.booleanValue());
        } catch (RemoteException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterDisplayFoldListener(IDisplayFoldListener listener) {
        this.mListeners.unregister(listener);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOverrideFoldedArea(Rect area) {
        this.mOverrideFoldedArea.set(area);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getFoldedArea() {
        if (!this.mOverrideFoldedArea.isEmpty()) {
            return this.mOverrideFoldedArea;
        }
        return this.mFoldedArea;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static DisplayFoldController createWithProxSensor(Context context, int displayId) {
        SensorManager sensorManager = (SensorManager) context.getSystemService(SensorManager.class);
        Sensor proxSensor = sensorManager.getDefaultSensor(8);
        if (proxSensor == null) {
            return null;
        }
        DisplayFoldController result = create(context, displayId);
        sensorManager.registerListener(new SensorEventListener() { // from class: com.android.server.policy.DisplayFoldController.1
            @Override // android.hardware.SensorEventListener
            public void onSensorChanged(SensorEvent event) {
                DisplayFoldController.this.requestDeviceFolded(event.values[0] < 1.0f);
            }

            @Override // android.hardware.SensorEventListener
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        }, proxSensor, 3);
        return result;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onDefaultDisplayFocusChanged(String pkg) {
        this.mFocusedApp = pkg;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static DisplayFoldController create(Context context, int displayId) {
        Rect foldedArea;
        DisplayManagerInternal displayService = (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);
        String configFoldedArea = context.getResources().getString(17039736);
        if (configFoldedArea == null || configFoldedArea.isEmpty()) {
            foldedArea = new Rect();
        } else {
            foldedArea = Rect.unflattenFromString(configFoldedArea);
        }
        return new DisplayFoldController((WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class), displayService, displayId, foldedArea, DisplayThread.getHandler());
    }
}
