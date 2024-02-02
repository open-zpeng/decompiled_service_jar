package com.android.server.wm;

import android.graphics.Rect;
import android.graphics.Region;
import android.util.SparseArray;
/* loaded from: classes.dex */
class TapExcludeRegionHolder {
    private SparseArray<Rect> mTapExcludeRects = new SparseArray<>();

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateRegion(int regionId, int left, int top, int width, int height) {
        if (width <= 0 || height <= 0) {
            this.mTapExcludeRects.remove(regionId);
            return;
        }
        Rect region = this.mTapExcludeRects.get(regionId);
        if (region == null) {
            region = new Rect();
        }
        region.set(left, top, left + width, top + height);
        this.mTapExcludeRects.put(regionId, region);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void amendRegion(Region region, Rect boundingRegion) {
        for (int i = this.mTapExcludeRects.size() - 1; i >= 0; i--) {
            Rect rect = this.mTapExcludeRects.valueAt(i);
            rect.intersect(boundingRegion);
            region.union(rect);
        }
    }
}
