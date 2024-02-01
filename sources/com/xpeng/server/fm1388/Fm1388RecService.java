package com.xpeng.server.fm1388;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Ifm1388RecService;
import android.util.Log;
import android.util.Slog;
import com.android.server.SystemService;
/* loaded from: classes.dex */
public class Fm1388RecService extends Ifm1388RecService.Stub {
    private static final String TAG = "Fm1388RecService";
    private Context mContext;
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.xpeng.server.fm1388.Fm1388RecService.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if ("android.intent.action.SCREEN_OFF".equals(intent.getAction())) {
                Log.v(Fm1388RecService.TAG, "fm1388RecService: Get the screen off message.");
            }
        }
    };
    private int iVal = init_native();

    private static native int get_frame_size_native();

    private static native int get_mode_by_index_native(int i, byte[] bArr);

    private static native int get_mode_native(byte[] bArr);

    private static native int get_mode_number_native();

    private static native int get_sample_rate_native();

    private static native int get_sdcard_path_native(byte[] bArr);

    private static native int init_native();

    private static native int set_mode_native(int i);

    private static native int start_record_native(int i, byte[] bArr, byte[] bArr2);

    private static native int stop_record_native();

    /* loaded from: classes.dex */
    public static class Lifecycle extends SystemService {
        static final String NAME = "fm1388RecService";
        private Fm1388RecService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            this.mService = new Fm1388RecService(getContext());
            publishBinderService(NAME, this.mService);
        }
    }

    public Fm1388RecService(Context context) {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: Failed to initialize fm1388rec service.");
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.SCREEN_OFF");
        this.mContext = context;
        this.mContext.registerReceiver(this.mBroadcastReceiver, intentFilter);
    }

    public int start_record(int channel_num, byte[] channel_idx, byte[] filepath) {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: fm1388rec service is not initialized.");
            return -1;
        }
        return start_record_native(channel_num, channel_idx, filepath);
    }

    public int stop_record() {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: fm1388rec service is not initialized.");
            return -1;
        }
        return stop_record_native();
    }

    public int get_mode(byte[] dsp_mode_string) {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: fm1388rec service is not initialized.");
            return -1;
        }
        return get_mode_native(dsp_mode_string);
    }

    public int get_mode_number() {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: fm1388rec service is not initialized.");
            return -1;
        }
        return get_mode_number_native();
    }

    public int get_mode_by_index(int index, byte[] dsp_mode_string) {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: fm1388rec service is not initialized.");
            return -1;
        }
        return get_mode_by_index_native(index, dsp_mode_string);
    }

    public int set_mode(int mode_index) {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: fm1388rec service is not initialized.");
            return -1;
        }
        return set_mode_native(mode_index);
    }

    public int get_frame_size() {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: fm1388rec service is not initialized.");
            return -1;
        }
        return get_frame_size_native();
    }

    public int get_sample_rate() {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: fm1388rec service is not initialized.");
            return -1;
        }
        return get_sample_rate_native();
    }

    public int get_sdcard_path(byte[] path_string) {
        if (this.iVal != 0) {
            Slog.e(TAG, "fm1388RecService: fm1388rec service is not initialized.");
            return -1;
        }
        return get_sdcard_path_native(path_string);
    }
}
