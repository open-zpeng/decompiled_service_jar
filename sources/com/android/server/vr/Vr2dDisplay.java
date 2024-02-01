package com.android.server.vr;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Handler;
import android.os.RemoteException;
import android.service.vr.IPersistentVrStateCallbacks;
import android.service.vr.IVrManager;
import android.util.Log;
import android.view.Surface;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

/* loaded from: classes2.dex */
class Vr2dDisplay {
    private static final boolean DEBUG = false;
    private static final String DEBUG_ACTION_SET_MODE = "com.android.server.vr.Vr2dDisplay.SET_MODE";
    private static final String DEBUG_ACTION_SET_SURFACE = "com.android.server.vr.Vr2dDisplay.SET_SURFACE";
    private static final String DEBUG_EXTRA_MODE_ON = "com.android.server.vr.Vr2dDisplay.EXTRA_MODE_ON";
    private static final String DEBUG_EXTRA_SURFACE = "com.android.server.vr.Vr2dDisplay.EXTRA_SURFACE";
    public static final int DEFAULT_VIRTUAL_DISPLAY_DPI = 320;
    public static final int DEFAULT_VIRTUAL_DISPLAY_HEIGHT = 1800;
    public static final int DEFAULT_VIRTUAL_DISPLAY_WIDTH = 1400;
    private static final String DISPLAY_NAME = "VR 2D Display";
    public static final int MIN_VR_DISPLAY_DPI = 1;
    public static final int MIN_VR_DISPLAY_HEIGHT = 1;
    public static final int MIN_VR_DISPLAY_WIDTH = 1;
    private static final int STOP_VIRTUAL_DISPLAY_DELAY_MILLIS = 2000;
    private static final String TAG = "Vr2dDisplay";
    private static final String UNIQUE_DISPLAY_ID = "277f1a09-b88d-4d1e-8716-796f114d080b";
    private final ActivityManagerInternal mActivityManagerInternal;
    private final DisplayManager mDisplayManager;
    private ImageReader mImageReader;
    private boolean mIsPersistentVrModeEnabled;
    private boolean mIsVrModeOverrideEnabled;
    private Runnable mStopVDRunnable;
    private Surface mSurface;
    private VirtualDisplay mVirtualDisplay;
    private final IVrManager mVrManager;
    private final WindowManagerInternal mWindowManagerInternal;
    private final Object mVdLock = new Object();
    private final Handler mHandler = new Handler();
    private final IPersistentVrStateCallbacks mVrStateCallbacks = new IPersistentVrStateCallbacks.Stub() { // from class: com.android.server.vr.Vr2dDisplay.1
        public void onPersistentVrStateChanged(boolean enabled) {
            if (enabled != Vr2dDisplay.this.mIsPersistentVrModeEnabled) {
                Vr2dDisplay.this.mIsPersistentVrModeEnabled = enabled;
                Vr2dDisplay.this.updateVirtualDisplay();
            }
        }
    };
    private boolean mIsVirtualDisplayAllowed = true;
    private boolean mBootsToVr = false;
    private int mVirtualDisplayWidth = DEFAULT_VIRTUAL_DISPLAY_WIDTH;
    private int mVirtualDisplayHeight = 1800;
    private int mVirtualDisplayDpi = DEFAULT_VIRTUAL_DISPLAY_DPI;

    public Vr2dDisplay(DisplayManager displayManager, ActivityManagerInternal activityManagerInternal, WindowManagerInternal windowManagerInternal, IVrManager vrManager) {
        this.mDisplayManager = displayManager;
        this.mActivityManagerInternal = activityManagerInternal;
        this.mWindowManagerInternal = windowManagerInternal;
        this.mVrManager = vrManager;
    }

