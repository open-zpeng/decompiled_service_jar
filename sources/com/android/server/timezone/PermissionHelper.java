package com.android.server.timezone;

import java.io.PrintWriter;
/* loaded from: classes.dex */
public interface PermissionHelper {
    boolean checkDumpPermission(String str, PrintWriter printWriter);

    void enforceCallerHasPermission(String str) throws SecurityException;
}
