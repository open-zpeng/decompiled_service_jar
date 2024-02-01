package com.android.server.wm;

import android.app.ActivityManager;
import android.content.Context;
import android.util.ArraySet;
import android.view.WindowManager;
import com.android.server.usb.descriptors.UsbACInterface;
import com.xiaopeng.view.xpWindowManager;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class PolicyControl {
    private static final boolean DEBUG = false;
    private static final String NAME_IMMERSIVE_FULL = "immersive.full";
    private static final String NAME_IMMERSIVE_NAVIGATION = "immersive.navigation";
    private static final String NAME_IMMERSIVE_PRECONFIRMATIONS = "immersive.preconfirms";
    private static final String NAME_IMMERSIVE_STATUS = "immersive.status";
    private static final String TAG = "PolicyControl";
    private static Filter sImmersiveNavigationFilter;
    private static Filter sImmersivePreconfirmationsFilter;
    private static Filter sImmersiveStatusFilter;
    private static String sSettingValue;

    PolicyControl() {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int getSystemUiVisibility(WindowState win, WindowManager.LayoutParams attrs) {
        WindowManager.LayoutParams attrs2 = attrs != null ? attrs : win.getAttrs();
        int vis = win != null ? win.getSystemUiVisibility() : attrs2.systemUiVisibility | attrs2.subtreeSystemUiVisibility;
        Filter filter = sImmersiveStatusFilter;
        if (filter != null && filter.matches(attrs2)) {
            int vis2 = vis | 4100;
            if (attrs2.isFullscreen()) {
                vis2 |= 1024;
            }
            vis = vis2 & (-1073742081);
        }
        Filter filter2 = sImmersiveNavigationFilter;
        if (filter2 != null && filter2.matches(attrs2)) {
            int vis3 = vis | UsbACInterface.FORMAT_II_AC3;
            if (attrs2.isFullscreen()) {
                vis3 |= 512;
            }
            vis = vis3 & 2147483391;
        }
        WindowManager.LayoutParams lp = xpWindowManager.getOverrideLayoutParams(attrs2);
        if (lp != null) {
            return lp.systemUiVisibility;
        }
        return vis;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int getWindowFlags(WindowState win, WindowManager.LayoutParams attrs) {
        WindowManager.LayoutParams attrs2 = attrs != null ? attrs : win.getAttrs();
        int flags = attrs2.flags;
        Filter filter = sImmersiveStatusFilter;
        if (filter != null && filter.matches(attrs2)) {
            flags = (flags | 1024) & (-67110913);
        }
        Filter filter2 = sImmersiveNavigationFilter;
        if (filter2 != null && filter2.matches(attrs2)) {
            flags &= -134217729;
        }
        WindowManager.LayoutParams lp = xpWindowManager.getOverrideLayoutParams(attrs2);
        if (lp != null) {
            return lp.flags;
        }
        return flags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int adjustClearableFlags(WindowState win, int clearableFlags) {
        WindowManager.LayoutParams attrs = win != null ? win.getAttrs() : null;
        Filter filter = sImmersiveStatusFilter;
        if (filter != null && filter.matches(attrs)) {
            return clearableFlags & (-5);
        }
        return clearableFlags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean disableImmersiveConfirmation(String pkg) {
        Filter filter = sImmersivePreconfirmationsFilter;
        return (filter != null && filter.matches(pkg)) || ActivityManager.isRunningInTestHarness();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static boolean reloadFromSetting(Context context) {
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void dump(String prefix, PrintWriter pw) {
        dump("sImmersiveStatusFilter", sImmersiveStatusFilter, prefix, pw);
        dump("sImmersiveNavigationFilter", sImmersiveNavigationFilter, prefix, pw);
        dump("sImmersivePreconfirmationsFilter", sImmersivePreconfirmationsFilter, prefix, pw);
    }

    private static void dump(String name, Filter filter, String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("PolicyControl.");
        pw.print(name);
        pw.print('=');
        if (filter == null) {
            pw.println("null");
            return;
        }
        filter.dump(pw);
        pw.println();
    }

    private static void setFilters(String value) {
        sImmersiveStatusFilter = null;
        sImmersiveNavigationFilter = null;
        sImmersivePreconfirmationsFilter = null;
        if (value != null) {
            String[] nvps = value.split(":");
            for (String nvp : nvps) {
                int i = nvp.indexOf(61);
                if (i != -1) {
                    String n = nvp.substring(0, i);
                    String v = nvp.substring(i + 1);
                    if (n.equals(NAME_IMMERSIVE_FULL)) {
                        Filter f = Filter.parse(v);
                        sImmersiveNavigationFilter = f;
                        sImmersiveStatusFilter = f;
                        if (sImmersivePreconfirmationsFilter == null) {
                            sImmersivePreconfirmationsFilter = f;
                        }
                    } else if (n.equals(NAME_IMMERSIVE_STATUS)) {
                        sImmersiveStatusFilter = Filter.parse(v);
                    } else if (n.equals(NAME_IMMERSIVE_NAVIGATION)) {
                        Filter f2 = Filter.parse(v);
                        sImmersiveNavigationFilter = f2;
                        if (sImmersivePreconfirmationsFilter == null) {
                            sImmersivePreconfirmationsFilter = f2;
                        }
                    } else if (n.equals(NAME_IMMERSIVE_PRECONFIRMATIONS)) {
                        sImmersivePreconfirmationsFilter = Filter.parse(v);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class Filter {
        private static final String ALL = "*";
        private static final String APPS = "apps";
        private final ArraySet<String> mBlacklist;
        private final ArraySet<String> mWhitelist;

        private Filter(ArraySet<String> whitelist, ArraySet<String> blacklist) {
            this.mWhitelist = whitelist;
            this.mBlacklist = blacklist;
        }

        boolean matches(WindowManager.LayoutParams attrs) {
            if (attrs == null) {
                return false;
            }
            boolean isApp = attrs.type >= 1 && attrs.type <= 99;
            if ((isApp && this.mBlacklist.contains(APPS)) || onBlacklist(attrs.packageName)) {
                return false;
            }
            if (isApp && this.mWhitelist.contains(APPS)) {
                return true;
            }
            return onWhitelist(attrs.packageName);
        }

        boolean matches(String packageName) {
            return !onBlacklist(packageName) && onWhitelist(packageName);
        }

        private boolean onBlacklist(String packageName) {
            return this.mBlacklist.contains(packageName) || this.mBlacklist.contains(ALL);
        }

        private boolean onWhitelist(String packageName) {
            return this.mWhitelist.contains(ALL) || this.mWhitelist.contains(packageName);
        }

        void dump(PrintWriter pw) {
            pw.print("Filter[");
            dump("whitelist", this.mWhitelist, pw);
            pw.print(',');
            dump("blacklist", this.mBlacklist, pw);
            pw.print(']');
        }

        private void dump(String name, ArraySet<String> set, PrintWriter pw) {
            pw.print(name);
            pw.print("=(");
            int n = set.size();
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    pw.print(',');
                }
                pw.print(set.valueAt(i));
            }
            pw.print(')');
        }

        public String toString() {
            StringWriter sw = new StringWriter();
            dump(new PrintWriter((Writer) sw, true));
            return sw.toString();
        }

        static Filter parse(String value) {
            if (value == null) {
                return null;
            }
            ArraySet<String> whitelist = new ArraySet<>();
            ArraySet<String> blacklist = new ArraySet<>();
            for (String token : value.split(",")) {
                String token2 = token.trim();
                if (token2.startsWith("-") && token2.length() > 1) {
                    blacklist.add(token2.substring(1));
                } else {
                    whitelist.add(token2);
                }
            }
            return new Filter(whitelist, blacklist);
        }
    }
}
