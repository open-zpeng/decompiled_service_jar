package com.android.server.wm;

import android.content.res.Resources;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.concurrent.Executor;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class HighRefreshRateBlacklist {
    private final String[] mDefaultBlacklist;
    private final ArraySet<String> mBlacklistedPackages = new ArraySet<>();
    private final Object mLock = new Object();

    /* loaded from: classes2.dex */
    interface DeviceConfigInterface {
        void addOnPropertyChangedListener(String str, Executor executor, DeviceConfig.OnPropertyChangedListener onPropertyChangedListener);

        String getProperty(String str, String str2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static HighRefreshRateBlacklist create(Resources r) {
        return new HighRefreshRateBlacklist(r, new DeviceConfigInterface() { // from class: com.android.server.wm.HighRefreshRateBlacklist.1
            @Override // com.android.server.wm.HighRefreshRateBlacklist.DeviceConfigInterface
            public String getProperty(String namespace, String name) {
                return DeviceConfig.getProperty(namespace, name);
            }

            @Override // com.android.server.wm.HighRefreshRateBlacklist.DeviceConfigInterface
            public void addOnPropertyChangedListener(String namespace, Executor executor, DeviceConfig.OnPropertyChangedListener listener) {
                DeviceConfig.addOnPropertyChangedListener(namespace, executor, listener);
            }
        });
    }

    @VisibleForTesting
    HighRefreshRateBlacklist(Resources r, DeviceConfigInterface deviceConfig) {
        this.mDefaultBlacklist = r.getStringArray(17236039);
        deviceConfig.addOnPropertyChangedListener("display_manager", BackgroundThread.getExecutor(), new OnPropertyChangedListener());
        String property = deviceConfig.getProperty("display_manager", "high_refresh_rate_blacklist");
        updateBlacklist(property);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateBlacklist(String property) {
        synchronized (this.mLock) {
            this.mBlacklistedPackages.clear();
            int i = 0;
            if (property != null) {
                String[] packages = property.split(",");
                int length = packages.length;
                while (i < length) {
                    String pkg = packages[i];
                    String pkgName = pkg.trim();
                    if (!pkgName.isEmpty()) {
                        this.mBlacklistedPackages.add(pkgName);
                    }
                    i++;
                }
            } else {
                String[] strArr = this.mDefaultBlacklist;
                int length2 = strArr.length;
                while (i < length2) {
                    String pkg2 = strArr[i];
                    this.mBlacklistedPackages.add(pkg2);
                    i++;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isBlacklisted(String packageName) {
        boolean contains;
        synchronized (this.mLock) {
            contains = this.mBlacklistedPackages.contains(packageName);
        }
        return contains;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw) {
        pw.println("High Refresh Rate Blacklist");
        pw.println("  Packages:");
        synchronized (this.mLock) {
            Iterator<String> it = this.mBlacklistedPackages.iterator();
            while (it.hasNext()) {
                String pkg = it.next();
                pw.println("    " + pkg);
            }
        }
    }

    /* loaded from: classes2.dex */
    private class OnPropertyChangedListener implements DeviceConfig.OnPropertyChangedListener {
        private OnPropertyChangedListener() {
        }

        public void onPropertyChanged(String namespace, String name, String value) {
            if ("high_refresh_rate_blacklist".equals(name)) {
                HighRefreshRateBlacklist.this.updateBlacklist(value);
            }
        }
    }
}
