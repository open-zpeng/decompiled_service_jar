package com.android.server.webkit;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Slog;
import android.webkit.UserPackage;
import android.webkit.WebViewFactory;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;
import com.android.server.backup.BackupManagerConstants;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
/* loaded from: classes.dex */
class WebViewUpdater {
    private static final String TAG = WebViewUpdater.class.getSimpleName();
    private static final int VALIDITY_INCORRECT_SDK_VERSION = 1;
    private static final int VALIDITY_INCORRECT_SIGNATURE = 3;
    private static final int VALIDITY_INCORRECT_VERSION_CODE = 2;
    private static final int VALIDITY_NO_LIBRARY_FLAG = 4;
    private static final int VALIDITY_OK = 0;
    private static final int WAIT_TIMEOUT_MS = 1000;
    private Context mContext;
    private SystemInterface mSystemInterface;
    private long mMinimumVersionCode = -1;
    private int mNumRelroCreationsStarted = 0;
    private int mNumRelroCreationsFinished = 0;
    private boolean mWebViewPackageDirty = false;
    private boolean mAnyWebViewInstalled = false;
    private int NUMBER_OF_RELROS_UNKNOWN = Integer.MAX_VALUE;
    private PackageInfo mCurrentWebViewPackage = null;
    private final Object mLock = new Object();

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class WebViewPackageMissingException extends Exception {
        public WebViewPackageMissingException(String message) {
            super(message);
        }

        public WebViewPackageMissingException(Exception e) {
            super(e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WebViewUpdater(Context context, SystemInterface systemInterface) {
        this.mContext = context;
        this.mSystemInterface = systemInterface;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:34:0x006d A[Catch: all -> 0x0071, WebViewPackageMissingException -> 0x0073, TRY_LEAVE, TryCatch #1 {WebViewPackageMissingException -> 0x0073, blocks: (B:8:0x001c, B:10:0x0025, B:12:0x002c, B:16:0x0036, B:18:0x003e, B:23:0x004a, B:25:0x0054, B:27:0x005c, B:32:0x0063, B:34:0x006d), top: B:51:0x001c, outer: #0 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void packageStateChanged(java.lang.String r13, int r14) {
        /*
            r12 = this;
            com.android.server.webkit.SystemInterface r0 = r12.mSystemInterface
            android.webkit.WebViewProviderInfo[] r0 = r0.getWebViewPackages()
            int r1 = r0.length
            r2 = 0
            r3 = r2
        L9:
            if (r3 >= r1) goto La0
            r4 = r0[r3]
            java.lang.String r5 = r4.packageName
            boolean r6 = r5.equals(r13)
            if (r6 == 0) goto L9c
            r0 = 0
            r1 = 0
            r3 = 0
            r6 = 0
            java.lang.Object r7 = r12.mLock
            monitor-enter(r7)
            android.content.pm.PackageInfo r8 = r12.findPreferredWebViewPackage()     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            r6 = r8
            android.content.pm.PackageInfo r8 = r12.mCurrentWebViewPackage     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            if (r8 == 0) goto L4a
            android.content.pm.PackageInfo r8 = r12.mCurrentWebViewPackage     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            java.lang.String r8 = r8.packageName     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            r3 = r8
            if (r14 != 0) goto L36
            java.lang.String r8 = r6.packageName     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            boolean r8 = r8.equals(r3)     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            if (r8 == 0) goto L36
            monitor-exit(r7)     // Catch: java.lang.Throwable -> L71
            return
        L36:
            java.lang.String r8 = r6.packageName     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            boolean r8 = r8.equals(r3)     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            if (r8 == 0) goto L4a
            long r8 = r6.lastUpdateTime     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            android.content.pm.PackageInfo r10 = r12.mCurrentWebViewPackage     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            long r10 = r10.lastUpdateTime     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            int r8 = (r8 > r10 ? 1 : (r8 == r10 ? 0 : -1))
            if (r8 != 0) goto L4a
            monitor-exit(r7)     // Catch: java.lang.Throwable -> L71
            return
        L4a:
            java.lang.String r8 = r4.packageName     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            java.lang.String r9 = r6.packageName     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            boolean r8 = r8.equals(r9)     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            if (r8 != 0) goto L62
            java.lang.String r8 = r4.packageName     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            boolean r8 = r8.equals(r3)     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            if (r8 != 0) goto L62
            android.content.pm.PackageInfo r8 = r12.mCurrentWebViewPackage     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            if (r8 != 0) goto L61
            goto L62
        L61:
            goto L63
        L62:
            r2 = 1
        L63:
            r0 = r2
            java.lang.String r2 = r4.packageName     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            boolean r2 = r2.equals(r3)     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
            r1 = r2
            if (r0 == 0) goto L70
            r12.onWebViewProviderChanged(r6)     // Catch: java.lang.Throwable -> L71 com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L73
        L70:
            goto L8d
        L71:
            r2 = move-exception
            goto L9a
        L73:
            r2 = move-exception
            r8 = 0
            r12.mCurrentWebViewPackage = r8     // Catch: java.lang.Throwable -> L71
            java.lang.String r8 = com.android.server.webkit.WebViewUpdater.TAG     // Catch: java.lang.Throwable -> L71
            java.lang.StringBuilder r9 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L71
            r9.<init>()     // Catch: java.lang.Throwable -> L71
            java.lang.String r10 = "Could not find valid WebView package to create relro with "
            r9.append(r10)     // Catch: java.lang.Throwable -> L71
            r9.append(r2)     // Catch: java.lang.Throwable -> L71
            java.lang.String r9 = r9.toString()     // Catch: java.lang.Throwable -> L71
            android.util.Slog.e(r8, r9)     // Catch: java.lang.Throwable -> L71
        L8d:
            monitor-exit(r7)     // Catch: java.lang.Throwable -> L71
            if (r0 == 0) goto L99
            if (r1 != 0) goto L99
            if (r3 == 0) goto L99
            com.android.server.webkit.SystemInterface r2 = r12.mSystemInterface
            r2.killPackageDependents(r3)
        L99:
            return
        L9a:
            monitor-exit(r7)     // Catch: java.lang.Throwable -> L71
            throw r2
        L9c:
            int r3 = r3 + 1
            goto L9
        La0:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.webkit.WebViewUpdater.packageStateChanged(java.lang.String, int):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareWebViewInSystemServer() {
        try {
            synchronized (this.mLock) {
                this.mCurrentWebViewPackage = findPreferredWebViewPackage();
                this.mSystemInterface.updateUserSetting(this.mContext, this.mCurrentWebViewPackage.packageName);
                onWebViewProviderChanged(this.mCurrentWebViewPackage);
            }
        } catch (Throwable t) {
            Slog.e(TAG, "error preparing webview provider from system server", t);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String changeProviderAndSetting(String newProviderName) {
        PackageInfo newPackage = updateCurrentWebViewPackage(newProviderName);
        return newPackage == null ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : newPackage.packageName;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:16:0x002b A[Catch: all -> 0x0057, TRY_ENTER, TryCatch #0 {, blocks: (B:4:0x0006, B:6:0x000b, B:7:0x0012, B:9:0x0019, B:16:0x002b, B:17:0x002e, B:24:0x003d, B:25:0x0055), top: B:30:0x0006, inners: #1 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public android.content.pm.PackageInfo updateCurrentWebViewPackage(java.lang.String r10) {
        /*
            r9 = this;
            r0 = 0
            r1 = 0
            r2 = 0
            java.lang.Object r3 = r9.mLock
            monitor-enter(r3)
            android.content.pm.PackageInfo r4 = r9.mCurrentWebViewPackage     // Catch: java.lang.Throwable -> L57
            r0 = r4
            if (r10 == 0) goto L12
            com.android.server.webkit.SystemInterface r4 = r9.mSystemInterface     // Catch: java.lang.Throwable -> L57
            android.content.Context r5 = r9.mContext     // Catch: java.lang.Throwable -> L57
            r4.updateUserSetting(r5, r10)     // Catch: java.lang.Throwable -> L57
        L12:
            android.content.pm.PackageInfo r4 = r9.findPreferredWebViewPackage()     // Catch: com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L3b java.lang.Throwable -> L57
            r1 = r4
            if (r0 == 0) goto L26
            java.lang.String r4 = r1.packageName     // Catch: com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L3b java.lang.Throwable -> L57
            java.lang.String r5 = r0.packageName     // Catch: com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L3b java.lang.Throwable -> L57
            boolean r4 = r4.equals(r5)     // Catch: com.android.server.webkit.WebViewUpdater.WebViewPackageMissingException -> L3b java.lang.Throwable -> L57
            if (r4 != 0) goto L24
            goto L26
        L24:
            r4 = 0
            goto L27
        L26:
            r4 = 1
        L27:
            r2 = r4
            if (r2 == 0) goto L2e
            r9.onWebViewProviderChanged(r1)     // Catch: java.lang.Throwable -> L57
        L2e:
            monitor-exit(r3)     // Catch: java.lang.Throwable -> L57
            if (r2 == 0) goto L3a
            if (r0 == 0) goto L3a
            com.android.server.webkit.SystemInterface r3 = r9.mSystemInterface
            java.lang.String r4 = r0.packageName
            r3.killPackageDependents(r4)
        L3a:
            return r1
        L3b:
            r4 = move-exception
            r5 = 0
            r9.mCurrentWebViewPackage = r5     // Catch: java.lang.Throwable -> L57
            java.lang.String r6 = com.android.server.webkit.WebViewUpdater.TAG     // Catch: java.lang.Throwable -> L57
            java.lang.StringBuilder r7 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L57
            r7.<init>()     // Catch: java.lang.Throwable -> L57
            java.lang.String r8 = "Couldn't find WebView package to use "
            r7.append(r8)     // Catch: java.lang.Throwable -> L57
            r7.append(r4)     // Catch: java.lang.Throwable -> L57
            java.lang.String r7 = r7.toString()     // Catch: java.lang.Throwable -> L57
            android.util.Slog.e(r6, r7)     // Catch: java.lang.Throwable -> L57
            monitor-exit(r3)     // Catch: java.lang.Throwable -> L57
            return r5
        L57:
            r4 = move-exception
            monitor-exit(r3)     // Catch: java.lang.Throwable -> L57
            throw r4
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.webkit.WebViewUpdater.updateCurrentWebViewPackage(java.lang.String):android.content.pm.PackageInfo");
    }

    private void onWebViewProviderChanged(PackageInfo newPackage) {
        synchronized (this.mLock) {
            this.mAnyWebViewInstalled = true;
            if (this.mNumRelroCreationsStarted == this.mNumRelroCreationsFinished) {
                this.mCurrentWebViewPackage = newPackage;
                this.mNumRelroCreationsStarted = this.NUMBER_OF_RELROS_UNKNOWN;
                this.mNumRelroCreationsFinished = 0;
                this.mNumRelroCreationsStarted = this.mSystemInterface.onWebViewProviderChanged(newPackage);
                checkIfRelrosDoneLocked();
            } else {
                this.mWebViewPackageDirty = true;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WebViewProviderInfo[] getValidWebViewPackages() {
        ProviderAndPackageInfo[] providersAndPackageInfos = getValidWebViewPackagesAndInfos();
        WebViewProviderInfo[] providers = new WebViewProviderInfo[providersAndPackageInfos.length];
        for (int n = 0; n < providersAndPackageInfos.length; n++) {
            providers[n] = providersAndPackageInfos[n].provider;
        }
        return providers;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ProviderAndPackageInfo {
        public final PackageInfo packageInfo;
        public final WebViewProviderInfo provider;

        public ProviderAndPackageInfo(WebViewProviderInfo provider, PackageInfo packageInfo) {
            this.provider = provider;
            this.packageInfo = packageInfo;
        }
    }

    private ProviderAndPackageInfo[] getValidWebViewPackagesAndInfos() {
        WebViewProviderInfo[] allProviders = this.mSystemInterface.getWebViewPackages();
        List<ProviderAndPackageInfo> providers = new ArrayList<>();
        for (int n = 0; n < allProviders.length; n++) {
            try {
                PackageInfo packageInfo = this.mSystemInterface.getPackageInfoForProvider(allProviders[n]);
                if (isValidProvider(allProviders[n], packageInfo)) {
                    providers.add(new ProviderAndPackageInfo(allProviders[n], packageInfo));
                }
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
        int n2 = providers.size();
        return (ProviderAndPackageInfo[]) providers.toArray(new ProviderAndPackageInfo[n2]);
    }

    private PackageInfo findPreferredWebViewPackage() throws WebViewPackageMissingException {
        ProviderAndPackageInfo[] providers = getValidWebViewPackagesAndInfos();
        String userChosenProvider = this.mSystemInterface.getUserChosenWebViewProvider(this.mContext);
        for (ProviderAndPackageInfo providerAndPackage : providers) {
            if (providerAndPackage.provider.packageName.equals(userChosenProvider)) {
                List<UserPackage> userPackages = this.mSystemInterface.getPackageInfoForProviderAllUsers(this.mContext, providerAndPackage.provider);
                if (isInstalledAndEnabledForAllUsers(userPackages)) {
                    return providerAndPackage.packageInfo;
                }
            }
        }
        for (ProviderAndPackageInfo providerAndPackage2 : providers) {
            if (providerAndPackage2.provider.availableByDefault) {
                List<UserPackage> userPackages2 = this.mSystemInterface.getPackageInfoForProviderAllUsers(this.mContext, providerAndPackage2.provider);
                if (isInstalledAndEnabledForAllUsers(userPackages2)) {
                    return providerAndPackage2.packageInfo;
                }
            }
        }
        this.mAnyWebViewInstalled = false;
        throw new WebViewPackageMissingException("Could not find a loadable WebView package");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:5:0x000a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static boolean isInstalledAndEnabledForAllUsers(java.util.List<android.webkit.UserPackage> r3) {
        /*
            java.util.Iterator r0 = r3.iterator()
        L4:
            boolean r1 = r0.hasNext()
            if (r1 == 0) goto L20
            java.lang.Object r1 = r0.next()
            android.webkit.UserPackage r1 = (android.webkit.UserPackage) r1
            boolean r2 = r1.isInstalledPackage()
            if (r2 == 0) goto L1e
            boolean r2 = r1.isEnabledPackage()
            if (r2 != 0) goto L1d
            goto L1e
        L1d:
            goto L4
        L1e:
            r0 = 0
            return r0
        L20:
            r0 = 1
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.webkit.WebViewUpdater.isInstalledAndEnabledForAllUsers(java.util.List):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyRelroCreationCompleted() {
        synchronized (this.mLock) {
            this.mNumRelroCreationsFinished++;
            checkIfRelrosDoneLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WebViewProviderResponse waitForAndGetProvider() {
        boolean webViewReady;
        PackageInfo webViewPackage;
        long timeoutTimeMs = (System.nanoTime() / 1000000) + 1000;
        int webViewStatus = 0;
        synchronized (this.mLock) {
            webViewReady = webViewIsReadyLocked();
            while (!webViewReady) {
                long timeNowMs = System.nanoTime() / 1000000;
                if (timeNowMs >= timeoutTimeMs) {
                    break;
                }
                try {
                    this.mLock.wait(timeoutTimeMs - timeNowMs);
                } catch (InterruptedException e) {
                }
                webViewReady = webViewIsReadyLocked();
            }
            webViewPackage = this.mCurrentWebViewPackage;
            if (!webViewReady) {
                if (this.mAnyWebViewInstalled) {
                    webViewStatus = 3;
                    Slog.e(TAG, "Timed out waiting for relro creation, relros started " + this.mNumRelroCreationsStarted + " relros finished " + this.mNumRelroCreationsFinished + " package dirty? " + this.mWebViewPackageDirty);
                } else {
                    webViewStatus = 4;
                }
            }
        }
        if (!webViewReady) {
            Slog.w(TAG, "creating relro file timed out");
        }
        return new WebViewProviderResponse(webViewPackage, webViewStatus);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageInfo getCurrentWebViewPackage() {
        PackageInfo packageInfo;
        synchronized (this.mLock) {
            packageInfo = this.mCurrentWebViewPackage;
        }
        return packageInfo;
    }

    private boolean webViewIsReadyLocked() {
        return !this.mWebViewPackageDirty && this.mNumRelroCreationsStarted == this.mNumRelroCreationsFinished && this.mAnyWebViewInstalled;
    }

    private void checkIfRelrosDoneLocked() {
        if (this.mNumRelroCreationsStarted == this.mNumRelroCreationsFinished) {
            if (this.mWebViewPackageDirty) {
                this.mWebViewPackageDirty = false;
                try {
                    PackageInfo newPackage = findPreferredWebViewPackage();
                    onWebViewProviderChanged(newPackage);
                    return;
                } catch (WebViewPackageMissingException e) {
                    this.mCurrentWebViewPackage = null;
                    return;
                }
            }
            this.mLock.notifyAll();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isValidProvider(WebViewProviderInfo configInfo, PackageInfo packageInfo) {
        return validityResult(configInfo, packageInfo) == 0;
    }

    private int validityResult(WebViewProviderInfo configInfo, PackageInfo packageInfo) {
        if (!UserPackage.hasCorrectTargetSdkVersion(packageInfo)) {
            return 1;
        }
        if (!versionCodeGE(packageInfo.getLongVersionCode(), getMinimumVersionCode()) && !this.mSystemInterface.systemIsDebuggable()) {
            return 2;
        }
        if (!providerHasValidSignature(configInfo, packageInfo, this.mSystemInterface)) {
            return 3;
        }
        if (WebViewFactory.getWebViewLibrary(packageInfo.applicationInfo) == null) {
            return 4;
        }
        return 0;
    }

    private static boolean versionCodeGE(long versionCode1, long versionCode2) {
        long v1 = versionCode1 / 100000;
        long v2 = versionCode2 / 100000;
        return v1 >= v2;
    }

    private long getMinimumVersionCode() {
        WebViewProviderInfo[] webViewPackages;
        if (this.mMinimumVersionCode > 0) {
            return this.mMinimumVersionCode;
        }
        long minimumVersionCode = -1;
        for (WebViewProviderInfo provider : this.mSystemInterface.getWebViewPackages()) {
            if (provider.availableByDefault && !provider.isFallback) {
                try {
                    long versionCode = this.mSystemInterface.getFactoryPackageVersion(provider.packageName);
                    if (minimumVersionCode < 0 || versionCode < minimumVersionCode) {
                        minimumVersionCode = versionCode;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
        }
        this.mMinimumVersionCode = minimumVersionCode;
        return this.mMinimumVersionCode;
    }

    private static boolean providerHasValidSignature(WebViewProviderInfo provider, PackageInfo packageInfo, SystemInterface systemInterface) {
        Signature[] signatureArr;
        if (systemInterface.systemIsDebuggable()) {
            return true;
        }
        if (provider.signatures == null || provider.signatures.length == 0) {
            return packageInfo.applicationInfo.isSystemApp();
        }
        if (packageInfo.signatures.length != 1) {
            return false;
        }
        for (Signature signature : provider.signatures) {
            if (signature.equals(packageInfo.signatures[0])) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpState(PrintWriter pw) {
        synchronized (this.mLock) {
            if (this.mCurrentWebViewPackage == null) {
                pw.println("  Current WebView package is null");
            } else {
                pw.println(String.format("  Current WebView package (name, version): (%s, %s)", this.mCurrentWebViewPackage.packageName, this.mCurrentWebViewPackage.versionName));
            }
            pw.println(String.format("  Minimum WebView version code: %d", Long.valueOf(this.mMinimumVersionCode)));
            pw.println(String.format("  Number of relros started: %d", Integer.valueOf(this.mNumRelroCreationsStarted)));
            pw.println(String.format("  Number of relros finished: %d", Integer.valueOf(this.mNumRelroCreationsFinished)));
            pw.println(String.format("  WebView package dirty: %b", Boolean.valueOf(this.mWebViewPackageDirty)));
            pw.println(String.format("  Any WebView package installed: %b", Boolean.valueOf(this.mAnyWebViewInstalled)));
            try {
                PackageInfo preferredWebViewPackage = findPreferredWebViewPackage();
                pw.println(String.format("  Preferred WebView package (name, version): (%s, %s)", preferredWebViewPackage.packageName, preferredWebViewPackage.versionName));
            } catch (WebViewPackageMissingException e) {
                pw.println(String.format("  Preferred WebView package: none", new Object[0]));
            }
            dumpAllPackageInformationLocked(pw);
        }
    }

    private void dumpAllPackageInformationLocked(PrintWriter pw) {
        WebViewProviderInfo[] allProviders = this.mSystemInterface.getWebViewPackages();
        pw.println("  WebView packages:");
        for (WebViewProviderInfo provider : allProviders) {
            List<UserPackage> userPackages = this.mSystemInterface.getPackageInfoForProviderAllUsers(this.mContext, provider);
            PackageInfo systemUserPackageInfo = userPackages.get(0).getPackageInfo();
            if (systemUserPackageInfo == null) {
                pw.println(String.format("    %s is NOT installed.", provider.packageName));
            } else {
                int validity = validityResult(provider, systemUserPackageInfo);
                String packageDetails = String.format("versionName: %s, versionCode: %d, targetSdkVersion: %d", systemUserPackageInfo.versionName, Long.valueOf(systemUserPackageInfo.getLongVersionCode()), Integer.valueOf(systemUserPackageInfo.applicationInfo.targetSdkVersion));
                if (validity != 0) {
                    pw.println(String.format("    Invalid package %s (%s), reason: %s", systemUserPackageInfo.packageName, packageDetails, getInvalidityReason(validity)));
                } else {
                    boolean installedForAllUsers = isInstalledAndEnabledForAllUsers(this.mSystemInterface.getPackageInfoForProviderAllUsers(this.mContext, provider));
                    Object[] objArr = new Object[3];
                    objArr[0] = systemUserPackageInfo.packageName;
                    objArr[1] = packageDetails;
                    objArr[2] = installedForAllUsers ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : "NOT";
                    pw.println(String.format("    Valid package %s (%s) is %s installed/enabled for all users", objArr));
                }
            }
        }
    }

    private static String getInvalidityReason(int invalidityReason) {
        switch (invalidityReason) {
            case 1:
                return "SDK version too low";
            case 2:
                return "Version code too low";
            case 3:
                return "Incorrect signature";
            case 4:
                return "No WebView-library manifest flag";
            default:
                return "Unexcepted validity-reason";
        }
    }
}
