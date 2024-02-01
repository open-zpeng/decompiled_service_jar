package com.android.server.accessibility;

import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/* compiled from: lambda */
/* renamed from: com.android.server.accessibility.-$$Lambda$X-d4PICw0vnPU2BuBjOCbMMfcgU  reason: invalid class name */
/* loaded from: classes.dex */
public final /* synthetic */ class $$Lambda$Xd4PICw0vnPU2BuBjOCbMMfcgU implements BiConsumer {
    public static final /* synthetic */ $$Lambda$Xd4PICw0vnPU2BuBjOCbMMfcgU INSTANCE = new $$Lambda$Xd4PICw0vnPU2BuBjOCbMMfcgU();

    private /* synthetic */ $$Lambda$Xd4PICw0vnPU2BuBjOCbMMfcgU() {
    }

    @Override // java.util.function.BiConsumer
    public final void accept(Object obj, Object obj2) {
        ((AccessibilityManagerService) obj).clearAccessibilityFocus((IntSupplier) obj2);
    }
}
