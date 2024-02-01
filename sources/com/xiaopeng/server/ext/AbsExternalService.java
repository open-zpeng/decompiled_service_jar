package com.xiaopeng.server.ext;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
/* loaded from: classes.dex */
public abstract class AbsExternalService {
    protected static final long RECONNECT_DELAY = 2000;
    protected Context mContext;
    protected Handler mHandler = new Handler();
    protected final ServiceConnection mConnection = new ServiceConnection() { // from class: com.xiaopeng.server.ext.AbsExternalService.1
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            AbsExternalService.this.onConnected(name, service);
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            AbsExternalService.this.onDisconnected(name);
        }
    };

    public abstract void bind();

    public abstract void connect();

    public abstract Object getValue(int i, Object obj, Object... objArr);

    public abstract void init();

    public abstract boolean isReady();

    public abstract void onConnected(ComponentName componentName, IBinder iBinder);

    public abstract void onDisconnected(ComponentName componentName);

    public abstract void onEventChanged(int i, Object obj);

    public abstract void setValue(int i, Object obj, Object... objArr);

    public AbsExternalService(Context context) {
        this.mContext = context;
    }
}
