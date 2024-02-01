package com.android.server.wallpaper;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IWallpaperManager;
import android.app.IWallpaperManagerCallback;
import android.app.UserSwitchObserver;
import android.app.WallpaperColors;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.IWallpaperEngine;
import android.service.wallpaper.IWallpaperService;
import android.system.ErrnoException;
import android.system.Os;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.IWindowManager;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.JournaledFile;
import com.android.server.BatteryService;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.Settings;
import com.android.server.wallpaper.WallpaperManagerService;
import com.android.server.wm.WindowManagerInternal;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

/* loaded from: classes2.dex */
public class WallpaperManagerService extends IWallpaperManager.Stub implements IWallpaperManagerService {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_LIVE = true;
    private static final int MAX_BITMAP_SIZE = 104857600;
    private static final int MAX_WALLPAPER_COMPONENT_LOG_LENGTH = 128;
    private static final long MIN_WALLPAPER_CRASH_TIME = 10000;
    private static final String TAG = "WallpaperManagerService";
    private final AppOpsManager mAppOpsManager;
    private WallpaperColors mCacheDefaultImageWallpaperColors;
    private final SparseArray<SparseArray<RemoteCallbackList<IWallpaperManagerCallback>>> mColorsChangedListeners;
    private final Context mContext;
    private final ComponentName mDefaultWallpaperComponent;
    private final DisplayManager mDisplayManager;
    private WallpaperData mFallbackWallpaper;
    private final ComponentName mImageWallpaper;
    private boolean mInAmbientMode;
    private IWallpaperManagerCallback mKeyguardListener;
    private WallpaperData mLastWallpaper;
    private final MyPackageMonitor mMonitor;
    private boolean mWaitingForUnlock;
    private int mWallpaperId;
    static final String WALLPAPER = "wallpaper_orig";
    static final String WALLPAPER_CROP = "wallpaper";
    static final String WALLPAPER_LOCK_ORIG = "wallpaper_lock_orig";
    static final String WALLPAPER_LOCK_CROP = "wallpaper_lock";
    static final String WALLPAPER_INFO = "wallpaper_info.xml";
    private static final String[] sPerUserFiles = {WALLPAPER, WALLPAPER_CROP, WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP, WALLPAPER_INFO};
    private final Object mLock = new Object();
    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() { // from class: com.android.server.wallpaper.WallpaperManagerService.1
        @Override // android.hardware.display.DisplayManager.DisplayListener
        public void onDisplayAdded(int displayId) {
        }

        @Override // android.hardware.display.DisplayManager.DisplayListener
        public void onDisplayRemoved(int displayId) {
            synchronized (WallpaperManagerService.this.mLock) {
                if (WallpaperManagerService.this.mLastWallpaper != null) {
                    WallpaperData targetWallpaper = null;
                    if (WallpaperManagerService.this.mLastWallpaper.connection.containsDisplay(displayId)) {
                        targetWallpaper = WallpaperManagerService.this.mLastWallpaper;
                    } else if (WallpaperManagerService.this.mFallbackWallpaper.connection.containsDisplay(displayId)) {
                        targetWallpaper = WallpaperManagerService.this.mFallbackWallpaper;
                    }
                    if (targetWallpaper == null) {
                        return;
                    }
                    WallpaperConnection.DisplayConnector connector = targetWallpaper.connection.getDisplayConnectorOrCreate(displayId);
                    if (connector == null) {
                        return;
                    }
                    connector.disconnectLocked();
                    targetWallpaper.connection.removeDisplayConnector(displayId);
                    WallpaperManagerService.this.removeDisplayData(displayId);
                }
                for (int i = WallpaperManagerService.this.mColorsChangedListeners.size() - 1; i >= 0; i--) {
                    SparseArray<RemoteCallbackList<IWallpaperManagerCallback>> callbacks = (SparseArray) WallpaperManagerService.this.mColorsChangedListeners.valueAt(i);
                    callbacks.delete(displayId);
                }
            }
        }

        @Override // android.hardware.display.DisplayManager.DisplayListener
        public void onDisplayChanged(int displayId) {
        }
    };
    private final SparseArray<WallpaperData> mWallpaperMap = new SparseArray<>();
    private final SparseArray<WallpaperData> mLockWallpaperMap = new SparseArray<>();
    private SparseArray<DisplayData> mDisplayDatas = new SparseArray<>();
    private final SparseBooleanArray mUserRestorecon = new SparseBooleanArray();
    private int mCurrentUserId = -10000;
    private boolean mShuttingDown = false;
    private final IWindowManager mIWindowManager = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
    private final WindowManagerInternal mWindowManagerInternal = (WindowManagerInternal) LocalServices.getService(WindowManagerInternal.class);
    private final IPackageManager mIPackageManager = AppGlobals.getPackageManager();

