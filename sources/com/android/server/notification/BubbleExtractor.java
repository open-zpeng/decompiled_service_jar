package com.android.server.notification;

import android.content.Context;

/* loaded from: classes.dex */
public class BubbleExtractor implements NotificationSignalExtractor {
    private static final boolean DBG = false;
    private static final String TAG = "BubbleExtractor";
    private RankingConfig mConfig;

    @Override // com.android.server.notification.NotificationSignalExtractor
    public void initialize(Context ctx, NotificationUsageStats usageStats) {
    }

    @Override // com.android.server.notification.NotificationSignalExtractor
    public RankingReconsideration process(NotificationRecord record) {
        RankingConfig rankingConfig;
        if (record == null || record.getNotification() == null || (rankingConfig = this.mConfig) == null) {
            return null;
        }
        boolean appCanShowBubble = rankingConfig.areBubblesAllowed(record.sbn.getPackageName(), record.sbn.getUid());
        boolean z = false;
        if (!this.mConfig.bubblesEnabled() || !appCanShowBubble) {
            record.setAllowBubble(false);
        } else if (record.getChannel() != null) {
            if (record.getChannel().canBubble() && appCanShowBubble) {
                z = true;
            }
            record.setAllowBubble(z);
        } else {
            record.setAllowBubble(appCanShowBubble);
        }
        return null;
    }

    @Override // com.android.server.notification.NotificationSignalExtractor
    public void setConfig(RankingConfig config) {
        this.mConfig = config;
    }

    @Override // com.android.server.notification.NotificationSignalExtractor
    public void setZenHelper(ZenModeHelper helper) {
    }
}
