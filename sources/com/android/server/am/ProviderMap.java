package com.android.server.am;

import android.content.ComponentName;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.SparseArray;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.utils.PriorityDump;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/* loaded from: classes.dex */
public final class ProviderMap {
    private static final boolean DBG = false;
    private static final String TAG = "ProviderMap";
    private final ActivityManagerService mAm;
    private final HashMap<String, ContentProviderRecord> mSingletonByName = new HashMap<>();
    private final HashMap<ComponentName, ContentProviderRecord> mSingletonByClass = new HashMap<>();
    private final SparseArray<HashMap<String, ContentProviderRecord>> mProvidersByNamePerUser = new SparseArray<>();
    private final SparseArray<HashMap<ComponentName, ContentProviderRecord>> mProvidersByClassPerUser = new SparseArray<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public ProviderMap(ActivityManagerService am) {
        this.mAm = am;
    }

    ContentProviderRecord getProviderByName(String name) {
        return getProviderByName(name, -1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContentProviderRecord getProviderByName(String name, int userId) {
        ContentProviderRecord record = this.mSingletonByName.get(name);
        if (record != null) {
            return record;
        }
        return getProvidersByName(userId).get(name);
    }

    ContentProviderRecord getProviderByClass(ComponentName name) {
        return getProviderByClass(name, -1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ContentProviderRecord getProviderByClass(ComponentName name, int userId) {
        ContentProviderRecord record = this.mSingletonByClass.get(name);
        if (record != null) {
            return record;
        }
        return getProvidersByClass(userId).get(name);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void putProviderByName(String name, ContentProviderRecord record) {
        if (record.singleton) {
            this.mSingletonByName.put(name, record);
            return;
        }
        int userId = UserHandle.getUserId(record.appInfo.uid);
        getProvidersByName(userId).put(name, record);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void putProviderByClass(ComponentName name, ContentProviderRecord record) {
        if (record.singleton) {
            this.mSingletonByClass.put(name, record);
            return;
        }
        int userId = UserHandle.getUserId(record.appInfo.uid);
        getProvidersByClass(userId).put(name, record);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeProviderByName(String name, int userId) {
        if (this.mSingletonByName.containsKey(name)) {
            this.mSingletonByName.remove(name);
        } else if (userId < 0) {
            throw new IllegalArgumentException("Bad user " + userId);
        } else {
            HashMap<String, ContentProviderRecord> map = getProvidersByName(userId);
            map.remove(name);
            if (map.size() == 0) {
                this.mProvidersByNamePerUser.remove(userId);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeProviderByClass(ComponentName name, int userId) {
        if (this.mSingletonByClass.containsKey(name)) {
            this.mSingletonByClass.remove(name);
        } else if (userId < 0) {
            throw new IllegalArgumentException("Bad user " + userId);
        } else {
            HashMap<ComponentName, ContentProviderRecord> map = getProvidersByClass(userId);
            map.remove(name);
            if (map.size() == 0) {
                this.mProvidersByClassPerUser.remove(userId);
            }
        }
    }

    private HashMap<String, ContentProviderRecord> getProvidersByName(int userId) {
        if (userId < 0) {
            throw new IllegalArgumentException("Bad user " + userId);
        }
        HashMap<String, ContentProviderRecord> map = this.mProvidersByNamePerUser.get(userId);
        if (map == null) {
            HashMap<String, ContentProviderRecord> newMap = new HashMap<>();
            this.mProvidersByNamePerUser.put(userId, newMap);
            return newMap;
        }
        return map;
    }

    HashMap<ComponentName, ContentProviderRecord> getProvidersByClass(int userId) {
        if (userId < 0) {
            throw new IllegalArgumentException("Bad user " + userId);
        }
        HashMap<ComponentName, ContentProviderRecord> map = this.mProvidersByClassPerUser.get(userId);
        if (map == null) {
            HashMap<ComponentName, ContentProviderRecord> newMap = new HashMap<>();
            this.mProvidersByClassPerUser.put(userId, newMap);
            return newMap;
        }
        return map;
    }

    /* JADX WARN: Code restructure failed: missing block: B:22:0x0046, code lost:
        return true;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean collectPackageProvidersLocked(java.lang.String r7, java.util.Set<java.lang.String> r8, boolean r9, boolean r10, java.util.HashMap<android.content.ComponentName, com.android.server.am.ContentProviderRecord> r11, java.util.ArrayList<com.android.server.am.ContentProviderRecord> r12) {
        /*
            r6 = this;
            r0 = 0
            java.util.Collection r1 = r11.values()
            java.util.Iterator r1 = r1.iterator()
        L9:
            boolean r2 = r1.hasNext()
            if (r2 == 0) goto L4c
            java.lang.Object r2 = r1.next()
            com.android.server.am.ContentProviderRecord r2 = (com.android.server.am.ContentProviderRecord) r2
            r3 = 1
            if (r7 == 0) goto L33
            android.content.pm.ProviderInfo r4 = r2.info
            java.lang.String r4 = r4.packageName
            boolean r4 = r4.equals(r7)
            if (r4 == 0) goto L31
            if (r8 == 0) goto L33
            android.content.ComponentName r4 = r2.name
            java.lang.String r4 = r4.getClassName()
            boolean r4 = r8.contains(r4)
            if (r4 == 0) goto L31
            goto L33
        L31:
            r4 = 0
            goto L34
        L33:
            r4 = r3
        L34:
            if (r4 == 0) goto L4b
            com.android.server.am.ProcessRecord r5 = r2.proc
            if (r5 == 0) goto L44
            if (r10 != 0) goto L44
            com.android.server.am.ProcessRecord r5 = r2.proc
            boolean r5 = r5.isPersistent()
            if (r5 != 0) goto L4b
        L44:
            if (r9 != 0) goto L47
            return r3
        L47:
            r0 = 1
            r12.add(r2)
        L4b:
            goto L9
        L4c:
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.ProviderMap.collectPackageProvidersLocked(java.lang.String, java.util.Set, boolean, boolean, java.util.HashMap, java.util.ArrayList):boolean");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean collectPackageProvidersLocked(String packageName, Set<String> filterByClasses, boolean doit, boolean evenPersistent, int userId, ArrayList<ContentProviderRecord> result) {
        boolean didSomething = false;
        if (userId == -1 || userId == 0) {
            didSomething = collectPackageProvidersLocked(packageName, filterByClasses, doit, evenPersistent, this.mSingletonByClass, result);
        }
        if (!doit && didSomething) {
            return true;
        }
        if (userId == -1) {
            boolean didSomething2 = didSomething;
            for (int i = 0; i < this.mProvidersByClassPerUser.size(); i++) {
                if (collectPackageProvidersLocked(packageName, filterByClasses, doit, evenPersistent, this.mProvidersByClassPerUser.valueAt(i), result)) {
                    if (!doit) {
                        return true;
                    }
                    didSomething2 = true;
                }
            }
            return didSomething2;
        }
        HashMap<ComponentName, ContentProviderRecord> items = getProvidersByClass(userId);
        if (items != null) {
            return didSomething | collectPackageProvidersLocked(packageName, filterByClasses, doit, evenPersistent, items, result);
        }
        return didSomething;
    }

    private boolean dumpProvidersByClassLocked(PrintWriter pw, boolean dumpAll, String dumpPackage, String header, boolean needSep, HashMap<ComponentName, ContentProviderRecord> map) {
        boolean written = false;
        for (Map.Entry<ComponentName, ContentProviderRecord> e : map.entrySet()) {
            ContentProviderRecord r = e.getValue();
            if (dumpPackage == null || dumpPackage.equals(r.appInfo.packageName)) {
                if (needSep) {
                    pw.println("");
                    needSep = false;
                }
                if (header != null) {
                    pw.println(header);
                    header = null;
                }
                written = true;
                pw.print("  * ");
                pw.println(r);
                r.dump(pw, "    ", dumpAll);
            }
        }
        return written;
    }

    private boolean dumpProvidersByNameLocked(PrintWriter pw, String dumpPackage, String header, boolean needSep, HashMap<String, ContentProviderRecord> map) {
        boolean written = false;
        for (Map.Entry<String, ContentProviderRecord> e : map.entrySet()) {
            ContentProviderRecord r = e.getValue();
            if (dumpPackage == null || dumpPackage.equals(r.appInfo.packageName)) {
                if (needSep) {
                    pw.println("");
                    needSep = false;
                }
                if (header != null) {
                    pw.println(header);
                    header = null;
                }
                written = true;
                pw.print("  ");
                pw.print(e.getKey());
                pw.print(": ");
                pw.println(r.toShortString());
            }
        }
        return written;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean dumpProvidersLocked(PrintWriter pw, boolean dumpAll, String dumpPackage) {
        boolean needSep = this.mSingletonByClass.size() > 0 ? false | dumpProvidersByClassLocked(pw, dumpAll, dumpPackage, "  Published single-user content providers (by class):", false, this.mSingletonByClass) : false;
        boolean needSep2 = needSep;
        for (int i = 0; i < this.mProvidersByClassPerUser.size(); i++) {
            HashMap<ComponentName, ContentProviderRecord> map = this.mProvidersByClassPerUser.valueAt(i);
            needSep2 |= dumpProvidersByClassLocked(pw, dumpAll, dumpPackage, "  Published user " + this.mProvidersByClassPerUser.keyAt(i) + " content providers (by class):", needSep2, map);
        }
        if (!dumpAll) {
            return needSep2;
        }
        boolean needSep3 = dumpProvidersByNameLocked(pw, dumpPackage, "  Single-user authority to provider mappings:", needSep2, this.mSingletonByName) | needSep2;
        for (int i2 = 0; i2 < this.mProvidersByNamePerUser.size(); i2++) {
            needSep3 |= dumpProvidersByNameLocked(pw, dumpPackage, "  User " + this.mProvidersByNamePerUser.keyAt(i2) + " authority to provider mappings:", needSep3, this.mProvidersByNamePerUser.valueAt(i2));
        }
        return needSep3;
    }

    private ArrayList<ContentProviderRecord> getProvidersForName(String name) {
        ArrayList<ContentProviderRecord> allProviders = new ArrayList<>();
        ArrayList<ContentProviderRecord> ret = new ArrayList<>();
        Predicate<ContentProviderRecord> filter = DumpUtils.filterRecord(name);
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                allProviders.addAll(this.mSingletonByClass.values());
                for (int i = 0; i < this.mProvidersByClassPerUser.size(); i++) {
                    allProviders.addAll(this.mProvidersByClassPerUser.valueAt(i).values());
                }
                CollectionUtils.addIf(allProviders, ret, filter);
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        ret.sort(Comparator.comparing(new Function() { // from class: com.android.server.am.-$$Lambda$HKoBBTwYfMTyX1rzuzxIXu0s2cc
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((ContentProviderRecord) obj).getComponentName();
            }
        }));
        return ret;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean dumpProvider(FileDescriptor fd, PrintWriter pw, String name, String[] args, int opti, boolean dumpAll) {
        ArrayList<ContentProviderRecord> providers = getProvidersForName(name);
        if (providers.size() <= 0) {
            return false;
        }
        boolean needSep = false;
        int i = 0;
        while (i < providers.size()) {
            if (needSep) {
                pw.println();
            }
            dumpProvider("", fd, pw, providers.get(i), args, dumpAll);
            i++;
            needSep = true;
        }
        return true;
    }

    private void dumpProvider(String prefix, FileDescriptor fd, PrintWriter pw, ContentProviderRecord r, String[] args, boolean dumpAll) {
        for (String s : args) {
            if (!dumpAll && s.contains(PriorityDump.PROTO_ARG)) {
                if (r.proc != null && r.proc.thread != null) {
                    dumpToTransferPipe(null, fd, pw, r, args);
                    return;
                } else {
                    return;
                }
            }
        }
        String innerPrefix = prefix + "  ";
        synchronized (this.mAm) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                pw.print(prefix);
                pw.print("PROVIDER ");
                pw.print(r);
                pw.print(" pid=");
                if (r.proc != null) {
                    pw.println(r.proc.pid);
                } else {
                    pw.println("(not running)");
                }
                if (dumpAll) {
                    r.dump(pw, innerPrefix, true);
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        if (r.proc != null && r.proc.thread != null) {
            pw.println("    Client:");
            pw.flush();
            dumpToTransferPipe("      ", fd, pw, r, args);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean dumpProviderProto(FileDescriptor fd, PrintWriter pw, String name, String[] args) {
        String[] newArgs = (String[]) Arrays.copyOf(args, args.length + 1);
        newArgs[args.length] = PriorityDump.PROTO_ARG;
        ArrayList<ContentProviderRecord> providers = getProvidersForName(name);
        if (providers.size() <= 0) {
            return false;
        }
        for (int i = 0; i < providers.size(); i++) {
            ContentProviderRecord r = providers.get(i);
            if (r.proc != null && r.proc.thread != null) {
                dumpToTransferPipe(null, fd, pw, r, newArgs);
                return true;
            }
        }
        return false;
    }

    private void dumpToTransferPipe(String prefix, FileDescriptor fd, PrintWriter pw, ContentProviderRecord r, String[] args) {
        try {
            TransferPipe tp = new TransferPipe();
            try {
                r.proc.thread.dumpProvider(tp.getWriteFd(), r.provider.asBinder(), args);
                tp.setBufferPrefix(prefix);
                tp.go(fd, 2000L);
                tp.kill();
            } catch (Throwable th) {
                tp.kill();
                throw th;
            }
        } catch (RemoteException e) {
            pw.println("      Got a RemoteException while dumping the service");
        } catch (IOException ex) {
            pw.println("      Failure while dumping the provider: " + ex);
        }
    }
}