    /* loaded from: classes2.dex */
    public static class Lifecycle extends SystemService {
        private IWallpaperManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            try {
                this.mService = (IWallpaperManagerService) Class.forName(getContext().getResources().getString(17039785)).getConstructor(Context.class).newInstance(getContext());
                publishBinderService(WallpaperManagerService.WALLPAPER_CROP, this.mService);
            } catch (Exception exp) {
                Slog.wtf(WallpaperManagerService.TAG, "Failed to instantiate WallpaperManagerService", exp);
            }
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            IWallpaperManagerService iWallpaperManagerService = this.mService;
            if (iWallpaperManagerService != null) {
                iWallpaperManagerService.onBootPhase(phase);
            }
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            IWallpaperManagerService iWallpaperManagerService = this.mService;
            if (iWallpaperManagerService != null) {
                iWallpaperManagerService.onUnlockUser(userHandle);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class WallpaperObserver extends FileObserver {
        final int mUserId;
        final WallpaperData mWallpaper;
        final File mWallpaperDir;
        final File mWallpaperFile;
        final File mWallpaperLockFile;

        public WallpaperObserver(WallpaperData wallpaper) {
            super(WallpaperManagerService.getWallpaperDir(wallpaper.userId).getAbsolutePath(), 1672);
            this.mUserId = wallpaper.userId;
            this.mWallpaperDir = WallpaperManagerService.getWallpaperDir(wallpaper.userId);
            this.mWallpaper = wallpaper;
            this.mWallpaperFile = new File(this.mWallpaperDir, WallpaperManagerService.WALLPAPER);
            this.mWallpaperLockFile = new File(this.mWallpaperDir, WallpaperManagerService.WALLPAPER_LOCK_ORIG);
        }

        private WallpaperData dataForEvent(boolean sysChanged, boolean lockChanged) {
            WallpaperData wallpaper = null;
            synchronized (WallpaperManagerService.this.mLock) {
                if (lockChanged) {
                    try {
                        wallpaper = (WallpaperData) WallpaperManagerService.this.mLockWallpaperMap.get(this.mUserId);
                    } catch (Throwable th) {
                        throw th;
                    }
                }
                if (wallpaper == null) {
                    wallpaper = (WallpaperData) WallpaperManagerService.this.mWallpaperMap.get(this.mUserId);
                }
            }
            return wallpaper != null ? wallpaper : this.mWallpaper;
        }

        /* JADX WARN: Removed duplicated region for block: B:83:0x00d4 A[EXC_TOP_SPLITTER, SYNTHETIC] */
        @Override // android.os.FileObserver
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void onEvent(int r21, java.lang.String r22) {
            /*
                Method dump skipped, instructions count: 236
                To view this dump change 'Code comments level' option to 'DEBUG'
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.wallpaper.WallpaperManagerService.WallpaperObserver.onEvent(int, java.lang.String):void");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyLockWallpaperChanged() {
        IWallpaperManagerCallback cb = this.mKeyguardListener;
        if (cb != null) {
            try {
                cb.onWallpaperChanged();
            } catch (RemoteException e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyWallpaperColorsChanged(final WallpaperData wallpaper, final int which) {
        if (wallpaper.connection != null) {
            wallpaper.connection.forEachDisplayConnector(new Consumer() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$la7x4YHA-l88Cd6HFTscnLBbKfI
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    WallpaperManagerService.this.lambda$notifyWallpaperColorsChanged$0$WallpaperManagerService(wallpaper, which, (WallpaperManagerService.WallpaperConnection.DisplayConnector) obj);
                }
            });
        } else {
            notifyWallpaperColorsChangedOnDisplay(wallpaper, which, 0);
        }
    }

    public /* synthetic */ void lambda$notifyWallpaperColorsChanged$0$WallpaperManagerService(WallpaperData wallpaper, int which, WallpaperConnection.DisplayConnector connector) {
        notifyWallpaperColorsChangedOnDisplay(wallpaper, which, connector.mDisplayId);
    }

    private RemoteCallbackList<IWallpaperManagerCallback> getWallpaperCallbacks(int userId, int displayId) {
        SparseArray<RemoteCallbackList<IWallpaperManagerCallback>> displayListeners = this.mColorsChangedListeners.get(userId);
        if (displayListeners == null) {
            return null;
        }
        RemoteCallbackList<IWallpaperManagerCallback> listeners = displayListeners.get(displayId);
        return listeners;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyWallpaperColorsChangedOnDisplay(WallpaperData wallpaper, int which, int displayId) {
        synchronized (this.mLock) {
            RemoteCallbackList<IWallpaperManagerCallback> currentUserColorListeners = getWallpaperCallbacks(wallpaper.userId, displayId);
            RemoteCallbackList<IWallpaperManagerCallback> userAllColorListeners = getWallpaperCallbacks(-1, displayId);
            if (emptyCallbackList(currentUserColorListeners) && emptyCallbackList(userAllColorListeners)) {
                return;
            }
            boolean needsExtraction = wallpaper.primaryColors == null;
            notifyColorListeners(wallpaper.primaryColors, which, wallpaper.userId, displayId);
            if (needsExtraction) {
                extractColors(wallpaper);
                synchronized (this.mLock) {
                    if (wallpaper.primaryColors == null) {
                        return;
                    }
                    notifyColorListeners(wallpaper.primaryColors, which, wallpaper.userId, displayId);
                }
            }
        }
    }

    private static <T extends IInterface> boolean emptyCallbackList(RemoteCallbackList<T> list) {
        return list == null || list.getRegisteredCallbackCount() == 0;
    }

    private void notifyColorListeners(WallpaperColors wallpaperColors, int which, int userId, int displayId) {
        IWallpaperManagerCallback keyguardListener;
        ArrayList<IWallpaperManagerCallback> colorListeners = new ArrayList<>();
        synchronized (this.mLock) {
            RemoteCallbackList<IWallpaperManagerCallback> currentUserColorListeners = getWallpaperCallbacks(userId, displayId);
            RemoteCallbackList<IWallpaperManagerCallback> userAllColorListeners = getWallpaperCallbacks(-1, displayId);
            keyguardListener = this.mKeyguardListener;
            if (currentUserColorListeners != null) {
                int count = currentUserColorListeners.beginBroadcast();
                for (int i = 0; i < count; i++) {
                    colorListeners.add(currentUserColorListeners.getBroadcastItem(i));
                }
                currentUserColorListeners.finishBroadcast();
            }
            if (userAllColorListeners != null) {
                int count2 = userAllColorListeners.beginBroadcast();
                for (int i2 = 0; i2 < count2; i2++) {
                    colorListeners.add(userAllColorListeners.getBroadcastItem(i2));
                }
                userAllColorListeners.finishBroadcast();
            }
        }
        int count3 = colorListeners.size();
        for (int i3 = 0; i3 < count3; i3++) {
            try {
                colorListeners.get(i3).onWallpaperColorsChanged(wallpaperColors, which, userId);
            } catch (RemoteException e) {
            }
        }
        if (keyguardListener != null && displayId == 0) {
            try {
                keyguardListener.onWallpaperColorsChanged(wallpaperColors, which, userId);
            } catch (RemoteException e2) {
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:54:0x0083  */
    /* JADX WARN: Removed duplicated region for block: B:56:0x008b  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private void extractColors(com.android.server.wallpaper.WallpaperManagerService.WallpaperData r8) {
        /*
            r7 = this;
            r0 = 0
            r1 = 0
            com.android.server.wallpaper.WallpaperManagerService$WallpaperData r2 = r7.mFallbackWallpaper
            boolean r2 = r8.equals(r2)
            if (r2 == 0) goto L29
            java.lang.Object r2 = r7.mLock
            monitor-enter(r2)
            com.android.server.wallpaper.WallpaperManagerService$WallpaperData r3 = r7.mFallbackWallpaper     // Catch: java.lang.Throwable -> L26
            android.app.WallpaperColors r3 = r3.primaryColors     // Catch: java.lang.Throwable -> L26
            if (r3 == 0) goto L15
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L26
            return
        L15:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L26
            android.app.WallpaperColors r3 = r7.extractDefaultImageWallpaperColors()
            java.lang.Object r4 = r7.mLock
            monitor-enter(r4)
            com.android.server.wallpaper.WallpaperManagerService$WallpaperData r2 = r7.mFallbackWallpaper     // Catch: java.lang.Throwable -> L23
            r2.primaryColors = r3     // Catch: java.lang.Throwable -> L23
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L23
            return
        L23:
            r2 = move-exception
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L23
            throw r2
        L26:
            r3 = move-exception
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L26
            throw r3
        L29:
            java.lang.Object r2 = r7.mLock
            monitor-enter(r2)
            android.content.ComponentName r3 = r7.mImageWallpaper     // Catch: java.lang.Throwable -> La6
            android.content.ComponentName r4 = r8.wallpaperComponent     // Catch: java.lang.Throwable -> La6
            boolean r3 = r3.equals(r4)     // Catch: java.lang.Throwable -> La6
            if (r3 != 0) goto L3d
            android.content.ComponentName r3 = r8.wallpaperComponent     // Catch: java.lang.Throwable -> La6
            if (r3 != 0) goto L3b
            goto L3d
        L3b:
            r3 = 0
            goto L3e
        L3d:
            r3 = 1
        L3e:
            if (r3 == 0) goto L54
            java.io.File r4 = r8.cropFile     // Catch: java.lang.Throwable -> La6
            if (r4 == 0) goto L54
            java.io.File r4 = r8.cropFile     // Catch: java.lang.Throwable -> La6
            boolean r4 = r4.exists()     // Catch: java.lang.Throwable -> La6
            if (r4 == 0) goto L54
            java.io.File r4 = r8.cropFile     // Catch: java.lang.Throwable -> La6
            java.lang.String r4 = r4.getAbsolutePath()     // Catch: java.lang.Throwable -> La6
            r0 = r4
            goto L63
        L54:
            if (r3 == 0) goto L63
            boolean r4 = r8.cropExists()     // Catch: java.lang.Throwable -> La6
            if (r4 != 0) goto L63
            boolean r4 = r8.sourceExists()     // Catch: java.lang.Throwable -> La6
            if (r4 != 0) goto L63
            r1 = 1
        L63:
            int r4 = r8.wallpaperId     // Catch: java.lang.Throwable -> La6
            r3 = r4
            monitor-exit(r2)     // Catch: java.lang.Throwable -> La6
            r2 = 0
            if (r0 == 0) goto L78
            android.graphics.Bitmap r4 = android.graphics.BitmapFactory.decodeFile(r0)
            if (r4 == 0) goto L80
            android.app.WallpaperColors r2 = android.app.WallpaperColors.fromBitmap(r4)
            r4.recycle()
            goto L80
        L78:
            if (r1 == 0) goto L80
            android.app.WallpaperColors r2 = r7.extractDefaultImageWallpaperColors()
            r4 = r2
            goto L81
        L80:
            r4 = r2
        L81:
            if (r4 != 0) goto L8b
            java.lang.String r2 = "WallpaperManagerService"
            java.lang.String r5 = "Cannot extract colors because wallpaper could not be read."
            android.util.Slog.w(r2, r5)
            return
        L8b:
            java.lang.Object r5 = r7.mLock
            monitor-enter(r5)
            int r2 = r8.wallpaperId     // Catch: java.lang.Throwable -> La3
            if (r2 != r3) goto L9a
            r8.primaryColors = r4     // Catch: java.lang.Throwable -> La3
            int r2 = r8.userId     // Catch: java.lang.Throwable -> La3
            r7.saveSettingsLocked(r2)     // Catch: java.lang.Throwable -> La3
            goto La1
        L9a:
            java.lang.String r2 = "WallpaperManagerService"
            java.lang.String r6 = "Not setting primary colors since wallpaper changed"
            android.util.Slog.w(r2, r6)     // Catch: java.lang.Throwable -> La3
        La1:
            monitor-exit(r5)     // Catch: java.lang.Throwable -> La3
            return
        La3:
            r2 = move-exception
            monitor-exit(r5)     // Catch: java.lang.Throwable -> La3
            throw r2
        La6:
            r3 = move-exception
            monitor-exit(r2)     // Catch: java.lang.Throwable -> La6
            throw r3
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wallpaper.WallpaperManagerService.extractColors(com.android.server.wallpaper.WallpaperManagerService$WallpaperData):void");
    }

    private WallpaperColors extractDefaultImageWallpaperColors() {
        WallpaperColors colors;
        InputStream is;
        synchronized (this.mLock) {
            if (this.mCacheDefaultImageWallpaperColors != null) {
                return this.mCacheDefaultImageWallpaperColors;
            }
            WallpaperColors colors2 = null;
            try {
                is = WallpaperManager.openDefaultWallpaper(this.mContext, 1);
            } catch (IOException e) {
                Slog.w(TAG, "Can't close default wallpaper stream", e);
                colors = null;
            } catch (OutOfMemoryError e2) {
                Slog.w(TAG, "Can't decode default wallpaper stream", e2);
            }
            try {
                if (is == null) {
                    Slog.w(TAG, "Can't open default wallpaper stream");
                    if (is != null) {
                        is.close();
                    }
                    return null;
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
                if (bitmap != null) {
                    colors2 = WallpaperColors.fromBitmap(bitmap);
                    bitmap.recycle();
                }
                is.close();
                colors = colors2;
                if (colors == null) {
                    Slog.e(TAG, "Extract default image wallpaper colors failed");
                } else {
                    synchronized (this.mLock) {
                        this.mCacheDefaultImageWallpaperColors = colors;
                    }
                }
                return colors;
            } finally {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:101:0x0244  */
    /* JADX WARN: Removed duplicated region for block: B:104:0x0256  */
    /* JADX WARN: Removed duplicated region for block: B:114:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void generateCrop(com.android.server.wallpaper.WallpaperManagerService.WallpaperData r25) {
        /*
            Method dump skipped, instructions count: 608
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wallpaper.WallpaperManagerService.generateCrop(com.android.server.wallpaper.WallpaperManagerService$WallpaperData):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public static class WallpaperData {
        boolean allowBackup;
        WallpaperConnection connection;
        final File cropFile;
        boolean imageWallpaperPending;
        long lastDiedTime;
        ComponentName nextWallpaperComponent;
        WallpaperColors primaryColors;
        IWallpaperManagerCallback setComplete;
        int userId;
        ComponentName wallpaperComponent;
        final File wallpaperFile;
        int wallpaperId;
        WallpaperObserver wallpaperObserver;
        boolean wallpaperUpdating;
        int whichPending;
        String name = "";
        private RemoteCallbackList<IWallpaperManagerCallback> callbacks = new RemoteCallbackList<>();
        final Rect cropHint = new Rect(0, 0, 0, 0);

        WallpaperData(int userId, String inputFileName, String cropFileName) {
            this.userId = userId;
            File wallpaperDir = WallpaperManagerService.getWallpaperDir(userId);
            this.wallpaperFile = new File(wallpaperDir, inputFileName);
            this.cropFile = new File(wallpaperDir, cropFileName);
        }

        boolean cropExists() {
            return this.cropFile.exists();
        }

        boolean sourceExists() {
            return this.wallpaperFile.exists();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static final class DisplayData {
        final int mDisplayId;
        int mWidth = -1;
        int mHeight = -1;
        final Rect mPadding = new Rect(0, 0, 0, 0);

        DisplayData(int displayId) {
            this.mDisplayId = displayId;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeDisplayData(int displayId) {
        this.mDisplayDatas.remove(displayId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public DisplayData getDisplayDataOrCreate(int displayId) {
        DisplayData wpdData = this.mDisplayDatas.get(displayId);
        if (wpdData == null) {
            DisplayData wpdData2 = new DisplayData(displayId);
            ensureSaneWallpaperDisplaySize(wpdData2, displayId);
            this.mDisplayDatas.append(displayId, wpdData2);
            return wpdData2;
        }
        return wpdData;
    }

    private void ensureSaneWallpaperDisplaySize(DisplayData wpdData, int displayId) {
        int baseSize = getMaximumSizeDimension(displayId);
        if (wpdData.mWidth < baseSize) {
            wpdData.mWidth = baseSize;
        }
        if (wpdData.mHeight < baseSize) {
            wpdData.mHeight = baseSize;
        }
    }

    private int getMaximumSizeDimension(int displayId) {
        Display display = this.mDisplayManager.getDisplay(displayId);
        if (display == null) {
            Slog.w(TAG, "Invalid displayId=" + displayId + " " + Debug.getCallers(4));
            display = this.mDisplayManager.getDisplay(0);
        }
        return display.getMaximumSizeDimension();
    }

    void forEachDisplayData(Consumer<DisplayData> action) {
        for (int i = this.mDisplayDatas.size() - 1; i >= 0; i--) {
            DisplayData wpdData = this.mDisplayDatas.valueAt(i);
            action.accept(wpdData);
        }
    }

    int makeWallpaperIdLocked() {
        int i;
        do {
            this.mWallpaperId++;
            i = this.mWallpaperId;
        } while (i == 0);
        return i;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean supportsMultiDisplay(WallpaperConnection connection) {
        if (connection != null) {
            return connection.mInfo == null || connection.mInfo.supportsMultipleDisplays();
        }
        return false;
    }

    private void updateFallbackConnection() {
        WallpaperData wallpaperData = this.mLastWallpaper;
        if (wallpaperData == null || this.mFallbackWallpaper == null) {
            return;
        }
        WallpaperConnection systemConnection = wallpaperData.connection;
        final WallpaperConnection fallbackConnection = this.mFallbackWallpaper.connection;
        if (fallbackConnection == null) {
            Slog.w(TAG, "Fallback wallpaper connection has not been created yet!!");
        } else if (supportsMultiDisplay(systemConnection)) {
            if (fallbackConnection.mDisplayConnector.size() != 0) {
                fallbackConnection.forEachDisplayConnector(new Consumer() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$pVmree9DyIpBSg0s3RDK3MDesvs
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        WallpaperManagerService.lambda$updateFallbackConnection$1((WallpaperManagerService.WallpaperConnection.DisplayConnector) obj);
                    }
                });
                fallbackConnection.mDisplayConnector.clear();
            }
        } else {
            fallbackConnection.appendConnectorWithCondition(new Predicate() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$SxaUJpgTTfzUoz6u3AWuAOQdoNw
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return WallpaperManagerService.lambda$updateFallbackConnection$2(WallpaperManagerService.WallpaperConnection.this, (Display) obj);
                }
            });
            fallbackConnection.forEachDisplayConnector(new Consumer() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$tRb4SPHGj0pcxb3p7arcqKFqs08
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    WallpaperManagerService.this.lambda$updateFallbackConnection$3$WallpaperManagerService(fallbackConnection, (WallpaperManagerService.WallpaperConnection.DisplayConnector) obj);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$updateFallbackConnection$1(WallpaperConnection.DisplayConnector connector) {
        if (connector.mEngine != null) {
            connector.disconnectLocked();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$updateFallbackConnection$2(WallpaperConnection fallbackConnection, Display display) {
        return (!fallbackConnection.isUsableDisplay(display) || display.getDisplayId() == 0 || fallbackConnection.containsDisplay(display.getDisplayId())) ? false : true;
    }

    public /* synthetic */ void lambda$updateFallbackConnection$3$WallpaperManagerService(WallpaperConnection fallbackConnection, WallpaperConnection.DisplayConnector connector) {
        if (connector.mEngine == null) {
            connector.connectLocked(fallbackConnection, this.mFallbackWallpaper);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public class WallpaperConnection extends IWallpaperConnection.Stub implements ServiceConnection {
        private static final long WALLPAPER_RECONNECT_TIMEOUT_MS = 10000;
        final int mClientUid;
        final WallpaperInfo mInfo;
        IRemoteCallback mReply;
        IWallpaperService mService;
        WallpaperData mWallpaper;
        private SparseArray<DisplayConnector> mDisplayConnector = new SparseArray<>();
        private Runnable mResetRunnable = new Runnable() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$WallpaperConnection$QhODF3v-swnwSYvDbeEhU85gOBw
            @Override // java.lang.Runnable
            public final void run() {
                WallpaperManagerService.WallpaperConnection.this.lambda$new$0$WallpaperManagerService$WallpaperConnection();
            }
        };
        private Runnable mTryToRebindRunnable = new Runnable() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$WallpaperConnection$d7gUC6mQx1Xv_Bvlwss1NEF5PwU
            @Override // java.lang.Runnable
            public final void run() {
                WallpaperManagerService.WallpaperConnection.this.lambda$new$1$WallpaperManagerService$WallpaperConnection();
            }
        };

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes2.dex */
        public final class DisplayConnector {
            boolean mDimensionsChanged;
            final int mDisplayId;
            IWallpaperEngine mEngine;
            boolean mPaddingChanged;
            final Binder mToken = new Binder();

            DisplayConnector(int displayId) {
                this.mDisplayId = displayId;
            }

            void ensureStatusHandled() {
                DisplayData wpdData = WallpaperManagerService.this.getDisplayDataOrCreate(this.mDisplayId);
                if (this.mDimensionsChanged) {
                    try {
                        this.mEngine.setDesiredSize(wpdData.mWidth, wpdData.mHeight);
                    } catch (RemoteException e) {
                        Slog.w(WallpaperManagerService.TAG, "Failed to set wallpaper dimensions", e);
                    }
                    this.mDimensionsChanged = false;
                }
                if (this.mPaddingChanged) {
                    try {
                        this.mEngine.setDisplayPadding(wpdData.mPadding);
                    } catch (RemoteException e2) {
                        Slog.w(WallpaperManagerService.TAG, "Failed to set wallpaper padding", e2);
                    }
                    this.mPaddingChanged = false;
                }
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            public void connectLocked(WallpaperConnection connection, WallpaperData wallpaper) {
                if (connection.mService != null) {
                    try {
                        WallpaperManagerService.this.mIWindowManager.addWindowToken(this.mToken, 2013, this.mDisplayId);
                        DisplayData wpdData = WallpaperManagerService.this.getDisplayDataOrCreate(this.mDisplayId);
                        try {
                            connection.mService.attach(connection, this.mToken, 2013, false, wpdData.mWidth, wpdData.mHeight, wpdData.mPadding, this.mDisplayId);
                            return;
                        } catch (RemoteException e) {
                            Slog.w(WallpaperManagerService.TAG, "Failed attaching wallpaper on display", e);
                            if (wallpaper != null && !wallpaper.wallpaperUpdating && connection.getConnectedEngineSize() == 0) {
                                WallpaperManagerService.this.bindWallpaperComponentLocked(null, false, false, wallpaper, null);
                                return;
                            }
                            return;
                        }
                    } catch (RemoteException e2) {
                        Slog.e(WallpaperManagerService.TAG, "Failed add wallpaper window token on display " + this.mDisplayId, e2);
                        return;
                    }
                }
                Slog.w(WallpaperManagerService.TAG, "WallpaperService is not connected yet");
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            public void disconnectLocked() {
                try {
                    WallpaperManagerService.this.mIWindowManager.removeWindowToken(this.mToken, this.mDisplayId);
                } catch (RemoteException e) {
                }
                try {
                    if (this.mEngine != null) {
                        this.mEngine.destroy();
                    }
                } catch (RemoteException e2) {
                }
                this.mEngine = null;
            }
        }

        public /* synthetic */ void lambda$new$0$WallpaperManagerService$WallpaperConnection() {
            synchronized (WallpaperManagerService.this.mLock) {
                if (WallpaperManagerService.this.mShuttingDown) {
                    Slog.i(WallpaperManagerService.TAG, "Ignoring relaunch timeout during shutdown");
                    return;
                }
                if (!this.mWallpaper.wallpaperUpdating && this.mWallpaper.userId == WallpaperManagerService.this.mCurrentUserId) {
                    Slog.w(WallpaperManagerService.TAG, "Wallpaper reconnect timed out for " + this.mWallpaper.wallpaperComponent + ", reverting to built-in wallpaper!");
                    WallpaperManagerService.this.clearWallpaperLocked(true, 1, this.mWallpaper.userId, null);
                }
            }
        }

        WallpaperConnection(WallpaperInfo info, WallpaperData wallpaper, int clientUid) {
            this.mInfo = info;
            this.mWallpaper = wallpaper;
            this.mClientUid = clientUid;
            initDisplayState();
        }

        private void initDisplayState() {
            if (!this.mWallpaper.equals(WallpaperManagerService.this.mFallbackWallpaper)) {
                if (WallpaperManagerService.this.supportsMultiDisplay(this)) {
                    appendConnectorWithCondition(new Predicate() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$WallpaperConnection$NrNkceFJLqjCb8eAxErUhpLd5c8
                        @Override // java.util.function.Predicate
                        public final boolean test(Object obj) {
                            boolean isUsableDisplay;
                            isUsableDisplay = WallpaperManagerService.WallpaperConnection.this.isUsableDisplay((Display) obj);
                            return isUsableDisplay;
                        }
                    });
                } else {
                    this.mDisplayConnector.append(0, new DisplayConnector(0));
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void appendConnectorWithCondition(Predicate<Display> tester) {
            Display[] displays = WallpaperManagerService.this.mDisplayManager.getDisplays();
            for (Display display : displays) {
                if (tester.test(display)) {
                    int displayId = display.getDisplayId();
                    DisplayConnector connector = this.mDisplayConnector.get(displayId);
                    if (connector == null) {
                        this.mDisplayConnector.append(displayId, new DisplayConnector(displayId));
                    }
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isUsableDisplay(Display display) {
            if (display == null || !display.hasAccess(this.mClientUid)) {
                return false;
            }
            int displayId = display.getDisplayId();
            if (displayId == 0) {
                return true;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                return WallpaperManagerService.this.mWindowManagerInternal.shouldShowSystemDecorOnDisplay(displayId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        void forEachDisplayConnector(Consumer<DisplayConnector> action) {
            for (int i = this.mDisplayConnector.size() - 1; i >= 0; i--) {
                DisplayConnector connector = this.mDisplayConnector.valueAt(i);
                action.accept(connector);
            }
        }

        int getConnectedEngineSize() {
            int engineSize = 0;
            for (int i = this.mDisplayConnector.size() - 1; i >= 0; i--) {
                DisplayConnector connector = this.mDisplayConnector.valueAt(i);
                if (connector.mEngine != null) {
                    engineSize++;
                }
            }
            return engineSize;
        }

        DisplayConnector getDisplayConnectorOrCreate(int displayId) {
            DisplayConnector connector = this.mDisplayConnector.get(displayId);
            if (connector == null) {
                Display display = WallpaperManagerService.this.mDisplayManager.getDisplay(displayId);
                if (isUsableDisplay(display)) {
                    DisplayConnector connector2 = new DisplayConnector(displayId);
                    this.mDisplayConnector.append(displayId, connector2);
                    return connector2;
                }
                return connector;
            }
            return connector;
        }

        boolean containsDisplay(int displayId) {
            return this.mDisplayConnector.get(displayId) != null;
        }

        void removeDisplayConnector(int displayId) {
            DisplayConnector connector = this.mDisplayConnector.get(displayId);
            if (connector != null) {
                this.mDisplayConnector.remove(displayId);
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (WallpaperManagerService.this.mLock) {
                if (this.mWallpaper.connection == this) {
                    this.mService = IWallpaperService.Stub.asInterface(service);
                    WallpaperManagerService.this.attachServiceLocked(this, this.mWallpaper);
                    if (!this.mWallpaper.equals(WallpaperManagerService.this.mFallbackWallpaper)) {
                        WallpaperManagerService.this.saveSettingsLocked(this.mWallpaper.userId);
                    }
                    FgThread.getHandler().removeCallbacks(this.mResetRunnable);
                    WallpaperManagerService.this.mContext.getMainThreadHandler().removeCallbacks(this.mTryToRebindRunnable);
                }
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            synchronized (WallpaperManagerService.this.mLock) {
                Slog.w(WallpaperManagerService.TAG, "Wallpaper service gone: " + name);
                if (!Objects.equals(name, this.mWallpaper.wallpaperComponent)) {
                    Slog.e(WallpaperManagerService.TAG, "Does not match expected wallpaper component " + this.mWallpaper.wallpaperComponent);
                }
                this.mService = null;
                forEachDisplayConnector(new Consumer() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$WallpaperConnection$pf_7EcVpbLQlQnQ4nGnqzkGUhqg
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ((WallpaperManagerService.WallpaperConnection.DisplayConnector) obj).mEngine = null;
                    }
                });
                if (this.mWallpaper.connection == this && !this.mWallpaper.wallpaperUpdating) {
                    WallpaperManagerService.this.mContext.getMainThreadHandler().postDelayed(new Runnable() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$WallpaperConnection$zdJsFydiwYuUG4WFwlznTvMvYfw
                        @Override // java.lang.Runnable
                        public final void run() {
                            WallpaperManagerService.WallpaperConnection.this.lambda$onServiceDisconnected$3$WallpaperManagerService$WallpaperConnection();
                        }
                    }, 1000L);
                }
            }
        }

        public /* synthetic */ void lambda$onServiceDisconnected$3$WallpaperManagerService$WallpaperConnection() {
            processDisconnect(this);
        }

        public void scheduleTimeoutLocked() {
            Handler fgHandler = FgThread.getHandler();
            fgHandler.removeCallbacks(this.mResetRunnable);
            fgHandler.postDelayed(this.mResetRunnable, 10000L);
            Slog.i(WallpaperManagerService.TAG, "Started wallpaper reconnect timeout for " + this.mWallpaper.wallpaperComponent);
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* renamed from: tryToRebind */
        public void lambda$new$1$WallpaperManagerService$WallpaperConnection() {
            synchronized (WallpaperManagerService.this.mLock) {
                if (this.mWallpaper.wallpaperUpdating) {
                    return;
                }
                ComponentName wpService = this.mWallpaper.wallpaperComponent;
                if (WallpaperManagerService.this.bindWallpaperComponentLocked(wpService, true, false, this.mWallpaper, null)) {
                    this.mWallpaper.connection.scheduleTimeoutLocked();
                } else if (SystemClock.uptimeMillis() - this.mWallpaper.lastDiedTime < 10000) {
                    Slog.w(WallpaperManagerService.TAG, "Rebind fail! Try again later");
                    WallpaperManagerService.this.mContext.getMainThreadHandler().postDelayed(this.mTryToRebindRunnable, 1000L);
                } else {
                    Slog.w(WallpaperManagerService.TAG, "Reverting to built-in wallpaper!");
                    WallpaperManagerService.this.clearWallpaperLocked(true, 1, this.mWallpaper.userId, null);
                    String flattened = wpService.flattenToString();
                    EventLog.writeEvent((int) EventLogTags.WP_WALLPAPER_CRASHED, flattened.substring(0, Math.min(flattened.length(), 128)));
                }
            }
        }

        private void processDisconnect(ServiceConnection connection) {
            synchronized (WallpaperManagerService.this.mLock) {
                if (connection == this.mWallpaper.connection) {
                    ComponentName wpService = this.mWallpaper.wallpaperComponent;
                    if (!this.mWallpaper.wallpaperUpdating && this.mWallpaper.userId == WallpaperManagerService.this.mCurrentUserId && !Objects.equals(WallpaperManagerService.this.mDefaultWallpaperComponent, wpService) && !Objects.equals(WallpaperManagerService.this.mImageWallpaper, wpService)) {
                        if (this.mWallpaper.lastDiedTime != 0 && this.mWallpaper.lastDiedTime + 10000 > SystemClock.uptimeMillis()) {
                            Slog.w(WallpaperManagerService.TAG, "Reverting to built-in wallpaper!");
                            WallpaperManagerService.this.clearWallpaperLocked(true, 1, this.mWallpaper.userId, null);
                        } else {
                            this.mWallpaper.lastDiedTime = SystemClock.uptimeMillis();
                            lambda$new$1$WallpaperManagerService$WallpaperConnection();
                        }
                    }
                } else {
                    Slog.i(WallpaperManagerService.TAG, "Wallpaper changed during disconnect tracking; ignoring");
                }
            }
        }

        public void onWallpaperColorsChanged(WallpaperColors primaryColors, int displayId) {
            synchronized (WallpaperManagerService.this.mLock) {
                if (WallpaperManagerService.this.mImageWallpaper.equals(this.mWallpaper.wallpaperComponent)) {
                    return;
                }
                this.mWallpaper.primaryColors = primaryColors;
                int which = 1;
                if (displayId == 0) {
                    WallpaperData lockedWallpaper = (WallpaperData) WallpaperManagerService.this.mLockWallpaperMap.get(this.mWallpaper.userId);
                    if (lockedWallpaper == null) {
                        which = 1 | 2;
                    }
                }
                if (which != 0) {
                    WallpaperManagerService.this.notifyWallpaperColorsChangedOnDisplay(this.mWallpaper, which, displayId);
                }
            }
        }

        public void attachEngine(IWallpaperEngine engine, int displayId) {
            synchronized (WallpaperManagerService.this.mLock) {
                DisplayConnector connector = getDisplayConnectorOrCreate(displayId);
                if (connector == null) {
                    try {
                        engine.destroy();
                    } catch (RemoteException e) {
                        Slog.w(WallpaperManagerService.TAG, "Failed to destroy engine", e);
                    }
                    return;
                }
                connector.mEngine = engine;
                connector.ensureStatusHandled();
                if (this.mInfo != null && this.mInfo.supportsAmbientMode() && displayId == 0) {
                    try {
                        connector.mEngine.setInAmbientMode(WallpaperManagerService.this.mInAmbientMode, 0L);
                    } catch (RemoteException e2) {
                        Slog.w(WallpaperManagerService.TAG, "Failed to set ambient mode state", e2);
                    }
                }
                try {
                    connector.mEngine.requestWallpaperColors();
                } catch (RemoteException e3) {
                    Slog.w(WallpaperManagerService.TAG, "Failed to request wallpaper colors", e3);
                }
                return;
            }
        }

        public void engineShown(IWallpaperEngine engine) {
            synchronized (WallpaperManagerService.this.mLock) {
                if (this.mReply != null) {
                    long ident = Binder.clearCallingIdentity();
                    try {
                        this.mReply.sendResult((Bundle) null);
                    } catch (RemoteException e) {
                        Binder.restoreCallingIdentity(ident);
                    }
                    this.mReply = null;
                }
            }
        }

        public ParcelFileDescriptor setWallpaper(String name) {
            synchronized (WallpaperManagerService.this.mLock) {
                if (this.mWallpaper.connection == this) {
                    return WallpaperManagerService.this.updateWallpaperBitmapLocked(name, this.mWallpaper, null);
                }
                return null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes2.dex */
    public class MyPackageMonitor extends PackageMonitor {
        MyPackageMonitor() {
        }

        public void onPackageUpdateFinished(String packageName, int uid) {
            ComponentName wpService;
            synchronized (WallpaperManagerService.this.mLock) {
                if (WallpaperManagerService.this.mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = (WallpaperData) WallpaperManagerService.this.mWallpaperMap.get(WallpaperManagerService.this.mCurrentUserId);
                if (wallpaper != null && (wpService = wallpaper.wallpaperComponent) != null && wpService.getPackageName().equals(packageName)) {
                    Slog.i(WallpaperManagerService.TAG, "Wallpaper " + wpService + " update has finished");
                    wallpaper.wallpaperUpdating = false;
                    WallpaperManagerService.this.clearWallpaperComponentLocked(wallpaper);
                    if (!WallpaperManagerService.this.bindWallpaperComponentLocked(wpService, false, false, wallpaper, null)) {
                        Slog.w(WallpaperManagerService.TAG, "Wallpaper " + wpService + " no longer available; reverting to default");
                        WallpaperManagerService.this.clearWallpaperLocked(false, 1, wallpaper.userId, null);
                    }
                }
            }
        }

        public void onPackageModified(String packageName) {
            synchronized (WallpaperManagerService.this.mLock) {
                if (WallpaperManagerService.this.mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = (WallpaperData) WallpaperManagerService.this.mWallpaperMap.get(WallpaperManagerService.this.mCurrentUserId);
                if (wallpaper != null) {
                    if (wallpaper.wallpaperComponent != null && wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        doPackagesChangedLocked(true, wallpaper);
                    }
                }
            }
        }

        public void onPackageUpdateStarted(String packageName, int uid) {
            synchronized (WallpaperManagerService.this.mLock) {
                if (WallpaperManagerService.this.mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = (WallpaperData) WallpaperManagerService.this.mWallpaperMap.get(WallpaperManagerService.this.mCurrentUserId);
                if (wallpaper != null && wallpaper.wallpaperComponent != null && wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                    Slog.i(WallpaperManagerService.TAG, "Wallpaper service " + wallpaper.wallpaperComponent + " is updating");
                    wallpaper.wallpaperUpdating = true;
                    if (wallpaper.connection != null) {
                        FgThread.getHandler().removeCallbacks(wallpaper.connection.mResetRunnable);
                    }
                }
            }
        }

        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            synchronized (WallpaperManagerService.this.mLock) {
                boolean changed = false;
                if (WallpaperManagerService.this.mCurrentUserId == getChangingUserId()) {
                    WallpaperData wallpaper = (WallpaperData) WallpaperManagerService.this.mWallpaperMap.get(WallpaperManagerService.this.mCurrentUserId);
                    if (wallpaper != null) {
                        boolean res = doPackagesChangedLocked(doit, wallpaper);
                        changed = false | res;
                    }
                    return changed;
                }
                return false;
            }
        }

        public void onSomePackagesChanged() {
            synchronized (WallpaperManagerService.this.mLock) {
                if (WallpaperManagerService.this.mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = (WallpaperData) WallpaperManagerService.this.mWallpaperMap.get(WallpaperManagerService.this.mCurrentUserId);
                if (wallpaper != null) {
                    doPackagesChangedLocked(true, wallpaper);
                }
            }
        }

        boolean doPackagesChangedLocked(boolean doit, WallpaperData wallpaper) {
            int change;
            int change2;
            boolean changed = false;
            if (wallpaper.wallpaperComponent != null && ((change2 = isPackageDisappearing(wallpaper.wallpaperComponent.getPackageName())) == 3 || change2 == 2)) {
                changed = true;
                if (doit) {
                    Slog.w(WallpaperManagerService.TAG, "Wallpaper uninstalled, removing: " + wallpaper.wallpaperComponent);
                    WallpaperManagerService.this.clearWallpaperLocked(false, 1, wallpaper.userId, null);
                }
            }
            if (wallpaper.nextWallpaperComponent != null && ((change = isPackageDisappearing(wallpaper.nextWallpaperComponent.getPackageName())) == 3 || change == 2)) {
                wallpaper.nextWallpaperComponent = null;
            }
            if (wallpaper.wallpaperComponent != null && isPackageModified(wallpaper.wallpaperComponent.getPackageName())) {
                try {
                    WallpaperManagerService.this.mContext.getPackageManager().getServiceInfo(wallpaper.wallpaperComponent, 786432);
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.w(WallpaperManagerService.TAG, "Wallpaper component gone, removing: " + wallpaper.wallpaperComponent);
                    WallpaperManagerService.this.clearWallpaperLocked(false, 1, wallpaper.userId, null);
                }
            }
            if (wallpaper.nextWallpaperComponent != null && isPackageModified(wallpaper.nextWallpaperComponent.getPackageName())) {
                try {
                    WallpaperManagerService.this.mContext.getPackageManager().getServiceInfo(wallpaper.nextWallpaperComponent, 786432);
                } catch (PackageManager.NameNotFoundException e2) {
                    wallpaper.nextWallpaperComponent = null;
                }
            }
            return changed;
        }
    }

    public WallpaperManagerService(Context context) {
        this.mContext = context;
        this.mImageWallpaper = ComponentName.unflattenFromString(context.getResources().getString(17040131));
        this.mDefaultWallpaperComponent = WallpaperManager.getDefaultWallpaperComponent(context);
        this.mAppOpsManager = (AppOpsManager) this.mContext.getSystemService("appops");
        this.mDisplayManager = (DisplayManager) this.mContext.getSystemService(DisplayManager.class);
        this.mDisplayManager.registerDisplayListener(this.mDisplayListener, null);
        this.mMonitor = new MyPackageMonitor();
        this.mColorsChangedListeners = new SparseArray<>();
        LocalServices.addService(WallpaperManagerInternal.class, new LocalService());
    }

    /* loaded from: classes2.dex */
    private final class LocalService extends WallpaperManagerInternal {
        private LocalService() {
        }

        @Override // com.android.server.wallpaper.WallpaperManagerInternal
        public void onDisplayReady(int displayId) {
            WallpaperManagerService.this.onDisplayReadyInternal(displayId);
        }
    }

    void initialize() {
        this.mMonitor.register(this.mContext, null, UserHandle.ALL, true);
        getWallpaperDir(0).mkdirs();
        loadSettingsLocked(0, false);
        getWallpaperSafeLocked(0, 1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static File getWallpaperDir(int userId) {
        return Environment.getUserSystemDirectory(userId);
    }

    protected void finalize() throws Throwable {
        super.finalize();
        for (int i = 0; i < this.mWallpaperMap.size(); i++) {
            WallpaperData wallpaper = this.mWallpaperMap.valueAt(i);
            wallpaper.wallpaperObserver.stopWatching();
        }
    }

    void systemReady() {
        initialize();
        WallpaperData wallpaper = this.mWallpaperMap.get(0);
        if (this.mImageWallpaper.equals(wallpaper.nextWallpaperComponent)) {
            if (!wallpaper.cropExists()) {
                generateCrop(wallpaper);
            }
            if (!wallpaper.cropExists()) {
                clearWallpaperLocked(false, 1, 0, null);
            }
        }
        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction("android.intent.action.USER_REMOVED");
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.wallpaper.WallpaperManagerService.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("android.intent.action.USER_REMOVED".equals(action)) {
                    WallpaperManagerService.this.onRemoveUser(intent.getIntExtra("android.intent.extra.user_handle", -10000));
                }
            }
        }, userFilter);
        IntentFilter shutdownFilter = new IntentFilter("android.intent.action.ACTION_SHUTDOWN");
        this.mContext.registerReceiver(new BroadcastReceiver() { // from class: com.android.server.wallpaper.WallpaperManagerService.3
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                if ("android.intent.action.ACTION_SHUTDOWN".equals(intent.getAction())) {
                    synchronized (WallpaperManagerService.this.mLock) {
                        WallpaperManagerService.this.mShuttingDown = true;
                    }
                }
            }
        }, shutdownFilter);
        try {
            ActivityManager.getService().registerUserSwitchObserver(new UserSwitchObserver() { // from class: com.android.server.wallpaper.WallpaperManagerService.4
                public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                    WallpaperManagerService.this.switchUser(newUserId, reply);
                }
            }, TAG);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    public String getName() {
        String str;
        if (Binder.getCallingUid() != 1000) {
            throw new RuntimeException("getName() can only be called from the system process");
        }
        synchronized (this.mLock) {
            str = this.mWallpaperMap.get(0).name;
        }
        return str;
    }

    void stopObserver(WallpaperData wallpaper) {
        if (wallpaper != null && wallpaper.wallpaperObserver != null) {
            wallpaper.wallpaperObserver.stopWatching();
            wallpaper.wallpaperObserver = null;
        }
    }

    void stopObserversLocked(int userId) {
        stopObserver(this.mWallpaperMap.get(userId));
        stopObserver(this.mLockWallpaperMap.get(userId));
        this.mWallpaperMap.remove(userId);
        this.mLockWallpaperMap.remove(userId);
    }

    @Override // com.android.server.wallpaper.IWallpaperManagerService
    public void onBootPhase(int phase) {
        if (phase == 550) {
            systemReady();
        } else if (phase == 600) {
            switchUser(0, null);
        }
    }

    @Override // com.android.server.wallpaper.IWallpaperManagerService
    public void onUnlockUser(final int userId) {
        synchronized (this.mLock) {
            if (this.mCurrentUserId == userId) {
                if (this.mWaitingForUnlock) {
                    WallpaperData systemWallpaper = getWallpaperSafeLocked(userId, 1);
                    switchWallpaper(systemWallpaper, null);
                    notifyCallbacksLocked(systemWallpaper);
                }
                if (!this.mUserRestorecon.get(userId)) {
                    this.mUserRestorecon.put(userId, true);
                    Runnable relabeler = new Runnable() { // from class: com.android.server.wallpaper.WallpaperManagerService.5
                        @Override // java.lang.Runnable
                        public void run() {
                            String[] strArr;
                            File wallpaperDir = WallpaperManagerService.getWallpaperDir(userId);
                            for (String filename : WallpaperManagerService.sPerUserFiles) {
                                File f = new File(wallpaperDir, filename);
                                if (f.exists()) {
                                    SELinux.restorecon(f);
                                }
                            }
                        }
                    };
                    BackgroundThread.getHandler().post(relabeler);
                }
            }
        }
    }

    void onRemoveUser(int userId) {
        String[] strArr;
        if (userId < 1) {
            return;
        }
        File wallpaperDir = getWallpaperDir(userId);
        synchronized (this.mLock) {
            stopObserversLocked(userId);
            for (String filename : sPerUserFiles) {
                new File(wallpaperDir, filename).delete();
            }
            this.mUserRestorecon.delete(userId);
        }
    }

    void switchUser(int userId, IRemoteCallback reply) {
        synchronized (this.mLock) {
            if (this.mCurrentUserId == userId) {
                return;
            }
            this.mCurrentUserId = userId;
            final WallpaperData systemWallpaper = getWallpaperSafeLocked(userId, 1);
            WallpaperData tmpLockWallpaper = this.mLockWallpaperMap.get(userId);
            final WallpaperData lockWallpaper = tmpLockWallpaper == null ? systemWallpaper : tmpLockWallpaper;
            if (systemWallpaper.wallpaperObserver == null) {
                systemWallpaper.wallpaperObserver = new WallpaperObserver(systemWallpaper);
                systemWallpaper.wallpaperObserver.startWatching();
            }
            switchWallpaper(systemWallpaper, reply);
            FgThread.getHandler().post(new Runnable() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$xeJGAwCI8tssclwKFf8jMsYdoKQ
                @Override // java.lang.Runnable
                public final void run() {
                    WallpaperManagerService.this.lambda$switchUser$4$WallpaperManagerService(systemWallpaper, lockWallpaper);
                }
            });
        }
    }

    public /* synthetic */ void lambda$switchUser$4$WallpaperManagerService(WallpaperData systemWallpaper, WallpaperData lockWallpaper) {
        notifyWallpaperColorsChanged(systemWallpaper, 1);
        notifyWallpaperColorsChanged(lockWallpaper, 2);
        notifyWallpaperColorsChanged(this.mFallbackWallpaper, 1);
    }

    void switchWallpaper(WallpaperData wallpaper, IRemoteCallback reply) {
        ServiceInfo si;
        synchronized (this.mLock) {
            try {
            } catch (Throwable th) {
                th = th;
            }
            try {
                this.mWaitingForUnlock = false;
                ComponentName cname = wallpaper.wallpaperComponent != null ? wallpaper.wallpaperComponent : wallpaper.nextWallpaperComponent;
                if (!bindWallpaperComponentLocked(cname, true, false, wallpaper, reply)) {
                    try {
                        si = this.mIPackageManager.getServiceInfo(cname, 262144, wallpaper.userId);
                    } catch (RemoteException e) {
                        si = null;
                    }
                    if (si == null) {
                        Slog.w(TAG, "Failure starting previous wallpaper; clearing");
                        clearWallpaperLocked(false, 1, wallpaper.userId, reply);
                    } else {
                        Slog.w(TAG, "Wallpaper isn't direct boot aware; using fallback until unlocked");
                        wallpaper.wallpaperComponent = wallpaper.nextWallpaperComponent;
                        WallpaperData fallback = new WallpaperData(wallpaper.userId, WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
                        ensureSaneWallpaperData(fallback, 0);
                        bindWallpaperComponentLocked(this.mImageWallpaper, true, false, fallback, reply);
                        this.mWaitingForUnlock = true;
                    }
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    public void clearWallpaper(String callingPackage, int which, int userId) {
        checkPermission("android.permission.SET_WALLPAPER");
        if (!isWallpaperSupported(callingPackage) || !isSetWallpaperAllowed(callingPackage)) {
            return;
        }
        int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, "clearWallpaper", null);
        WallpaperData data = null;
        synchronized (this.mLock) {
            clearWallpaperLocked(false, which, userId2, null);
            if (which == 2) {
                data = this.mLockWallpaperMap.get(userId2);
            }
            if (which == 1 || data == null) {
                data = this.mWallpaperMap.get(userId2);
            }
        }
        if (data != null) {
            notifyWallpaperColorsChanged(data, which);
            notifyWallpaperColorsChanged(this.mFallbackWallpaper, 1);
        }
    }

    void clearWallpaperLocked(boolean defaultFailed, int which, int userId, IRemoteCallback reply) {
        WallpaperData wallpaper;
        if (which != 1 && which != 2) {
            throw new IllegalArgumentException("Must specify exactly one kind of wallpaper to clear");
        }
        if (which == 2) {
            WallpaperData wallpaper2 = this.mLockWallpaperMap.get(userId);
            if (wallpaper2 == null) {
                return;
            }
            wallpaper = wallpaper2;
        } else {
            WallpaperData wallpaper3 = this.mWallpaperMap.get(userId);
            if (wallpaper3 == null) {
                loadSettingsLocked(userId, false);
                wallpaper = this.mWallpaperMap.get(userId);
            } else {
                wallpaper = wallpaper3;
            }
        }
        if (wallpaper == null) {
            return;
        }
        long ident = Binder.clearCallingIdentity();
        try {
            if (wallpaper.wallpaperFile.exists()) {
                wallpaper.wallpaperFile.delete();
                wallpaper.cropFile.delete();
                if (which == 2) {
                    this.mLockWallpaperMap.remove(userId);
                    IWallpaperManagerCallback cb = this.mKeyguardListener;
                    if (cb != null) {
                        try {
                            cb.onWallpaperChanged();
                        } catch (RemoteException e) {
                        }
                    }
                    saveSettingsLocked(userId);
                    return;
                }
            }
            RuntimeException e2 = null;
            try {
                wallpaper.primaryColors = null;
                wallpaper.imageWallpaperPending = false;
            } catch (IllegalArgumentException e1) {
                e2 = e1;
            }
            if (userId != this.mCurrentUserId) {
                return;
            }
            if (bindWallpaperComponentLocked(defaultFailed ? this.mImageWallpaper : null, true, false, wallpaper, reply)) {
                return;
            }
            Slog.e(TAG, "Default wallpaper component not found!", e2);
            clearWallpaperComponentLocked(wallpaper);
            if (reply != null) {
                try {
                    reply.sendResult((Bundle) null);
                } catch (RemoteException e3) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean hasNamedWallpaper(String name) {
        synchronized (this.mLock) {
            long ident = Binder.clearCallingIdentity();
            List<UserInfo> users = ((UserManager) this.mContext.getSystemService("user")).getUsers();
            Binder.restoreCallingIdentity(ident);
            for (UserInfo user : users) {
                if (!user.isManagedProfile()) {
                    WallpaperData wd = this.mWallpaperMap.get(user.id);
                    if (wd == null) {
                        loadSettingsLocked(user.id, false);
                        wd = this.mWallpaperMap.get(user.id);
                    }
                    if (wd != null && name.equals(wd.name)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private boolean isValidDisplay(int displayId) {
        return this.mDisplayManager.getDisplay(displayId) != null;
    }

    public void setDimensionHints(int width, int height, String callingPackage, int displayId) throws RemoteException {
        checkPermission("android.permission.SET_WALLPAPER_HINTS");
        if (!isWallpaperSupported(callingPackage)) {
            return;
        }
        int width2 = Math.min(width, GLHelper.getMaxTextureSize());
        int height2 = Math.min(height, GLHelper.getMaxTextureSize());
        synchronized (this.mLock) {
            int userId = UserHandle.getCallingUserId();
            WallpaperData wallpaper = getWallpaperSafeLocked(userId, 1);
            if (width2 <= 0 || height2 <= 0) {
                throw new IllegalArgumentException("width and height must be > 0");
            }
            if (!isValidDisplay(displayId)) {
                throw new IllegalArgumentException("Cannot find display with id=" + displayId);
            }
            DisplayData wpdData = getDisplayDataOrCreate(displayId);
            if (width2 != wpdData.mWidth || height2 != wpdData.mHeight) {
                wpdData.mWidth = width2;
                wpdData.mHeight = height2;
                if (displayId == 0) {
                    saveSettingsLocked(userId);
                }
                if (this.mCurrentUserId != userId) {
                    return;
                }
                if (wallpaper.connection != null) {
                    WallpaperConnection.DisplayConnector connector = wallpaper.connection.getDisplayConnectorOrCreate(displayId);
                    IWallpaperEngine engine = connector != null ? connector.mEngine : null;
                    if (engine != null) {
                        try {
                            engine.setDesiredSize(width2, height2);
                        } catch (RemoteException e) {
                        }
                        notifyCallbacksLocked(wallpaper);
                    } else if (wallpaper.connection.mService != null && connector != null) {
                        connector.mDimensionsChanged = true;
                    }
                }
            }
        }
    }

    public int getWidthHint(int displayId) throws RemoteException {
        synchronized (this.mLock) {
            if (!isValidDisplay(displayId)) {
                throw new IllegalArgumentException("Cannot find display with id=" + displayId);
            }
            WallpaperData wallpaper = this.mWallpaperMap.get(UserHandle.getCallingUserId());
            if (wallpaper != null) {
                DisplayData wpdData = getDisplayDataOrCreate(displayId);
                return wpdData.mWidth;
            }
            return 0;
        }
    }

    public int getHeightHint(int displayId) throws RemoteException {
        synchronized (this.mLock) {
            if (!isValidDisplay(displayId)) {
                throw new IllegalArgumentException("Cannot find display with id=" + displayId);
            }
            WallpaperData wallpaper = this.mWallpaperMap.get(UserHandle.getCallingUserId());
            if (wallpaper != null) {
                DisplayData wpdData = getDisplayDataOrCreate(displayId);
                return wpdData.mHeight;
            }
            return 0;
        }
    }

    public void setDisplayPadding(Rect padding, String callingPackage, int displayId) {
        checkPermission("android.permission.SET_WALLPAPER_HINTS");
        if (!isWallpaperSupported(callingPackage)) {
            return;
        }
        synchronized (this.mLock) {
            if (!isValidDisplay(displayId)) {
                throw new IllegalArgumentException("Cannot find display with id=" + displayId);
            }
            int userId = UserHandle.getCallingUserId();
            WallpaperData wallpaper = getWallpaperSafeLocked(userId, 1);
            if (padding.left < 0 || padding.top < 0 || padding.right < 0 || padding.bottom < 0) {
                throw new IllegalArgumentException("padding must be positive: " + padding);
            }
            DisplayData wpdData = getDisplayDataOrCreate(displayId);
            if (!padding.equals(wpdData.mPadding)) {
                wpdData.mPadding.set(padding);
                if (displayId == 0) {
                    saveSettingsLocked(userId);
                }
                if (this.mCurrentUserId != userId) {
                    return;
                }
                if (wallpaper.connection != null) {
                    WallpaperConnection.DisplayConnector connector = wallpaper.connection.getDisplayConnectorOrCreate(displayId);
                    IWallpaperEngine engine = connector != null ? connector.mEngine : null;
                    if (engine != null) {
                        try {
                            engine.setDisplayPadding(padding);
                        } catch (RemoteException e) {
                        }
                        notifyCallbacksLocked(wallpaper);
                    } else if (wallpaper.connection.mService != null && connector != null) {
                        connector.mPaddingChanged = true;
                    }
                }
            }
        }
    }

    public ParcelFileDescriptor getWallpaper(String callingPkg, IWallpaperManagerCallback cb, int which, Bundle outParams, int wallpaperUserId) {
        int hasPrivilege = this.mContext.checkCallingOrSelfPermission("android.permission.READ_WALLPAPER_INTERNAL");
        if (hasPrivilege != 0) {
            ((StorageManager) this.mContext.getSystemService(StorageManager.class)).checkPermissionReadImages(true, Binder.getCallingPid(), Binder.getCallingUid(), callingPkg);
        }
        int wallpaperUserId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), wallpaperUserId, false, true, "getWallpaper", null);
        if (which != 1 && which != 2) {
            throw new IllegalArgumentException("Must specify exactly one kind of wallpaper to read");
        }
        synchronized (this.mLock) {
            SparseArray<WallpaperData> whichSet = which == 2 ? this.mLockWallpaperMap : this.mWallpaperMap;
            WallpaperData wallpaper = whichSet.get(wallpaperUserId2);
            if (wallpaper != null) {
                DisplayData wpdData = getDisplayDataOrCreate(0);
                if (outParams != null) {
                    try {
                        outParams.putInt("width", wpdData.mWidth);
                        outParams.putInt("height", wpdData.mHeight);
                    } catch (FileNotFoundException e) {
                        Slog.w(TAG, "Error getting wallpaper", e);
                        return null;
                    }
                }
                if (cb != null) {
                    wallpaper.callbacks.register(cb);
                }
                if (!wallpaper.cropFile.exists()) {
                    return null;
                }
                return ParcelFileDescriptor.open(wallpaper.cropFile, 268435456);
            }
            return null;
        }
    }

    public WallpaperInfo getWallpaperInfo(int userId) {
        int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, "getWallpaperInfo", null);
        synchronized (this.mLock) {
            WallpaperData wallpaper = this.mWallpaperMap.get(userId2);
            if (wallpaper != null && wallpaper.connection != null) {
                return wallpaper.connection.mInfo;
            }
            return null;
        }
    }

    public int getWallpaperIdForUser(int which, int userId) {
        int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, "getWallpaperIdForUser", null);
        if (which != 1 && which != 2) {
            throw new IllegalArgumentException("Must specify exactly one kind of wallpaper");
        }
        SparseArray<WallpaperData> map = which == 2 ? this.mLockWallpaperMap : this.mWallpaperMap;
        synchronized (this.mLock) {
            WallpaperData wallpaper = map.get(userId2);
            if (wallpaper != null) {
                return wallpaper.wallpaperId;
            }
            return -1;
        }
    }

    public void registerWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId, int displayId) {
        int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, true, true, "registerWallpaperColorsCallback", null);
        synchronized (this.mLock) {
            SparseArray<RemoteCallbackList<IWallpaperManagerCallback>> userDisplayColorsChangedListeners = this.mColorsChangedListeners.get(userId2);
            if (userDisplayColorsChangedListeners == null) {
                userDisplayColorsChangedListeners = new SparseArray<>();
                this.mColorsChangedListeners.put(userId2, userDisplayColorsChangedListeners);
            }
            RemoteCallbackList<IWallpaperManagerCallback> displayChangedListeners = userDisplayColorsChangedListeners.get(displayId);
            if (displayChangedListeners == null) {
                displayChangedListeners = new RemoteCallbackList<>();
                userDisplayColorsChangedListeners.put(displayId, displayChangedListeners);
            }
            displayChangedListeners.register(cb);
        }
    }

    public void unregisterWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId, int displayId) {
        RemoteCallbackList<IWallpaperManagerCallback> displayChangedListeners;
        int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, true, true, "unregisterWallpaperColorsCallback", null);
        synchronized (this.mLock) {
            SparseArray<RemoteCallbackList<IWallpaperManagerCallback>> userDisplayColorsChangedListeners = this.mColorsChangedListeners.get(userId2);
            if (userDisplayColorsChangedListeners != null && (displayChangedListeners = userDisplayColorsChangedListeners.get(displayId)) != null) {
                displayChangedListeners.unregister(cb);
            }
        }
    }

    public void setInAmbientMode(boolean inAmbientMode, long animationDuration) {
        IWallpaperEngine engine;
        synchronized (this.mLock) {
            this.mInAmbientMode = inAmbientMode;
            WallpaperData data = this.mWallpaperMap.get(this.mCurrentUserId);
            if (data != null && data.connection != null && (data.connection.mInfo == null || data.connection.mInfo.supportsAmbientMode())) {
                engine = data.connection.getDisplayConnectorOrCreate(0).mEngine;
            } else {
                engine = null;
            }
        }
        if (engine != null) {
            try {
                engine.setInAmbientMode(inAmbientMode, animationDuration);
            } catch (RemoteException e) {
            }
        }
    }

    public boolean setLockWallpaperCallback(IWallpaperManagerCallback cb) {
        checkPermission("android.permission.INTERNAL_SYSTEM_WINDOW");
        synchronized (this.mLock) {
            this.mKeyguardListener = cb;
        }
        return true;
    }

    public WallpaperColors getWallpaperColors(int which, int userId, int displayId) throws RemoteException {
        WallpaperColors wallpaperColors;
        boolean shouldExtract = true;
        if (which != 2 && which != 1) {
            throw new IllegalArgumentException("which should be either FLAG_LOCK or FLAG_SYSTEM");
        }
        int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, true, "getWallpaperColors", null);
        WallpaperData wallpaperData = null;
        synchronized (this.mLock) {
            if (which == 2) {
                try {
                    wallpaperData = this.mLockWallpaperMap.get(userId2);
                } catch (Throwable th) {
                    throw th;
                }
            }
            if (wallpaperData == null) {
                wallpaperData = findWallpaperAtDisplay(userId2, displayId);
            }
            if (wallpaperData == null) {
                return null;
            }
            if (wallpaperData.primaryColors != null) {
                shouldExtract = false;
            }
            if (shouldExtract) {
                extractColors(wallpaperData);
            }
            synchronized (this.mLock) {
                wallpaperColors = wallpaperData.primaryColors;
            }
            return wallpaperColors;
        }
    }

    private WallpaperData findWallpaperAtDisplay(int userId, int displayId) {
        WallpaperData wallpaperData = this.mFallbackWallpaper;
        if (wallpaperData != null && wallpaperData.connection != null && this.mFallbackWallpaper.connection.containsDisplay(displayId)) {
            return this.mFallbackWallpaper;
        }
        return this.mWallpaperMap.get(userId);
    }

    public ParcelFileDescriptor setWallpaper(String name, String callingPackage, Rect cropHint, boolean allowBackup, Bundle extras, int which, IWallpaperManagerCallback completion, int userId) {
        Rect cropHint2;
        ParcelFileDescriptor pfd;
        int userId2 = ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId, false, true, "changing wallpaper", null);
        checkPermission("android.permission.SET_WALLPAPER");
        if ((which & 3) == 0) {
            Slog.e(TAG, "Must specify a valid wallpaper category to set");
            throw new IllegalArgumentException("Must specify a valid wallpaper category to set");
        } else if (!isWallpaperSupported(callingPackage) || !isSetWallpaperAllowed(callingPackage)) {
            return null;
        } else {
            if (cropHint == null) {
                cropHint2 = new Rect(0, 0, 0, 0);
            } else if (cropHint.isEmpty() || cropHint.left < 0 || cropHint.top < 0) {
                throw new IllegalArgumentException("Invalid crop rect supplied: " + cropHint);
            } else {
                cropHint2 = cropHint;
            }
            synchronized (this.mLock) {
                if (which == 1) {
                    if (this.mLockWallpaperMap.get(userId2) == null) {
                        migrateSystemToLockWallpaperLocked(userId2);
                    }
                }
                WallpaperData wallpaper = getWallpaperSafeLocked(userId2, which);
                long ident = Binder.clearCallingIdentity();
                pfd = updateWallpaperBitmapLocked(name, wallpaper, extras);
                if (pfd != null) {
                    wallpaper.imageWallpaperPending = true;
                    wallpaper.whichPending = which;
                    wallpaper.setComplete = completion;
                    wallpaper.cropHint.set(cropHint2);
                    wallpaper.allowBackup = allowBackup;
                }
                Binder.restoreCallingIdentity(ident);
            }
            return pfd;
        }
    }

    private void migrateSystemToLockWallpaperLocked(int userId) {
        WallpaperData sysWP = this.mWallpaperMap.get(userId);
        if (sysWP == null) {
            return;
        }
        WallpaperData lockWP = new WallpaperData(userId, WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
        lockWP.wallpaperId = sysWP.wallpaperId;
        lockWP.cropHint.set(sysWP.cropHint);
        lockWP.allowBackup = sysWP.allowBackup;
        lockWP.primaryColors = sysWP.primaryColors;
        try {
            Os.rename(sysWP.wallpaperFile.getAbsolutePath(), lockWP.wallpaperFile.getAbsolutePath());
            Os.rename(sysWP.cropFile.getAbsolutePath(), lockWP.cropFile.getAbsolutePath());
            this.mLockWallpaperMap.put(userId, lockWP);
        } catch (ErrnoException e) {
            Slog.e(TAG, "Can't migrate system wallpaper: " + e.getMessage());
            lockWP.wallpaperFile.delete();
            lockWP.cropFile.delete();
        }
    }

    ParcelFileDescriptor updateWallpaperBitmapLocked(String name, WallpaperData wallpaper, Bundle extras) {
        if (name == null) {
            name = "";
        }
        try {
            File dir = getWallpaperDir(wallpaper.userId);
            if (!dir.exists()) {
                dir.mkdir();
                FileUtils.setPermissions(dir.getPath(), 505, -1, -1);
            }
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(wallpaper.wallpaperFile, 1006632960);
            if (!SELinux.restorecon(wallpaper.wallpaperFile)) {
                return null;
            }
            wallpaper.name = name;
            wallpaper.wallpaperId = makeWallpaperIdLocked();
            if (extras != null) {
                extras.putInt("android.service.wallpaper.extra.ID", wallpaper.wallpaperId);
            }
            wallpaper.primaryColors = null;
            return fd;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Error setting wallpaper", e);
            return null;
        }
    }

    public void setWallpaperComponentChecked(ComponentName name, String callingPackage, int userId) {
        if (isWallpaperSupported(callingPackage) && isSetWallpaperAllowed(callingPackage)) {
            setWallpaperComponent(name, userId);
        }
    }

    public void setWallpaperComponent(ComponentName name) {
        setWallpaperComponent(name, UserHandle.getCallingUserId());
    }

    private void setWallpaperComponent(ComponentName name, int userId) {
        WallpaperData wallpaper;
        int userId2 = ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId, false, true, "changing live wallpaper", null);
        checkPermission("android.permission.SET_WALLPAPER_COMPONENT");
        int which = 1;
        boolean shouldNotifyColors = false;
        synchronized (this.mLock) {
            wallpaper = this.mWallpaperMap.get(userId2);
            if (wallpaper == null) {
                throw new IllegalStateException("Wallpaper not yet initialized for user " + userId2);
            }
            long ident = Binder.clearCallingIdentity();
            if (this.mImageWallpaper.equals(wallpaper.wallpaperComponent) && this.mLockWallpaperMap.get(userId2) == null) {
                migrateSystemToLockWallpaperLocked(userId2);
            }
            if (this.mLockWallpaperMap.get(userId2) == null) {
                which = 1 | 2;
            }
            wallpaper.imageWallpaperPending = false;
            boolean same = changingToSame(name, wallpaper);
            if (bindWallpaperComponentLocked(name, false, true, wallpaper, null)) {
                if (!same) {
                    wallpaper.primaryColors = null;
                }
                wallpaper.wallpaperId = makeWallpaperIdLocked();
                notifyCallbacksLocked(wallpaper);
                shouldNotifyColors = true;
            }
            Binder.restoreCallingIdentity(ident);
        }
        if (shouldNotifyColors) {
            notifyWallpaperColorsChanged(wallpaper, which);
            notifyWallpaperColorsChanged(this.mFallbackWallpaper, 1);
        }
    }

    private boolean changingToSame(ComponentName componentName, WallpaperData wallpaper) {
        if (wallpaper.connection != null) {
            return wallpaper.wallpaperComponent == null ? componentName == null : wallpaper.wallpaperComponent.equals(componentName);
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code restructure failed: missing block: B:39:0x00e1, code lost:
        r8 = new android.app.WallpaperInfo(r19.mContext, r10.get(r11));
     */
    /* JADX WARN: Removed duplicated region for block: B:102:0x021e  */
    /* JADX WARN: Removed duplicated region for block: B:104:0x0223  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public boolean bindWallpaperComponentLocked(android.content.ComponentName r20, boolean r21, boolean r22, com.android.server.wallpaper.WallpaperManagerService.WallpaperData r23, android.os.IRemoteCallback r24) {
        /*
            Method dump skipped, instructions count: 553
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wallpaper.WallpaperManagerService.bindWallpaperComponentLocked(android.content.ComponentName, boolean, boolean, com.android.server.wallpaper.WallpaperManagerService$WallpaperData, android.os.IRemoteCallback):boolean");
    }

    private void detachWallpaperLocked(WallpaperData wallpaper) {
        if (wallpaper.connection != null) {
            if (wallpaper.connection.mReply != null) {
                try {
                    wallpaper.connection.mReply.sendResult((Bundle) null);
                } catch (RemoteException e) {
                }
                wallpaper.connection.mReply = null;
            }
            try {
                if (wallpaper.connection.mService != null) {
                    wallpaper.connection.mService.detach();
                }
            } catch (RemoteException e2) {
                Slog.w(TAG, "Failed detaching wallpaper service ", e2);
            }
            this.mContext.unbindService(wallpaper.connection);
            wallpaper.connection.forEachDisplayConnector(new Consumer() { // from class: com.android.server.wallpaper.-$$Lambda$havGP5uMdRgWQrLydPeIOu1qDGE
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((WallpaperManagerService.WallpaperConnection.DisplayConnector) obj).disconnectLocked();
                }
            });
            wallpaper.connection.mService = null;
            wallpaper.connection.mDisplayConnector.clear();
            wallpaper.connection = null;
            if (wallpaper == this.mLastWallpaper) {
                this.mLastWallpaper = null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearWallpaperComponentLocked(WallpaperData wallpaper) {
        wallpaper.wallpaperComponent = null;
        detachWallpaperLocked(wallpaper);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void attachServiceLocked(final WallpaperConnection conn, final WallpaperData wallpaper) {
        conn.forEachDisplayConnector(new Consumer() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$UhAlBGB5jhuZrLndUPRmIvoHRZc
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ((WallpaperManagerService.WallpaperConnection.DisplayConnector) obj).connectLocked(WallpaperManagerService.WallpaperConnection.this, wallpaper);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void notifyCallbacksLocked(WallpaperData wallpaper) {
        int n = wallpaper.callbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                wallpaper.callbacks.getBroadcastItem(i).onWallpaperChanged();
            } catch (RemoteException e) {
            }
        }
        wallpaper.callbacks.finishBroadcast();
        Intent intent = new Intent("android.intent.action.WALLPAPER_CHANGED");
        this.mContext.sendBroadcastAsUser(intent, new UserHandle(this.mCurrentUserId));
    }

    private void checkPermission(String permission) {
        if (this.mContext.checkCallingOrSelfPermission(permission) != 0) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid() + ", must have permission " + permission);
        }
    }

    public boolean isWallpaperSupported(String callingPackage) {
        return this.mAppOpsManager.checkOpNoThrow(48, Binder.getCallingUid(), callingPackage) == 0;
    }

    public boolean isSetWallpaperAllowed(String callingPackage) {
        PackageManager pm = this.mContext.getPackageManager();
        String[] uidPackages = pm.getPackagesForUid(Binder.getCallingUid());
        boolean uidMatchPackage = Arrays.asList(uidPackages).contains(callingPackage);
        if (!uidMatchPackage) {
            return false;
        }
        DevicePolicyManager dpm = (DevicePolicyManager) this.mContext.getSystemService(DevicePolicyManager.class);
        if (dpm.isDeviceOwnerApp(callingPackage) || dpm.isProfileOwnerApp(callingPackage)) {
            return true;
        }
        UserManager um = (UserManager) this.mContext.getSystemService("user");
        return true ^ um.hasUserRestriction("no_set_wallpaper");
    }

    public boolean isWallpaperBackupEligible(int which, int userId) {
        WallpaperData wallpaper;
        if (Binder.getCallingUid() != 1000) {
            throw new SecurityException("Only the system may call isWallpaperBackupEligible");
        }
        if (which == 2) {
            wallpaper = this.mLockWallpaperMap.get(userId);
        } else {
            wallpaper = this.mWallpaperMap.get(userId);
        }
        if (wallpaper != null) {
            return wallpaper.allowBackup;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onDisplayReadyInternal(int displayId) {
        synchronized (this.mLock) {
            if (this.mLastWallpaper == null) {
                return;
            }
            if (supportsMultiDisplay(this.mLastWallpaper.connection)) {
                WallpaperConnection.DisplayConnector connector = this.mLastWallpaper.connection.getDisplayConnectorOrCreate(displayId);
                if (connector == null) {
                    return;
                }
                connector.connectLocked(this.mLastWallpaper.connection, this.mLastWallpaper);
                return;
            }
            if (this.mFallbackWallpaper != null) {
                WallpaperConnection.DisplayConnector connector2 = this.mFallbackWallpaper.connection.getDisplayConnectorOrCreate(displayId);
                if (connector2 == null) {
                    return;
                }
                connector2.connectLocked(this.mFallbackWallpaper.connection, this.mFallbackWallpaper);
            } else {
                Slog.w(TAG, "No wallpaper can be added to the new display");
            }
        }
    }

    private static JournaledFile makeJournaledFile(int userId) {
        String base = new File(getWallpaperDir(userId), WALLPAPER_INFO).getAbsolutePath();
        File file = new File(base);
        return new JournaledFile(file, new File(base + ".tmp"));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveSettingsLocked(int userId) {
        JournaledFile journal = makeJournaledFile(userId);
        BufferedOutputStream stream = null;
        try {
            XmlSerializer out = new FastXmlSerializer();
            FileOutputStream fstream = new FileOutputStream(journal.chooseForWrite(), false);
            stream = new BufferedOutputStream(fstream);
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            WallpaperData wallpaper = this.mWallpaperMap.get(userId);
            if (wallpaper != null) {
                writeWallpaperAttributes(out, "wp", wallpaper);
            }
            WallpaperData wallpaper2 = this.mLockWallpaperMap.get(userId);
            if (wallpaper2 != null) {
                writeWallpaperAttributes(out, "kwp", wallpaper2);
            }
            out.endDocument();
            stream.flush();
            FileUtils.sync(fstream);
            stream.close();
            journal.commit();
        } catch (IOException e) {
            IoUtils.closeQuietly(stream);
            journal.rollback();
        }
    }

    private void writeWallpaperAttributes(XmlSerializer out, String tag, WallpaperData wallpaper) throws IllegalArgumentException, IllegalStateException, IOException {
        DisplayData wpdData = getDisplayDataOrCreate(0);
        out.startTag(null, tag);
        out.attribute(null, "id", Integer.toString(wallpaper.wallpaperId));
        out.attribute(null, "width", Integer.toString(wpdData.mWidth));
        out.attribute(null, "height", Integer.toString(wpdData.mHeight));
        out.attribute(null, "cropLeft", Integer.toString(wallpaper.cropHint.left));
        out.attribute(null, "cropTop", Integer.toString(wallpaper.cropHint.top));
        out.attribute(null, "cropRight", Integer.toString(wallpaper.cropHint.right));
        out.attribute(null, "cropBottom", Integer.toString(wallpaper.cropHint.bottom));
        if (wpdData.mPadding.left != 0) {
            out.attribute(null, "paddingLeft", Integer.toString(wpdData.mPadding.left));
        }
        if (wpdData.mPadding.top != 0) {
            out.attribute(null, "paddingTop", Integer.toString(wpdData.mPadding.top));
        }
        if (wpdData.mPadding.right != 0) {
            out.attribute(null, "paddingRight", Integer.toString(wpdData.mPadding.right));
        }
        if (wpdData.mPadding.bottom != 0) {
            out.attribute(null, "paddingBottom", Integer.toString(wpdData.mPadding.bottom));
        }
        if (wallpaper.primaryColors != null) {
            int colorsCount = wallpaper.primaryColors.getMainColors().size();
            out.attribute(null, "colorsCount", Integer.toString(colorsCount));
            if (colorsCount > 0) {
                for (int i = 0; i < colorsCount; i++) {
                    Color wc = (Color) wallpaper.primaryColors.getMainColors().get(i);
                    out.attribute(null, "colorValue" + i, Integer.toString(wc.toArgb()));
                }
            }
            out.attribute(null, "colorHints", Integer.toString(wallpaper.primaryColors.getColorHints()));
        }
        out.attribute(null, Settings.ATTR_NAME, wallpaper.name);
        if (wallpaper.wallpaperComponent != null && !wallpaper.wallpaperComponent.equals(this.mImageWallpaper)) {
            out.attribute(null, "component", wallpaper.wallpaperComponent.flattenToShortString());
        }
        if (wallpaper.allowBackup) {
            out.attribute(null, BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD, "true");
        }
        out.endTag(null, tag);
    }

    private void migrateFromOld() {
        File preNWallpaper = new File(getWallpaperDir(0), WALLPAPER_CROP);
        File originalWallpaper = new File("/data/data/com.android.settings/files/wallpaper");
        File newWallpaper = new File(getWallpaperDir(0), WALLPAPER);
        if (preNWallpaper.exists()) {
            if (!newWallpaper.exists()) {
                FileUtils.copyFile(preNWallpaper, newWallpaper);
            }
        } else if (originalWallpaper.exists()) {
            File oldInfo = new File("/data/system/wallpaper_info.xml");
            if (oldInfo.exists()) {
                File newInfo = new File(getWallpaperDir(0), WALLPAPER_INFO);
                oldInfo.renameTo(newInfo);
            }
            FileUtils.copyFile(originalWallpaper, preNWallpaper);
            originalWallpaper.renameTo(newWallpaper);
        }
    }

    private int getAttributeInt(XmlPullParser parser, String name, int defValue) {
        String value = parser.getAttributeValue(null, name);
        if (value == null) {
            return defValue;
        }
        return Integer.parseInt(value);
    }

    private WallpaperData getWallpaperSafeLocked(int userId, int which) {
        SparseArray<WallpaperData> whichSet = which == 2 ? this.mLockWallpaperMap : this.mWallpaperMap;
        WallpaperData wallpaper = whichSet.get(userId);
        if (wallpaper == null) {
            loadSettingsLocked(userId, false);
            WallpaperData wallpaper2 = whichSet.get(userId);
            if (wallpaper2 == null) {
                if (which == 2) {
                    WallpaperData wallpaper3 = new WallpaperData(userId, WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
                    this.mLockWallpaperMap.put(userId, wallpaper3);
                    ensureSaneWallpaperData(wallpaper3, 0);
                    return wallpaper3;
                }
                Slog.wtf(TAG, "Didn't find wallpaper in non-lock case!");
                WallpaperData wallpaper4 = new WallpaperData(userId, WALLPAPER, WALLPAPER_CROP);
                this.mWallpaperMap.put(userId, wallpaper4);
                ensureSaneWallpaperData(wallpaper4, 0);
                return wallpaper4;
            }
            return wallpaper2;
        }
        return wallpaper;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:138:0x0230  */
    /* JADX WARN: Removed duplicated region for block: B:139:0x0245  */
    /* JADX WARN: Removed duplicated region for block: B:144:0x0260  */
    /* JADX WARN: Removed duplicated region for block: B:159:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public void loadSettingsLocked(int r20, boolean r21) {
        /*
            Method dump skipped, instructions count: 612
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wallpaper.WallpaperManagerService.loadSettingsLocked(int, boolean):void");
    }

    private void initializeFallbackWallpaper() {
        if (this.mFallbackWallpaper == null) {
            this.mFallbackWallpaper = new WallpaperData(0, WALLPAPER, WALLPAPER_CROP);
            WallpaperData wallpaperData = this.mFallbackWallpaper;
            wallpaperData.allowBackup = false;
            wallpaperData.wallpaperId = makeWallpaperIdLocked();
            bindWallpaperComponentLocked(this.mImageWallpaper, true, false, this.mFallbackWallpaper, null);
        }
    }

    private void ensureSaneWallpaperData(WallpaperData wallpaper, int displayId) {
        DisplayData size = getDisplayDataOrCreate(displayId);
        if (displayId == 0) {
            if (wallpaper.cropHint.width() <= 0 || wallpaper.cropHint.height() <= 0) {
                wallpaper.cropHint.set(0, 0, size.mWidth, size.mHeight);
            }
        }
    }

    private void parseWallpaperAttributes(XmlPullParser parser, WallpaperData wallpaper, boolean keepDimensionHints) {
        String idString = parser.getAttributeValue(null, "id");
        if (idString != null) {
            int id = Integer.parseInt(idString);
            wallpaper.wallpaperId = id;
            if (id > this.mWallpaperId) {
                this.mWallpaperId = id;
            }
        } else {
            wallpaper.wallpaperId = makeWallpaperIdLocked();
        }
        DisplayData wpData = getDisplayDataOrCreate(0);
        if (!keepDimensionHints) {
            wpData.mWidth = Integer.parseInt(parser.getAttributeValue(null, "width"));
            wpData.mHeight = Integer.parseInt(parser.getAttributeValue(null, "height"));
        }
        wallpaper.cropHint.left = getAttributeInt(parser, "cropLeft", 0);
        wallpaper.cropHint.top = getAttributeInt(parser, "cropTop", 0);
        wallpaper.cropHint.right = getAttributeInt(parser, "cropRight", 0);
        wallpaper.cropHint.bottom = getAttributeInt(parser, "cropBottom", 0);
        wpData.mPadding.left = getAttributeInt(parser, "paddingLeft", 0);
        wpData.mPadding.top = getAttributeInt(parser, "paddingTop", 0);
        wpData.mPadding.right = getAttributeInt(parser, "paddingRight", 0);
        wpData.mPadding.bottom = getAttributeInt(parser, "paddingBottom", 0);
        int colorsCount = getAttributeInt(parser, "colorsCount", 0);
        if (colorsCount > 0) {
            Color primary = null;
            Color secondary = null;
            Color tertiary = null;
            for (int i = 0; i < colorsCount; i++) {
                Color color = Color.valueOf(getAttributeInt(parser, "colorValue" + i, 0));
                if (i == 0) {
                    primary = color;
                } else if (i == 1) {
                    secondary = color;
                } else if (i != 2) {
                    break;
                } else {
                    tertiary = color;
                }
            }
            int colorHints = getAttributeInt(parser, "colorHints", 0);
            wallpaper.primaryColors = new WallpaperColors(primary, secondary, tertiary, colorHints);
        }
        wallpaper.name = parser.getAttributeValue(null, Settings.ATTR_NAME);
        wallpaper.allowBackup = "true".equals(parser.getAttributeValue(null, BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD));
    }

    public void settingsRestored() {
        WallpaperData wallpaper;
        boolean success;
        if (Binder.getCallingUid() != 1000) {
            throw new RuntimeException("settingsRestored() can only be called from the system process");
        }
        synchronized (this.mLock) {
            loadSettingsLocked(0, false);
            wallpaper = this.mWallpaperMap.get(0);
            wallpaper.wallpaperId = makeWallpaperIdLocked();
            wallpaper.allowBackup = true;
            if (wallpaper.nextWallpaperComponent != null && !wallpaper.nextWallpaperComponent.equals(this.mImageWallpaper)) {
                if (!bindWallpaperComponentLocked(wallpaper.nextWallpaperComponent, false, false, wallpaper, null)) {
                    bindWallpaperComponentLocked(null, false, false, wallpaper, null);
                }
                success = true;
            } else {
                if ("".equals(wallpaper.name)) {
                    success = true;
                } else {
                    success = restoreNamedResourceLocked(wallpaper);
                }
                if (success) {
                    generateCrop(wallpaper);
                    bindWallpaperComponentLocked(wallpaper.nextWallpaperComponent, true, false, wallpaper, null);
                }
            }
        }
        if (!success) {
            Slog.e(TAG, "Failed to restore wallpaper: '" + wallpaper.name + "'");
            wallpaper.name = "";
            getWallpaperDir(0).delete();
        }
        synchronized (this.mLock) {
            saveSettingsLocked(0);
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:53:0x0130, code lost:
        if (0 != 0) goto L61;
     */
    /* JADX WARN: Code restructure failed: missing block: B:54:0x0132, code lost:
        android.os.FileUtils.sync(null);
     */
    /* JADX WARN: Code restructure failed: missing block: B:55:0x0135, code lost:
        libcore.io.IoUtils.closeQuietly((java.lang.AutoCloseable) null);
        libcore.io.IoUtils.closeQuietly((java.lang.AutoCloseable) null);
     */
    /* JADX WARN: Code restructure failed: missing block: B:61:0x015a, code lost:
        if (0 != 0) goto L61;
     */
    /* JADX WARN: Code restructure failed: missing block: B:68:0x0180, code lost:
        if (0 != 0) goto L61;
     */
    /* JADX WARN: Code restructure failed: missing block: B:83:?, code lost:
        return false;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean restoreNamedResourceLocked(com.android.server.wallpaper.WallpaperManagerService.WallpaperData r19) {
        /*
            Method dump skipped, instructions count: 414
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wallpaper.WallpaperManagerService.restoreNamedResourceLocked(com.android.server.wallpaper.WallpaperManagerService$WallpaperData):boolean");
    }

    protected void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            synchronized (this.mLock) {
                pw.println("System wallpaper state:");
                for (int i = 0; i < this.mWallpaperMap.size(); i++) {
                    WallpaperData wallpaper = this.mWallpaperMap.valueAt(i);
                    pw.print(" User ");
                    pw.print(wallpaper.userId);
                    pw.print(": id=");
                    pw.println(wallpaper.wallpaperId);
                    pw.println(" Display state:");
                    forEachDisplayData(new Consumer() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$VlOcXJ2BasDkYqNidSTRvw-HBpM
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            WallpaperManagerService.lambda$dump$6(pw, (WallpaperManagerService.DisplayData) obj);
                        }
                    });
                    pw.print("  mCropHint=");
                    pw.println(wallpaper.cropHint);
                    pw.print("  mName=");
                    pw.println(wallpaper.name);
                    pw.print("  mAllowBackup=");
                    pw.println(wallpaper.allowBackup);
                    pw.print("  mWallpaperComponent=");
                    pw.println(wallpaper.wallpaperComponent);
                    if (wallpaper.connection != null) {
                        WallpaperConnection conn = wallpaper.connection;
                        pw.print("  Wallpaper connection ");
                        pw.print(conn);
                        pw.println(":");
                        if (conn.mInfo != null) {
                            pw.print("    mInfo.component=");
                            pw.println(conn.mInfo.getComponent());
                        }
                        conn.forEachDisplayConnector(new Consumer() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$fLM_YLhVBfWS7QM0ta-qHXvJ4Uc
                            @Override // java.util.function.Consumer
                            public final void accept(Object obj) {
                                WallpaperManagerService.lambda$dump$7(pw, (WallpaperManagerService.WallpaperConnection.DisplayConnector) obj);
                            }
                        });
                        pw.print("    mService=");
                        pw.println(conn.mService);
                        pw.print("    mLastDiedTime=");
                        pw.println(wallpaper.lastDiedTime - SystemClock.uptimeMillis());
                    }
                }
                pw.println("Lock wallpaper state:");
                for (int i2 = 0; i2 < this.mLockWallpaperMap.size(); i2++) {
                    WallpaperData wallpaper2 = this.mLockWallpaperMap.valueAt(i2);
                    pw.print(" User ");
                    pw.print(wallpaper2.userId);
                    pw.print(": id=");
                    pw.println(wallpaper2.wallpaperId);
                    pw.print("  mCropHint=");
                    pw.println(wallpaper2.cropHint);
                    pw.print("  mName=");
                    pw.println(wallpaper2.name);
                    pw.print("  mAllowBackup=");
                    pw.println(wallpaper2.allowBackup);
                }
                pw.println("Fallback wallpaper state:");
                pw.print(" User ");
                pw.print(this.mFallbackWallpaper.userId);
                pw.print(": id=");
                pw.println(this.mFallbackWallpaper.wallpaperId);
                pw.print("  mCropHint=");
                pw.println(this.mFallbackWallpaper.cropHint);
                pw.print("  mName=");
                pw.println(this.mFallbackWallpaper.name);
                pw.print("  mAllowBackup=");
                pw.println(this.mFallbackWallpaper.allowBackup);
                if (this.mFallbackWallpaper.connection != null) {
                    WallpaperConnection conn2 = this.mFallbackWallpaper.connection;
                    pw.print("  Fallback Wallpaper connection ");
                    pw.print(conn2);
                    pw.println(":");
                    if (conn2.mInfo != null) {
                        pw.print("    mInfo.component=");
                        pw.println(conn2.mInfo.getComponent());
                    }
                    conn2.forEachDisplayConnector(new Consumer() { // from class: com.android.server.wallpaper.-$$Lambda$WallpaperManagerService$VUhQWq8Flr0dsQqeVHhHT8jU7qY
                        @Override // java.util.function.Consumer
                        public final void accept(Object obj) {
                            WallpaperManagerService.lambda$dump$8(pw, (WallpaperManagerService.WallpaperConnection.DisplayConnector) obj);
                        }
                    });
                    pw.print("    mService=");
                    pw.println(conn2.mService);
                    pw.print("    mLastDiedTime=");
                    pw.println(this.mFallbackWallpaper.lastDiedTime - SystemClock.uptimeMillis());
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dump$6(PrintWriter pw, DisplayData wpSize) {
        pw.print("  displayId=");
        pw.println(wpSize.mDisplayId);
        pw.print("  mWidth=");
        pw.print(wpSize.mWidth);
        pw.print("  mHeight=");
        pw.println(wpSize.mHeight);
        pw.print("  mPadding=");
        pw.println(wpSize.mPadding);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dump$7(PrintWriter pw, WallpaperConnection.DisplayConnector connector) {
        pw.print("     mDisplayId=");
        pw.println(connector.mDisplayId);
        pw.print("     mToken=");
        pw.println(connector.mToken);
        pw.print("     mEngine=");
        pw.println(connector.mEngine);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dump$8(PrintWriter pw, WallpaperConnection.DisplayConnector connector) {
        pw.print("     mDisplayId=");
        pw.println(connector.mDisplayId);
        pw.print("     mToken=");
        pw.println(connector.mToken);
        pw.print("     mEngine=");
        pw.println(connector.mEngine);
    }
}
