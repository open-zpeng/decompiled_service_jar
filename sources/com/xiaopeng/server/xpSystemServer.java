package com.xiaopeng.server;

import android.content.Context;
import com.xiaopeng.server.ext.ExternalManagerService;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.server.wallpaper.xpWallpaperManagerService;
import com.xiaopeng.util.xpLogger;

/* loaded from: classes2.dex */
public class xpSystemServer {
    private static xpSystemServer sService = null;

    public static xpSystemServer get() {
        if (sService == null) {
            synchronized (xpSystemServer.class) {
                if (sService == null) {
                    sService = new xpSystemServer();
                }
            }
        }
        return sService;
    }

    public void init(Context context) {
    }

    public void systemReady(Context context) {
        xpLogger.i("xpSystemServer", "systemReady");
        xpWallpaperManagerService.get(context).init();
        xpInputManagerService.get(context).init();
        ExternalManagerService.get(context).init();
    }
}
