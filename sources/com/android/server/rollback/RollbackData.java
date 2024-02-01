package com.android.server.rollback;

import android.content.rollback.RollbackInfo;
import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class RollbackData {
    static final int ROLLBACK_STATE_AVAILABLE = 1;
    static final int ROLLBACK_STATE_COMMITTED = 3;
    static final int ROLLBACK_STATE_ENABLING = 0;
    public int apkSessionId;
    public final File backupDir;
    public final RollbackInfo info;
    public boolean restoreUserDataInProgress;
    public final int stagedSessionId;
    public int state;
    public Instant timestamp;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    @interface RollbackState {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RollbackData(int rollbackId, File backupDir, int stagedSessionId) {
        this.apkSessionId = -1;
        this.restoreUserDataInProgress = false;
        this.info = new RollbackInfo(rollbackId, new ArrayList(), stagedSessionId != -1, new ArrayList(), -1);
        this.backupDir = backupDir;
        this.stagedSessionId = stagedSessionId;
        this.state = 0;
        this.timestamp = Instant.now();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public RollbackData(RollbackInfo info, File backupDir, Instant timestamp, int stagedSessionId, int state, int apkSessionId, boolean restoreUserDataInProgress) {
        this.apkSessionId = -1;
        this.restoreUserDataInProgress = false;
        this.info = info;
        this.backupDir = backupDir;
        this.timestamp = timestamp;
        this.stagedSessionId = stagedSessionId;
        this.state = state;
        this.apkSessionId = apkSessionId;
        this.restoreUserDataInProgress = restoreUserDataInProgress;
    }

    public boolean isStaged() {
        return this.info.isStaged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String rollbackStateToString(int state) {
        if (state != 0) {
            if (state != 1) {
                if (state == 3) {
                    return "committed";
                }
                throw new AssertionError("Invalid rollback state: " + state);
            }
            return "available";
        }
        return "enabling";
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int rollbackStateFromString(String state) throws ParseException {
        char c;
        int hashCode = state.hashCode();
        if (hashCode == -1491142788) {
            if (state.equals("committed")) {
                c = 2;
            }
            c = 65535;
        } else if (hashCode != -733902135) {
            if (hashCode == 1642196352 && state.equals("enabling")) {
                c = 0;
            }
            c = 65535;
        } else {
            if (state.equals("available")) {
                c = 1;
            }
            c = 65535;
        }
        if (c != 0) {
            if (c != 1) {
                if (c == 2) {
                    return 3;
                }
                throw new ParseException("Invalid rollback state: " + state, 0);
            }
            return 1;
        }
        return 0;
    }

    public String getStateAsString() {
        return rollbackStateToString(this.state);
    }
}
