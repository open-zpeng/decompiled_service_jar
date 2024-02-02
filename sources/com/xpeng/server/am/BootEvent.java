package com.xpeng.server.am;

import android.os.Build;
import android.os.SystemProperties;
import android.util.Slog;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
/* loaded from: classes.dex */
public class BootEvent {
    private static final String TAG = "BootEvent";
    private static final boolean sDebug = false;
    private static boolean sEnabled = true;
    private static final boolean IS_USER_BUILD = "user".equals(Build.TYPE);
    private static boolean mBootProfDisable = "1".equals(SystemProperties.get("ro.bootprof.disable", "0"));

    public static void addBootEvent(String bootevent) {
        if (!sEnabled || mBootProfDisable) {
            return;
        }
        try {
            FileOutputStream fbp = new FileOutputStream("/proc/bootprof");
            fbp.write(bootevent.getBytes());
            fbp.flush();
            fbp.close();
        } catch (FileNotFoundException e) {
            Slog.e(TAG, "Failure open /proc/bootprof, not found!", e);
        } catch (IOException e2) {
            Slog.e(TAG, "Failure open /proc/bootprof entry", e2);
        }
    }

    public static void setEnabled(boolean enabled) {
        Slog.d(TAG, "setEnabled " + enabled);
        sEnabled = enabled;
    }
}
