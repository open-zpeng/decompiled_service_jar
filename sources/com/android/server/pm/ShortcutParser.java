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
    private static final String TAG_INTENT = "intent";
    private static final String TAG_SHORTCUT = "shortcut";
    private static final String TAG_SHORTCUTS = "shortcuts";

    public static List<ShortcutInfo> parseShortcuts(ShortcutService service, String packageName, int userId) throws IOException, XmlPullParserException {
        ActivityInfo activityInfoWithMetadata;
        List<ResolveInfo> activities = service.injectGetMainActivities(packageName, userId);
        if (activities == null || activities.size() == 0) {
            return null;
        }
        List<ShortcutInfo> result = null;
        try {
            int size = activities.size();
            for (int i = 0; i < size; i++) {
                ActivityInfo activityInfoNoMetadata = activities.get(i).activityInfo;
                if (activityInfoNoMetadata != null && (activityInfoWithMetadata = service.getActivityInfoWithMetadata(activityInfoNoMetadata.getComponentName(), userId)) != null) {
                    result = parseShortcutsOneFile(service, activityInfoWithMetadata, packageName, userId, result);
                }
            }
            return result;
        } catch (RuntimeException e) {
            service.wtf("Exception caught while parsing shortcut XML for package=" + packageName, e);
            return null;
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:117:0x02a4, code lost:
        if (r9 == null) goto L160;
     */
    /* JADX WARN: Code restructure failed: missing block: B:118:0x02a6, code lost:
        r9.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:119:0x02a9, code lost:
        return r12;
     */
    /* JADX WARN: Code restructure failed: missing block: B:39:0x00c4, code lost:
        android.util.Log.e(com.android.server.pm.ShortcutParser.TAG, "More than " + r1 + " shortcuts found for " + r23.getComponentName() + ". Skipping the rest.");
     */
    /* JADX WARN: Code restructure failed: missing block: B:40:0x00ec, code lost:
        if (r9 == null) goto L144;
     */
    /* JADX WARN: Code restructure failed: missing block: B:41:0x00ee, code lost:
        r9.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:42:0x00f1, code lost:
        return r4;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static java.util.List<android.content.pm.ShortcutInfo> parseShortcutsOneFile(com.android.server.pm.ShortcutService r22, android.content.pm.ActivityInfo r23, java.lang.String r24, int r25, java.util.List<android.content.pm.ShortcutInfo> r26) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
        /*
            Method dump skipped, instructions count: 699
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutParser.parseShortcutsOneFile(com.android.server.pm.ShortcutService, android.content.pm.ActivityInfo, java.lang.String, int, java.util.List):java.util.List");
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
        int flags = (enabled ? 32 : 64) | 256 | (iconResId != 0 ? 4 : 0);
        int disabledReason = enabled ? 0 : 1;
        return new ShortcutInfo(userId, id, packageName, activityComponent, null, null, titleResId, null, null, textResId, null, null, disabledMessageResId, null, null, null, rank, null, service.injectCurrentTimeMillis(), flags, iconResId, null, null, disabledReason);
    }
}
