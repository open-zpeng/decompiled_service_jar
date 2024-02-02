package com.android.server.power;

import android.util.proto.ProtoOutputStream;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public interface SuspendBlocker {
    void acquire();

    void release();

    void writeToProto(ProtoOutputStream protoOutputStream, long j);
}
