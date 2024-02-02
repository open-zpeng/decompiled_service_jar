package com.android.server.connectivity.tethering;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.util.VersionedBroadcastListener;
import android.os.Handler;
import android.util.Log;
import java.util.function.Consumer;
/* loaded from: classes.dex */
public class SimChangeListener extends VersionedBroadcastListener {
    private static final boolean DBG = false;
    private static final String TAG = SimChangeListener.class.getSimpleName();

    public SimChangeListener(Context ctx, Handler handler, Runnable onSimCardLoadedCallback) {
        super(TAG, ctx, handler, makeIntentFilter(), makeCallback(onSimCardLoadedCallback));
    }

    private static IntentFilter makeIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.SIM_STATE_CHANGED");
        return filter;
    }

    private static Consumer<Intent> makeCallback(final Runnable onSimCardLoadedCallback) {
        return new Consumer<Intent>() { // from class: com.android.server.connectivity.tethering.SimChangeListener.1
            private boolean mSimNotLoadedSeen = false;

            @Override // java.util.function.Consumer
            public void accept(Intent intent) {
                String state = intent.getStringExtra("ss");
                String str = SimChangeListener.TAG;
                Log.d(str, "got Sim changed to state " + state + ", mSimNotLoadedSeen=" + this.mSimNotLoadedSeen);
                if (!"LOADED".equals(state)) {
                    this.mSimNotLoadedSeen = true;
                } else if (this.mSimNotLoadedSeen) {
                    this.mSimNotLoadedSeen = false;
                    onSimCardLoadedCallback.run();
                }
            }
        };
    }
}
