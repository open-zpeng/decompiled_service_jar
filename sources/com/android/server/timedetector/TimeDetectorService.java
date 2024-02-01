package com.android.server.timedetector;

import android.app.timedetector.ITimeDetectorService;
import android.app.timedetector.TimeSignal;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Binder;
import android.provider.Settings;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.timedetector.TimeDetectorStrategy;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

/* loaded from: classes2.dex */
public final class TimeDetectorService extends ITimeDetectorService.Stub {
    private static final String TAG = "timedetector.TimeDetectorService";
    private final TimeDetectorStrategy.Callback mCallback;
    private final Context mContext;
    private final Object mStrategyLock = new Object();
    @GuardedBy({"mStrategyLock"})
    private final TimeDetectorStrategy mTimeDetectorStrategy;

    /* loaded from: classes2.dex */
    public static class Lifecycle extends SystemService {
        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            publishBinderService("time_detector", TimeDetectorService.create(getContext()));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static TimeDetectorService create(Context context) {
        TimeDetectorStrategy timeDetector = new SimpleTimeDetectorStrategy();
        TimeDetectorStrategyCallbackImpl callback = new TimeDetectorStrategyCallbackImpl(context);
        timeDetector.initialize(callback);
        final TimeDetectorService timeDetectorService = new TimeDetectorService(context, callback, timeDetector);
        ContentResolver contentResolver = context.getContentResolver();
        contentResolver.registerContentObserver(Settings.Global.getUriFor("auto_time"), true, new ContentObserver(FgThread.getHandler()) { // from class: com.android.server.timedetector.TimeDetectorService.1
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                timeDetectorService.handleAutoTimeDetectionToggle();
            }
        });
        return timeDetectorService;
    }

    @VisibleForTesting
    public TimeDetectorService(Context context, TimeDetectorStrategy.Callback callback, TimeDetectorStrategy timeDetectorStrategy) {
        this.mContext = (Context) Objects.requireNonNull(context);
        this.mCallback = (TimeDetectorStrategy.Callback) Objects.requireNonNull(callback);
        this.mTimeDetectorStrategy = (TimeDetectorStrategy) Objects.requireNonNull(timeDetectorStrategy);
    }

    public void suggestTime(TimeSignal timeSignal) {
        enforceSetTimePermission();
        Objects.requireNonNull(timeSignal);
        long idToken = Binder.clearCallingIdentity();
        try {
            synchronized (this.mStrategyLock) {
                this.mTimeDetectorStrategy.suggestTime(timeSignal);
            }
        } finally {
            Binder.restoreCallingIdentity(idToken);
        }
    }

    @VisibleForTesting
    public void handleAutoTimeDetectionToggle() {
        synchronized (this.mStrategyLock) {
            boolean timeDetectionEnabled = this.mCallback.isTimeDetectionEnabled();
            this.mTimeDetectorStrategy.handleAutoTimeDetectionToggle(timeDetectionEnabled);
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            synchronized (this.mStrategyLock) {
                this.mTimeDetectorStrategy.dump(pw, args);
            }
        }
    }

    private void enforceSetTimePermission() {
        this.mContext.enforceCallingPermission("android.permission.SET_TIME", "set time");
    }
}
