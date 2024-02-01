package com.android.server.signedconfig;

import android.util.StatsLog;

/* loaded from: classes.dex */
public class SignedConfigEvent {
    public int type = 0;
    public int status = 0;
    public int version = 0;
    public String fromPackage = null;
    public int verifiedWith = 0;

    public void send() {
        StatsLog.write(123, this.type, this.status, this.version, this.fromPackage, this.verifiedWith);
    }
}
