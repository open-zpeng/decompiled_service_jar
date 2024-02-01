package com.android.server.pm;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.ShareTargetInfo;
import java.io.IOException;
import java.util.List;
import org.xmlpull.v1.XmlPullParserException;

/* loaded from: classes.dex */
public class ShortcutParser {
    private static final boolean DEBUG = false;
    @VisibleForTesting
    static final String METADATA_KEY = "android.app.shortcuts";
    private static final String TAG = "ShortcutService";
    private static final String TAG_CATEGORIES = "categories";
    private static final String TAG_CATEGORY = "category";
    private static final String TAG_DATA = "data";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_SHARE_TARGET = "share-target";
    private static final String TAG_SHORTCUT = "shortcut";
    private static final String TAG_SHORTCUTS = "shortcuts";

    public static List<ShortcutInfo> parseShortcuts(ShortcutService service, String packageName, int userId, List<ShareTargetInfo> outShareTargets) throws IOException, XmlPullParserException {
        List<ResolveInfo> activities = service.injectGetMainActivities(packageName, userId);
        if (activities != null && activities.size() != 0) {
            outShareTargets.clear();
            try {
                int size = activities.size();
                List<ShortcutInfo> result = null;
                for (int i = 0; i < size; i++) {
                    try {
                        ActivityInfo activityInfoNoMetadata = activities.get(i).activityInfo;
                        if (activityInfoNoMetadata != null) {
                            try {
                                ActivityInfo activityInfoWithMetadata = service.getActivityInfoWithMetadata(activityInfoNoMetadata.getComponentName(), userId);
                                if (activityInfoWithMetadata != null) {
                                    result = parseShortcutsOneFile(service, activityInfoWithMetadata, packageName, userId, result, outShareTargets);
                                }
                            } catch (RuntimeException e) {
                                e = e;
                                service.wtf("Exception caught while parsing shortcut XML for package=" + packageName, e);
                                return null;
                            }
                        }
                    } catch (RuntimeException e2) {
                        e = e2;
                    }
                }
                return result;
            } catch (RuntimeException e3) {
                e = e3;
            }
        }
        return null;
    }

    /* JADX WARN: Code restructure failed: missing block: B:182:0x041c, code lost:
        r9.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:183:0x0420, code lost:
        return r5;
     */
    /* JADX WARN: Removed duplicated region for block: B:192:0x0436  */
    /* JADX WARN: Removed duplicated region for block: B:250:0x01f8 A[SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:85:0x0200  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private static java.util.List<android.content.pm.ShortcutInfo> parseShortcutsOneFile(com.android.server.pm.ShortcutService r24, android.content.pm.ActivityInfo r25, java.lang.String r26, int r27, java.util.List<android.content.pm.ShortcutInfo> r28, java.util.List<com.android.server.pm.ShareTargetInfo> r29) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
        /*
            Method dump skipped, instructions count: 1082
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutParser.parseShortcutsOneFile(com.android.server.pm.ShortcutService, android.content.pm.ActivityInfo, java.lang.String, int, java.util.List, java.util.List):java.util.List");
    }

    private static String parseCategories(ShortcutService service, AttributeSet attrs) {
        TypedArray sa = service.mContext.getResources().obtainAttributes(attrs, R.styleable.ShortcutCategories);
        try {
            if (sa.getType(0) == 3) {
                return sa.getNonResourceString(0);
            }
            Log.w(TAG, "android:name for shortcut category must be string literal.");
            return null;
        } finally {
            sa.recycle();
        }
    }

    private static ShortcutInfo parseShortcutAttributes(ShortcutService service, AttributeSet attrs, String packageName, ComponentName activity, int userId, int rank) {
        TypedArray sa = service.mContext.getResources().obtainAttributes(attrs, R.styleable.Shortcut);
        try {
            if (sa.getType(2) != 3) {
                Log.w(TAG, "android:shortcutId must be string literal. activity=" + activity);
                return null;
            }
            String id = sa.getNonResourceString(2);
            boolean enabled = sa.getBoolean(1, true);
            int iconResId = sa.getResourceId(0, 0);
            int titleResId = sa.getResourceId(3, 0);
            int textResId = sa.getResourceId(4, 0);
            int disabledMessageResId = sa.getResourceId(5, 0);
            if (TextUtils.isEmpty(id)) {
                Log.w(TAG, "android:shortcutId must be provided. activity=" + activity);
                return null;
            } else if (titleResId == 0) {
                Log.w(TAG, "android:shortcutShortLabel must be provided. activity=" + activity);
                return null;
            } else {
                return createShortcutFromManifest(service, userId, id, packageName, activity, titleResId, textResId, disabledMessageResId, rank, iconResId, enabled);
            }
        } finally {
            sa.recycle();
        }
    }

    private static ShortcutInfo createShortcutFromManifest(ShortcutService service, int userId, String id, String packageName, ComponentName activityComponent, int titleResId, int textResId, int disabledMessageResId, int rank, int iconResId, boolean enabled) {
        int disabledReason;
        int flags = (enabled ? 32 : 64) | 256 | (iconResId != 0 ? 4 : 0);
        if (enabled) {
            disabledReason = 0;
        } else {
            disabledReason = 1;
        }
        return new ShortcutInfo(userId, id, packageName, activityComponent, null, null, titleResId, null, null, textResId, null, null, disabledMessageResId, null, null, null, rank, null, service.injectCurrentTimeMillis(), flags, iconResId, null, null, disabledReason, null, null);
    }

    private static String parseCategory(ShortcutService service, AttributeSet attrs) {
        TypedArray sa = service.mContext.getResources().obtainAttributes(attrs, R.styleable.IntentCategory);
        try {
            if (sa.getType(0) != 3) {
                Log.w(TAG, "android:name must be string literal.");
                return null;
            }
            return sa.getString(0);
        } finally {
            sa.recycle();
        }
    }

    private static ShareTargetInfo parseShareTargetAttributes(ShortcutService service, AttributeSet attrs) {
        TypedArray sa = service.mContext.getResources().obtainAttributes(attrs, R.styleable.Intent);
        try {
            String targetClass = sa.getString(4);
            if (TextUtils.isEmpty(targetClass)) {
                Log.w(TAG, "android:targetClass must be provided.");
                return null;
            }
            return new ShareTargetInfo(null, targetClass, null);
        } finally {
            sa.recycle();
        }
    }

    private static ShareTargetInfo.TargetData parseShareTargetData(ShortcutService service, AttributeSet attrs) {
        TypedArray sa = service.mContext.getResources().obtainAttributes(attrs, R.styleable.AndroidManifestData);
        try {
            if (sa.getType(0) != 3) {
                Log.w(TAG, "android:mimeType must be string literal.");
                return null;
            }
            String scheme = sa.getString(1);
            String host = sa.getString(2);
            String port = sa.getString(3);
            String path = sa.getString(4);
            String pathPattern = sa.getString(6);
            String pathPrefix = sa.getString(5);
            String mimeType = sa.getString(0);
            return new ShareTargetInfo.TargetData(scheme, host, port, path, pathPattern, pathPrefix, mimeType);
        } finally {
            sa.recycle();
        }
    }
}
