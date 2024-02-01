package com.xpeng.server.fm1388;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Ifm1388Service;
import android.util.Slog;
import com.android.server.SystemService;

/* loaded from: classes2.dex */
public class Fm1388Service extends Ifm1388Service.Stub {
    private static final String TAG = "Fm1388Service";
    private Context mContext;
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.xpeng.server.fm1388.Fm1388Service.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            "android.intent.action.SCREEN_OFF".equals(intent.getAction());
        }
    };
    private int iVal = init_native();

    private static native int getData_native(int i, long j);

    private static native int getVECPath_native(byte[] bArr);

    private static native int getVal_native(int i);

    private static native int init_native();

    private static native void setData_native(int i, long j, int i2);

    private static native void setVal_native(int i);

    /* loaded from: classes2.dex */
    public static class Lifecycle extends SystemService {
        static final String NAME = "fm1388Service";
        private Fm1388Service mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            this.mService = new Fm1388Service(getContext());
            publishBinderService(NAME, this.mService);
        }
    }

    public Fm1388Service(Context context) {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388Service: Failed to initialize fm1388 service." + String.valueOf(this.iVal));
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.SCREEN_OFF");
        this.mContext = context;
        this.mContext.registerReceiver(this.mBroadcastReceiver, intentFilter);
    }

    public void setVal(int val) {
        int pid = Binder.getCallingPid();
        String tmp = "calling form pid " + String.valueOf(pid);
        Slog.e(TAG, tmp);
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388Service: fm1388 service is not initialized.");
            return;
        }
        String str = "fm1388Service: setVal -- val =" + String.valueOf(val);
        setVal_native(val);
    }

    public int getVal(int val) {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388Service: fm1388 service is not initialized.");
            return 0;
        }
        String str = "fm1388Service: getVal -- val =" + String.valueOf(val);
        return getVal_native(val);
    }

    public void setData(int action, long val1, int val2) {
        int pid = Binder.getCallingPid();
        String tmp = "calling form pid " + String.valueOf(pid);
        Slog.e(TAG, tmp);
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388Service: fm1388 service is not initialized.");
            return;
        }
        String str = "fm1388Service: setData -- val =" + String.valueOf(val1);
        setData_native(action, val1, val2);
    }

    public int getData(int action, long val) {
        Slog.e(TAG, "fm1388Service: fm1388 service 1");
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388Service: fm1388 service is not initialized.");
            return 0;
        }
        String str = "fm1388Service: getData -- val =" + String.valueOf(val);
        return getData_native(action, val);
    }

    public int getVECPath(byte[] path_string) {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388Service: fm1388 service is not initialized.");
            return -1;
        }
        return getVECPath_native(path_string);
    }
}
