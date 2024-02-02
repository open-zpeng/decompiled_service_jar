package com.xpeng.server.display;

import android.os.Build;
import android.util.Slog;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
/* loaded from: classes.dex */
public class ResumeProfEvent {
    private static final String TAG = "ResumeProfEvent";

    public static void addResumeProfEvent(String resumeprofevent) {
        if (Build.IS_USER) {
            return;
        }
        try {
            FileOutputStream frp = new FileOutputStream("/sys/xpeng_performance/resume_prof");
            frp.write(resumeprofevent.getBytes());
            frp.flush();
            frp.close();
        } catch (FileNotFoundException e) {
            Slog.e("SUSPEND_RESUME_PROF", "Failure open /sys/xpeng_performance/resume_prof, not found!", e);
        } catch (IOException e2) {
            Slog.e("SUSPEND_RESUME_PROF", "Failure open /sys/xpeng_performance/resume_prof entry", e2);
        }
    }
}
