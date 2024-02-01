package com.android.server.pm;

import android.content.IIntentReceiver;
import android.os.Bundle;

/* compiled from: PackageManagerService.java */
/* loaded from: classes.dex */
interface PackageSender {
    void notifyPackageAdded(String str, int i);

    void notifyPackageChanged(String str, int i);

    void notifyPackageRemoved(String str, int i);

    void sendPackageAddedForNewUsers(String str, boolean z, boolean z2, int i, int[] iArr, int[] iArr2);

    void sendPackageBroadcast(String str, String str2, Bundle bundle, int i, String str3, IIntentReceiver iIntentReceiver, int[] iArr, int[] iArr2);
}
