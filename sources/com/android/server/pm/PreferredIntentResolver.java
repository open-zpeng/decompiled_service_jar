package com.android.server.pm;

import com.android.server.IntentResolver;
import java.io.PrintWriter;

/* loaded from: classes.dex */
public class PreferredIntentResolver extends IntentResolver<PreferredActivity, PreferredActivity> {
    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.IntentResolver
    public PreferredActivity[] newArray(int size) {
        return new PreferredActivity[size];
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.IntentResolver
    public boolean isPackageForFilter(String packageName, PreferredActivity filter) {
        return packageName.equals(filter.mPref.mComponent.getPackageName());
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.IntentResolver
    public void dumpFilter(PrintWriter out, String prefix, PreferredActivity filter) {
        filter.mPref.dump(out, prefix, filter);
    }
}
