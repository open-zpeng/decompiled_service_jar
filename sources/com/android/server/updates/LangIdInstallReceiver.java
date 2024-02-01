package com.android.server.updates;
/* loaded from: classes.dex */
public class LangIdInstallReceiver extends ConfigUpdateInstallReceiver {
    public LangIdInstallReceiver() {
        super("/data/misc/textclassifier/", "textclassifier.langid.model", "metadata/langid", "version");
    }
}
