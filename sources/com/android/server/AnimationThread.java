package com.android.server;

import android.os.Handler;

/* loaded from: classes.dex */
public final class AnimationThread extends ServiceThread {
    private static Handler sHandler;
    private static AnimationThread sInstance;

    private AnimationThread() {
        super("android.anim", -4, false);
    }

    private static void ensureThreadLocked() {
        if (sInstance == null) {
            sInstance = new AnimationThread();
            sInstance.start();
            sInstance.getLooper().setTraceTag(32L);
            sHandler = new Handler(sInstance.getLooper());
        }
    }

    public static AnimationThread get() {
        AnimationThread animationThread;
        synchronized (AnimationThread.class) {
            ensureThreadLocked();
            animationThread = sInstance;
        }
        return animationThread;
    }

    public static Handler getHandler() {
        Handler handler;
        synchronized (AnimationThread.class) {
            ensureThreadLocked();
            handler = sHandler;
        }
        return handler;
    }
}
