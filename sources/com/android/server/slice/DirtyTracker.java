package com.android.server.slice;

import java.io.IOException;
import org.xmlpull.v1.XmlSerializer;

/* loaded from: classes.dex */
public interface DirtyTracker {

    /* loaded from: classes.dex */
    public interface Persistable {
        String getFileName();

        void writeTo(XmlSerializer xmlSerializer) throws IOException;
    }

    void onPersistableDirty(Persistable persistable);
}
