package com.android.server.gpu;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.gamedriver.GameDriverProto;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.Base64;
import com.android.framework.protobuf.InvalidProtocolBufferException;
import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.server.pm.DumpState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes.dex */
public class GpuService extends SystemService {
    private static final int BASE64_FLAGS = 3;
    public static final boolean DEBUG = false;
    private static final String GAME_DRIVER_WHITELIST_FILENAME = "whitelist.txt";
    private static final String PROPERTY_GFX_DRIVER = "ro.gfx.driver.0";
    public static final String TAG = "GpuService";
    @GuardedBy({"mLock"})
    private GameDriverProto.Blacklists mBlacklists;
    private ContentResolver mContentResolver;
    private final Context mContext;
    private DeviceConfigListener mDeviceConfigListener;
    private final Object mDeviceConfigLock;
    private final String mDriverPackageName;
    private long mGameDriverVersionCode;
    private final Object mLock;
    private final PackageManager mPackageManager;
    private SettingsObserver mSettingsObserver;

    public GpuService(Context context) {
        super(context);
        this.mLock = new Object();
        this.mDeviceConfigLock = new Object();
        this.mContext = context;
        this.mDriverPackageName = SystemProperties.get(PROPERTY_GFX_DRIVER);
        this.mGameDriverVersionCode = -1L;
        this.mPackageManager = context.getPackageManager();
        String str = this.mDriverPackageName;
        if (str != null && !str.isEmpty()) {
            IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction("android.intent.action.PACKAGE_ADDED");
            packageFilter.addAction("android.intent.action.PACKAGE_CHANGED");
            packageFilter.addAction("android.intent.action.PACKAGE_REMOVED");
            packageFilter.addDataScheme("package");
            getContext().registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL, packageFilter, null, null);
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 1000) {
            this.mContentResolver = this.mContext.getContentResolver();
            String str = this.mDriverPackageName;
            if (str == null || str.isEmpty()) {
                return;
            }
            this.mSettingsObserver = new SettingsObserver();
            this.mDeviceConfigListener = new DeviceConfigListener();
            fetchGameDriverPackageProperties();
            processBlacklists();
            setBlacklist();
        }
    }

    /* loaded from: classes.dex */
    private final class SettingsObserver extends ContentObserver {
        private final Uri mGameDriverBlackUri;

        SettingsObserver() {
            super(new Handler());
            this.mGameDriverBlackUri = Settings.Global.getUriFor("game_driver_blacklists");
            GpuService.this.mContentResolver.registerContentObserver(this.mGameDriverBlackUri, false, this, -1);
        }

        @Override // android.database.ContentObserver
        public void onChange(boolean selfChange, Uri uri) {
            if (uri != null && this.mGameDriverBlackUri.equals(uri)) {
                GpuService.this.processBlacklists();
                GpuService.this.setBlacklist();
            }
        }
    }

    /* loaded from: classes.dex */
    private final class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {
        DeviceConfigListener() {
            DeviceConfig.addOnPropertiesChangedListener("game_driver", GpuService.this.mContext.getMainExecutor(), this);
        }

        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            synchronized (GpuService.this.mDeviceConfigLock) {
                if (properties.getKeyset().contains("game_driver_blacklists")) {
                    GpuService.this.parseBlacklists(properties.getString("game_driver_blacklists", ""));
                    GpuService.this.setBlacklist();
                }
            }
        }
    }

    /* loaded from: classes.dex */
    private final class PackageReceiver extends BroadcastReceiver {
        private PackageReceiver() {
        }

        /* JADX WARN: Code restructure failed: missing block: B:13:0x003e, code lost:
            if (r4.equals("android.intent.action.PACKAGE_ADDED") == false) goto L19;
         */
        @Override // android.content.BroadcastReceiver
        /*
            Code decompiled incorrectly, please refer to instructions dump.
            To view partially-correct code enable 'Show inconsistent code' option in preferences
        */
        public void onReceive(android.content.Context r11, android.content.Intent r12) {
            /*
                r10 = this;
                android.net.Uri r0 = r12.getData()
                java.lang.String r1 = r0.getSchemeSpecificPart()
                com.android.server.gpu.GpuService r2 = com.android.server.gpu.GpuService.this
                java.lang.String r2 = com.android.server.gpu.GpuService.access$700(r2)
                boolean r2 = r1.equals(r2)
                if (r2 != 0) goto L16
                return
            L16:
                r2 = 0
                java.lang.String r3 = "android.intent.extra.REPLACING"
                boolean r3 = r12.getBooleanExtra(r3, r2)
                java.lang.String r4 = r12.getAction()
                r5 = -1
                int r6 = r4.hashCode()
                r7 = 172491798(0xa480416, float:9.630418E-33)
                r8 = 2
                r9 = 1
                if (r6 == r7) goto L4b
                r7 = 525384130(0x1f50b9c2, float:4.419937E-20)
                if (r6 == r7) goto L41
                r7 = 1544582882(0x5c1076e2, float:1.6265244E17)
                if (r6 == r7) goto L38
            L37:
                goto L55
            L38:
                java.lang.String r6 = "android.intent.action.PACKAGE_ADDED"
                boolean r4 = r4.equals(r6)
                if (r4 == 0) goto L37
                goto L56
            L41:
                java.lang.String r2 = "android.intent.action.PACKAGE_REMOVED"
                boolean r2 = r4.equals(r2)
                if (r2 == 0) goto L37
                r2 = r8
                goto L56
            L4b:
                java.lang.String r2 = "android.intent.action.PACKAGE_CHANGED"
                boolean r2 = r4.equals(r2)
                if (r2 == 0) goto L37
                r2 = r9
                goto L56
            L55:
                r2 = r5
            L56:
                if (r2 == 0) goto L5d
                if (r2 == r9) goto L5d
                if (r2 == r8) goto L5d
                goto L68
            L5d:
                com.android.server.gpu.GpuService r2 = com.android.server.gpu.GpuService.this
                com.android.server.gpu.GpuService.access$800(r2)
                com.android.server.gpu.GpuService r2 = com.android.server.gpu.GpuService.this
                com.android.server.gpu.GpuService.access$300(r2)
            L68:
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.server.gpu.GpuService.PackageReceiver.onReceive(android.content.Context, android.content.Intent):void");
        }
    }

    private static void assetToSettingsGlobal(Context context, Context driverContext, String fileName, String settingsGlobal, CharSequence delimiter) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(driverContext.getAssets().open(fileName)));
            ArrayList<String> assetStrings = new ArrayList<>();
            while (true) {
                String assetString = reader.readLine();
                if (assetString != null) {
                    assetStrings.add(assetString);
                } else {
                    Settings.Global.putString(context.getContentResolver(), settingsGlobal, String.join(delimiter, assetStrings));
                    return;
                }
            }
        } catch (IOException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void fetchGameDriverPackageProperties() {
        try {
            ApplicationInfo driverInfo = this.mPackageManager.getApplicationInfo(this.mDriverPackageName, DumpState.DUMP_DEXOPT);
            if (driverInfo.targetSdkVersion < 26) {
                return;
            }
            Settings.Global.putString(this.mContentResolver, "game_driver_whitelist", "");
            this.mGameDriverVersionCode = driverInfo.longVersionCode;
            try {
                Context driverContext = this.mContext.createPackageContext(this.mDriverPackageName, 4);
                assetToSettingsGlobal(this.mContext, driverContext, GAME_DRIVER_WHITELIST_FILENAME, "game_driver_whitelist", ",");
            } catch (PackageManager.NameNotFoundException e) {
            }
        } catch (PackageManager.NameNotFoundException e2) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void processBlacklists() {
        String base64String = DeviceConfig.getProperty("game_driver", "game_driver_blacklists");
        if (base64String == null) {
            base64String = Settings.Global.getString(this.mContentResolver, "game_driver_blacklists");
        }
        parseBlacklists(base64String != null ? base64String : "");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void parseBlacklists(String base64String) {
        synchronized (this.mLock) {
            this.mBlacklists = null;
            try {
                this.mBlacklists = GameDriverProto.Blacklists.parseFrom(Base64.decode(base64String, 3));
            } catch (IllegalArgumentException e) {
            } catch (InvalidProtocolBufferException e2) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setBlacklist() {
        Settings.Global.putString(this.mContentResolver, "game_driver_blacklist", "");
        synchronized (this.mLock) {
            if (this.mBlacklists == null) {
                return;
            }
            List<GameDriverProto.Blacklist> blacklists = this.mBlacklists.getBlacklistsList();
            for (GameDriverProto.Blacklist blacklist : blacklists) {
                if (blacklist.getVersionCode() == this.mGameDriverVersionCode) {
                    Settings.Global.putString(this.mContentResolver, "game_driver_blacklist", String.join(",", blacklist.getPackageNamesList()));
                    return;
                }
            }
        }
    }
}
