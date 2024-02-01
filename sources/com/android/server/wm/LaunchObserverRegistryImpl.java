package com.android.server.wm;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/* loaded from: classes2.dex */
class LaunchObserverRegistryImpl implements ActivityMetricsLaunchObserverRegistry, ActivityMetricsLaunchObserver {
    private final Handler mHandler;
    private final ArrayList<ActivityMetricsLaunchObserver> mList = new ArrayList<>();

    public LaunchObserverRegistryImpl(Looper looper) {
        this.mHandler = new Handler(looper);
    }

    @Override // com.android.server.wm.ActivityMetricsLaunchObserverRegistry
    public void registerLaunchObserver(ActivityMetricsLaunchObserver launchObserver) {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$LaunchObserverRegistryImpl$pWUDt4Ot3BWLJOTAhXMkkhHUhpc
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((LaunchObserverRegistryImpl) obj).handleRegisterLaunchObserver((ActivityMetricsLaunchObserver) obj2);
            }
        }, this, launchObserver));
    }

    @Override // com.android.server.wm.ActivityMetricsLaunchObserverRegistry
    public void unregisterLaunchObserver(ActivityMetricsLaunchObserver launchObserver) {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$LaunchObserverRegistryImpl$850Ez4IkbH192NuVFW_l12sZL_E
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((LaunchObserverRegistryImpl) obj).handleUnregisterLaunchObserver((ActivityMetricsLaunchObserver) obj2);
            }
        }, this, launchObserver));
    }

    @Override // com.android.server.wm.ActivityMetricsLaunchObserver
    public void onIntentStarted(Intent intent) {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$LaunchObserverRegistryImpl$kM3MnXbSpyNkUV4eUyr4OwWCqqA
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((LaunchObserverRegistryImpl) obj).handleOnIntentStarted((Intent) obj2);
            }
        }, this, intent));
    }

    @Override // com.android.server.wm.ActivityMetricsLaunchObserver
    public void onIntentFailed() {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.wm.-$$Lambda$LaunchObserverRegistryImpl$KukKmVpn5W_1xSV6Dnp8wW2H2Ks
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((LaunchObserverRegistryImpl) obj).handleOnIntentFailed();
            }
        }, this));
    }

    @Override // com.android.server.wm.ActivityMetricsLaunchObserver
    public void onActivityLaunched(byte[] activity, int temperature) {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new TriConsumer() { // from class: com.android.server.wm.-$$Lambda$LaunchObserverRegistryImpl$UGY1OclnLIQLMEL9B55qjERFf4o
            public final void accept(Object obj, Object obj2, Object obj3) {
                ((LaunchObserverRegistryImpl) obj).handleOnActivityLaunched((byte[]) obj2, ((Integer) obj3).intValue());
            }
        }, this, activity, Integer.valueOf(temperature)));
    }

    @Override // com.android.server.wm.ActivityMetricsLaunchObserver
    public void onActivityLaunchCancelled(byte[] activity) {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$LaunchObserverRegistryImpl$lAGPwfsXJvBWsyG2rbEfo3sTv34
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((LaunchObserverRegistryImpl) obj).handleOnActivityLaunchCancelled((byte[]) obj2);
            }
        }, this, activity));
    }

    @Override // com.android.server.wm.ActivityMetricsLaunchObserver
    public void onActivityLaunchFinished(byte[] activity) {
        this.mHandler.sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.wm.-$$Lambda$LaunchObserverRegistryImpl$iVXZh14_jAo_Gegs5q3ygQDW-ow
            @Override // java.util.function.BiConsumer
            public final void accept(Object obj, Object obj2) {
                ((LaunchObserverRegistryImpl) obj).handleOnActivityLaunchFinished((byte[]) obj2);
            }
        }, this, activity));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRegisterLaunchObserver(ActivityMetricsLaunchObserver observer) {
        this.mList.add(observer);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleUnregisterLaunchObserver(ActivityMetricsLaunchObserver observer) {
        this.mList.remove(observer);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnIntentStarted(Intent intent) {
        for (int i = 0; i < this.mList.size(); i++) {
            ActivityMetricsLaunchObserver o = this.mList.get(i);
            o.onIntentStarted(intent);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnIntentFailed() {
        for (int i = 0; i < this.mList.size(); i++) {
            ActivityMetricsLaunchObserver o = this.mList.get(i);
            o.onIntentFailed();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnActivityLaunched(byte[] activity, int temperature) {
        for (int i = 0; i < this.mList.size(); i++) {
            ActivityMetricsLaunchObserver o = this.mList.get(i);
            o.onActivityLaunched(activity, temperature);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnActivityLaunchCancelled(byte[] activity) {
        for (int i = 0; i < this.mList.size(); i++) {
            ActivityMetricsLaunchObserver o = this.mList.get(i);
            o.onActivityLaunchCancelled(activity);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleOnActivityLaunchFinished(byte[] activity) {
        for (int i = 0; i < this.mList.size(); i++) {
            ActivityMetricsLaunchObserver o = this.mList.get(i);
            o.onActivityLaunchFinished(activity);
        }
    }
}
