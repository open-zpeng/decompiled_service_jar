package com.android.server.pm;

import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@VisibleForTesting
/* loaded from: classes.dex */
public class ModuleInfoProvider {
    private static final String MODULE_METADATA_KEY = "android.content.pm.MODULE_METADATA";
    private static final String TAG = "PackageManager.ModuleInfoProvider";
    private final Context mContext;
    private volatile boolean mMetadataLoaded;
    private final Map<String, ModuleInfo> mModuleInfo;
    private final IPackageManager mPackageManager;
    private volatile String mPackageName;

    /* JADX INFO: Access modifiers changed from: package-private */
    public ModuleInfoProvider(Context context, IPackageManager packageManager) {
        this.mContext = context;
        this.mPackageManager = packageManager;
        this.mModuleInfo = new ArrayMap();
    }

    @VisibleForTesting
    public ModuleInfoProvider(XmlResourceParser metadata, Resources resources) {
        this.mContext = null;
        this.mPackageManager = null;
        this.mModuleInfo = new ArrayMap();
        loadModuleMetadata(metadata, resources);
    }

    public void systemReady() {
        this.mPackageName = this.mContext.getResources().getString(17039707);
        if (TextUtils.isEmpty(this.mPackageName)) {
            Slog.w(TAG, "No configured module metadata provider.");
            return;
        }
        try {
            PackageInfo pi = this.mPackageManager.getPackageInfo(this.mPackageName, 128, 0);
            Context packageContext = this.mContext.createPackageContext(this.mPackageName, 0);
            Resources packageResources = packageContext.getResources();
            XmlResourceParser parser = packageResources.getXml(pi.applicationInfo.metaData.getInt(MODULE_METADATA_KEY));
            loadModuleMetadata(parser, packageResources);
        } catch (PackageManager.NameNotFoundException | RemoteException e) {
            Slog.w(TAG, "Unable to discover metadata package: " + this.mPackageName, e);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:9:0x0020, code lost:
        android.util.Slog.w(com.android.server.pm.ModuleInfoProvider.TAG, "Unexpected metadata element: " + r8.getName());
        r7.mModuleInfo.clear();
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void loadModuleMetadata(android.content.res.XmlResourceParser r8, android.content.res.Resources r9) {
        /*
            r7 = this;
            java.lang.String r0 = "PackageManager.ModuleInfoProvider"
            r1 = 1
            java.lang.String r2 = "module-metadata"
            com.android.internal.util.XmlUtils.beginDocument(r8, r2)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
        L9:
            com.android.internal.util.XmlUtils.nextElement(r8)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            int r2 = r8.getEventType()     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            if (r2 != r1) goto L13
            goto L3e
        L13:
            java.lang.String r2 = "module"
            java.lang.String r3 = r8.getName()     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            boolean r2 = r2.equals(r3)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            if (r2 != 0) goto L44
            java.lang.StringBuilder r2 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            r2.<init>()     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            java.lang.String r3 = "Unexpected metadata element: "
            r2.append(r3)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            java.lang.String r3 = r8.getName()     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            r2.append(r3)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            java.lang.String r2 = r2.toString()     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            android.util.Slog.w(r0, r2)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            java.util.Map<java.lang.String, android.content.pm.ModuleInfo> r2 = r7.mModuleInfo     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            r2.clear()     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
        L3e:
            r8.close()
            r7.mMetadataLoaded = r1
            goto L88
        L44:
            r2 = 0
            java.lang.String r3 = "name"
            java.lang.String r2 = r8.getAttributeValue(r2, r3)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            java.lang.String r2 = r2.substring(r1)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            int r2 = java.lang.Integer.parseInt(r2)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            java.lang.CharSequence r2 = r9.getText(r2)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            java.lang.String r3 = "packageName"
            java.lang.String r3 = com.android.internal.util.XmlUtils.readStringAttribute(r8, r3)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            java.lang.String r4 = "isHidden"
            boolean r4 = com.android.internal.util.XmlUtils.readBooleanAttribute(r8, r4)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            android.content.pm.ModuleInfo r5 = new android.content.pm.ModuleInfo     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            r5.<init>()     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            r5.setHidden(r4)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            r5.setPackageName(r3)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            r5.setName(r2)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            java.util.Map<java.lang.String, android.content.pm.ModuleInfo> r6 = r7.mModuleInfo     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            r6.put(r3, r5)     // Catch: java.lang.Throwable -> L7a java.lang.Throwable -> L7c
            goto L9
        L7a:
            r0 = move-exception
            goto L89
        L7c:
            r2 = move-exception
            java.lang.String r3 = "Error parsing module metadata"
            android.util.Slog.w(r0, r3, r2)     // Catch: java.lang.Throwable -> L7a
            java.util.Map<java.lang.String, android.content.pm.ModuleInfo> r0 = r7.mModuleInfo     // Catch: java.lang.Throwable -> L7a
            r0.clear()     // Catch: java.lang.Throwable -> L7a
            goto L3e
        L88:
            return
        L89:
            r8.close()
            r7.mMetadataLoaded = r1
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ModuleInfoProvider.loadModuleMetadata(android.content.res.XmlResourceParser, android.content.res.Resources):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public List<ModuleInfo> getInstalledModules(int flags) {
        if (!this.mMetadataLoaded) {
            throw new IllegalStateException("Call to getInstalledModules before metadata loaded");
        }
        if ((131072 & flags) != 0) {
            return new ArrayList(this.mModuleInfo.values());
        }
        try {
            List<PackageInfo> allPackages = this.mPackageManager.getInstalledPackages(1073741824 | flags, 0).getList();
            ArrayList<ModuleInfo> installedModules = new ArrayList<>(allPackages.size());
            for (PackageInfo p : allPackages) {
                ModuleInfo m = this.mModuleInfo.get(p.packageName);
                if (m != null) {
                    installedModules.add(m);
                }
            }
            return installedModules;
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to retrieve all package names", e);
            return Collections.emptyList();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ModuleInfo getModuleInfo(String packageName, int flags) {
        if (!this.mMetadataLoaded) {
            throw new IllegalStateException("Call to getModuleInfo before metadata loaded");
        }
        return this.mModuleInfo.get(packageName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getPackageName() {
        if (!this.mMetadataLoaded) {
            throw new IllegalStateException("Call to getVersion before metadata loaded");
        }
        return this.mPackageName;
    }
}
