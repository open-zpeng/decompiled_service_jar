package com.android.server.power.batterysaver;

import android.content.Context;
import android.provider.Settings;
import com.android.server.power.batterysaver.BatterySaverController;

/* loaded from: classes.dex */
public class BatterySaverLocationPlugin implements BatterySaverController.Plugin {
    private static final boolean DEBUG = false;
    private static final String TAG = "BatterySaverLocationPlugin";
    private final Context mContext;

    public BatterySaverLocationPlugin(Context context) {
        this.mContext = context;
    }

    @Override // com.android.server.power.batterysaver.BatterySaverController.Plugin
    public void onBatterySaverChanged(BatterySaverController caller) {
        updateLocationState(caller);
    }

    @Override // com.android.server.power.batterysaver.BatterySaverController.Plugin
    public void onSystemReady(BatterySaverController caller) {
        updateLocationState(caller);
    }

    private void updateLocationState(BatterySaverController caller) {
        boolean kill = caller.getBatterySaverPolicy().getGpsMode() == 2 && !caller.isInteractive();
        Settings.Global.putInt(this.mContext.getContentResolver(), "location_global_kill_switch", kill ? 1 : 0);
    }
}
