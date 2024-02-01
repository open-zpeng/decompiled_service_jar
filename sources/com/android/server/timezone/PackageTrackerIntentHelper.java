package com.android.server.timezone;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public interface PackageTrackerIntentHelper {
    void initialize(String str, String str2, PackageTracker packageTracker);

    void scheduleReliabilityTrigger(long j);

    void sendTriggerUpdateCheck(CheckToken checkToken);

    void unscheduleReliabilityTrigger();
}
