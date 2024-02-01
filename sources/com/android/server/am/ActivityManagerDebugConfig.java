package com.android.server.am;

import android.os.Build;
import com.xiaopeng.util.DebugOption;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ActivityManagerDebugConfig {
    static final boolean APPEND_CATEGORY_NAME = false;
    static final boolean DEBUG_ALL = DebugOption.DEBUG_AM;
    static final boolean DEBUG_ANR = false;
    static final boolean DEBUG_BACKGROUND_CHECK;
    static final boolean DEBUG_BACKUP;
    static final boolean DEBUG_BROADCAST;
    static final boolean DEBUG_BROADCAST_BACKGROUND;
    static final boolean DEBUG_BROADCAST_DEFERRAL;
    static final boolean DEBUG_BROADCAST_LIGHT;
    static final boolean DEBUG_COMPACTION;
    static final boolean DEBUG_FOREGROUND_SERVICE;
    static final boolean DEBUG_LRU;
    static final boolean DEBUG_MU;
    static final boolean DEBUG_NETWORK;
    static final boolean DEBUG_OOM_ADJ;
    static final boolean DEBUG_OOM_ADJ_REASON;
    static final boolean DEBUG_PERMISSIONS_REVIEW;
    static final boolean DEBUG_POWER;
    static final boolean DEBUG_POWER_QUICK;
    static final boolean DEBUG_PROCESSES;
    static final boolean DEBUG_PROCESS_OBSERVERS;
    static final boolean DEBUG_PROVIDER;
    static final boolean DEBUG_PSS;
    static final boolean DEBUG_SERVICE;
    static final boolean DEBUG_SERVICE_EXECUTING;
    static final boolean DEBUG_UID_OBSERVERS;
    static final boolean DEBUG_USAGE_STATS;
    static final boolean DEBUG_WHITELISTS;
    static final boolean IS_DEBUG;
    static final String POSTFIX_BACKUP = "";
    static final String POSTFIX_BROADCAST = "";
    static final String POSTFIX_CLEANUP = "";
    static final String POSTFIX_LRU = "";
    static final String POSTFIX_MU = "_MU";
    static final String POSTFIX_NETWORK = "_Network";
    static final String POSTFIX_OOM_ADJ = "";
    static final String POSTFIX_POWER = "";
    static final String POSTFIX_PROCESSES = "";
    static final String POSTFIX_PROCESS_OBSERVERS = "";
    static final String POSTFIX_PROVIDER = "";
    static final String POSTFIX_PSS = "";
    static final String POSTFIX_SERVICE = "";
    static final String POSTFIX_SERVICE_EXECUTING = "";
    static final String POSTFIX_UID_OBSERVERS = "";
    static final String TAG_AM = "ActivityManager";
    static final boolean TAG_WITH_CLASS_NAME = false;

    ActivityManagerDebugConfig() {
    }

    static {
        DEBUG_BACKGROUND_CHECK = DEBUG_ALL;
        DEBUG_BACKUP = DEBUG_ALL;
        DEBUG_BROADCAST = DEBUG_ALL || DebugOption.DEBUG_AM_BROADCAST;
        DEBUG_BROADCAST_BACKGROUND = DEBUG_BROADCAST;
        DEBUG_BROADCAST_LIGHT = DEBUG_BROADCAST;
        DEBUG_BROADCAST_DEFERRAL = DEBUG_BROADCAST;
        DEBUG_COMPACTION = DEBUG_ALL;
        DEBUG_LRU = DEBUG_ALL;
        DEBUG_MU = DEBUG_ALL;
        DEBUG_NETWORK = DEBUG_ALL;
        DEBUG_OOM_ADJ = DEBUG_ALL;
        DEBUG_OOM_ADJ_REASON = DEBUG_ALL;
        DEBUG_POWER = DEBUG_ALL;
        DEBUG_POWER_QUICK = DEBUG_POWER;
        DEBUG_PROCESS_OBSERVERS = DEBUG_ALL;
        DEBUG_PROCESSES = DEBUG_ALL;
        DEBUG_PROVIDER = DEBUG_ALL;
        DEBUG_PSS = DEBUG_ALL;
        DEBUG_SERVICE = DEBUG_ALL;
        DEBUG_FOREGROUND_SERVICE = DEBUG_ALL;
        DEBUG_SERVICE_EXECUTING = DEBUG_ALL;
        DEBUG_UID_OBSERVERS = DEBUG_ALL;
        DEBUG_USAGE_STATS = DEBUG_ALL;
        DEBUG_PERMISSIONS_REVIEW = DEBUG_ALL;
        DEBUG_WHITELISTS = DEBUG_ALL;
        IS_DEBUG = !Build.IS_USER;
    }
}
