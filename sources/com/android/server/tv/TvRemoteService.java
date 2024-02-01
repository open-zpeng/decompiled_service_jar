package com.android.server.tv;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.tv.TvRemoteProviderProxy;
import com.android.server.tv.TvRemoteProviderWatcher;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/* loaded from: classes2.dex */
public class TvRemoteService extends SystemService implements Watchdog.Monitor {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_KEYS = false;
    private static final String TAG = "TvRemoteService";
    private Map<IBinder, UinputBridge> mBridgeMap;
    public final UserHandler mHandler;
    private final Object mLock;
    private ArrayList<TvRemoteProviderProxy> mProviderList;
    private Map<IBinder, TvRemoteProviderProxy> mProviderMap;

    public TvRemoteService(Context context) {
        super(context);
        this.mBridgeMap = new ArrayMap();
        this.mProviderMap = new ArrayMap();
        this.mProviderList = new ArrayList<>();
        this.mLock = new Object();
        this.mHandler = new UserHandler(new UserProvider(this), context);
        Watchdog.getInstance().addMonitor(this);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
    }

    @Override // com.android.server.Watchdog.Monitor
    public void monitor() {
        synchronized (this.mLock) {
        }
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 600) {
            this.mHandler.sendEmptyMessage(1);
        }
    }

    private void informInputBridgeConnected(IBinder token) {
        this.mHandler.obtainMessage(2, 0, 0, token).sendToTarget();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openInputBridgeInternalLocked(TvRemoteProviderProxy provider, IBinder token, String name, int width, int height, int maxPointers) {
        try {
            if (this.mBridgeMap.containsKey(token)) {
                informInputBridgeConnected(token);
                return;
            }
            UinputBridge inputBridge = new UinputBridge(token, name, width, height, maxPointers);
            this.mBridgeMap.put(token, inputBridge);
            this.mProviderMap.put(token, provider);
            informInputBridgeConnected(token);
        } catch (IOException e) {
            Slog.e(TAG, "Cannot create device for " + name);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void closeInputBridgeInternalLocked(IBinder token) {
        UinputBridge inputBridge = this.mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.close(token);
        }
        this.mBridgeMap.remove(token);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearInputBridgeInternalLocked(IBinder token) {
        UinputBridge inputBridge = this.mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.clear(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendTimeStampInternalLocked(IBinder token, long timestamp) {
        UinputBridge inputBridge = this.mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendTimestamp(token, timestamp);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendKeyDownInternalLocked(IBinder token, int keyCode) {
        UinputBridge inputBridge = this.mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendKeyDown(token, keyCode);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendKeyUpInternalLocked(IBinder token, int keyCode) {
        UinputBridge inputBridge = this.mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendKeyUp(token, keyCode);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendPointerDownInternalLocked(IBinder token, int pointerId, int x, int y) {
        UinputBridge inputBridge = this.mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendPointerDown(token, pointerId, x, y);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendPointerUpInternalLocked(IBinder token, int pointerId) {
        UinputBridge inputBridge = this.mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendPointerUp(token, pointerId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendPointerSyncInternalLocked(IBinder token) {
        UinputBridge inputBridge = this.mBridgeMap.get(token);
        if (inputBridge != null) {
            inputBridge.sendPointerSync(token);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class UserHandler extends Handler {
        public static final int MSG_INPUT_BRIDGE_CONNECTED = 2;
        public static final int MSG_START = 1;
        private boolean mRunning;
        private final TvRemoteProviderWatcher mWatcher;

        public UserHandler(UserProvider provider, Context context) {
            super(Looper.getMainLooper(), null, true);
            this.mWatcher = new TvRemoteProviderWatcher(context, provider, this);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                start();
            } else if (i == 2) {
                IBinder token = (IBinder) msg.obj;
                TvRemoteProviderProxy provider = (TvRemoteProviderProxy) TvRemoteService.this.mProviderMap.get(token);
                if (provider != null) {
                    provider.inputBridgeConnected(token);
                }
            }
        }

        private void start() {
            if (!this.mRunning) {
                this.mRunning = true;
                this.mWatcher.start();
            }
        }
    }

    /* loaded from: classes2.dex */
    private final class UserProvider implements TvRemoteProviderWatcher.ProviderMethods, TvRemoteProviderProxy.ProviderMethods {
        private final TvRemoteService mService;

        public UserProvider(TvRemoteService service) {
            this.mService = service;
        }

        @Override // com.android.server.tv.TvRemoteProviderProxy.ProviderMethods
        public void openInputBridge(TvRemoteProviderProxy provider, IBinder token, String name, int width, int height, int maxPointers) {
            synchronized (TvRemoteService.this.mLock) {
                if (TvRemoteService.this.mProviderList.contains(provider)) {
                    this.mService.openInputBridgeInternalLocked(provider, token, name, width, height, maxPointers);
                }
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderProxy.ProviderMethods
        public void closeInputBridge(TvRemoteProviderProxy provider, IBinder token) {
            synchronized (TvRemoteService.this.mLock) {
                if (TvRemoteService.this.mProviderList.contains(provider)) {
                    this.mService.closeInputBridgeInternalLocked(token);
                    TvRemoteService.this.mProviderMap.remove(token);
                }
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderProxy.ProviderMethods
        public void clearInputBridge(TvRemoteProviderProxy provider, IBinder token) {
            synchronized (TvRemoteService.this.mLock) {
                if (TvRemoteService.this.mProviderList.contains(provider)) {
                    this.mService.clearInputBridgeInternalLocked(token);
                }
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderProxy.ProviderMethods
        public void sendTimeStamp(TvRemoteProviderProxy provider, IBinder token, long timestamp) {
            synchronized (TvRemoteService.this.mLock) {
                if (TvRemoteService.this.mProviderList.contains(provider)) {
                    this.mService.sendTimeStampInternalLocked(token, timestamp);
                }
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderProxy.ProviderMethods
        public void sendKeyDown(TvRemoteProviderProxy provider, IBinder token, int keyCode) {
            synchronized (TvRemoteService.this.mLock) {
                if (TvRemoteService.this.mProviderList.contains(provider)) {
                    this.mService.sendKeyDownInternalLocked(token, keyCode);
                }
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderProxy.ProviderMethods
        public void sendKeyUp(TvRemoteProviderProxy provider, IBinder token, int keyCode) {
            synchronized (TvRemoteService.this.mLock) {
                if (TvRemoteService.this.mProviderList.contains(provider)) {
                    this.mService.sendKeyUpInternalLocked(token, keyCode);
                }
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderProxy.ProviderMethods
        public void sendPointerDown(TvRemoteProviderProxy provider, IBinder token, int pointerId, int x, int y) {
            synchronized (TvRemoteService.this.mLock) {
                if (TvRemoteService.this.mProviderList.contains(provider)) {
                    this.mService.sendPointerDownInternalLocked(token, pointerId, x, y);
                }
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderProxy.ProviderMethods
        public void sendPointerUp(TvRemoteProviderProxy provider, IBinder token, int pointerId) {
            synchronized (TvRemoteService.this.mLock) {
                if (TvRemoteService.this.mProviderList.contains(provider)) {
                    this.mService.sendPointerUpInternalLocked(token, pointerId);
                }
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderProxy.ProviderMethods
        public void sendPointerSync(TvRemoteProviderProxy provider, IBinder token) {
            synchronized (TvRemoteService.this.mLock) {
                if (TvRemoteService.this.mProviderList.contains(provider)) {
                    this.mService.sendPointerSyncInternalLocked(token);
                }
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderWatcher.ProviderMethods
        public void addProvider(TvRemoteProviderProxy provider) {
            synchronized (TvRemoteService.this.mLock) {
                provider.setProviderSink(this);
                TvRemoteService.this.mProviderList.add(provider);
                Slog.d(TvRemoteService.TAG, "provider: " + provider.toString());
            }
        }

        @Override // com.android.server.tv.TvRemoteProviderWatcher.ProviderMethods
        public void removeProvider(TvRemoteProviderProxy provider) {
            synchronized (TvRemoteService.this.mLock) {
                if (!TvRemoteService.this.mProviderList.remove(provider)) {
                    Slog.e(TvRemoteService.TAG, "Unknown provider " + provider);
                }
            }
        }
    }
}
