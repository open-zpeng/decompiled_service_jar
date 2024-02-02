package com.xiaopeng.server.aftersales;
/* loaded from: classes.dex */
interface IAfterSalesDaemonCallbacks {
    void onDaemonConnected();

    void onEvent(AfterSalesDaemonEvent afterSalesDaemonEvent);
}
