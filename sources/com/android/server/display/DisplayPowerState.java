package com.android.server.display;

import android.content.Context;
import android.os.Handler;
import android.os.Trace;
import android.util.FloatProperty;
import android.util.IntProperty;
import android.util.Slog;
import android.view.Choreographer;
import android.view.Display;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class DisplayPowerState {
    private static final boolean DEBUG_POWER = true;
    private static final String TAG = "DisplayPowerState";
    private final DisplayBlanker mBlanker;
    private Runnable mCleanListener;
    private final ColorFade mColorFade;
    private boolean mColorFadeDrawPending;
    private float mColorFadeLevel;
    private boolean mColorFadePrepared;
    private boolean mColorFadeReady;
    private int mScreenBrightness;
    private boolean mScreenReady;
    private int mScreenState;
    private boolean mScreenUpdatePending;
    private static boolean DEBUG = false;
    private static String COUNTER_COLOR_FADE = "ColorFadeLevel";
    public static final FloatProperty<DisplayPowerState> COLOR_FADE_LEVEL = new FloatProperty<DisplayPowerState>("electronBeamLevel") { // from class: com.android.server.display.DisplayPowerState.1
        @Override // android.util.FloatProperty
        public void setValue(DisplayPowerState object, float value) {
            object.setColorFadeLevel(value);
        }

        @Override // android.util.Property
        public Float get(DisplayPowerState object) {
            return Float.valueOf(object.getColorFadeLevel());
        }
    };
    public static final IntProperty<DisplayPowerState> SCREEN_BRIGHTNESS = new IntProperty<DisplayPowerState>("screenBrightness") { // from class: com.android.server.display.DisplayPowerState.2
        @Override // android.util.IntProperty
        public void setValue(DisplayPowerState object, int value) {
            object.setScreenBrightness(value);
        }

        @Override // android.util.Property
        public Integer get(DisplayPowerState object) {
            return Integer.valueOf(object.getScreenBrightness());
        }
    };
    private final Runnable mScreenUpdateRunnable = new Runnable() { // from class: com.android.server.display.DisplayPowerState.3
        @Override // java.lang.Runnable
        public void run() {
            int i = 0;
            DisplayPowerState.this.mScreenUpdatePending = false;
            if (DisplayPowerState.this.mScreenState != 1 && DisplayPowerState.this.mColorFadeLevel > 0.0f) {
                i = DisplayPowerState.this.mScreenBrightness;
            }
            int brightness = i;
            if (DisplayPowerState.this.mPhotonicModulator.setState(DisplayPowerState.this.mScreenState, brightness)) {
                if (DisplayPowerState.DEBUG) {
                    Slog.i(DisplayPowerState.TAG, "Screen ready");
                }
                DisplayPowerState.this.mScreenReady = true;
                DisplayPowerState.this.invokeCleanListenerIfNeeded();
            } else if (DisplayPowerState.DEBUG) {
                Slog.i(DisplayPowerState.TAG, "Screen not ready");
            }
        }
    };
    private final Runnable mColorFadeDrawRunnable = new Runnable() { // from class: com.android.server.display.DisplayPowerState.4
        @Override // java.lang.Runnable
        public void run() {
            DisplayPowerState.this.mColorFadeDrawPending = false;
            if (DisplayPowerState.this.mColorFadePrepared) {
                DisplayPowerState.this.mColorFade.draw(DisplayPowerState.this.mColorFadeLevel);
                Trace.traceCounter(131072L, DisplayPowerState.COUNTER_COLOR_FADE, Math.round(DisplayPowerState.this.mColorFadeLevel * 100.0f));
            }
            DisplayPowerState.this.mColorFadeReady = true;
            DisplayPowerState.this.invokeCleanListenerIfNeeded();
        }
    };
    private final Handler mHandler = new Handler(true);
    private final Choreographer mChoreographer = Choreographer.getInstance();
    private final PhotonicModulator mPhotonicModulator = new PhotonicModulator();

    public DisplayPowerState(DisplayBlanker blanker, ColorFade colorFade) {
        this.mBlanker = blanker;
        this.mColorFade = colorFade;
        this.mPhotonicModulator.start();
        this.mScreenState = 2;
        this.mScreenBrightness = 255;
        scheduleScreenUpdate();
        this.mColorFadePrepared = false;
        this.mColorFadeLevel = 1.0f;
        this.mColorFadeReady = true;
    }

    public void setScreenState(int state) {
        if (this.mScreenState != state) {
            boolean z = DEBUG;
            Slog.i(TAG, "setScreenState: state=" + state);
            this.mScreenState = state;
            this.mScreenReady = false;
            scheduleScreenUpdate();
        }
    }

    public int getScreenState() {
        return this.mScreenState;
    }

    public void setScreenBrightness(int brightness) {
        if (this.mScreenBrightness != brightness) {
            if (DEBUG) {
                Slog.d(TAG, "setScreenBrightness: brightness=" + brightness);
            }
            this.mScreenBrightness = brightness;
            if (this.mScreenState != 1) {
                this.mScreenReady = false;
                scheduleScreenUpdate();
            }
        }
    }

    public int getScreenBrightness() {
        return this.mScreenBrightness;
    }

    public boolean prepareColorFade(Context context, int mode) {
        if (this.mColorFade == null || !this.mColorFade.prepare(context, mode)) {
            this.mColorFadePrepared = false;
            this.mColorFadeReady = true;
            return false;
        }
        this.mColorFadePrepared = true;
        this.mColorFadeReady = false;
        scheduleColorFadeDraw();
        return true;
    }

    public void dismissColorFade() {
        Trace.traceCounter(131072L, COUNTER_COLOR_FADE, 100);
        if (this.mColorFade != null) {
            this.mColorFade.dismiss();
        }
        this.mColorFadePrepared = false;
        this.mColorFadeReady = true;
    }

    public void dismissColorFadeResources() {
        if (this.mColorFade != null) {
            this.mColorFade.dismissResources();
        }
    }

    public void setColorFadeLevel(float level) {
        if (this.mColorFadeLevel != level) {
            if (DEBUG || level == 1.0f || level == 0.0f) {
                Slog.i(TAG, "setColorFadeLevel: level=" + level);
            }
            this.mColorFadeLevel = level;
            if (this.mScreenState != 1) {
                this.mScreenReady = false;
                scheduleScreenUpdate();
            }
            if (this.mColorFadePrepared) {
                this.mColorFadeReady = false;
                scheduleColorFadeDraw();
            }
        }
    }

    public float getColorFadeLevel() {
        return this.mColorFadeLevel;
    }

    public boolean waitUntilClean(Runnable listener) {
        if (!this.mScreenReady || !this.mColorFadeReady) {
            this.mCleanListener = listener;
            return false;
        }
        this.mCleanListener = null;
        return true;
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Display Power State:");
        pw.println("  mScreenState=" + Display.stateToString(this.mScreenState));
        pw.println("  mScreenBrightness=" + this.mScreenBrightness);
        pw.println("  mScreenReady=" + this.mScreenReady);
        pw.println("  mScreenUpdatePending=" + this.mScreenUpdatePending);
        pw.println("  mColorFadePrepared=" + this.mColorFadePrepared);
        pw.println("  mColorFadeLevel=" + this.mColorFadeLevel);
        pw.println("  mColorFadeReady=" + this.mColorFadeReady);
        pw.println("  mColorFadeDrawPending=" + this.mColorFadeDrawPending);
        this.mPhotonicModulator.dump(pw);
        if (this.mColorFade != null) {
            this.mColorFade.dump(pw);
        }
    }

    private void scheduleScreenUpdate() {
        if (!this.mScreenUpdatePending) {
            this.mScreenUpdatePending = true;
            postScreenUpdateThreadSafe();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void postScreenUpdateThreadSafe() {
        this.mHandler.removeCallbacks(this.mScreenUpdateRunnable);
        this.mHandler.post(this.mScreenUpdateRunnable);
    }

    private void scheduleColorFadeDraw() {
        if (!this.mColorFadeDrawPending) {
            this.mColorFadeDrawPending = true;
            this.mChoreographer.postCallback(2, this.mColorFadeDrawRunnable, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void invokeCleanListenerIfNeeded() {
        Runnable listener = this.mCleanListener;
        if (listener != null && this.mScreenReady && this.mColorFadeReady) {
            this.mCleanListener = null;
            listener.run();
        }
    }

    /* loaded from: classes.dex */
    private final class PhotonicModulator extends Thread {
        private static final int INITIAL_BACKLIGHT = -1;
        private static final int INITIAL_SCREEN_STATE = 1;
        private int mActualBacklight;
        private int mActualState;
        private boolean mBacklightChangeInProgress;
        private final Object mLock;
        private int mPendingBacklight;
        private int mPendingState;
        private boolean mStateChangeInProgress;

        public PhotonicModulator() {
            super("PhotonicModulator");
            this.mLock = new Object();
            this.mPendingState = 1;
            this.mPendingBacklight = -1;
            this.mActualState = 1;
            this.mActualBacklight = -1;
        }

        /* JADX WARN: Removed duplicated region for block: B:33:0x005f A[Catch: all -> 0x0074, TryCatch #0 {, blocks: (B:4:0x0003, B:8:0x000c, B:41:0x006f, B:42:0x0072, B:14:0x0017, B:18:0x0041, B:20:0x0049, B:26:0x0053, B:31:0x005b, B:33:0x005f, B:38:0x0066, B:40:0x006a, B:17:0x001f), top: B:47:0x0003 }] */
        /* JADX WARN: Removed duplicated region for block: B:40:0x006a A[Catch: all -> 0x0074, TryCatch #0 {, blocks: (B:4:0x0003, B:8:0x000c, B:41:0x006f, B:42:0x0072, B:14:0x0017, B:18:0x0041, B:20:0x0049, B:26:0x0053, B:31:0x005b, B:33:0x005f, B:38:0x0066, B:40:0x006a, B:17:0x001f), top: B:47:0x0003 }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct add '--show-bad-code' argument
        */
        public boolean setState(int r9, int r10) {
            /*
                r8 = this;
                java.lang.Object r0 = r8.mLock
                monitor-enter(r0)
                int r1 = r8.mPendingState     // Catch: java.lang.Throwable -> L74
                r2 = 0
                r3 = 1
                if (r9 == r1) goto Lb
                r1 = r3
                goto Lc
            Lb:
                r1 = r2
            Lc:
                int r4 = r8.mPendingBacklight     // Catch: java.lang.Throwable -> L74
                if (r10 == r4) goto L12
                r4 = r3
                goto L13
            L12:
                r4 = r2
            L13:
                if (r1 != 0) goto L17
                if (r4 == 0) goto L6f
            L17:
                boolean r5 = com.android.server.display.DisplayPowerState.access$500()     // Catch: java.lang.Throwable -> L74
                if (r5 != 0) goto L1f
                if (r1 == 0) goto L41
            L1f:
                java.lang.String r5 = "DisplayPowerState"
                java.lang.StringBuilder r6 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L74
                r6.<init>()     // Catch: java.lang.Throwable -> L74
                java.lang.String r7 = "Requesting new screen state: state="
                r6.append(r7)     // Catch: java.lang.Throwable -> L74
                java.lang.String r7 = android.view.Display.stateToString(r9)     // Catch: java.lang.Throwable -> L74
                r6.append(r7)     // Catch: java.lang.Throwable -> L74
                java.lang.String r7 = ", backlight="
                r6.append(r7)     // Catch: java.lang.Throwable -> L74
                r6.append(r10)     // Catch: java.lang.Throwable -> L74
                java.lang.String r6 = r6.toString()     // Catch: java.lang.Throwable -> L74
                android.util.Slog.i(r5, r6)     // Catch: java.lang.Throwable -> L74
            L41:
                r8.mPendingState = r9     // Catch: java.lang.Throwable -> L74
                r8.mPendingBacklight = r10     // Catch: java.lang.Throwable -> L74
                boolean r5 = r8.mStateChangeInProgress     // Catch: java.lang.Throwable -> L74
                if (r5 != 0) goto L50
                boolean r5 = r8.mBacklightChangeInProgress     // Catch: java.lang.Throwable -> L74
                if (r5 == 0) goto L4e
                goto L50
            L4e:
                r5 = r2
                goto L51
            L50:
                r5 = r3
            L51:
                if (r1 != 0) goto L5a
                boolean r6 = r8.mStateChangeInProgress     // Catch: java.lang.Throwable -> L74
                if (r6 == 0) goto L58
                goto L5a
            L58:
                r6 = r2
                goto L5b
            L5a:
                r6 = r3
            L5b:
                r8.mStateChangeInProgress = r6     // Catch: java.lang.Throwable -> L74
                if (r4 != 0) goto L65
                boolean r6 = r8.mBacklightChangeInProgress     // Catch: java.lang.Throwable -> L74
                if (r6 == 0) goto L64
                goto L65
            L64:
                goto L66
            L65:
                r2 = r3
            L66:
                r8.mBacklightChangeInProgress = r2     // Catch: java.lang.Throwable -> L74
                if (r5 != 0) goto L6f
                java.lang.Object r2 = r8.mLock     // Catch: java.lang.Throwable -> L74
                r2.notifyAll()     // Catch: java.lang.Throwable -> L74
            L6f:
                boolean r2 = r8.mStateChangeInProgress     // Catch: java.lang.Throwable -> L74
                r2 = r2 ^ r3
                monitor-exit(r0)     // Catch: java.lang.Throwable -> L74
                return r2
            L74:
                r1 = move-exception
                monitor-exit(r0)     // Catch: java.lang.Throwable -> L74
                throw r1
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.display.DisplayPowerState.PhotonicModulator.setState(int, int):boolean");
        }

        public void dump(PrintWriter pw) {
            synchronized (this.mLock) {
                pw.println();
                pw.println("Photonic Modulator State:");
                pw.println("  mPendingState=" + Display.stateToString(this.mPendingState));
                pw.println("  mPendingBacklight=" + this.mPendingBacklight);
                pw.println("  mActualState=" + Display.stateToString(this.mActualState));
                pw.println("  mActualBacklight=" + this.mActualBacklight);
                pw.println("  mStateChangeInProgress=" + this.mStateChangeInProgress);
                pw.println("  mBacklightChangeInProgress=" + this.mBacklightChangeInProgress);
            }
        }

        @Override // java.lang.Thread, java.lang.Runnable
        public void run() {
            while (true) {
                synchronized (this.mLock) {
                    int state = this.mPendingState;
                    boolean backlightChanged = true;
                    boolean stateChanged = state != this.mActualState;
                    int backlight = this.mPendingBacklight;
                    if (backlight == this.mActualBacklight) {
                        backlightChanged = false;
                    }
                    if (!stateChanged) {
                        DisplayPowerState.this.postScreenUpdateThreadSafe();
                        this.mStateChangeInProgress = false;
                    }
                    if (!backlightChanged) {
                        this.mBacklightChangeInProgress = false;
                    }
                    if (!stateChanged && !backlightChanged) {
                        try {
                            this.mLock.wait();
                        } catch (InterruptedException e) {
                        }
                    } else {
                        this.mActualState = state;
                        this.mActualBacklight = backlight;
                        if (DisplayPowerState.DEBUG || stateChanged) {
                            Slog.i(DisplayPowerState.TAG, "Updating screen state: state=" + Display.stateToString(state) + ", backlight=" + backlight);
                        }
                        DisplayPowerState.this.mBlanker.requestDisplayState(state, backlight);
                    }
                }
            }
        }
    }
}
