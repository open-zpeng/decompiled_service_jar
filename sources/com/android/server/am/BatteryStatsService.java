package com.android.server.am;

import android.os.PowerManagerInternal;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;

/* loaded from: classes.dex */
public abstract class BatteryStatsService extends IBatteryStats.Stub implements PowerManagerInternal.LowPowerModeListener, BatteryStatsImpl.PlatformIdleStateCallback, BatteryStatsImpl.RailEnergyDataCallback {
    public static IBatteryStats getService() {
        return XpBatteryStatsService.getService();
    }
}
