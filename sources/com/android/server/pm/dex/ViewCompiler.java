package com.android.server.pm.dex;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.os.Binder;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.server.pm.Installer;

/* loaded from: classes.dex */
public class ViewCompiler {
    private final Object mInstallLock;
    @GuardedBy({"mInstallLock"})
    private final Installer mInstaller;

    public ViewCompiler(Object installLock, Installer installer) {
        this.mInstallLock = installLock;
        this.mInstaller = installer;
    }

    public boolean compileLayouts(PackageParser.Package pkg) {
        boolean compileLayouts;
        try {
            String packageName = pkg.packageName;
            String apkPath = pkg.baseCodePath;
            ApplicationInfo appInfo = pkg.applicationInfo;
            String outDexFile = appInfo.dataDir + "/code_cache/compiled_view.dex";
            Log.i("PackageManager", "Compiling layouts in " + packageName + " (" + apkPath + ") to " + outDexFile);
            long callingId = Binder.clearCallingIdentity();
            synchronized (this.mInstallLock) {
                compileLayouts = this.mInstaller.compileLayouts(apkPath, packageName, outDexFile, appInfo.uid);
            }
            Binder.restoreCallingIdentity(callingId);
            return compileLayouts;
        } catch (Throwable e) {
            Log.e("PackageManager", "Failed to compile layouts", e);
            return false;
        }
    }
}
