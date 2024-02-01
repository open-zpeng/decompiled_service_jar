package com.android.server;

import android.content.Context;
import android.location.Country;
import android.location.CountryListener;
import android.location.ICountryDetector;
import android.location.ICountryListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.location.ComprehensiveCountryDetector;
import com.android.server.location.CountryDetectorBase;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;

/* loaded from: classes.dex */
public class CountryDetectorService extends ICountryDetector.Stub {
    private static final boolean DEBUG = false;
    private static final String TAG = "CountryDetector";
    private final Context mContext;
    private CountryDetectorBase mCountryDetector;
    private Handler mHandler;
    private CountryListener mLocationBasedDetectorListener;
    private final HashMap<IBinder, Receiver> mReceivers;
    private boolean mSystemReady;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class Receiver implements IBinder.DeathRecipient {
        private final IBinder mKey;
        private final ICountryListener mListener;

        public Receiver(ICountryListener listener) {
            this.mListener = listener;
            this.mKey = listener.asBinder();
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            CountryDetectorService.this.removeListener(this.mKey);
        }

        public boolean equals(Object otherObj) {
            if (otherObj instanceof Receiver) {
                return this.mKey.equals(((Receiver) otherObj).mKey);
            }
            return false;
        }

        public int hashCode() {
            return this.mKey.hashCode();
        }

        public ICountryListener getListener() {
            return this.mListener;
        }
    }

    public CountryDetectorService(Context context) {
        this(context, BackgroundThread.getHandler());
    }

    @VisibleForTesting
    CountryDetectorService(Context context, Handler handler) {
        this.mReceivers = new HashMap<>();
        this.mContext = context;
        this.mHandler = handler;
    }

    public Country detectCountry() {
        if (!this.mSystemReady) {
            return null;
        }
        return this.mCountryDetector.detectCountry();
    }

    public void addCountryListener(ICountryListener listener) throws RemoteException {
        if (!this.mSystemReady) {
            throw new RemoteException();
        }
        addListener(listener);
    }

    public void removeCountryListener(ICountryListener listener) throws RemoteException {
        if (!this.mSystemReady) {
            throw new RemoteException();
        }
        removeListener(listener.asBinder());
    }

    private void addListener(ICountryListener listener) {
        synchronized (this.mReceivers) {
            Receiver r = new Receiver(listener);
            try {
                listener.asBinder().linkToDeath(r, 0);
                this.mReceivers.put(listener.asBinder(), r);
                if (this.mReceivers.size() == 1) {
                    Slog.d(TAG, "The first listener is added");
                    setCountryListener(this.mLocationBasedDetectorListener);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "linkToDeath failed:", e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeListener(IBinder key) {
        synchronized (this.mReceivers) {
            this.mReceivers.remove(key);
            if (this.mReceivers.isEmpty()) {
                setCountryListener(null);
                Slog.d(TAG, "No listener is left");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* renamed from: notifyReceivers */
    public void lambda$initialize$1$CountryDetectorService(Country country) {
        synchronized (this.mReceivers) {
            for (Receiver receiver : this.mReceivers.values()) {
                try {
                    receiver.getListener().onCountryDetected(country);
                } catch (RemoteException e) {
                    Slog.e(TAG, "notifyReceivers failed:", e);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void systemRunning() {
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$CountryDetectorService$ESi5ICoEixGJHWdY67G_J38VrJI
            @Override // java.lang.Runnable
            public final void run() {
                CountryDetectorService.this.lambda$systemRunning$0$CountryDetectorService();
            }
        });
    }

    public /* synthetic */ void lambda$systemRunning$0$CountryDetectorService() {
        initialize();
        this.mSystemReady = true;
    }

    @VisibleForTesting
    void initialize() {
        String customCountryClass = this.mContext.getString(17039691);
        if (!TextUtils.isEmpty(customCountryClass)) {
            this.mCountryDetector = loadCustomCountryDetectorIfAvailable(customCountryClass);
        }
        if (this.mCountryDetector == null) {
            Slog.d(TAG, "Using default country detector");
            this.mCountryDetector = new ComprehensiveCountryDetector(this.mContext);
        }
        this.mLocationBasedDetectorListener = new CountryListener() { // from class: com.android.server.-$$Lambda$CountryDetectorService$YZWlE4qqoDuiwnkSrasi91p2-Tk
            public final void onCountryDetected(Country country) {
                CountryDetectorService.this.lambda$initialize$2$CountryDetectorService(country);
            }
        };
    }

    public /* synthetic */ void lambda$initialize$2$CountryDetectorService(final Country country) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$CountryDetectorService$fFSTHORponDwFf2wlaJLUdUhirQ
            @Override // java.lang.Runnable
            public final void run() {
                CountryDetectorService.this.lambda$initialize$1$CountryDetectorService(country);
            }
        });
    }

    public /* synthetic */ void lambda$setCountryListener$3$CountryDetectorService(CountryListener listener) {
        this.mCountryDetector.setCountryListener(listener);
    }

    protected void setCountryListener(final CountryListener listener) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.-$$Lambda$CountryDetectorService$UdoYpHRrhGb-PF6QTwQ4SluOe20
            @Override // java.lang.Runnable
            public final void run() {
                CountryDetectorService.this.lambda$setCountryListener$3$CountryDetectorService(listener);
            }
        });
    }

    @VisibleForTesting
    CountryDetectorBase getCountryDetector() {
        return this.mCountryDetector;
    }

    @VisibleForTesting
    boolean isSystemReady() {
        return this.mSystemReady;
    }

    private CountryDetectorBase loadCustomCountryDetectorIfAvailable(String customCountryClass) {
        Slog.d(TAG, "Using custom country detector class: " + customCountryClass);
        try {
            CountryDetectorBase customCountryDetector = (CountryDetectorBase) Class.forName(customCountryClass).asSubclass(CountryDetectorBase.class).getConstructor(Context.class).newInstance(this.mContext);
            return customCountryDetector;
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
            Slog.e(TAG, "Could not instantiate the custom country detector class");
            return null;
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, fout)) {
        }
    }
}
