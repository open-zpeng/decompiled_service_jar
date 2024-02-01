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
    private static final int DISPLAY_MAX = 3;
    private static final String TAG = "DisplayPowerState";
    private final DisplayBlanker mBlanker;
    private Runnable mCleanListener;
    private final ColorFade mColorFade;
    private boolean mColorFadeDrawPending;
    private float mColorFadeLevel;
    private boolean mColorFadePrepared;
    private boolean mColorFadeReady;
    private int[] mOtherScreenBrightnessArray;
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
    public static final IntProperty<DisplayPowerState> PASSENGER_SCREEN_BRIGHTNESS = new IntProperty<DisplayPowerState>("PassengerScreenBrightness") { // from class: com.android.server.display.DisplayPowerState.3
        @Override // android.util.IntProperty
        public void setValue(DisplayPowerState object, int value) {
            object.setOthersScreenBrightness(1, value);
        }

        @Override // android.util.Property
        public Integer get(DisplayPowerState object) {
            return Integer.valueOf(object.getOtherScreenBrightness(1));
        }
    };
    public static final IntProperty<DisplayPowerState> ICM_BRIGHTNESS = new IntProperty<DisplayPowerState>("ICMBrightness") { // from class: com.android.server.display.DisplayPowerState.4
        @Override // android.util.IntProperty
        public void setValue(DisplayPowerState object, int value) {
            object.setOthersScreenBrightness(2, value);
        }

        @Override // android.util.Property
        public Integer get(DisplayPowerState object) {
            return Integer.valueOf(object.getOtherScreenBrightness(2));
        }
    };
    private final Runnable mScreenUpdateRunnable = new Runnable() { // from class: com.android.server.display.DisplayPowerState.5
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
                    Slog.d(DisplayPowerState.TAG, "Screen ready");
                }
                DisplayPowerState.this.mScreenReady = true;
                DisplayPowerState.this.invokeCleanListenerIfNeeded();
            } else if (DisplayPowerState.DEBUG) {
                Slog.d(DisplayPowerState.TAG, "Screen not ready");
            }
        }
    };
    private final Runnable mColorFadeDrawRunnable = new Runnable() { // from class: com.android.server.display.DisplayPowerState.6
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
        this.mOtherScreenBrightnessArray = new int[3];
        scheduleScreenUpdate();
        this.mColorFadePrepared = false;
        this.mColorFadeLevel = 1.0f;
        this.mColorFadeReady = true;
    }

    public void setScreenState(int state) {
        if (this.mScreenState != state) {
            if (DEBUG) {
                Slog.d(TAG, "setScreenState: state=" + state);
            }
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

    public void setOthersScreenBrightness(int type, int brightness) {
        if (type < 0 || type > 2) {
            Slog.d(TAG, "setOthersScreenBrightness wrong type=" + type);
        } else if (this.mOtherScreenBrightnessArray[type] != brightness) {
            if (DEBUG) {
                Slog.d(TAG, "setOthersScreenBrightness: brightness=" + brightness + " type=" + type);
            }
            this.mOtherScreenBrightnessArray[type] = brightness;
            int brightness2 = brightness & 255;
            if (type == 1) {
                this.mBlanker.requestBrightness(brightness2, "/sys/class/backlight/panel2-backlight/brightness");
            } else if (type == 2) {
                this.mBlanker.requestBrightness(brightness2, "/sys/class/backlight/panel1-backlight/brightness");
            } else {
                Slog.d(TAG, "setOthersScreenBrightness: brightness=" + brightness2 + " wrong type=" + type);
            }
        }
    }

    public int getScreenBrightness() {
        return this.mScreenBrightness;
    }

    public int getOtherScreenBrightness(int type) {
        if (type < 0 || type > 2) {
            Slog.d(TAG, "getOtherScreenBrightness wrong type=" + type);
            return this.mScreenBrightness;
        }
        return this.mOtherScreenBrightnessArray[type];
    }

    public boolean prepareColorFade(Context context, int mode) {
        ColorFade colorFade = this.mColorFade;
        if (colorFade == null || !colorFade.prepare(context, mode)) {
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
        ColorFade colorFade = this.mColorFade;
        if (colorFade != null) {
            colorFade.dismiss();
        }
        this.mColorFadePrepared = false;
        this.mColorFadeReady = true;
    }

    public void dismissColorFadeResources() {
        ColorFade colorFade = this.mColorFade;
        if (colorFade != null) {
            colorFade.dismissResources();
        }
    }

    public void setColorFadeLevel(float level) {
        if (this.mColorFadeLevel != level) {
            if (DEBUG) {
                Slog.d(TAG, "setColorFadeLevel: level=" + level);
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
        ColorFade colorFade = this.mColorFade;
        if (colorFade != null) {
            colorFade.dump(pw);
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
            this.mChoreographer.postCallback(3, this.mColorFadeDrawRunnable, null);
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

        /* JADX WARN: Removed duplicated region for block: B:32:0x005d A[Catch: all -> 0x0076, TryCatch #0 {, blocks: (B:4:0x0003, B:8:0x000c, B:40:0x006e, B:44:0x0074, B:14:0x0017, B:16:0x001d, B:17:0x003f, B:19:0x0047, B:25:0x0051, B:30:0x0059, B:32:0x005d, B:37:0x0065, B:39:0x0069), top: B:49:0x0003 }] */
        /* JADX WARN: Removed duplicated region for block: B:39:0x0069 A[Catch: all -> 0x0076, TryCatch #0 {, blocks: (B:4:0x0003, B:8:0x000c, B:40:0x006e, B:44:0x0074, B:14:0x0017, B:16:0x001d, B:17:0x003f, B:19:0x0047, B:25:0x0051, B:30:0x0059, B:32:0x005d, B:37:0x0065, B:39:0x0069), top: B:49:0x0003 }] */
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public boolean setState(int r9, int r10) {
            /*
                r8 = this;
                java.lang.Object r0 = r8.mLock
                monitor-enter(r0)
                int r1 = r8.mPendingState     // Catch: java.lang.Throwable -> L76
                r2 = 1
                r3 = 0
                if (r9 == r1) goto Lb
                r1 = r2
                goto Lc
            Lb:
                r1 = r3
            Lc:
                int r4 = r8.mPendingBacklight     // Catch: java.lang.Throwable -> L76
                if (r10 == r4) goto L12
                r4 = r2
                goto L13
            L12:
                r4 = r3
            L13:
                if (r1 != 0) goto L17
                if (r4 == 0) goto L6e
            L17:
                boolean r5 = com.android.server.display.DisplayPowerState.access$500()     // Catch: java.lang.Throwable -> L76
                if (r5 == 0) goto L3f
                java.lang.String r5 = "DisplayPowerState"
                java.lang.StringBuilder r6 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L76
                r6.<init>()     // Catch: java.lang.Throwable -> L76
                java.lang.String r7 = "Requesting new screen state: state="
                r6.append(r7)     // Catch: java.lang.Throwable -> L76
                java.lang.String r7 = android.view.Display.stateToString(r9)     // Catch: java.lang.Throwable -> L76
                r6.append(r7)     // Catch: java.lang.Throwable -> L76
                java.lang.String r7 = ", backlight="
                r6.append(r7)     // Catch: java.lang.Throwable -> L76
                r6.append(r10)     // Catch: java.lang.Throwable -> L76
                java.lang.String r6 = r6.toString()     // Catch: java.lang.Throwable -> L76
                android.util.Slog.d(r5, r6)     // Catch: java.lang.Throwable -> L76
            L3f:
                r8.mPendingState = r9     // Catch: java.lang.Throwable -> L76
                r8.mPendingBacklight = r10     // Catch: java.lang.Throwable -> L76
                boolean r5 = r8.mStateChangeInProgress     // Catch: java.lang.Throwable -> L76
                if (r5 != 0) goto L4e
                boolean r5 = r8.mBacklightChangeInProgress     // Catch: java.lang.Throwable -> L76
                if (r5 == 0) goto L4c
                goto L4e
            L4c:
                r5 = r3
                goto L4f
            L4e:
                r5 = r2
            L4f:
                if (r1 != 0) goto L58
                boolean r6 = r8.mStateChangeInProgress     // Catch: java.lang.Throwable -> L76
                if (r6 == 0) goto L56
                goto L58
            L56:
                r6 = r3
                goto L59
            L58:
                r6 = r2
            L59:
                r8.mStateChangeInProgress = r6     // Catch: java.lang.Throwable -> L76
                if (r4 != 0) goto L64
                boolean r6 = r8.mBacklightChangeInProgress     // Catch: java.lang.Throwable -> L76
                if (r6 == 0) goto L62
                goto L64
            L62:
                r6 = r3
                goto L65
            L64:
                r6 = r2
            L65:
                r8.mBacklightChangeInProgress = r6     // Catch: java.lang.Throwable -> L76
                if (r5 != 0) goto L6e
                java.lang.Object r6 = r8.mLock     // Catch: java.lang.Throwable -> L76
                r6.notifyAll()     // Catch: java.lang.Throwable -> L76
            L6e:
                boolean r5 = r8.mStateChangeInProgress     // Catch: java.lang.Throwable -> L76
                if (r5 != 0) goto L73
                goto L74
            L73:
                r2 = r3
            L74:
                monitor-exit(r0)     // Catch: java.lang.Throwable -> L76
                return r2
            L76:
                r1 = move-exception
                monitor-exit(r0)     // Catch: java.lang.Throwable -> L76
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
                        if (DisplayPowerState.DEBUG) {
                            Slog.d(DisplayPowerState.TAG, "Updating screen state: state=" + Display.stateToString(state) + ", backlight=" + backlight);
                        }
                        DisplayPowerState.this.mBlanker.requestDisplayState(state, backlight);
                    }
                }
            }
        }
    }
}
