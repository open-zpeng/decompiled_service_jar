package com.android.server.wm;

import com.xiaopeng.util.DebugOption;

/* loaded from: classes2.dex */
public class ActivityTaskManagerDebugConfig {
    private static final boolean APPEND_CATEGORY_NAME = false;
    static final boolean DEBUG_ADD_REMOVE;
    static final boolean DEBUG_ALL = DebugOption.DEBUG_ATM;
    private static final boolean DEBUG_ALL_ACTIVITIES;
    static final boolean DEBUG_APP;
    public static final boolean DEBUG_CLEANUP;
    public static final boolean DEBUG_CONFIGURATION;
    static final boolean DEBUG_CONTAINERS;
    static final boolean DEBUG_FOCUS = false;
    static final boolean DEBUG_IDLE;
    static final boolean DEBUG_IMMERSIVE;
    static final boolean DEBUG_LOCKTASK;
    public static final boolean DEBUG_METRICS;
    static final boolean DEBUG_PAUSE;
    static final boolean DEBUG_PERMISSIONS_REVIEW;
    static final boolean DEBUG_RECENTS;
    static final boolean DEBUG_RECENTS_TRIM_TASKS;
    static final boolean DEBUG_RELEASE;
    static final boolean DEBUG_RESULTS;
    static final boolean DEBUG_SAVED_STATE;
    static final boolean DEBUG_STACK;
    static final boolean DEBUG_STATES;
    public static final boolean DEBUG_SWITCH;
    static final boolean DEBUG_TASKS;
    static final boolean DEBUG_TRANSITION;
    static final boolean DEBUG_USER_LEAVING;
    static final boolean DEBUG_VISIBILITY;
    static final String POSTFIX_ADD_REMOVE = "";
    static final String POSTFIX_APP = "";
    static final String POSTFIX_CLEANUP = "";
    public static final String POSTFIX_CONFIGURATION = "";
    static final String POSTFIX_CONTAINERS = "";
    static final String POSTFIX_FOCUS = "";
    static final String POSTFIX_IDLE = "";
    static final String POSTFIX_IMMERSIVE = "";
    public static final String POSTFIX_LOCKTASK = "";
    static final String POSTFIX_PAUSE = "";
    static final String POSTFIX_RECENTS = "";
    static final String POSTFIX_RELEASE = "";
    static final String POSTFIX_RESULTS = "";
    static final String POSTFIX_SAVED_STATE = "";
    static final String POSTFIX_STACK = "";
    static final String POSTFIX_STATES = "";
    public static final String POSTFIX_SWITCH = "";
    static final String POSTFIX_TASKS = "";
    static final String POSTFIX_TRANSITION = "";
    static final String POSTFIX_USER_LEAVING = "";
    static final String POSTFIX_VISIBILITY = "";
    static final String TAG_ATM = "ActivityTaskManager";
    static final boolean TAG_WITH_CLASS_NAME = false;

    static {
        DEBUG_ALL_ACTIVITIES = DEBUG_ALL;
        DEBUG_ADD_REMOVE = DEBUG_ALL_ACTIVITIES;
        DEBUG_CONFIGURATION = DEBUG_ALL || DebugOption.DEBUG_CONFIGURATION;
        DEBUG_CONTAINERS = DEBUG_ALL_ACTIVITIES;
        DEBUG_IMMERSIVE = DEBUG_ALL;
        DEBUG_LOCKTASK = DEBUG_ALL;
        DEBUG_PAUSE = DEBUG_ALL;
        DEBUG_RECENTS = DEBUG_ALL;
        DEBUG_RECENTS_TRIM_TASKS = DEBUG_RECENTS;
        DEBUG_SAVED_STATE = DEBUG_ALL_ACTIVITIES;
        DEBUG_STACK = DEBUG_ALL;
        DEBUG_STATES = DEBUG_ALL_ACTIVITIES;
        DEBUG_SWITCH = DEBUG_ALL;
        DEBUG_TASKS = DEBUG_ALL;
        DEBUG_TRANSITION = DEBUG_ALL;
        DEBUG_VISIBILITY = DEBUG_ALL;
        DEBUG_APP = DEBUG_ALL_ACTIVITIES;
        DEBUG_IDLE = DEBUG_ALL_ACTIVITIES;
        DEBUG_RELEASE = DEBUG_ALL_ACTIVITIES;
        DEBUG_USER_LEAVING = DEBUG_ALL;
        DEBUG_PERMISSIONS_REVIEW = DEBUG_ALL;
        DEBUG_RESULTS = DEBUG_ALL;
        DEBUG_CLEANUP = DEBUG_ALL;
        DEBUG_METRICS = DEBUG_ALL;
    }
}
