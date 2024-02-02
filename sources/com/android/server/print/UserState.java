package com.android.server.print;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.print.IPrintDocumentAdapter;
import android.print.IPrintJobStateChangeListener;
import android.print.IPrintServicesChangeListener;
import android.print.IPrinterDiscoveryObserver;
import android.print.PrintAttributes;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintServiceInfo;
import android.printservice.recommendation.IRecommendationsChangeListener;
import android.printservice.recommendation.RecommendationInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.dump.DumpUtils;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledSupplier;
import com.android.server.pm.DumpState;
import com.android.server.print.RemotePrintService;
import com.android.server.print.RemotePrintServiceRecommendationService;
import com.android.server.print.RemotePrintSpooler;
import com.android.server.print.UserState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class UserState implements RemotePrintSpooler.PrintSpoolerCallbacks, RemotePrintService.PrintServiceCallbacks, RemotePrintServiceRecommendationService.RemotePrintServiceRecommendationServiceCallbacks {
    private static final char COMPONENT_NAME_SEPARATOR = ':';
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "UserState";
    private static final int SERVICE_RESTART_DELAY_MILLIS = 500;
    private final Context mContext;
    private boolean mDestroyed;
    private boolean mIsInstantServiceAllowed;
    private final Object mLock;
    private List<PrintJobStateChangeListenerRecord> mPrintJobStateChangeListenerRecords;
    private List<RecommendationInfo> mPrintServiceRecommendations;
    private List<ListenerRecord<IRecommendationsChangeListener>> mPrintServiceRecommendationsChangeListenerRecords;
    private RemotePrintServiceRecommendationService mPrintServiceRecommendationsService;
    private List<ListenerRecord<IPrintServicesChangeListener>> mPrintServicesChangeListenerRecords;
    private PrinterDiscoverySessionMediator mPrinterDiscoverySession;
    private final RemotePrintSpooler mSpooler;
    private final int mUserId;
    private final TextUtils.SimpleStringSplitter mStringColonSplitter = new TextUtils.SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);
    private final Intent mQueryIntent = new Intent("android.printservice.PrintService");
    private final ArrayMap<ComponentName, RemotePrintService> mActiveServices = new ArrayMap<>();
    private final List<PrintServiceInfo> mInstalledServices = new ArrayList();
    private final Set<ComponentName> mDisabledServices = new ArraySet();
    private final PrintJobForAppCache mPrintJobForAppCache = new PrintJobForAppCache();

    public UserState(Context context, int userId, Object lock, boolean lowPriority) {
        this.mContext = context;
        this.mUserId = userId;
        this.mLock = lock;
        this.mSpooler = new RemotePrintSpooler(context, userId, lowPriority, this);
        synchronized (this.mLock) {
            readInstalledPrintServicesLocked();
            upgradePersistentStateIfNeeded();
            readDisabledPrintServicesLocked();
        }
        prunePrintServices();
        onConfigurationChanged();
    }

    public void increasePriority() {
        this.mSpooler.increasePriority();
    }

    @Override // com.android.server.print.RemotePrintSpooler.PrintSpoolerCallbacks
    public void onPrintJobQueued(PrintJobInfo printJob) {
        RemotePrintService service;
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            ComponentName printServiceName = printJob.getPrinterId().getServiceName();
            service = this.mActiveServices.get(printServiceName);
        }
        if (service != null) {
            service.onPrintJobQueued(printJob);
        } else {
            this.mSpooler.setPrintJobState(printJob.getId(), 6, this.mContext.getString(17040751));
        }
    }

    @Override // com.android.server.print.RemotePrintSpooler.PrintSpoolerCallbacks
    public void onAllPrintJobsForServiceHandled(ComponentName printService) {
        RemotePrintService service;
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            service = this.mActiveServices.get(printService);
        }
        if (service != null) {
            service.onAllPrintJobsHandled();
        }
    }

    public void removeObsoletePrintJobs() {
        this.mSpooler.removeObsoletePrintJobs();
    }

    public Bundle print(String printJobName, IPrintDocumentAdapter adapter, PrintAttributes attributes, String packageName, int appId) {
        PrintJobInfo printJob = new PrintJobInfo();
        printJob.setId(new PrintJobId());
        printJob.setAppId(appId);
        printJob.setLabel(printJobName);
        printJob.setAttributes(attributes);
        printJob.setState(1);
        printJob.setCopies(1);
        printJob.setCreationTime(System.currentTimeMillis());
        if (!this.mPrintJobForAppCache.onPrintJobCreated(adapter.asBinder(), appId, printJob)) {
            return null;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent("android.print.PRINT_DIALOG");
            intent.setData(Uri.fromParts("printjob", printJob.getId().flattenToString(), null));
            intent.putExtra("android.print.intent.extra.EXTRA_PRINT_DOCUMENT_ADAPTER", adapter.asBinder());
            intent.putExtra("android.print.intent.extra.EXTRA_PRINT_JOB", printJob);
            try {
                intent.putExtra("android.content.extra.PACKAGE_NAME", packageName);
                IntentSender intentSender = PendingIntent.getActivityAsUser(this.mContext, 0, intent, 1409286144, null, new UserHandle(this.mUserId)).getIntentSender();
                Bundle result = new Bundle();
                result.putParcelable("android.print.intent.extra.EXTRA_PRINT_JOB", printJob);
                result.putParcelable("android.print.intent.extra.EXTRA_PRINT_DIALOG_INTENT", intentSender);
                Binder.restoreCallingIdentity(identity);
                return result;
            } catch (Throwable th) {
                th = th;
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        } catch (Throwable th2) {
            th = th2;
        }
    }

    public List<PrintJobInfo> getPrintJobInfos(int appId) {
        List<PrintJobInfo> cachedPrintJobs = this.mPrintJobForAppCache.getPrintJobs(appId);
        ArrayMap<PrintJobId, PrintJobInfo> result = new ArrayMap<>();
        int cachedPrintJobCount = cachedPrintJobs.size();
        for (int i = 0; i < cachedPrintJobCount; i++) {
            PrintJobInfo cachedPrintJob = cachedPrintJobs.get(i);
            result.put(cachedPrintJob.getId(), cachedPrintJob);
            cachedPrintJob.setTag(null);
            cachedPrintJob.setAdvancedOptions(null);
        }
        List<PrintJobInfo> printJobs = this.mSpooler.getPrintJobInfos(null, -1, appId);
        if (printJobs != null) {
            int printJobCount = printJobs.size();
            for (int i2 = 0; i2 < printJobCount; i2++) {
                PrintJobInfo printJob = printJobs.get(i2);
                result.put(printJob.getId(), printJob);
                printJob.setTag(null);
                printJob.setAdvancedOptions(null);
            }
        }
        return new ArrayList(result.values());
    }

    public PrintJobInfo getPrintJobInfo(PrintJobId printJobId, int appId) {
        PrintJobInfo printJob = this.mPrintJobForAppCache.getPrintJob(printJobId, appId);
        if (printJob == null) {
            printJob = this.mSpooler.getPrintJobInfo(printJobId, appId);
        }
        if (printJob != null) {
            printJob.setTag(null);
            printJob.setAdvancedOptions(null);
        }
        return printJob;
    }

    public Icon getCustomPrinterIcon(PrinterId printerId) {
        RemotePrintService service;
        Icon icon = this.mSpooler.getCustomPrinterIcon(printerId);
        if (icon == null && (service = this.mActiveServices.get(printerId.getServiceName())) != null) {
            service.requestCustomPrinterIcon(printerId);
        }
        return icon;
    }

    public void cancelPrintJob(PrintJobId printJobId, int appId) {
        RemotePrintService printService;
        PrintJobInfo printJobInfo = this.mSpooler.getPrintJobInfo(printJobId, appId);
        if (printJobInfo == null) {
            return;
        }
        this.mSpooler.setPrintJobCancelling(printJobId, true);
        if (printJobInfo.getState() != 6) {
            PrinterId printerId = printJobInfo.getPrinterId();
            if (printerId != null) {
                ComponentName printServiceName = printerId.getServiceName();
                synchronized (this.mLock) {
                    printService = this.mActiveServices.get(printServiceName);
                }
                if (printService == null) {
                    return;
                }
                printService.onRequestCancelPrintJob(printJobInfo);
                return;
            }
            return;
        }
        this.mSpooler.setPrintJobState(printJobId, 7, null);
    }

    public void restartPrintJob(PrintJobId printJobId, int appId) {
        PrintJobInfo printJobInfo = getPrintJobInfo(printJobId, appId);
        if (printJobInfo == null || printJobInfo.getState() != 6) {
            return;
        }
        this.mSpooler.setPrintJobState(printJobId, 2, null);
    }

    /* JADX WARN: Removed duplicated region for block: B:16:0x0045 A[Catch: all -> 0x0053, TryCatch #0 {, blocks: (B:5:0x0004, B:7:0x000d, B:9:0x0039, B:18:0x004e, B:16:0x0045, B:17:0x004b, B:12:0x003e, B:19:0x0051), top: B:24:0x0004 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.util.List<android.printservice.PrintServiceInfo> getPrintServices(int r9) {
        /*
            r8 = this;
            java.lang.Object r0 = r8.mLock
            monitor-enter(r0)
            r1 = 0
            java.util.List<android.printservice.PrintServiceInfo> r2 = r8.mInstalledServices     // Catch: java.lang.Throwable -> L53
            int r2 = r2.size()     // Catch: java.lang.Throwable -> L53
            r3 = 0
        Lb:
            if (r3 >= r2) goto L51
            java.util.List<android.printservice.PrintServiceInfo> r4 = r8.mInstalledServices     // Catch: java.lang.Throwable -> L53
            java.lang.Object r4 = r4.get(r3)     // Catch: java.lang.Throwable -> L53
            android.printservice.PrintServiceInfo r4 = (android.printservice.PrintServiceInfo) r4     // Catch: java.lang.Throwable -> L53
            android.content.ComponentName r5 = new android.content.ComponentName     // Catch: java.lang.Throwable -> L53
            android.content.pm.ResolveInfo r6 = r4.getResolveInfo()     // Catch: java.lang.Throwable -> L53
            android.content.pm.ServiceInfo r6 = r6.serviceInfo     // Catch: java.lang.Throwable -> L53
            java.lang.String r6 = r6.packageName     // Catch: java.lang.Throwable -> L53
            android.content.pm.ResolveInfo r7 = r4.getResolveInfo()     // Catch: java.lang.Throwable -> L53
            android.content.pm.ServiceInfo r7 = r7.serviceInfo     // Catch: java.lang.Throwable -> L53
            java.lang.String r7 = r7.name     // Catch: java.lang.Throwable -> L53
            r5.<init>(r6, r7)     // Catch: java.lang.Throwable -> L53
            android.util.ArrayMap<android.content.ComponentName, com.android.server.print.RemotePrintService> r6 = r8.mActiveServices     // Catch: java.lang.Throwable -> L53
            boolean r6 = r6.containsKey(r5)     // Catch: java.lang.Throwable -> L53
            r4.setIsEnabled(r6)     // Catch: java.lang.Throwable -> L53
            boolean r6 = r4.isEnabled()     // Catch: java.lang.Throwable -> L53
            if (r6 == 0) goto L3e
            r6 = r9 & 1
            if (r6 != 0) goto L43
            goto L4e
        L3e:
            r6 = r9 & 2
            if (r6 != 0) goto L43
            goto L4e
        L43:
            if (r1 != 0) goto L4b
            java.util.ArrayList r6 = new java.util.ArrayList     // Catch: java.lang.Throwable -> L53
            r6.<init>()     // Catch: java.lang.Throwable -> L53
            r1 = r6
        L4b:
            r1.add(r4)     // Catch: java.lang.Throwable -> L53
        L4e:
            int r3 = r3 + 1
            goto Lb
        L51:
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L53
            return r1
        L53:
            r1 = move-exception
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L53
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.print.UserState.getPrintServices(int):java.util.List");
    }

    public void setPrintServiceEnabled(ComponentName serviceName, boolean isEnabled) {
        synchronized (this.mLock) {
            boolean isChanged = false;
            try {
                if (isEnabled) {
                    isChanged = this.mDisabledServices.remove(serviceName);
                } else {
                    int numServices = this.mInstalledServices.size();
                    int i = 0;
                    while (true) {
                        if (i >= numServices) {
                            break;
                        }
                        PrintServiceInfo service = this.mInstalledServices.get(i);
                        if (!service.getComponentName().equals(serviceName)) {
                            i++;
                        } else {
                            this.mDisabledServices.add(serviceName);
                            isChanged = true;
                            break;
                        }
                    }
                }
                if (isChanged) {
                    writeDisabledPrintServicesLocked(this.mDisabledServices);
                    MetricsLogger.action(this.mContext, 511, !isEnabled ? 1 : 0);
                    onConfigurationChangedLocked();
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public List<RecommendationInfo> getPrintServiceRecommendations() {
        return this.mPrintServiceRecommendations;
    }

    public void createPrinterDiscoverySession(IPrinterDiscoveryObserver observer) {
        this.mSpooler.clearCustomPrinterIconCache();
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrinterDiscoverySession == null) {
                this.mPrinterDiscoverySession = new PrinterDiscoverySessionMediator() { // from class: com.android.server.print.UserState.1
                    @Override // com.android.server.print.UserState.PrinterDiscoverySessionMediator
                    public void onDestroyed() {
                        UserState.this.mPrinterDiscoverySession = null;
                    }
                };
                this.mPrinterDiscoverySession.addObserverLocked(observer);
            } else {
                this.mPrinterDiscoverySession.addObserverLocked(observer);
            }
        }
    }

    public void destroyPrinterDiscoverySession(IPrinterDiscoveryObserver observer) {
        synchronized (this.mLock) {
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.removeObserverLocked(observer);
        }
    }

    public void startPrinterDiscovery(IPrinterDiscoveryObserver observer, List<PrinterId> printerIds) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.startPrinterDiscoveryLocked(observer, printerIds);
        }
    }

    public void stopPrinterDiscovery(IPrinterDiscoveryObserver observer) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.stopPrinterDiscoveryLocked(observer);
        }
    }

    public void validatePrinters(List<PrinterId> printerIds) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mActiveServices.isEmpty()) {
                return;
            }
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.validatePrintersLocked(printerIds);
        }
    }

    public void startPrinterStateTracking(PrinterId printerId) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mActiveServices.isEmpty()) {
                return;
            }
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.startPrinterStateTrackingLocked(printerId);
        }
    }

    public void stopPrinterStateTracking(PrinterId printerId) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mActiveServices.isEmpty()) {
                return;
            }
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.stopPrinterStateTrackingLocked(printerId);
        }
    }

    public void addPrintJobStateChangeListener(IPrintJobStateChangeListener listener, int appId) throws RemoteException {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrintJobStateChangeListenerRecords == null) {
                this.mPrintJobStateChangeListenerRecords = new ArrayList();
            }
            this.mPrintJobStateChangeListenerRecords.add(new PrintJobStateChangeListenerRecord(listener, appId) { // from class: com.android.server.print.UserState.2
                @Override // com.android.server.print.UserState.PrintJobStateChangeListenerRecord
                public void onBinderDied() {
                    synchronized (UserState.this.mLock) {
                        if (UserState.this.mPrintJobStateChangeListenerRecords != null) {
                            UserState.this.mPrintJobStateChangeListenerRecords.remove(this);
                        }
                    }
                }
            });
        }
    }

    public void removePrintJobStateChangeListener(IPrintJobStateChangeListener listener) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrintJobStateChangeListenerRecords == null) {
                return;
            }
            int recordCount = this.mPrintJobStateChangeListenerRecords.size();
            int i = 0;
            while (true) {
                if (i >= recordCount) {
                    break;
                }
                PrintJobStateChangeListenerRecord record = this.mPrintJobStateChangeListenerRecords.get(i);
                if (!record.listener.asBinder().equals(listener.asBinder())) {
                    i++;
                } else {
                    record.destroy();
                    this.mPrintJobStateChangeListenerRecords.remove(i);
                    break;
                }
            }
            if (this.mPrintJobStateChangeListenerRecords.isEmpty()) {
                this.mPrintJobStateChangeListenerRecords = null;
            }
        }
    }

    public void addPrintServicesChangeListener(IPrintServicesChangeListener listener) throws RemoteException {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrintServicesChangeListenerRecords == null) {
                this.mPrintServicesChangeListenerRecords = new ArrayList();
            }
            this.mPrintServicesChangeListenerRecords.add(new ListenerRecord<IPrintServicesChangeListener>(listener) { // from class: com.android.server.print.UserState.3
                @Override // com.android.server.print.UserState.ListenerRecord
                public void onBinderDied() {
                    synchronized (UserState.this.mLock) {
                        if (UserState.this.mPrintServicesChangeListenerRecords != null) {
                            UserState.this.mPrintServicesChangeListenerRecords.remove(this);
                        }
                    }
                }
            });
        }
    }

    public void removePrintServicesChangeListener(IPrintServicesChangeListener listener) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrintServicesChangeListenerRecords == null) {
                return;
            }
            int recordCount = this.mPrintServicesChangeListenerRecords.size();
            int i = 0;
            while (true) {
                if (i >= recordCount) {
                    break;
                }
                ListenerRecord<IPrintServicesChangeListener> record = this.mPrintServicesChangeListenerRecords.get(i);
                if (!record.listener.asBinder().equals(listener.asBinder())) {
                    i++;
                } else {
                    record.destroy();
                    this.mPrintServicesChangeListenerRecords.remove(i);
                    break;
                }
            }
            if (this.mPrintServicesChangeListenerRecords.isEmpty()) {
                this.mPrintServicesChangeListenerRecords = null;
            }
        }
    }

    public void addPrintServiceRecommendationsChangeListener(IRecommendationsChangeListener listener) throws RemoteException {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrintServiceRecommendationsChangeListenerRecords == null) {
                this.mPrintServiceRecommendationsChangeListenerRecords = new ArrayList();
                this.mPrintServiceRecommendationsService = new RemotePrintServiceRecommendationService(this.mContext, UserHandle.getUserHandleForUid(this.mUserId), this);
            }
            this.mPrintServiceRecommendationsChangeListenerRecords.add(new ListenerRecord<IRecommendationsChangeListener>(listener) { // from class: com.android.server.print.UserState.4
                @Override // com.android.server.print.UserState.ListenerRecord
                public void onBinderDied() {
                    synchronized (UserState.this.mLock) {
                        if (UserState.this.mPrintServiceRecommendationsChangeListenerRecords != null) {
                            UserState.this.mPrintServiceRecommendationsChangeListenerRecords.remove(this);
                        }
                    }
                }
            });
        }
    }

    public void removePrintServiceRecommendationsChangeListener(IRecommendationsChangeListener listener) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrintServiceRecommendationsChangeListenerRecords == null) {
                return;
            }
            int recordCount = this.mPrintServiceRecommendationsChangeListenerRecords.size();
            int i = 0;
            while (true) {
                if (i >= recordCount) {
                    break;
                }
                ListenerRecord<IRecommendationsChangeListener> record = this.mPrintServiceRecommendationsChangeListenerRecords.get(i);
                if (!record.listener.asBinder().equals(listener.asBinder())) {
                    i++;
                } else {
                    record.destroy();
                    this.mPrintServiceRecommendationsChangeListenerRecords.remove(i);
                    break;
                }
            }
            if (this.mPrintServiceRecommendationsChangeListenerRecords.isEmpty()) {
                this.mPrintServiceRecommendationsChangeListenerRecords = null;
                this.mPrintServiceRecommendations = null;
                this.mPrintServiceRecommendationsService.close();
                this.mPrintServiceRecommendationsService = null;
            }
        }
    }

    @Override // com.android.server.print.RemotePrintSpooler.PrintSpoolerCallbacks
    public void onPrintJobStateChanged(PrintJobInfo printJob) {
        this.mPrintJobForAppCache.onPrintJobStateChanged(printJob);
        Handler.getMain().sendMessage(PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$d-WQxYwbHYb6N0le5ohwQsWVdjw
            public final void accept(Object obj, Object obj2, Object obj3) {
                ((UserState) obj).handleDispatchPrintJobStateChanged((PrintJobId) obj2, (PooledSupplier.OfInt) obj3);
            }
        }, this, printJob.getId(), PooledLambda.obtainSupplier(printJob.getAppId()).recycleOnUse()));
    }

    public void onPrintServicesChanged() {
        Handler.getMain().sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.print.-$$Lambda$UserState$LdWYUAKz4cbWqoxOD4oZ_ZslKdg
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((UserState) obj).handleDispatchPrintServicesChanged();
            }
        }, this));
    }

    @Override // com.android.server.print.RemotePrintServiceRecommendationService.RemotePrintServiceRecommendationServiceCallbacks
    public void onPrintServiceRecommendationsUpdated(List<RecommendationInfo> recommendations) {
        Handler.getMain().sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$f3loorfBpq9Tu3Vl5vt4Ul321ok
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((UserState) obj).handleDispatchPrintServiceRecommendationsUpdated((List) obj2);
            }
        }, this, recommendations));
    }

    @Override // com.android.server.print.RemotePrintService.PrintServiceCallbacks
    public void onPrintersAdded(List<PrinterInfo> printers) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mActiveServices.isEmpty()) {
                return;
            }
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.onPrintersAddedLocked(printers);
        }
    }

    @Override // com.android.server.print.RemotePrintService.PrintServiceCallbacks
    public void onPrintersRemoved(List<PrinterId> printerIds) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mActiveServices.isEmpty()) {
                return;
            }
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.onPrintersRemovedLocked(printerIds);
        }
    }

    @Override // com.android.server.print.RemotePrintService.PrintServiceCallbacks
    public void onCustomPrinterIconLoaded(PrinterId printerId, Icon icon) {
        this.mSpooler.onCustomPrinterIconLoaded(printerId, icon);
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.onCustomPrinterIconLoadedLocked(printerId);
        }
    }

    @Override // com.android.server.print.RemotePrintService.PrintServiceCallbacks
    public void onServiceDied(RemotePrintService service) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mActiveServices.isEmpty()) {
                return;
            }
            failActivePrintJobsForService(service.getComponentName());
            service.onAllPrintJobsHandled();
            this.mActiveServices.remove(service.getComponentName());
            Handler.getMain().sendMessageDelayed(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.print.-$$Lambda$UserState$lM4y7oOfdlEk7JJ3u_zy-rL_-YI
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((UserState) obj).onConfigurationChanged();
                }
            }, this), 500L);
            if (this.mPrinterDiscoverySession == null) {
                return;
            }
            this.mPrinterDiscoverySession.onServiceDiedLocked(service);
        }
    }

    public void updateIfNeededLocked() {
        throwIfDestroyedLocked();
        readConfigurationLocked();
        onConfigurationChangedLocked();
    }

    public void destroyLocked() {
        throwIfDestroyedLocked();
        this.mSpooler.destroy();
        for (RemotePrintService service : this.mActiveServices.values()) {
            service.destroy();
        }
        this.mActiveServices.clear();
        this.mInstalledServices.clear();
        this.mDisabledServices.clear();
        if (this.mPrinterDiscoverySession != null) {
            this.mPrinterDiscoverySession.destroyLocked();
            this.mPrinterDiscoverySession = null;
        }
        this.mDestroyed = true;
    }

    public void dump(DualDumpOutputStream dumpStream) {
        synchronized (this.mLock) {
            dumpStream.write("user_id", 1120986464257L, this.mUserId);
            int installedServiceCount = this.mInstalledServices.size();
            for (int i = 0; i < installedServiceCount; i++) {
                long token = dumpStream.start("installed_services", 2246267895810L);
                PrintServiceInfo installedService = this.mInstalledServices.get(i);
                ResolveInfo resolveInfo = installedService.getResolveInfo();
                DumpUtils.writeComponentName(dumpStream, "component_name", 1146756268033L, new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name));
                DumpUtils.writeStringIfNotNull(dumpStream, "settings_activity", 1138166333442L, installedService.getSettingsActivityName());
                DumpUtils.writeStringIfNotNull(dumpStream, "add_printers_activity", 1138166333443L, installedService.getAddPrintersActivityName());
                DumpUtils.writeStringIfNotNull(dumpStream, "advanced_options_activity", 1138166333444L, installedService.getAdvancedOptionsActivityName());
                dumpStream.end(token);
            }
            for (ComponentName disabledService : this.mDisabledServices) {
                DumpUtils.writeComponentName(dumpStream, "disabled_services", 2246267895811L, disabledService);
            }
            int activeServiceCount = this.mActiveServices.size();
            for (int i2 = 0; i2 < activeServiceCount; i2++) {
                long token2 = dumpStream.start("actives_services", 2246267895812L);
                this.mActiveServices.valueAt(i2).dump(dumpStream);
                dumpStream.end(token2);
            }
            this.mPrintJobForAppCache.dumpLocked(dumpStream);
            if (this.mPrinterDiscoverySession != null) {
                long token3 = dumpStream.start("discovery_service", 2246267895814L);
                this.mPrinterDiscoverySession.dumpLocked(dumpStream);
                dumpStream.end(token3);
            }
        }
        long token4 = dumpStream.start("print_spooler_state", 1146756268039L);
        this.mSpooler.dump(dumpStream);
        dumpStream.end(token4);
    }

    private void readConfigurationLocked() {
        readInstalledPrintServicesLocked();
        readDisabledPrintServicesLocked();
    }

    private void readInstalledPrintServicesLocked() {
        Set<PrintServiceInfo> tempPrintServices = new HashSet<>();
        int queryIntentFlags = this.mIsInstantServiceAllowed ? 268435588 | DumpState.DUMP_VOLUMES : 268435588;
        List<ResolveInfo> installedServices = this.mContext.getPackageManager().queryIntentServicesAsUser(this.mQueryIntent, queryIntentFlags, this.mUserId);
        int installedCount = installedServices.size();
        for (int i = 0; i < installedCount; i++) {
            ResolveInfo installedService = installedServices.get(i);
            if (!"android.permission.BIND_PRINT_SERVICE".equals(installedService.serviceInfo.permission)) {
                ComponentName serviceName = new ComponentName(installedService.serviceInfo.packageName, installedService.serviceInfo.name);
                Slog.w(LOG_TAG, "Skipping print service " + serviceName.flattenToShortString() + " since it does not require permission android.permission.BIND_PRINT_SERVICE");
            } else {
                tempPrintServices.add(PrintServiceInfo.create(this.mContext, installedService));
            }
        }
        this.mInstalledServices.clear();
        this.mInstalledServices.addAll(tempPrintServices);
    }

    private void upgradePersistentStateIfNeeded() {
        String enabledSettingValue = Settings.Secure.getStringForUser(this.mContext.getContentResolver(), "enabled_print_services", this.mUserId);
        if (enabledSettingValue != null) {
            Set<ComponentName> enabledServiceNameSet = new HashSet<>();
            readPrintServicesFromSettingLocked("enabled_print_services", enabledServiceNameSet);
            ArraySet<ComponentName> disabledServices = new ArraySet<>();
            int numInstalledServices = this.mInstalledServices.size();
            for (int i = 0; i < numInstalledServices; i++) {
                ComponentName serviceName = this.mInstalledServices.get(i).getComponentName();
                if (!enabledServiceNameSet.contains(serviceName)) {
                    disabledServices.add(serviceName);
                }
            }
            writeDisabledPrintServicesLocked(disabledServices);
            Settings.Secure.putStringForUser(this.mContext.getContentResolver(), "enabled_print_services", null, this.mUserId);
        }
    }

    private void readDisabledPrintServicesLocked() {
        Set<ComponentName> tempDisabledServiceNameSet = new HashSet<>();
        readPrintServicesFromSettingLocked("disabled_print_services", tempDisabledServiceNameSet);
        if (!tempDisabledServiceNameSet.equals(this.mDisabledServices)) {
            this.mDisabledServices.clear();
            this.mDisabledServices.addAll(tempDisabledServiceNameSet);
        }
    }

    private void readPrintServicesFromSettingLocked(String setting, Set<ComponentName> outServiceNames) {
        ComponentName componentName;
        String settingValue = Settings.Secure.getStringForUser(this.mContext.getContentResolver(), setting, this.mUserId);
        if (!TextUtils.isEmpty(settingValue)) {
            TextUtils.SimpleStringSplitter splitter = this.mStringColonSplitter;
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                String string = splitter.next();
                if (!TextUtils.isEmpty(string) && (componentName = ComponentName.unflattenFromString(string)) != null) {
                    outServiceNames.add(componentName);
                }
            }
        }
    }

    private void writeDisabledPrintServicesLocked(Set<ComponentName> disabledServices) {
        StringBuilder builder = new StringBuilder();
        for (ComponentName componentName : disabledServices) {
            if (builder.length() > 0) {
                builder.append(COMPONENT_NAME_SEPARATOR);
            }
            builder.append(componentName.flattenToShortString());
        }
        Settings.Secure.putStringForUser(this.mContext.getContentResolver(), "disabled_print_services", builder.toString(), this.mUserId);
    }

    private ArrayList<ComponentName> getInstalledComponents() {
        ArrayList<ComponentName> installedComponents = new ArrayList<>();
        int installedCount = this.mInstalledServices.size();
        for (int i = 0; i < installedCount; i++) {
            ResolveInfo resolveInfo = this.mInstalledServices.get(i).getResolveInfo();
            ComponentName serviceName = new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
            installedComponents.add(serviceName);
        }
        return installedComponents;
    }

    public void prunePrintServices() {
        ArrayList<ComponentName> installedComponents;
        synchronized (this.mLock) {
            installedComponents = getInstalledComponents();
            boolean disabledServicesUninstalled = this.mDisabledServices.retainAll(installedComponents);
            if (disabledServicesUninstalled) {
                writeDisabledPrintServicesLocked(this.mDisabledServices);
            }
        }
        this.mSpooler.pruneApprovedPrintServices(installedComponents);
    }

    private void onConfigurationChangedLocked() {
        ArrayList<ComponentName> installedComponents = getInstalledComponents();
        int installedCount = installedComponents.size();
        for (int i = 0; i < installedCount; i++) {
            ComponentName serviceName = installedComponents.get(i);
            if (!this.mDisabledServices.contains(serviceName)) {
                if (!this.mActiveServices.containsKey(serviceName)) {
                    RemotePrintService service = new RemotePrintService(this.mContext, serviceName, this.mUserId, this.mSpooler, this);
                    addServiceLocked(service);
                }
            } else {
                RemotePrintService service2 = this.mActiveServices.remove(serviceName);
                if (service2 != null) {
                    removeServiceLocked(service2);
                }
            }
        }
        Iterator<Map.Entry<ComponentName, RemotePrintService>> iterator = this.mActiveServices.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ComponentName, RemotePrintService> entry = iterator.next();
            RemotePrintService service3 = entry.getValue();
            if (!installedComponents.contains(entry.getKey())) {
                removeServiceLocked(service3);
                iterator.remove();
            }
        }
        onPrintServicesChanged();
    }

    private void addServiceLocked(RemotePrintService service) {
        this.mActiveServices.put(service.getComponentName(), service);
        if (this.mPrinterDiscoverySession != null) {
            this.mPrinterDiscoverySession.onServiceAddedLocked(service);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeServiceLocked(RemotePrintService service) {
        failActivePrintJobsForService(service.getComponentName());
        if (this.mPrinterDiscoverySession != null) {
            this.mPrinterDiscoverySession.onServiceRemovedLocked(service);
        } else {
            service.destroy();
        }
    }

    private void failActivePrintJobsForService(ComponentName serviceName) {
        if (Looper.getMainLooper().isCurrentThread()) {
            BackgroundThread.getHandler().sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$HoM_sy_T_4RiQGYcbixewHZ2IMA
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((UserState) obj).failScheduledPrintJobsForServiceInternal((ComponentName) obj2);
                }
            }, this, serviceName));
        } else {
            failScheduledPrintJobsForServiceInternal(serviceName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void failScheduledPrintJobsForServiceInternal(ComponentName serviceName) {
        List<PrintJobInfo> printJobs = this.mSpooler.getPrintJobInfos(serviceName, -4, -2);
        if (printJobs == null) {
            return;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            int printJobCount = printJobs.size();
            for (int i = 0; i < printJobCount; i++) {
                PrintJobInfo printJob = printJobs.get(i);
                this.mSpooler.setPrintJobState(printJob.getId(), 6, this.mContext.getString(17040751));
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void throwIfDestroyedLocked() {
        if (this.mDestroyed) {
            throw new IllegalStateException("Cannot interact with a destroyed instance.");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDispatchPrintJobStateChanged(PrintJobId printJobId, IntSupplier appIdSupplier) {
        int appId = appIdSupplier.getAsInt();
        synchronized (this.mLock) {
            if (this.mPrintJobStateChangeListenerRecords == null) {
                return;
            }
            List<PrintJobStateChangeListenerRecord> records = new ArrayList<>(this.mPrintJobStateChangeListenerRecords);
            int recordCount = records.size();
            for (int i = 0; i < recordCount; i++) {
                PrintJobStateChangeListenerRecord record = records.get(i);
                if (record.appId == -2 || record.appId == appId) {
                    try {
                        record.listener.onPrintJobStateChanged(printJobId);
                    } catch (RemoteException re) {
                        Log.e(LOG_TAG, "Error notifying for print job state change", re);
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDispatchPrintServicesChanged() {
        synchronized (this.mLock) {
            if (this.mPrintServicesChangeListenerRecords == null) {
                return;
            }
            List<ListenerRecord<IPrintServicesChangeListener>> records = new ArrayList<>(this.mPrintServicesChangeListenerRecords);
            int recordCount = records.size();
            for (int i = 0; i < recordCount; i++) {
                ListenerRecord<IPrintServicesChangeListener> record = records.get(i);
                try {
                    record.listener.onPrintServicesChanged();
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error notifying for print services change", re);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleDispatchPrintServiceRecommendationsUpdated(List<RecommendationInfo> recommendations) {
        synchronized (this.mLock) {
            if (this.mPrintServiceRecommendationsChangeListenerRecords == null) {
                return;
            }
            List<ListenerRecord<IRecommendationsChangeListener>> records = new ArrayList<>(this.mPrintServiceRecommendationsChangeListenerRecords);
            this.mPrintServiceRecommendations = recommendations;
            int recordCount = records.size();
            for (int i = 0; i < recordCount; i++) {
                ListenerRecord<IRecommendationsChangeListener> record = records.get(i);
                try {
                    record.listener.onRecommendationsChanged();
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error notifying for print service recommendations change", re);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onConfigurationChanged() {
        synchronized (this.mLock) {
            onConfigurationChangedLocked();
        }
    }

    public boolean getBindInstantServiceAllowed() {
        return this.mIsInstantServiceAllowed;
    }

    public void setBindInstantServiceAllowed(boolean allowed) {
        synchronized (this.mLock) {
            this.mIsInstantServiceAllowed = allowed;
            updateIfNeededLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public abstract class PrintJobStateChangeListenerRecord implements IBinder.DeathRecipient {
        final int appId;
        final IPrintJobStateChangeListener listener;

        public abstract void onBinderDied();

        public PrintJobStateChangeListenerRecord(IPrintJobStateChangeListener listener, int appId) throws RemoteException {
            this.listener = listener;
            this.appId = appId;
            listener.asBinder().linkToDeath(this, 0);
        }

        public void destroy() {
            this.listener.asBinder().unlinkToDeath(this, 0);
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            this.listener.asBinder().unlinkToDeath(this, 0);
            onBinderDied();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public abstract class ListenerRecord<T extends IInterface> implements IBinder.DeathRecipient {
        final T listener;

        public abstract void onBinderDied();

        public ListenerRecord(T listener) throws RemoteException {
            this.listener = listener;
            listener.asBinder().linkToDeath(this, 0);
        }

        public void destroy() {
            this.listener.asBinder().unlinkToDeath(this, 0);
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            this.listener.asBinder().unlinkToDeath(this, 0);
            onBinderDied();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class PrinterDiscoverySessionMediator {
        private boolean mIsDestroyed;
        private final ArrayMap<PrinterId, PrinterInfo> mPrinters = new ArrayMap<>();
        private final RemoteCallbackList<IPrinterDiscoveryObserver> mDiscoveryObservers = new RemoteCallbackList<IPrinterDiscoveryObserver>() { // from class: com.android.server.print.UserState.PrinterDiscoverySessionMediator.1
            @Override // android.os.RemoteCallbackList
            public void onCallbackDied(IPrinterDiscoveryObserver observer) {
                synchronized (UserState.this.mLock) {
                    PrinterDiscoverySessionMediator.this.stopPrinterDiscoveryLocked(observer);
                    PrinterDiscoverySessionMediator.this.removeObserverLocked(observer);
                }
            }
        };
        private final List<IBinder> mStartedPrinterDiscoveryTokens = new ArrayList();
        private final List<PrinterId> mStateTrackedPrinters = new ArrayList();

        PrinterDiscoverySessionMediator() {
            Handler.getMain().sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$Ou3LUs53hzSrIma0FHPj2g3gePc
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((UserState.PrinterDiscoverySessionMediator) obj).handleDispatchCreatePrinterDiscoverySession((ArrayList) obj2);
                }
            }, this, new ArrayList(UserState.this.mActiveServices.values())));
        }

        public void addObserverLocked(IPrinterDiscoveryObserver observer) {
            this.mDiscoveryObservers.register(observer);
            if (!this.mPrinters.isEmpty()) {
                Handler.getMain().sendMessage(PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$vhz2AcQkYu3SdMlMt9bsncMGW7E
                    public final void accept(Object obj, Object obj2, Object obj3) {
                        ((UserState.PrinterDiscoverySessionMediator) obj).handlePrintersAdded((IPrinterDiscoveryObserver) obj2, (ArrayList) obj3);
                    }
                }, this, observer, new ArrayList(this.mPrinters.values())));
            }
        }

        public void removeObserverLocked(IPrinterDiscoveryObserver observer) {
            this.mDiscoveryObservers.unregister(observer);
            if (this.mDiscoveryObservers.getRegisteredCallbackCount() == 0) {
                destroyLocked();
            }
        }

        public final void startPrinterDiscoveryLocked(IPrinterDiscoveryObserver observer, List<PrinterId> priorityList) {
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not starting dicovery - session destroyed");
                return;
            }
            boolean discoveryStarted = !this.mStartedPrinterDiscoveryTokens.isEmpty();
            this.mStartedPrinterDiscoveryTokens.add(observer.asBinder());
            if (discoveryStarted && priorityList != null && !priorityList.isEmpty()) {
                UserState.this.validatePrinters(priorityList);
            } else if (this.mStartedPrinterDiscoveryTokens.size() > 1) {
            } else {
                Handler.getMain().sendMessage(PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$MT8AtQ4cegoEAucY7Fm8C8TCrjo
                    public final void accept(Object obj, Object obj2, Object obj3) {
                        ((UserState.PrinterDiscoverySessionMediator) obj).handleDispatchStartPrinterDiscovery((ArrayList) obj2, (List) obj3);
                    }
                }, this, new ArrayList(UserState.this.mActiveServices.values()), priorityList));
            }
        }

        public final void stopPrinterDiscoveryLocked(IPrinterDiscoveryObserver observer) {
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not stopping dicovery - session destroyed");
            } else if (!this.mStartedPrinterDiscoveryTokens.remove(observer.asBinder()) || !this.mStartedPrinterDiscoveryTokens.isEmpty()) {
            } else {
                Handler.getMain().sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$TNeLGO1RKf0CucB-BMQ_M0UyoRs
                    @Override // java.util.function.BiConsumer
                    public final void accept(Object obj, Object obj2) {
                        ((UserState.PrinterDiscoverySessionMediator) obj).handleDispatchStopPrinterDiscovery((ArrayList) obj2);
                    }
                }, this, new ArrayList(UserState.this.mActiveServices.values())));
            }
        }

        public void validatePrintersLocked(List<PrinterId> printerIds) {
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not validating pritners - session destroyed");
                return;
            }
            List<PrinterId> remainingList = new ArrayList<>(printerIds);
            while (!remainingList.isEmpty()) {
                Iterator<PrinterId> iterator = remainingList.iterator();
                List<PrinterId> updateList = new ArrayList<>();
                ComponentName serviceName = null;
                while (iterator.hasNext()) {
                    PrinterId printerId = iterator.next();
                    if (printerId != null) {
                        if (updateList.isEmpty()) {
                            updateList.add(printerId);
                            serviceName = printerId.getServiceName();
                            iterator.remove();
                        } else if (printerId.getServiceName().equals(serviceName)) {
                            updateList.add(printerId);
                            iterator.remove();
                        }
                    }
                }
                RemotePrintService service = (RemotePrintService) UserState.this.mActiveServices.get(serviceName);
                if (service != null) {
                    Handler.getMain().sendMessage(PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$Sqq0rjax7wbbY4ugrdxXopSyMNM
                        public final void accept(Object obj, Object obj2, Object obj3) {
                            ((UserState.PrinterDiscoverySessionMediator) obj).handleValidatePrinters((RemotePrintService) obj2, (List) obj3);
                        }
                    }, this, service, updateList));
                }
            }
        }

        public final void startPrinterStateTrackingLocked(PrinterId printerId) {
            RemotePrintService service;
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not starting printer state tracking - session destroyed");
            } else if (this.mStartedPrinterDiscoveryTokens.isEmpty()) {
            } else {
                boolean containedPrinterId = this.mStateTrackedPrinters.contains(printerId);
                this.mStateTrackedPrinters.add(printerId);
                if (containedPrinterId || (service = (RemotePrintService) UserState.this.mActiveServices.get(printerId.getServiceName())) == null) {
                    return;
                }
                Handler.getMain().sendMessage(PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$iQrjLK8luujjjp1uW3VGCsAZK_g
                    public final void accept(Object obj, Object obj2, Object obj3) {
                        ((UserState.PrinterDiscoverySessionMediator) obj).handleStartPrinterStateTracking((RemotePrintService) obj2, (PrinterId) obj3);
                    }
                }, this, service, printerId));
            }
        }

        public final void stopPrinterStateTrackingLocked(PrinterId printerId) {
            RemotePrintService service;
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not stopping printer state tracking - session destroyed");
            } else if (this.mStartedPrinterDiscoveryTokens.isEmpty() || !this.mStateTrackedPrinters.remove(printerId) || (service = (RemotePrintService) UserState.this.mActiveServices.get(printerId.getServiceName())) == null) {
            } else {
                Handler.getMain().sendMessage(PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$_XymASnzhemmGwK4Nu5RUIT0ahk
                    public final void accept(Object obj, Object obj2, Object obj3) {
                        ((UserState.PrinterDiscoverySessionMediator) obj).handleStopPrinterStateTracking((RemotePrintService) obj2, (PrinterId) obj3);
                    }
                }, this, service, printerId));
            }
        }

        public void onDestroyed() {
        }

        public void destroyLocked() {
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not destroying - session destroyed");
                return;
            }
            this.mIsDestroyed = true;
            int printerCount = this.mStateTrackedPrinters.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterId printerId = this.mStateTrackedPrinters.get(i);
                UserState.this.stopPrinterStateTracking(printerId);
            }
            int observerCount = this.mStartedPrinterDiscoveryTokens.size();
            for (int i2 = 0; i2 < observerCount; i2++) {
                IBinder token = this.mStartedPrinterDiscoveryTokens.get(i2);
                stopPrinterDiscoveryLocked(IPrinterDiscoveryObserver.Stub.asInterface(token));
            }
            Handler.getMain().sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$TAWPnRTK22Veu2-mmKNSJCvnBoU
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((UserState.PrinterDiscoverySessionMediator) obj).handleDispatchDestroyPrinterDiscoverySession((ArrayList) obj2);
                }
            }, this, new ArrayList(UserState.this.mActiveServices.values())));
        }

        public void onPrintersAddedLocked(List<PrinterInfo> printers) {
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not adding printers - session destroyed");
                return;
            }
            List<PrinterInfo> addedPrinters = null;
            int addedPrinterCount = printers.size();
            for (int i = 0; i < addedPrinterCount; i++) {
                PrinterInfo printer = printers.get(i);
                PrinterInfo oldPrinter = this.mPrinters.put(printer.getId(), printer);
                if (oldPrinter == null || !oldPrinter.equals(printer)) {
                    if (addedPrinters == null) {
                        addedPrinters = new ArrayList<>();
                    }
                    addedPrinters.add(printer);
                }
            }
            if (addedPrinters != null) {
                Handler.getMain().sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$lfSsgTy_1NLRRkjOH_yL2Tk_x2w
                    @Override // java.util.function.BiConsumer
                    public final void accept(Object obj, Object obj2) {
                        ((UserState.PrinterDiscoverySessionMediator) obj).handleDispatchPrintersAdded((List) obj2);
                    }
                }, this, addedPrinters));
            }
        }

        public void onPrintersRemovedLocked(List<PrinterId> printerIds) {
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not removing printers - session destroyed");
                return;
            }
            List<PrinterId> removedPrinterIds = null;
            int removedPrinterCount = printerIds.size();
            for (int i = 0; i < removedPrinterCount; i++) {
                PrinterId removedPrinterId = printerIds.get(i);
                if (this.mPrinters.remove(removedPrinterId) != null) {
                    if (removedPrinterIds == null) {
                        removedPrinterIds = new ArrayList<>();
                    }
                    removedPrinterIds.add(removedPrinterId);
                }
            }
            if (removedPrinterIds != null) {
                Handler.getMain().sendMessage(PooledLambda.obtainMessage($$Lambda$UserState$PrinterDiscoverySessionMediator$CjemUQP8s7wGdqpIggj9Oze6I.INSTANCE, this, removedPrinterIds));
            }
        }

        public void onServiceRemovedLocked(RemotePrintService service) {
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not updating removed service - session destroyed");
                return;
            }
            ComponentName serviceName = service.getComponentName();
            removePrintersForServiceLocked(serviceName);
            service.destroy();
        }

        public void onCustomPrinterIconLoadedLocked(PrinterId printerId) {
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not updating printer - session destroyed");
                return;
            }
            PrinterInfo printer = this.mPrinters.get(printerId);
            if (printer != null) {
                PrinterInfo newPrinter = new PrinterInfo.Builder(printer).incCustomPrinterIconGen().build();
                this.mPrinters.put(printerId, newPrinter);
                ArrayList<PrinterInfo> addedPrinters = new ArrayList<>(1);
                addedPrinters.add(newPrinter);
                Handler.getMain().sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.print.-$$Lambda$UserState$PrinterDiscoverySessionMediator$y51cj-jOuPNqkjzP4R89xJuclvo
                    @Override // java.util.function.BiConsumer
                    public final void accept(Object obj, Object obj2) {
                        ((UserState.PrinterDiscoverySessionMediator) obj).handleDispatchPrintersAdded((ArrayList) obj2);
                    }
                }, this, addedPrinters));
            }
        }

        public void onServiceDiedLocked(RemotePrintService service) {
            UserState.this.removeServiceLocked(service);
        }

        public void onServiceAddedLocked(RemotePrintService service) {
            if (this.mIsDestroyed) {
                Log.w(UserState.LOG_TAG, "Not updating added service - session destroyed");
                return;
            }
            Handler.getMain().sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.print.-$$Lambda$nSUd_Gl040MrfHGSQHSjunnnXaY
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((RemotePrintService) obj).createPrinterDiscoverySession();
                }
            }, service));
            if (!this.mStartedPrinterDiscoveryTokens.isEmpty()) {
                Handler.getMain().sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.print.-$$Lambda$gs6W8Li-g_ih6LLUIbTqHmyAoh0
                    @Override // java.util.function.BiConsumer
                    public final void accept(Object obj, Object obj2) {
                        ((RemotePrintService) obj).startPrinterDiscovery((List) obj2);
                    }
                }, service, (Object) null));
            }
            int trackedPrinterCount = this.mStateTrackedPrinters.size();
            for (int i = 0; i < trackedPrinterCount; i++) {
                PrinterId printerId = this.mStateTrackedPrinters.get(i);
                if (printerId.getServiceName().equals(service.getComponentName())) {
                    Handler.getMain().sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.print.-$$Lambda$qhnzLVwIUlj5cUdZ9YacT2IXyug
                        @Override // java.util.function.BiConsumer
                        public final void accept(Object obj, Object obj2) {
                            ((RemotePrintService) obj).startPrinterStateTracking((PrinterId) obj2);
                        }
                    }, service, printerId));
                }
            }
        }

        public void dumpLocked(DualDumpOutputStream dumpStream) {
            dumpStream.write("is_destroyed", 1133871366145L, UserState.this.mDestroyed);
            dumpStream.write("is_printer_discovery_in_progress", 1133871366146L, !this.mStartedPrinterDiscoveryTokens.isEmpty());
            int observerCount = this.mDiscoveryObservers.beginBroadcast();
            for (int i = 0; i < observerCount; i++) {
                IPrinterDiscoveryObserver observer = this.mDiscoveryObservers.getBroadcastItem(i);
                dumpStream.write("printer_discovery_observers", 2237677961219L, observer.toString());
            }
            this.mDiscoveryObservers.finishBroadcast();
            int tokenCount = this.mStartedPrinterDiscoveryTokens.size();
            for (int i2 = 0; i2 < tokenCount; i2++) {
                IBinder token = this.mStartedPrinterDiscoveryTokens.get(i2);
                dumpStream.write("discovery_requests", 2237677961220L, token.toString());
            }
            int trackedPrinters = this.mStateTrackedPrinters.size();
            for (int i3 = 0; i3 < trackedPrinters; i3++) {
                PrinterId printer = this.mStateTrackedPrinters.get(i3);
                com.android.internal.print.DumpUtils.writePrinterId(dumpStream, "tracked_printer_requests", 2246267895813L, printer);
            }
            int printerCount = this.mPrinters.size();
            for (int i4 = 0; i4 < printerCount; i4++) {
                PrinterInfo printer2 = this.mPrinters.valueAt(i4);
                com.android.internal.print.DumpUtils.writePrinterInfo(UserState.this.mContext, dumpStream, "printer", 2246267895814L, printer2);
            }
        }

        private void removePrintersForServiceLocked(ComponentName serviceName) {
            if (this.mPrinters.isEmpty()) {
                return;
            }
            int printerCount = this.mPrinters.size();
            List<PrinterId> removedPrinterIds = null;
            for (int i = 0; i < printerCount; i++) {
                PrinterId printerId = this.mPrinters.keyAt(i);
                if (printerId.getServiceName().equals(serviceName)) {
                    if (removedPrinterIds == null) {
                        removedPrinterIds = new ArrayList<>();
                    }
                    removedPrinterIds.add(printerId);
                }
            }
            if (removedPrinterIds != null) {
                int removedPrinterCount = removedPrinterIds.size();
                for (int i2 = 0; i2 < removedPrinterCount; i2++) {
                    this.mPrinters.remove(removedPrinterIds.get(i2));
                }
                Handler.getMain().sendMessage(PooledLambda.obtainMessage($$Lambda$UserState$PrinterDiscoverySessionMediator$CjemUQP8s7wGdqpIggj9Oze6I.INSTANCE, this, removedPrinterIds));
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleDispatchPrintersAdded(List<PrinterInfo> addedPrinters) {
            int observerCount = this.mDiscoveryObservers.beginBroadcast();
            for (int i = 0; i < observerCount; i++) {
                IPrinterDiscoveryObserver observer = this.mDiscoveryObservers.getBroadcastItem(i);
                handlePrintersAdded(observer, addedPrinters);
            }
            this.mDiscoveryObservers.finishBroadcast();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleDispatchPrintersRemoved(List<PrinterId> removedPrinterIds) {
            int observerCount = this.mDiscoveryObservers.beginBroadcast();
            for (int i = 0; i < observerCount; i++) {
                IPrinterDiscoveryObserver observer = this.mDiscoveryObservers.getBroadcastItem(i);
                handlePrintersRemoved(observer, removedPrinterIds);
            }
            this.mDiscoveryObservers.finishBroadcast();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleDispatchCreatePrinterDiscoverySession(List<RemotePrintService> services) {
            int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.createPrinterDiscoverySession();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleDispatchDestroyPrinterDiscoverySession(List<RemotePrintService> services) {
            int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.destroyPrinterDiscoverySession();
            }
            onDestroyed();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleDispatchStartPrinterDiscovery(List<RemotePrintService> services, List<PrinterId> printerIds) {
            int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.startPrinterDiscovery(printerIds);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleDispatchStopPrinterDiscovery(List<RemotePrintService> services) {
            int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.stopPrinterDiscovery();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleValidatePrinters(RemotePrintService service, List<PrinterId> printerIds) {
            service.validatePrinters(printerIds);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleStartPrinterStateTracking(RemotePrintService service, PrinterId printerId) {
            service.startPrinterStateTracking(printerId);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handleStopPrinterStateTracking(RemotePrintService service, PrinterId printerId) {
            service.stopPrinterStateTracking(printerId);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void handlePrintersAdded(IPrinterDiscoveryObserver observer, List<PrinterInfo> printers) {
            try {
                observer.onPrintersAdded(new ParceledListSlice(printers));
            } catch (RemoteException re) {
                Log.e(UserState.LOG_TAG, "Error sending added printers", re);
            }
        }

        private void handlePrintersRemoved(IPrinterDiscoveryObserver observer, List<PrinterId> printerIds) {
            try {
                observer.onPrintersRemoved(new ParceledListSlice(printerIds));
            } catch (RemoteException re) {
                Log.e(UserState.LOG_TAG, "Error sending removed printers", re);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class PrintJobForAppCache {
        private final SparseArray<List<PrintJobInfo>> mPrintJobsForRunningApp;

        private PrintJobForAppCache() {
            this.mPrintJobsForRunningApp = new SparseArray<>();
        }

        public boolean onPrintJobCreated(final IBinder creator, final int appId, PrintJobInfo printJob) {
            try {
                creator.linkToDeath(new IBinder.DeathRecipient() { // from class: com.android.server.print.UserState.PrintJobForAppCache.1
                    @Override // android.os.IBinder.DeathRecipient
                    public void binderDied() {
                        creator.unlinkToDeath(this, 0);
                        synchronized (UserState.this.mLock) {
                            PrintJobForAppCache.this.mPrintJobsForRunningApp.remove(appId);
                        }
                    }
                }, 0);
                synchronized (UserState.this.mLock) {
                    List<PrintJobInfo> printJobsForApp = this.mPrintJobsForRunningApp.get(appId);
                    if (printJobsForApp == null) {
                        printJobsForApp = new ArrayList();
                        this.mPrintJobsForRunningApp.put(appId, printJobsForApp);
                    }
                    printJobsForApp.add(printJob);
                }
                return true;
            } catch (RemoteException e) {
                return false;
            }
        }

        public void onPrintJobStateChanged(PrintJobInfo printJob) {
            synchronized (UserState.this.mLock) {
                List<PrintJobInfo> printJobsForApp = this.mPrintJobsForRunningApp.get(printJob.getAppId());
                if (printJobsForApp == null) {
                    return;
                }
                int printJobCount = printJobsForApp.size();
                for (int i = 0; i < printJobCount; i++) {
                    PrintJobInfo oldPrintJob = printJobsForApp.get(i);
                    if (oldPrintJob.getId().equals(printJob.getId())) {
                        printJobsForApp.set(i, printJob);
                    }
                }
            }
        }

        public PrintJobInfo getPrintJob(PrintJobId printJobId, int appId) {
            synchronized (UserState.this.mLock) {
                List<PrintJobInfo> printJobsForApp = this.mPrintJobsForRunningApp.get(appId);
                if (printJobsForApp == null) {
                    return null;
                }
                int printJobCount = printJobsForApp.size();
                for (int i = 0; i < printJobCount; i++) {
                    PrintJobInfo printJob = printJobsForApp.get(i);
                    if (printJob.getId().equals(printJobId)) {
                        return printJob;
                    }
                }
                return null;
            }
        }

        public List<PrintJobInfo> getPrintJobs(int appId) {
            synchronized (UserState.this.mLock) {
                List<PrintJobInfo> printJobs = null;
                try {
                    if (appId == -2) {
                        int bucketCount = this.mPrintJobsForRunningApp.size();
                        for (int i = 0; i < bucketCount; i++) {
                            List<PrintJobInfo> bucket = this.mPrintJobsForRunningApp.valueAt(i);
                            if (printJobs == null) {
                                printJobs = new ArrayList<>();
                            }
                            printJobs.addAll(bucket);
                        }
                    } else {
                        List<PrintJobInfo> bucket2 = this.mPrintJobsForRunningApp.get(appId);
                        if (bucket2 != null) {
                            if (0 == 0) {
                                printJobs = new ArrayList<>();
                            }
                            printJobs.addAll(bucket2);
                        }
                    }
                    if (printJobs != null) {
                        return printJobs;
                    }
                    return Collections.emptyList();
                } finally {
                }
            }
        }

        public void dumpLocked(DualDumpOutputStream dumpStream) {
            int bucketCount = this.mPrintJobsForRunningApp.size();
            int i = 0;
            while (true) {
                int i2 = i;
                if (i2 >= bucketCount) {
                    return;
                }
                int appId = this.mPrintJobsForRunningApp.keyAt(i2);
                List<PrintJobInfo> bucket = this.mPrintJobsForRunningApp.valueAt(i2);
                int printJobCount = bucket.size();
                int j = 0;
                while (true) {
                    int j2 = j;
                    if (j2 < printJobCount) {
                        long token = dumpStream.start("cached_print_jobs", 2246267895813L);
                        dumpStream.write("app_id", 1120986464257L, appId);
                        com.android.internal.print.DumpUtils.writePrintJobInfo(UserState.this.mContext, dumpStream, "print_job", 1146756268034L, bucket.get(j2));
                        dumpStream.end(token);
                        j = j2 + 1;
                        i2 = i2;
                    }
                }
                i = i2 + 1;
            }
        }
    }
}
