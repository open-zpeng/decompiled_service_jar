package com.android.server.am;

import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.PendingIntent;
import android.content.IIntentSender;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.AlarmManagerInternal;
import com.android.server.LocalServices;
import com.android.server.am.PendingIntentRecord;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.SafeActivityOptions;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.function.BiConsumer;

/* loaded from: classes.dex */
public class PendingIntentController {
    private static final String TAG = "ActivityManager";
    private static final String TAG_MU = "ActivityManager_MU";
    ActivityManagerInternal mAmInternal;
    final Handler mH;
    final UserController mUserController;
    final Object mLock = new Object();
    final HashMap<PendingIntentRecord.Key, WeakReference<PendingIntentRecord>> mIntentSenderRecords = new HashMap<>();
    final ActivityTaskManagerInternal mAtmInternal = (ActivityTaskManagerInternal) LocalServices.getService(ActivityTaskManagerInternal.class);

    /* JADX INFO: Access modifiers changed from: package-private */
    public PendingIntentController(Looper looper, UserController userController) {
        this.mH = new Handler(looper);
        this.mUserController = userController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onActivityManagerInternalAdded() {
        synchronized (this.mLock) {
            this.mAmInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        }
    }

    public PendingIntentRecord getIntentSender(int type, String packageName, int callingUid, int userId, IBinder token, String resultWho, int requestCode, Intent[] intents, String[] resolvedTypes, int flags, Bundle bOptions) {
        boolean noCreate;
        boolean cancelCurrent;
        boolean updateCurrent;
        Intent intent;
        synchronized (this.mLock) {
            try {
                try {
                    if (ActivityManagerDebugConfig.DEBUG_MU) {
                        Slog.v(TAG_MU, "getIntentSender(): uid=" + callingUid);
                    }
                    if (intents != null) {
                        for (Intent intent2 : intents) {
                            intent2.setDefusable(true);
                        }
                    }
                    Bundle.setDefusable(bOptions, true);
                    noCreate = (flags & 536870912) != 0;
                    cancelCurrent = (flags & 268435456) != 0;
                    updateCurrent = (flags & 134217728) != 0;
                } catch (Throwable th) {
                    th = th;
                }
            } catch (Throwable th2) {
                th = th2;
            }
            try {
                PendingIntentRecord.Key key = new PendingIntentRecord.Key(type, packageName, token, resultWho, requestCode, intents, resolvedTypes, flags & (-939524097), SafeActivityOptions.fromBundle(bOptions), userId);
                WeakReference<PendingIntentRecord> ref = this.mIntentSenderRecords.get(key);
                PendingIntentRecord rec = ref != null ? ref.get() : null;
                if (rec != null) {
                    if (cancelCurrent) {
                        makeIntentSenderCanceled(rec);
                        this.mIntentSenderRecords.remove(key);
                    } else {
                        if (updateCurrent) {
                            if (rec.key.requestIntent != null) {
                                Intent intent3 = rec.key.requestIntent;
                                if (intents == null) {
                                    intent = null;
                                } else {
                                    intent = intents[intents.length - 1];
                                }
                                intent3.replaceExtras(intent);
                            }
                            if (intents == null) {
                                rec.key.allIntents = null;
                                rec.key.allResolvedTypes = null;
                            } else {
                                intents[intents.length - 1] = rec.key.requestIntent;
                                rec.key.allIntents = intents;
                                rec.key.allResolvedTypes = resolvedTypes;
                            }
                        }
                        return rec;
                    }
                }
                if (noCreate) {
                    return rec;
                }
                PendingIntentRecord rec2 = new PendingIntentRecord(this, key, callingUid);
                this.mIntentSenderRecords.put(key, rec2.ref);
                return rec2;
            } catch (Throwable th3) {
                th = th3;
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:37:0x0063 A[Catch: all -> 0x0084, TryCatch #0 {, blocks: (B:4:0x0004, B:7:0x000d, B:9:0x000f, B:10:0x0019, B:12:0x001f, B:14:0x0027, B:15:0x002b, B:17:0x0033, B:19:0x0039, B:35:0x0061, B:37:0x0063, B:39:0x0070, B:22:0x0040, B:27:0x004c, B:30:0x0053, B:41:0x0082), top: B:46:0x0004 }] */
    /* JADX WARN: Removed duplicated region for block: B:57:0x0060 A[SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean removePendingIntentsForPackage(java.lang.String r9, int r10, int r11, boolean r12) {
        /*
            r8 = this;
            r0 = 0
            java.lang.Object r1 = r8.mLock
            monitor-enter(r1)
            java.util.HashMap<com.android.server.am.PendingIntentRecord$Key, java.lang.ref.WeakReference<com.android.server.am.PendingIntentRecord>> r2 = r8.mIntentSenderRecords     // Catch: java.lang.Throwable -> L84
            int r2 = r2.size()     // Catch: java.lang.Throwable -> L84
            if (r2 > 0) goto Lf
            r2 = 0
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L84
            return r2
        Lf:
            java.util.HashMap<com.android.server.am.PendingIntentRecord$Key, java.lang.ref.WeakReference<com.android.server.am.PendingIntentRecord>> r2 = r8.mIntentSenderRecords     // Catch: java.lang.Throwable -> L84
            java.util.Collection r2 = r2.values()     // Catch: java.lang.Throwable -> L84
            java.util.Iterator r2 = r2.iterator()     // Catch: java.lang.Throwable -> L84
        L19:
            boolean r3 = r2.hasNext()     // Catch: java.lang.Throwable -> L84
            if (r3 == 0) goto L82
            java.lang.Object r3 = r2.next()     // Catch: java.lang.Throwable -> L84
            java.lang.ref.WeakReference r3 = (java.lang.ref.WeakReference) r3     // Catch: java.lang.Throwable -> L84
            if (r3 != 0) goto L2b
            r2.remove()     // Catch: java.lang.Throwable -> L84
            goto L19
        L2b:
            java.lang.Object r4 = r3.get()     // Catch: java.lang.Throwable -> L84
            com.android.server.am.PendingIntentRecord r4 = (com.android.server.am.PendingIntentRecord) r4     // Catch: java.lang.Throwable -> L84
            if (r4 != 0) goto L37
            r2.remove()     // Catch: java.lang.Throwable -> L84
            goto L19
        L37:
            if (r9 != 0) goto L40
            com.android.server.am.PendingIntentRecord$Key r5 = r4.key     // Catch: java.lang.Throwable -> L84
            int r5 = r5.userId     // Catch: java.lang.Throwable -> L84
            if (r5 == r10) goto L5e
            goto L19
        L40:
            int r5 = r4.uid     // Catch: java.lang.Throwable -> L84
            int r5 = android.os.UserHandle.getAppId(r5)     // Catch: java.lang.Throwable -> L84
            if (r5 == r11) goto L49
            goto L19
        L49:
            r5 = -1
            if (r10 == r5) goto L53
            com.android.server.am.PendingIntentRecord$Key r5 = r4.key     // Catch: java.lang.Throwable -> L84
            int r5 = r5.userId     // Catch: java.lang.Throwable -> L84
            if (r5 == r10) goto L53
            goto L19
        L53:
            com.android.server.am.PendingIntentRecord$Key r5 = r4.key     // Catch: java.lang.Throwable -> L84
            java.lang.String r5 = r5.packageName     // Catch: java.lang.Throwable -> L84
            boolean r5 = r5.equals(r9)     // Catch: java.lang.Throwable -> L84
            if (r5 != 0) goto L5e
            goto L19
        L5e:
            if (r12 != 0) goto L63
            r5 = 1
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L84
            return r5
        L63:
            r0 = 1
            r2.remove()     // Catch: java.lang.Throwable -> L84
            r8.makeIntentSenderCanceled(r4)     // Catch: java.lang.Throwable -> L84
            com.android.server.am.PendingIntentRecord$Key r5 = r4.key     // Catch: java.lang.Throwable -> L84
            android.os.IBinder r5 = r5.activity     // Catch: java.lang.Throwable -> L84
            if (r5 == 0) goto L81
            com.android.server.am.-$$Lambda$PendingIntentController$sPmaborOkBSSEP2wiimxXw-eYDQ r5 = com.android.server.am.$$Lambda$PendingIntentController$sPmaborOkBSSEP2wiimxXweYDQ.INSTANCE     // Catch: java.lang.Throwable -> L84
            com.android.server.am.PendingIntentRecord$Key r6 = r4.key     // Catch: java.lang.Throwable -> L84
            android.os.IBinder r6 = r6.activity     // Catch: java.lang.Throwable -> L84
            java.lang.ref.WeakReference<com.android.server.am.PendingIntentRecord> r7 = r4.ref     // Catch: java.lang.Throwable -> L84
            android.os.Message r5 = com.android.internal.util.function.pooled.PooledLambda.obtainMessage(r5, r8, r6, r7)     // Catch: java.lang.Throwable -> L84
            android.os.Handler r6 = r8.mH     // Catch: java.lang.Throwable -> L84
            r6.sendMessage(r5)     // Catch: java.lang.Throwable -> L84
        L81:
            goto L19
        L82:
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L84
            return r0
        L84:
            r2 = move-exception
            monitor-exit(r1)     // Catch: java.lang.Throwable -> L84
            throw r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.PendingIntentController.removePendingIntentsForPackage(java.lang.String, int, int, boolean):boolean");
    }

    public void cancelIntentSender(IIntentSender sender) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized (this.mLock) {
            PendingIntentRecord rec = (PendingIntentRecord) sender;
            try {
                int uid = AppGlobals.getPackageManager().getPackageUid(rec.key.packageName, 268435456, UserHandle.getCallingUserId());
                if (!UserHandle.isSameApp(uid, Binder.getCallingUid())) {
                    String msg = "Permission Denial: cancelIntentSender() from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid() + " is not allowed to cancel package " + rec.key.packageName;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
                cancelIntentSender(rec, true);
            } catch (RemoteException e) {
                throw new SecurityException(e);
            }
        }
    }

    public void cancelIntentSender(PendingIntentRecord rec, boolean cleanActivity) {
        synchronized (this.mLock) {
            makeIntentSenderCanceled(rec);
            this.mIntentSenderRecords.remove(rec.key);
            if (cleanActivity && rec.key.activity != null) {
                Message m = PooledLambda.obtainMessage($$Lambda$PendingIntentController$sPmaborOkBSSEP2wiimxXweYDQ.INSTANCE, this, rec.key.activity, rec.ref);
                this.mH.sendMessage(m);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void registerIntentSenderCancelListener(IIntentSender sender, IResultReceiver receiver) {
        boolean isCancelled;
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized (this.mLock) {
            PendingIntentRecord pendingIntent = (PendingIntentRecord) sender;
            isCancelled = pendingIntent.canceled;
            if (!isCancelled) {
                pendingIntent.registerCancelListenerLocked(receiver);
            }
        }
        if (isCancelled) {
            try {
                receiver.send(0, (Bundle) null);
            } catch (RemoteException e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unregisterIntentSenderCancelListener(IIntentSender sender, IResultReceiver receiver) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized (this.mLock) {
            ((PendingIntentRecord) sender).unregisterCancelListenerLocked(receiver);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setPendingIntentWhitelistDuration(IIntentSender target, IBinder whitelistToken, long duration) {
        if (!(target instanceof PendingIntentRecord)) {
            Slog.w(TAG, "markAsSentFromNotification(): not a PendingIntentRecord: " + target);
            return;
        }
        synchronized (this.mLock) {
            ((PendingIntentRecord) target).setWhitelistDurationLocked(whitelistToken, duration);
        }
    }

    private void makeIntentSenderCanceled(PendingIntentRecord rec) {
        rec.canceled = true;
        RemoteCallbackList<IResultReceiver> callbacks = rec.detachCancelListenersLocked();
        if (callbacks != null) {
            Message m = PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.am.-$$Lambda$PendingIntentController$pDmmJDvS20vSAAXh9qdzbN0P8N0
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((PendingIntentController) obj).handlePendingIntentCancelled((RemoteCallbackList) obj2);
                }
            }, this, callbacks);
            this.mH.sendMessage(m);
        }
        AlarmManagerInternal ami = (AlarmManagerInternal) LocalServices.getService(AlarmManagerInternal.class);
        ami.remove(new PendingIntent(rec));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePendingIntentCancelled(RemoteCallbackList<IResultReceiver> callbacks) {
        int N = callbacks.beginBroadcast();
        for (int i = 0; i < N; i++) {
            try {
                callbacks.getBroadcastItem(i).send(0, (Bundle) null);
            } catch (RemoteException e) {
            }
        }
        callbacks.finishBroadcast();
        callbacks.kill();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearPendingResultForActivity(IBinder activityToken, WeakReference<PendingIntentRecord> pir) {
        this.mAtmInternal.clearPendingResultForActivity(activityToken, pir);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpPendingIntents(PrintWriter pw, boolean dumpAll, String dumpPackage) {
        synchronized (this.mLock) {
            boolean printed = false;
            pw.println("ACTIVITY MANAGER PENDING INTENTS (dumpsys activity intents)");
            if (this.mIntentSenderRecords.size() > 0) {
                ArrayMap<String, ArrayList<PendingIntentRecord>> byPackage = new ArrayMap<>();
                ArrayList<WeakReference<PendingIntentRecord>> weakRefs = new ArrayList<>();
                Iterator<WeakReference<PendingIntentRecord>> it = this.mIntentSenderRecords.values().iterator();
                while (it.hasNext()) {
                    WeakReference<PendingIntentRecord> ref = it.next();
                    PendingIntentRecord rec = ref != null ? ref.get() : null;
                    if (rec == null) {
                        weakRefs.add(ref);
                    } else if (dumpPackage == null || dumpPackage.equals(rec.key.packageName)) {
                        ArrayList<PendingIntentRecord> list = byPackage.get(rec.key.packageName);
                        if (list == null) {
                            list = new ArrayList<>();
                            byPackage.put(rec.key.packageName, list);
                        }
                        list.add(rec);
                    }
                }
                for (int i = 0; i < byPackage.size(); i++) {
                    ArrayList<PendingIntentRecord> intents = byPackage.valueAt(i);
                    printed = true;
                    pw.print("  * ");
                    pw.print(byPackage.keyAt(i));
                    pw.print(": ");
                    pw.print(intents.size());
                    pw.println(" items");
                    for (int j = 0; j < intents.size(); j++) {
                        pw.print("    #");
                        pw.print(j);
                        pw.print(": ");
                        pw.println(intents.get(j));
                        if (dumpAll) {
                            intents.get(j).dump(pw, "      ");
                        }
                    }
                }
                int i2 = weakRefs.size();
                if (i2 > 0) {
                    printed = true;
                    pw.println("  * WEAK REFS:");
                    for (int i3 = 0; i3 < weakRefs.size(); i3++) {
                        pw.print("    #");
                        pw.print(i3);
                        pw.print(": ");
                        pw.println(weakRefs.get(i3));
                    }
                }
            }
            if (!printed) {
                pw.println("  (nothing)");
            }
        }
    }
}
