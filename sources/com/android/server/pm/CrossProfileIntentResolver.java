package com.android.server.pm;

import com.android.server.IntentResolver;
import java.util.List;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class CrossProfileIntentResolver extends IntentResolver<CrossProfileIntentFilter, CrossProfileIntentFilter> {
    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.IntentResolver
    public CrossProfileIntentFilter[] newArray(int size) {
        return new CrossProfileIntentFilter[size];
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.IntentResolver
    public boolean isPackageForFilter(String packageName, CrossProfileIntentFilter filter) {
        return false;
    }

    @Override // com.android.server.IntentResolver
    protected void sortResults(List<CrossProfileIntentFilter> results) {
    }
}
