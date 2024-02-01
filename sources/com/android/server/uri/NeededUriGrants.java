package com.android.server.uri;

import android.util.proto.ProtoOutputStream;
import java.util.ArrayList;

/* loaded from: classes2.dex */
public class NeededUriGrants extends ArrayList<GrantUri> {
    final int flags;
    final String targetPkg;
    final int targetUid;

    public NeededUriGrants(String targetPkg, int targetUid, int flags) {
        this.targetPkg = targetPkg;
        this.targetUid = targetUid;
        this.flags = flags;
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(1138166333441L, this.targetPkg);
        proto.write(1120986464258L, this.targetUid);
        proto.write(1120986464259L, this.flags);
        int N = size();
        for (int i = 0; i < N; i++) {
            get(i).writeToProto(proto, 2246267895812L);
        }
        proto.end(token);
    }
}