    public void init(Context context, boolean bootsToVr) {
        startVrModeListener();
        startDebugOnlyBroadcastReceiver(context);
        this.mBootsToVr = bootsToVr;
        if (this.mBootsToVr) {
            updateVirtualDisplay();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateVirtualDisplay() {
        if (shouldRunVirtualDisplay()) {
            Log.i(TAG, "Attempting to start virtual display");
            startVirtualDisplay();
            return;
        }
        stopVirtualDisplay();
    }

    private void startDebugOnlyBroadcastReceiver(Context context) {
    }

    private void startVrModeListener() {
        IVrManager iVrManager = this.mVrManager;
        if (iVrManager != null) {
            try {
                iVrManager.registerPersistentVrStateListener(this.mVrStateCallbacks);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not register VR State listener.", e);
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:13:0x0073 A[Catch: all -> 0x00a5, TryCatch #0 {, blocks: (B:4:0x0003, B:9:0x0018, B:11:0x006c, B:13:0x0073, B:17:0x0080, B:20:0x0086, B:22:0x008a, B:23:0x00a0, B:24:0x00a3, B:14:0x0076, B:16:0x007d, B:10:0x0046), top: B:29:0x0003 }] */
    /* JADX WARN: Removed duplicated region for block: B:14:0x0076 A[Catch: all -> 0x00a5, TryCatch #0 {, blocks: (B:4:0x0003, B:9:0x0018, B:11:0x006c, B:13:0x0073, B:17:0x0080, B:20:0x0086, B:22:0x008a, B:23:0x00a0, B:24:0x00a3, B:14:0x0076, B:16:0x007d, B:10:0x0046), top: B:29:0x0003 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void setVirtualDisplayProperties(android.app.Vr2dDisplayProperties r10) {
        /*
            r9 = this;
            java.lang.Object r0 = r9.mVdLock
            monitor-enter(r0)
            int r1 = r10.getWidth()     // Catch: java.lang.Throwable -> La5
            int r2 = r10.getHeight()     // Catch: java.lang.Throwable -> La5
            int r3 = r10.getDpi()     // Catch: java.lang.Throwable -> La5
            r4 = 0
            r5 = 1
            if (r1 < r5) goto L46
            if (r2 < r5) goto L46
            if (r3 >= r5) goto L18
            goto L46
        L18:
            java.lang.String r6 = "Vr2dDisplay"
            java.lang.StringBuilder r7 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> La5
            r7.<init>()     // Catch: java.lang.Throwable -> La5
            java.lang.String r8 = "Setting width/height/dpi to "
            r7.append(r8)     // Catch: java.lang.Throwable -> La5
            r7.append(r1)     // Catch: java.lang.Throwable -> La5
            java.lang.String r8 = ","
            r7.append(r8)     // Catch: java.lang.Throwable -> La5
            r7.append(r2)     // Catch: java.lang.Throwable -> La5
            java.lang.String r8 = ","
            r7.append(r8)     // Catch: java.lang.Throwable -> La5
            r7.append(r3)     // Catch: java.lang.Throwable -> La5
            java.lang.String r7 = r7.toString()     // Catch: java.lang.Throwable -> La5
            android.util.Log.i(r6, r7)     // Catch: java.lang.Throwable -> La5
            r9.mVirtualDisplayWidth = r1     // Catch: java.lang.Throwable -> La5
            r9.mVirtualDisplayHeight = r2     // Catch: java.lang.Throwable -> La5
            r9.mVirtualDisplayDpi = r3     // Catch: java.lang.Throwable -> La5
            r4 = 1
            goto L6c
        L46:
            java.lang.String r6 = "Vr2dDisplay"
            java.lang.StringBuilder r7 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> La5
            r7.<init>()     // Catch: java.lang.Throwable -> La5
            java.lang.String r8 = "Ignoring Width/Height/Dpi values of "
            r7.append(r8)     // Catch: java.lang.Throwable -> La5
            r7.append(r1)     // Catch: java.lang.Throwable -> La5
            java.lang.String r8 = ","
            r7.append(r8)     // Catch: java.lang.Throwable -> La5
            r7.append(r2)     // Catch: java.lang.Throwable -> La5
            java.lang.String r8 = ","
            r7.append(r8)     // Catch: java.lang.Throwable -> La5
            r7.append(r3)     // Catch: java.lang.Throwable -> La5
            java.lang.String r7 = r7.toString()     // Catch: java.lang.Throwable -> La5
            android.util.Log.i(r6, r7)     // Catch: java.lang.Throwable -> La5
        L6c:
            int r6 = r10.getAddedFlags()     // Catch: java.lang.Throwable -> La5
            r6 = r6 & r5
            if (r6 != r5) goto L76
            r9.mIsVirtualDisplayAllowed = r5     // Catch: java.lang.Throwable -> La5
            goto L80
        L76:
            int r6 = r10.getRemovedFlags()     // Catch: java.lang.Throwable -> La5
            r6 = r6 & r5
            if (r6 != r5) goto L80
            r5 = 0
            r9.mIsVirtualDisplayAllowed = r5     // Catch: java.lang.Throwable -> La5
        L80:
            android.hardware.display.VirtualDisplay r5 = r9.mVirtualDisplay     // Catch: java.lang.Throwable -> La5
            if (r5 == 0) goto La0
            if (r4 == 0) goto La0
            boolean r5 = r9.mIsVirtualDisplayAllowed     // Catch: java.lang.Throwable -> La5
            if (r5 == 0) goto La0
            android.hardware.display.VirtualDisplay r5 = r9.mVirtualDisplay     // Catch: java.lang.Throwable -> La5
            int r6 = r9.mVirtualDisplayWidth     // Catch: java.lang.Throwable -> La5
            int r7 = r9.mVirtualDisplayHeight     // Catch: java.lang.Throwable -> La5
            int r8 = r9.mVirtualDisplayDpi     // Catch: java.lang.Throwable -> La5
            r5.resize(r6, r7, r8)     // Catch: java.lang.Throwable -> La5
            android.media.ImageReader r5 = r9.mImageReader     // Catch: java.lang.Throwable -> La5
            r6 = 0
            r9.mImageReader = r6     // Catch: java.lang.Throwable -> La5
            r9.startImageReader()     // Catch: java.lang.Throwable -> La5
            r5.close()     // Catch: java.lang.Throwable -> La5
        La0:
            r9.updateVirtualDisplay()     // Catch: java.lang.Throwable -> La5
            monitor-exit(r0)     // Catch: java.lang.Throwable -> La5
            return
        La5:
            r1 = move-exception
            monitor-exit(r0)     // Catch: java.lang.Throwable -> La5
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.vr.Vr2dDisplay.setVirtualDisplayProperties(android.app.Vr2dDisplayProperties):void");
    }

    public int getVirtualDisplayId() {
        synchronized (this.mVdLock) {
            if (this.mVirtualDisplay != null) {
                int virtualDisplayId = this.mVirtualDisplay.getDisplay().getDisplayId();
                return virtualDisplayId;
            }
            return -1;
        }
    }

    private void startVirtualDisplay() {
        if (this.mDisplayManager == null) {
            Log.w(TAG, "Cannot create virtual display because mDisplayManager == null");
            return;
        }
        synchronized (this.mVdLock) {
            if (this.mVirtualDisplay != null) {
                Log.i(TAG, "VD already exists, ignoring request");
                return;
            }
            int flags = 64 | 128;
            this.mVirtualDisplay = this.mDisplayManager.createVirtualDisplay(null, DISPLAY_NAME, this.mVirtualDisplayWidth, this.mVirtualDisplayHeight, this.mVirtualDisplayDpi, null, flags | 1 | 8 | 256 | 4, null, null, UNIQUE_DISPLAY_ID);
            if (this.mVirtualDisplay != null) {
                updateDisplayId(this.mVirtualDisplay.getDisplay().getDisplayId());
                startImageReader();
                Log.i(TAG, "VD created: " + this.mVirtualDisplay);
                return;
            }
            Log.w(TAG, "Virtual display id is null after createVirtualDisplay");
            updateDisplayId(-1);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateDisplayId(int displayId) {
        ((ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class)).setVr2dDisplayId(displayId);
        this.mWindowManagerInternal.setVr2dDisplayId(displayId);
    }

    private void stopVirtualDisplay() {
        if (this.mStopVDRunnable == null) {
            this.mStopVDRunnable = new Runnable() { // from class: com.android.server.vr.Vr2dDisplay.3
                @Override // java.lang.Runnable
                public void run() {
                    if (Vr2dDisplay.this.shouldRunVirtualDisplay()) {
                        Log.i(Vr2dDisplay.TAG, "Virtual Display destruction stopped: VrMode is back on.");
                        return;
                    }
                    Log.i(Vr2dDisplay.TAG, "Stopping Virtual Display");
                    synchronized (Vr2dDisplay.this.mVdLock) {
                        Vr2dDisplay.this.updateDisplayId(-1);
                        Vr2dDisplay.this.setSurfaceLocked(null);
                        if (Vr2dDisplay.this.mVirtualDisplay != null) {
                            Vr2dDisplay.this.mVirtualDisplay.release();
                            Vr2dDisplay.this.mVirtualDisplay = null;
                        }
                        Vr2dDisplay.this.stopImageReader();
                    }
                }
            };
        }
        this.mHandler.removeCallbacks(this.mStopVDRunnable);
        this.mHandler.postDelayed(this.mStopVDRunnable, 2000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setSurfaceLocked(Surface surface) {
        if (this.mSurface != surface) {
            if (surface == null || surface.isValid()) {
                Log.i(TAG, "Setting the new surface from " + this.mSurface + " to " + surface);
                VirtualDisplay virtualDisplay = this.mVirtualDisplay;
                if (virtualDisplay != null) {
                    virtualDisplay.setSurface(surface);
                }
                Surface surface2 = this.mSurface;
                if (surface2 != null) {
                    surface2.release();
                }
                this.mSurface = surface;
            }
        }
    }

    private void startImageReader() {
        if (this.mImageReader == null) {
            this.mImageReader = ImageReader.newInstance(this.mVirtualDisplayWidth, this.mVirtualDisplayHeight, 1, 2);
            Log.i(TAG, "VD startImageReader: res = " + this.mVirtualDisplayWidth + "X" + this.mVirtualDisplayHeight + ", dpi = " + this.mVirtualDisplayDpi);
        }
        synchronized (this.mVdLock) {
            setSurfaceLocked(this.mImageReader.getSurface());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopImageReader() {
        ImageReader imageReader = this.mImageReader;
        if (imageReader != null) {
            imageReader.close();
            this.mImageReader = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean shouldRunVirtualDisplay() {
        return this.mIsVirtualDisplayAllowed && (this.mBootsToVr || this.mIsPersistentVrModeEnabled || this.mIsVrModeOverrideEnabled);
    }
}
