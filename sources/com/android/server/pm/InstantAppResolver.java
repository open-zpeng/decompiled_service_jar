package com.android.server.pm;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.InstantAppIntentFilter;
import android.content.pm.InstantAppRequest;
import android.content.pm.InstantAppResolveInfo;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.logging.MetricsLogger;
import com.android.server.pm.ComponentResolver;
import com.android.server.pm.InstantAppResolverConnection;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/* loaded from: classes.dex */
public abstract class InstantAppResolver {
    private static final boolean DEBUG_INSTANT = Build.IS_DEBUGGABLE;
    private static final int RESOLUTION_BIND_TIMEOUT = 2;
    private static final int RESOLUTION_CALL_TIMEOUT = 3;
    private static final int RESOLUTION_FAILURE = 1;
    private static final int RESOLUTION_SUCCESS = 0;
    private static final String TAG = "PackageManager";
    private static MetricsLogger sMetricsLogger;

    @Retention(RetentionPolicy.SOURCE)
    /* loaded from: classes.dex */
    public @interface ResolutionStatus {
    }

    private static MetricsLogger getLogger() {
        if (sMetricsLogger == null) {
            sMetricsLogger = new MetricsLogger();
        }
        return sMetricsLogger;
    }

    public static Intent sanitizeIntent(Intent origIntent) {
        Uri sanitizedUri;
        Intent sanitizedIntent = new Intent(origIntent.getAction());
        Set<String> categories = origIntent.getCategories();
        if (categories != null) {
            for (String category : categories) {
                sanitizedIntent.addCategory(category);
            }
        }
        if (origIntent.getData() == null) {
            sanitizedUri = null;
        } else {
            sanitizedUri = Uri.fromParts(origIntent.getScheme(), "", "");
        }
        sanitizedIntent.setDataAndType(sanitizedUri, origIntent.getType());
        sanitizedIntent.addFlags(origIntent.getFlags());
        sanitizedIntent.setPackage(origIntent.getPackage());
        return sanitizedIntent;
    }

