package com.android.server.wm;

import android.graphics.Rect;
import android.graphics.Region;
import android.util.SparseArray;

/* loaded from: classes2.dex */
class TapExcludeRegionHolder {
    private SparseArray<Region> mTapExcludeRegions = new SparseArray<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateRegion(int regionId, Region region) {
        this.mTapExcludeRegions.remove(regionId);
        if (region == null || region.isEmpty()) {
            return;
        }
        this.mTapExcludeRegions.put(regionId, region);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void amendRegion(Region region, Rect bounds) {
        for (int i = this.mTapExcludeRegions.size() - 1; i >= 0; i--) {
            Region r = this.mTapExcludeRegions.valueAt(i);
            if (bounds != null) {
                r.op(bounds, Region.Op.INTERSECT);
            }
            region.op(r, Region.Op.UNION);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isEmpty() {
        return this.mTapExcludeRegions.size() == 0;
    }
}
