package com.xiaopeng.server;

import android.content.Context;
import com.xiaopeng.server.ext.ExternalManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.server.wallpaper.xpWallpaperManagerService;
/* loaded from: classes.dex */
public class xpSystemServer {
    public static void init(Context context) {
    }

    public static void systemReady(Context context) {
        xpWallpaperManagerService.get(context).init();
        xpInputManagerService.get(context).init();
        ExternalManagerService.get(context).init();
    }
}