    /* JADX WARN: Removed duplicated region for block: B:35:0x009f  */
    /* JADX WARN: Removed duplicated region for block: B:36:0x00b7  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static android.content.pm.AuxiliaryResolveInfo doInstantAppResolutionPhaseOne(com.android.server.pm.InstantAppResolverConnection r19, android.content.pm.InstantAppRequest r20) {
        /*
            Method dump skipped, instructions count: 282
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.InstantAppResolver.doInstantAppResolutionPhaseOne(com.android.server.pm.InstantAppResolverConnection, android.content.pm.InstantAppRequest):android.content.pm.AuxiliaryResolveInfo");
    }

    public static void doInstantAppResolutionPhaseTwo(final Context context, InstantAppResolverConnection connection, final InstantAppRequest requestObj, final ActivityInfo instantAppInstaller, Handler callbackHandler) {
        String str;
        String str2;
        long startTime;
        String token;
        int[] digestPrefixSecure;
        int i;
        long startTime2 = System.currentTimeMillis();
        final String token2 = requestObj.responseObj.token;
        if (DEBUG_INSTANT) {
            Log.d(TAG, "[" + token2 + "] Phase2; resolving");
        }
        final Intent origIntent = requestObj.origIntent;
        final Intent sanitizedIntent = sanitizeIntent(origIntent);
        InstantAppResolverConnection.PhaseTwoCallback callback = new InstantAppResolverConnection.PhaseTwoCallback() { // from class: com.android.server.pm.InstantAppResolver.1
            /* JADX INFO: Access modifiers changed from: package-private */
            @Override // com.android.server.pm.InstantAppResolverConnection.PhaseTwoCallback
            public void onPhaseTwoResolved(List<InstantAppResolveInfo> instantAppResolveInfoList, long startTime3) {
                Intent failureIntent;
                if (instantAppResolveInfoList != null && instantAppResolveInfoList.size() > 0) {
                    Intent intent = origIntent;
                    AuxiliaryResolveInfo instantAppIntentInfo = InstantAppResolver.filterInstantAppIntent(instantAppResolveInfoList, intent, null, 0, intent.getPackage(), requestObj.digest, token2);
                    if (instantAppIntentInfo != null) {
                        failureIntent = instantAppIntentInfo.failureIntent;
                    } else {
                        failureIntent = null;
                    }
                } else {
                    failureIntent = null;
                }
                Intent installerIntent = InstantAppResolver.buildEphemeralInstallerIntent(requestObj.origIntent, sanitizedIntent, failureIntent, requestObj.callingPackage, requestObj.verificationBundle, requestObj.resolvedType, requestObj.userId, requestObj.responseObj.installFailureActivity, token2, false, requestObj.responseObj.filters);
                installerIntent.setComponent(new ComponentName(instantAppInstaller.packageName, instantAppInstaller.name));
                InstantAppResolver.logMetrics(900, startTime3, token2, requestObj.responseObj.filters != null ? 0 : 1);
                context.startActivity(installerIntent);
            }
        };
        try {
            digestPrefixSecure = requestObj.digest.getDigestPrefixSecure();
            i = requestObj.userId;
            str = TAG;
            str2 = "[";
            startTime = startTime2;
            token = token2;
        } catch (InstantAppResolverConnection.ConnectionException e) {
            e = e;
            str = TAG;
            str2 = "[";
            startTime = startTime2;
            token = token2;
        }
        try {
            connection.getInstantAppIntentFilterList(sanitizedIntent, digestPrefixSecure, i, token2, callback, callbackHandler, startTime);
        } catch (InstantAppResolverConnection.ConnectionException e2) {
            e = e2;
            int resolutionStatus = 1;
            if (e.failure == 1) {
                resolutionStatus = 2;
            }
            logMetrics(900, startTime, token, resolutionStatus);
            if (DEBUG_INSTANT) {
                if (resolutionStatus == 2) {
                    Log.d(str, str2 + token + "] Phase2; bind timed out");
                    return;
                }
                Log.d(str, str2 + token + "] Phase2; service connection error");
            }
        }
    }

    public static Intent buildEphemeralInstallerIntent(Intent origIntent, Intent sanitizedIntent, Intent failureIntent, String callingPackage, Bundle verificationBundle, String resolvedType, int userId, ComponentName installFailureActivity, String token, boolean needsPhaseTwo, List<AuxiliaryResolveInfo.AuxiliaryFilter> filters) {
        Intent onFailureIntent;
        int flags = origIntent.getFlags();
        Intent intent = new Intent();
        intent.setFlags(1073741824 | flags | DumpState.DUMP_VOLUMES);
        if (token != null) {
            intent.putExtra("android.intent.extra.INSTANT_APP_TOKEN", token);
        }
        if (origIntent.getData() != null) {
            intent.putExtra("android.intent.extra.INSTANT_APP_HOSTNAME", origIntent.getData().getHost());
        }
        intent.putExtra("android.intent.extra.INSTANT_APP_ACTION", origIntent.getAction());
        intent.putExtra("android.intent.extra.INTENT", sanitizedIntent);
        if (!needsPhaseTwo) {
            if (failureIntent != null || installFailureActivity != null) {
                if (installFailureActivity != null) {
                    try {
                        onFailureIntent = new Intent();
                        onFailureIntent.setComponent(installFailureActivity);
                        if (filters != null && filters.size() == 1) {
                            onFailureIntent.putExtra("android.intent.extra.SPLIT_NAME", filters.get(0).splitName);
                        }
                        onFailureIntent.putExtra("android.intent.extra.INTENT", origIntent);
                    } catch (RemoteException e) {
                    }
                } else {
                    onFailureIntent = failureIntent;
                }
                IIntentSender failureIntentTarget = ActivityManager.getService().getIntentSender(2, callingPackage, (IBinder) null, (String) null, 1, new Intent[]{onFailureIntent}, new String[]{resolvedType}, 1409286144, (Bundle) null, userId);
                IntentSender failureSender = new IntentSender(failureIntentTarget);
                intent.putExtra("android.intent.extra.INSTANT_APP_FAILURE", failureSender);
            }
            Intent successIntent = new Intent(origIntent);
            successIntent.setLaunchToken(token);
            try {
                IIntentSender successIntentTarget = ActivityManager.getService().getIntentSender(2, callingPackage, (IBinder) null, (String) null, 0, new Intent[]{successIntent}, new String[]{resolvedType}, 1409286144, (Bundle) null, userId);
                IntentSender successSender = new IntentSender(successIntentTarget);
                intent.putExtra("android.intent.extra.INSTANT_APP_SUCCESS", successSender);
            } catch (RemoteException e2) {
            }
            if (verificationBundle != null) {
                intent.putExtra("android.intent.extra.VERIFICATION_BUNDLE", verificationBundle);
            }
            intent.putExtra("android.intent.extra.CALLING_PACKAGE", callingPackage);
            if (filters != null) {
                Bundle[] resolvableFilters = new Bundle[filters.size()];
                int max = filters.size();
                for (int i = 0; i < max; i++) {
                    Bundle resolvableFilter = new Bundle();
                    AuxiliaryResolveInfo.AuxiliaryFilter filter = filters.get(i);
                    resolvableFilter.putBoolean("android.intent.extra.UNKNOWN_INSTANT_APP", filter.resolveInfo != null && filter.resolveInfo.shouldLetInstallerDecide());
                    resolvableFilter.putString("android.intent.extra.PACKAGE_NAME", filter.packageName);
                    resolvableFilter.putString("android.intent.extra.SPLIT_NAME", filter.splitName);
                    resolvableFilter.putLong("android.intent.extra.LONG_VERSION_CODE", filter.versionCode);
                    resolvableFilter.putBundle("android.intent.extra.INSTANT_APP_EXTRAS", filter.extras);
                    resolvableFilters[i] = resolvableFilter;
                    if (i == 0) {
                        intent.putExtras(resolvableFilter);
                        intent.putExtra("android.intent.extra.VERSION_CODE", (int) filter.versionCode);
                    }
                }
                intent.putExtra("android.intent.extra.INSTANT_APP_BUNDLES", resolvableFilters);
            }
            intent.setAction("android.intent.action.INSTALL_INSTANT_APP_PACKAGE");
        } else {
            intent.setAction("android.intent.action.RESOLVE_INSTANT_APP_PACKAGE");
        }
        return intent;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Incorrect condition in loop: B:13:0x002d */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static android.content.pm.AuxiliaryResolveInfo filterInstantAppIntent(java.util.List<android.content.pm.InstantAppResolveInfo> r16, android.content.Intent r17, java.lang.String r18, int r19, java.lang.String r20, android.content.pm.InstantAppResolveInfo.InstantAppDigest r21, java.lang.String r22) {
        /*
            r6 = r22
            int[] r7 = r21.getDigestPrefix()
            byte[][] r8 = r21.getDigestBytes()
            r0 = 0
            r1 = 0
            boolean r2 = r17.isWebIntent()
            r9 = 1
            if (r2 != 0) goto L21
            int r2 = r7.length
            if (r2 <= 0) goto L1f
            int r2 = r17.getFlags()
            r2 = r2 & 2048(0x800, float:2.87E-42)
            if (r2 != 0) goto L1f
            goto L21
        L1f:
            r2 = 0
            goto L22
        L21:
            r2 = r9
        L22:
            r10 = r2
            java.util.Iterator r11 = r16.iterator()
            r13 = r0
            r12 = r1
        L29:
            boolean r0 = r11.hasNext()
            if (r0 == 0) goto L8c
            java.lang.Object r0 = r11.next()
            r14 = r0
            android.content.pm.InstantAppResolveInfo r14 = (android.content.pm.InstantAppResolveInfo) r14
            if (r10 == 0) goto L46
            boolean r0 = r14.shouldLetInstallerDecide()
            if (r0 == 0) goto L46
            java.lang.String r0 = "PackageManager"
            java.lang.String r1 = "InstantAppResolveInfo with mShouldLetInstallerDecide=true when digest required; ignoring"
            android.util.Slog.d(r0, r1)
            goto L29
        L46:
            byte[] r15 = r14.getDigestBytes()
            int r0 = r7.length
            if (r0 <= 0) goto L67
            if (r10 != 0) goto L52
            int r0 = r15.length
            if (r0 <= 0) goto L67
        L52:
            r0 = 0
            int r1 = r7.length
            int r1 = r1 - r9
        L55:
            if (r1 < 0) goto L64
            r2 = r8[r1]
            boolean r2 = java.util.Arrays.equals(r2, r15)
            if (r2 == 0) goto L61
            r0 = 1
            goto L64
        L61:
            int r1 = r1 + (-1)
            goto L55
        L64:
            if (r0 != 0) goto L67
            goto L29
        L67:
            r0 = r17
            r1 = r18
            r2 = r19
            r3 = r20
            r4 = r22
            r5 = r14
            java.util.List r0 = computeResolveFilters(r0, r1, r2, r3, r4, r5)
            if (r0 == 0) goto L8b
            boolean r1 = r0.isEmpty()
            if (r1 == 0) goto L7f
            r13 = 1
        L7f:
            if (r12 != 0) goto L88
            java.util.ArrayList r1 = new java.util.ArrayList
            r1.<init>(r0)
            r12 = r1
            goto L8b
        L88:
            r12.addAll(r0)
        L8b:
            goto L29
        L8c:
            if (r12 == 0) goto La0
            boolean r0 = r12.isEmpty()
            if (r0 != 0) goto La0
            android.content.pm.AuxiliaryResolveInfo r0 = new android.content.pm.AuxiliaryResolveInfo
            r1 = r17
            android.content.Intent r2 = createFailureIntent(r1, r6)
            r0.<init>(r6, r13, r2, r12)
            return r0
        La0:
            r1 = r17
            r0 = 0
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.InstantAppResolver.filterInstantAppIntent(java.util.List, android.content.Intent, java.lang.String, int, java.lang.String, android.content.pm.InstantAppResolveInfo$InstantAppDigest, java.lang.String):android.content.pm.AuxiliaryResolveInfo");
    }

    private static Intent createFailureIntent(Intent origIntent, String token) {
        Intent failureIntent = new Intent(origIntent);
        failureIntent.setFlags(failureIntent.getFlags() | 512);
        failureIntent.setFlags(failureIntent.getFlags() & (-2049));
        failureIntent.setLaunchToken(token);
        return failureIntent;
    }

    private static List<AuxiliaryResolveInfo.AuxiliaryFilter> computeResolveFilters(Intent origIntent, String resolvedType, int userId, String packageName, String token, InstantAppResolveInfo instantAppInfo) {
        if (instantAppInfo.shouldLetInstallerDecide()) {
            return Collections.singletonList(new AuxiliaryResolveInfo.AuxiliaryFilter(instantAppInfo, (String) null, instantAppInfo.getExtras()));
        }
        if (packageName == null || packageName.equals(instantAppInfo.getPackageName())) {
            List<InstantAppIntentFilter> instantAppFilters = instantAppInfo.getIntentFilters();
            if (instantAppFilters != null && !instantAppFilters.isEmpty()) {
                ComponentResolver.InstantAppIntentResolver instantAppResolver = new ComponentResolver.InstantAppIntentResolver();
                for (int j = instantAppFilters.size() - 1; j >= 0; j--) {
                    InstantAppIntentFilter instantAppFilter = instantAppFilters.get(j);
                    List<IntentFilter> splitFilters = instantAppFilter.getFilters();
                    if (splitFilters != null && !splitFilters.isEmpty()) {
                        for (int k = splitFilters.size() - 1; k >= 0; k--) {
                            IntentFilter filter = splitFilters.get(k);
                            Iterator<IntentFilter.AuthorityEntry> authorities = filter.authoritiesIterator();
                            if ((authorities != null && authorities.hasNext()) || ((!filter.hasDataScheme("http") && !filter.hasDataScheme("https")) || !filter.hasAction("android.intent.action.VIEW") || !filter.hasCategory("android.intent.category.BROWSABLE"))) {
                                instantAppResolver.addFilter(new AuxiliaryResolveInfo.AuxiliaryFilter(filter, instantAppInfo, instantAppFilter.getSplitName(), instantAppInfo.getExtras()));
                            }
                        }
                    }
                }
                List<AuxiliaryResolveInfo.AuxiliaryFilter> matchedResolveInfoList = instantAppResolver.queryIntent(origIntent, resolvedType, false, userId);
                if (!matchedResolveInfoList.isEmpty()) {
                    if (DEBUG_INSTANT) {
                        Log.d(TAG, "[" + token + "] Found match(es); " + matchedResolveInfoList);
                    }
                    return matchedResolveInfoList;
                }
                if (DEBUG_INSTANT) {
                    Log.d(TAG, "[" + token + "] No matches found package: " + instantAppInfo.getPackageName() + ", versionCode: " + instantAppInfo.getVersionCode());
                }
                return null;
            }
            if (origIntent.isWebIntent()) {
                return null;
            }
            if (DEBUG_INSTANT) {
                Log.d(TAG, "No app filters; go to phase 2");
            }
            return Collections.emptyList();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void logMetrics(int action, long startTime, String token, int status) {
        LogMaker logMaker = new LogMaker(action).setType(4).addTaggedData(901, new Long(System.currentTimeMillis() - startTime)).addTaggedData(903, token).addTaggedData(902, new Integer(status));
        getLogger().write(logMaker);
    }
}
