package com.android.server.usb;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.hardware.usb.AccessoryFilter;
import android.hardware.usb.DeviceFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.Immutable;
import com.android.internal.app.IntentForwarderActivity;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.pm.PackageManagerService;
import com.android.server.slice.SliceClientPermissions;
import com.android.server.usb.MtpNotificationManager;
import com.android.server.usb.descriptors.UsbDescriptorParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class UsbProfileGroupSettingsManager {
    private static final boolean DEBUG = false;
    private static final String TAG = UsbProfileGroupSettingsManager.class.getSimpleName();
    private static final File sSingleUserSettingsFile = new File("/data/system/usb_device_manager.xml");
    private final Context mContext;
    private final boolean mDisablePermissionDialogs;
    @GuardedBy({"mLock"})
    private boolean mIsWriteSettingsScheduled;
    private final MtpNotificationManager mMtpNotificationManager;
    private final PackageManager mPackageManager;
    private final UserHandle mParentUser;
    private final AtomicFile mSettingsFile;
    private final UsbSettingsManager mSettingsManager;
    private final UsbHandlerManager mUsbHandlerManager;
    private final UserManager mUserManager;
    @GuardedBy({"mLock"})
    private final HashMap<DeviceFilter, UserPackage> mDevicePreferenceMap = new HashMap<>();
    @GuardedBy({"mLock"})
    private final HashMap<AccessoryFilter, UserPackage> mAccessoryPreferenceMap = new HashMap<>();
    private final Object mLock = new Object();
    MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

    /* JADX INFO: Access modifiers changed from: private */
    @Immutable
    /* loaded from: classes2.dex */
    public static class UserPackage {
        final String packageName;
        final UserHandle user;

        private UserPackage(String packageName, UserHandle user) {
            this.packageName = packageName;
            this.user = user;
        }

        public boolean equals(Object obj) {
            if (obj instanceof UserPackage) {
                UserPackage other = (UserPackage) obj;
                return this.user.equals(other.user) && this.packageName.equals(other.packageName);
            }
            return false;
        }

        public int hashCode() {
            int result = this.user.hashCode();
            return (result * 31) + this.packageName.hashCode();
        }

        public String toString() {
            return this.user.getIdentifier() + SliceClientPermissions.SliceAuthority.DELIMITER + this.packageName;
        }

        public void dump(DualDumpOutputStream dump, String idName, long id) {
            long token = dump.start(idName, id);
            dump.write("user_id", 1120986464257L, this.user.getIdentifier());
            dump.write("package_name", 1138166333442L, this.packageName);
            dump.end(token);
        }
    }

    /* loaded from: classes2.dex */
    private class MyPackageMonitor extends PackageMonitor {
        private MyPackageMonitor() {
        }

        public void onPackageAdded(String packageName, int uid) {
            if (UsbProfileGroupSettingsManager.this.mUserManager.isSameProfileGroup(UsbProfileGroupSettingsManager.this.mParentUser.getIdentifier(), UserHandle.getUserId(uid))) {
                UsbProfileGroupSettingsManager.this.handlePackageAdded(new UserPackage(packageName, UserHandle.getUserHandleForUid(uid)));
            }
        }

        public void onPackageRemoved(String packageName, int uid) {
            if (!UsbProfileGroupSettingsManager.this.mUserManager.isSameProfileGroup(UsbProfileGroupSettingsManager.this.mParentUser.getIdentifier(), UserHandle.getUserId(uid))) {
                return;
            }
            UsbProfileGroupSettingsManager.this.clearDefaults(packageName, UserHandle.getUserHandleForUid(uid));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public UsbProfileGroupSettingsManager(Context context, UserHandle user, UsbSettingsManager settingsManager, UsbHandlerManager usbResolveActivityManager) {
        try {
            Context parentUserContext = context.createPackageContextAsUser(PackageManagerService.PLATFORM_PACKAGE_NAME, 0, user);
            this.mContext = context;
            this.mPackageManager = context.getPackageManager();
            this.mSettingsManager = settingsManager;
            this.mUserManager = (UserManager) context.getSystemService("user");
            this.mParentUser = user;
            this.mSettingsFile = new AtomicFile(new File(Environment.getUserSystemDirectory(user.getIdentifier()), "usb_device_manager.xml"), "usb-state");
            this.mDisablePermissionDialogs = context.getResources().getBoolean(17891410);
            synchronized (this.mLock) {
                if (UserHandle.SYSTEM.equals(user)) {
                    upgradeSingleUserLocked();
                }
                readSettingsLocked();
            }
            this.mPackageMonitor.register(context, null, UserHandle.ALL, true);
            this.mMtpNotificationManager = new MtpNotificationManager(parentUserContext, new MtpNotificationManager.OnOpenInAppListener() { // from class: com.android.server.usb.-$$Lambda$UsbProfileGroupSettingsManager$IQKTzU0q3lyaW9nLL_sbxJPW8ME
                @Override // com.android.server.usb.MtpNotificationManager.OnOpenInAppListener
                public final void onOpenInApp(UsbDevice usbDevice) {
                    UsbProfileGroupSettingsManager.this.lambda$new$0$UsbProfileGroupSettingsManager(usbDevice);
                }
            });
            this.mUsbHandlerManager = usbResolveActivityManager;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Missing android package");
        }
    }

    public /* synthetic */ void lambda$new$0$UsbProfileGroupSettingsManager(UsbDevice device) {
        resolveActivity(createDeviceAttachedIntent(device), device, false);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeAllDefaultsForUser(UserHandle userToRemove) {
        synchronized (this.mLock) {
            boolean needToPersist = false;
            Iterator<Map.Entry<DeviceFilter, UserPackage>> devicePreferenceIt = this.mDevicePreferenceMap.entrySet().iterator();
            while (devicePreferenceIt.hasNext()) {
                Map.Entry<DeviceFilter, UserPackage> entry = devicePreferenceIt.next();
                if (entry.getValue().user.equals(userToRemove)) {
                    devicePreferenceIt.remove();
                    needToPersist = true;
                }
            }
            Iterator<Map.Entry<AccessoryFilter, UserPackage>> accessoryPreferenceIt = this.mAccessoryPreferenceMap.entrySet().iterator();
            while (accessoryPreferenceIt.hasNext()) {
                Map.Entry<AccessoryFilter, UserPackage> entry2 = accessoryPreferenceIt.next();
                if (entry2.getValue().user.equals(userToRemove)) {
                    accessoryPreferenceIt.remove();
                    needToPersist = true;
                }
            }
            if (needToPersist) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    private void readPreference(XmlPullParser parser) throws XmlPullParserException, IOException {
        String packageName = null;
        UserHandle user = this.mParentUser;
        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if ("package".equals(parser.getAttributeName(i))) {
                packageName = parser.getAttributeValue(i);
            }
            if ("user".equals(parser.getAttributeName(i))) {
                user = this.mUserManager.getUserForSerialNumber(Integer.parseInt(parser.getAttributeValue(i)));
            }
        }
        XmlUtils.nextElement(parser);
        if ("usb-device".equals(parser.getName())) {
            DeviceFilter filter = DeviceFilter.read(parser);
            if (user != null) {
                this.mDevicePreferenceMap.put(filter, new UserPackage(packageName, user));
            }
        } else if ("usb-accessory".equals(parser.getName())) {
            AccessoryFilter filter2 = AccessoryFilter.read(parser);
            if (user != null) {
                this.mAccessoryPreferenceMap.put(filter2, new UserPackage(packageName, user));
            }
        }
        XmlUtils.nextElement(parser);
    }

    @GuardedBy({"mLock"})
    private void upgradeSingleUserLocked() {
        if (sSingleUserSettingsFile.exists()) {
            this.mDevicePreferenceMap.clear();
            this.mAccessoryPreferenceMap.clear();
            FileInputStream fis = null;
            try {
                try {
                    fis = new FileInputStream(sSingleUserSettingsFile);
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(fis, StandardCharsets.UTF_8.name());
                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != 1) {
                        String tagName = parser.getName();
                        if ("preference".equals(tagName)) {
                            readPreference(parser);
                        } else {
                            XmlUtils.nextElement(parser);
                        }
                    }
                } catch (IOException | XmlPullParserException e) {
                    Log.wtf(TAG, "Failed to read single-user settings", e);
                }
                IoUtils.closeQuietly(fis);
                scheduleWriteSettingsLocked();
                sSingleUserSettingsFile.delete();
            } catch (Throwable th) {
                IoUtils.closeQuietly(fis);
                throw th;
            }
        }
    }

    @GuardedBy({"mLock"})
    private void readSettingsLocked() {
        this.mDevicePreferenceMap.clear();
        this.mAccessoryPreferenceMap.clear();
        FileInputStream stream = null;
        try {
            try {
                stream = this.mSettingsFile.openRead();
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream, StandardCharsets.UTF_8.name());
                XmlUtils.nextElement(parser);
                while (parser.getEventType() != 1) {
                    String tagName = parser.getName();
                    if ("preference".equals(tagName)) {
                        readPreference(parser);
                    } else {
                        XmlUtils.nextElement(parser);
                    }
                }
            } catch (FileNotFoundException e) {
            } catch (Exception e2) {
                Slog.e(TAG, "error reading settings file, deleting to start fresh", e2);
                this.mSettingsFile.delete();
            }
        } finally {
            IoUtils.closeQuietly(stream);
        }
    }

    @GuardedBy({"mLock"})
    private void scheduleWriteSettingsLocked() {
        if (this.mIsWriteSettingsScheduled) {
            return;
        }
        this.mIsWriteSettingsScheduled = true;
        AsyncTask.execute(new Runnable() { // from class: com.android.server.usb.-$$Lambda$UsbProfileGroupSettingsManager$_G1PjxMa22pAIRMzYCwyomX8uhk
            @Override // java.lang.Runnable
            public final void run() {
                UsbProfileGroupSettingsManager.this.lambda$scheduleWriteSettingsLocked$1$UsbProfileGroupSettingsManager();
            }
        });
    }

    public /* synthetic */ void lambda$scheduleWriteSettingsLocked$1$UsbProfileGroupSettingsManager() {
        synchronized (this.mLock) {
            FileOutputStream fos = null;
            try {
                fos = this.mSettingsFile.startWrite();
                FastXmlSerializer serializer = new FastXmlSerializer();
                serializer.setOutput(fos, StandardCharsets.UTF_8.name());
                serializer.startDocument((String) null, true);
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                serializer.startTag((String) null, "settings");
                for (DeviceFilter filter : this.mDevicePreferenceMap.keySet()) {
                    serializer.startTag((String) null, "preference");
                    serializer.attribute((String) null, "package", this.mDevicePreferenceMap.get(filter).packageName);
                    serializer.attribute((String) null, "user", String.valueOf(getSerial(this.mDevicePreferenceMap.get(filter).user)));
                    filter.write(serializer);
                    serializer.endTag((String) null, "preference");
                }
                for (AccessoryFilter filter2 : this.mAccessoryPreferenceMap.keySet()) {
                    serializer.startTag((String) null, "preference");
                    serializer.attribute((String) null, "package", this.mAccessoryPreferenceMap.get(filter2).packageName);
                    serializer.attribute((String) null, "user", String.valueOf(getSerial(this.mAccessoryPreferenceMap.get(filter2).user)));
                    filter2.write(serializer);
                    serializer.endTag((String) null, "preference");
                }
                serializer.endTag((String) null, "settings");
                serializer.endDocument();
                this.mSettingsFile.finishWrite(fos);
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write settings", e);
                if (fos != null) {
                    this.mSettingsFile.failWrite(fos);
                }
            }
            this.mIsWriteSettingsScheduled = false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:26:0x0076, code lost:
        if (0 == 0) goto L25;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static java.util.ArrayList<android.hardware.usb.DeviceFilter> getDeviceFilters(android.content.pm.PackageManager r7, android.content.pm.ResolveInfo r8) {
        /*
            r0 = 0
            android.content.pm.ActivityInfo r1 = r8.activityInfo
            r2 = 0
            java.lang.String r3 = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
            android.content.res.XmlResourceParser r3 = r1.loadXmlMetaData(r7, r3)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r2 = r3
            if (r2 != 0) goto L2a
            java.lang.String r3 = com.android.server.usb.UsbProfileGroupSettingsManager.TAG     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r4.<init>()     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            java.lang.String r5 = "no meta-data for "
            r4.append(r5)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r4.append(r8)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            android.util.Slog.w(r3, r4)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r3 = 0
            if (r2 == 0) goto L29
            r2.close()
        L29:
            return r3
        L2a:
            com.android.internal.util.XmlUtils.nextElement(r2)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
        L2d:
            int r3 = r2.getEventType()     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r4 = 1
            if (r3 == r4) goto L53
            java.lang.String r3 = r2.getName()     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            java.lang.String r5 = "usb-device"
            boolean r5 = r5.equals(r3)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            if (r5 == 0) goto L4f
            if (r0 != 0) goto L48
            java.util.ArrayList r5 = new java.util.ArrayList     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r5.<init>(r4)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r0 = r5
        L48:
            android.hardware.usb.DeviceFilter r4 = android.hardware.usb.DeviceFilter.read(r2)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r0.add(r4)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
        L4f:
            com.android.internal.util.XmlUtils.nextElement(r2)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            goto L2d
        L53:
        L54:
            r2.close()
            goto L79
        L58:
            r3 = move-exception
            goto L7a
        L5a:
            r3 = move-exception
            java.lang.String r4 = com.android.server.usb.UsbProfileGroupSettingsManager.TAG     // Catch: java.lang.Throwable -> L58
            java.lang.StringBuilder r5 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L58
            r5.<init>()     // Catch: java.lang.Throwable -> L58
            java.lang.String r6 = "Unable to load component info "
            r5.append(r6)     // Catch: java.lang.Throwable -> L58
            java.lang.String r6 = r8.toString()     // Catch: java.lang.Throwable -> L58
            r5.append(r6)     // Catch: java.lang.Throwable -> L58
            java.lang.String r5 = r5.toString()     // Catch: java.lang.Throwable -> L58
            android.util.Slog.w(r4, r5, r3)     // Catch: java.lang.Throwable -> L58
            if (r2 == 0) goto L79
            goto L54
        L79:
            return r0
        L7a:
            if (r2 == 0) goto L7f
            r2.close()
        L7f:
            throw r3
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbProfileGroupSettingsManager.getDeviceFilters(android.content.pm.PackageManager, android.content.pm.ResolveInfo):java.util.ArrayList");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Code restructure failed: missing block: B:26:0x0076, code lost:
        if (0 == 0) goto L25;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static java.util.ArrayList<android.hardware.usb.AccessoryFilter> getAccessoryFilters(android.content.pm.PackageManager r7, android.content.pm.ResolveInfo r8) {
        /*
            r0 = 0
            android.content.pm.ActivityInfo r1 = r8.activityInfo
            r2 = 0
            java.lang.String r3 = "android.hardware.usb.action.USB_ACCESSORY_ATTACHED"
            android.content.res.XmlResourceParser r3 = r1.loadXmlMetaData(r7, r3)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r2 = r3
            if (r2 != 0) goto L2a
            java.lang.String r3 = com.android.server.usb.UsbProfileGroupSettingsManager.TAG     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r4.<init>()     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            java.lang.String r5 = "no meta-data for "
            r4.append(r5)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r4.append(r8)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            android.util.Slog.w(r3, r4)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r3 = 0
            if (r2 == 0) goto L29
            r2.close()
        L29:
            return r3
        L2a:
            com.android.internal.util.XmlUtils.nextElement(r2)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
        L2d:
            int r3 = r2.getEventType()     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r4 = 1
            if (r3 == r4) goto L53
            java.lang.String r3 = r2.getName()     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            java.lang.String r5 = "usb-accessory"
            boolean r5 = r5.equals(r3)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            if (r5 == 0) goto L4f
            if (r0 != 0) goto L48
            java.util.ArrayList r5 = new java.util.ArrayList     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r5.<init>(r4)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r0 = r5
        L48:
            android.hardware.usb.AccessoryFilter r4 = android.hardware.usb.AccessoryFilter.read(r2)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            r0.add(r4)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
        L4f:
            com.android.internal.util.XmlUtils.nextElement(r2)     // Catch: java.lang.Throwable -> L58 java.lang.Exception -> L5a
            goto L2d
        L53:
        L54:
            r2.close()
            goto L79
        L58:
            r3 = move-exception
            goto L7a
        L5a:
            r3 = move-exception
            java.lang.String r4 = com.android.server.usb.UsbProfileGroupSettingsManager.TAG     // Catch: java.lang.Throwable -> L58
            java.lang.StringBuilder r5 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L58
            r5.<init>()     // Catch: java.lang.Throwable -> L58
            java.lang.String r6 = "Unable to load component info "
            r5.append(r6)     // Catch: java.lang.Throwable -> L58
            java.lang.String r6 = r8.toString()     // Catch: java.lang.Throwable -> L58
            r5.append(r6)     // Catch: java.lang.Throwable -> L58
            java.lang.String r5 = r5.toString()     // Catch: java.lang.Throwable -> L58
            android.util.Slog.w(r4, r5, r3)     // Catch: java.lang.Throwable -> L58
            if (r2 == 0) goto L79
            goto L54
        L79:
            return r0
        L7a:
            if (r2 == 0) goto L7f
            r2.close()
        L7f:
            throw r3
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbProfileGroupSettingsManager.getAccessoryFilters(android.content.pm.PackageManager, android.content.pm.ResolveInfo):java.util.ArrayList");
    }

    private boolean packageMatchesLocked(ResolveInfo info, UsbDevice device, UsbAccessory accessory) {
        ArrayList<AccessoryFilter> accessoryFilters;
        ArrayList<DeviceFilter> deviceFilters;
        if (isForwardMatch(info)) {
            return true;
        }
        if (device != null && (deviceFilters = getDeviceFilters(this.mPackageManager, info)) != null) {
            int numDeviceFilters = deviceFilters.size();
            for (int i = 0; i < numDeviceFilters; i++) {
                if (deviceFilters.get(i).matches(device)) {
                    return true;
                }
            }
        }
        if (accessory != null && (accessoryFilters = getAccessoryFilters(this.mPackageManager, info)) != null) {
            int numAccessoryFilters = accessoryFilters.size();
            for (int i2 = 0; i2 < numAccessoryFilters; i2++) {
                if (accessoryFilters.get(i2).matches(accessory)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private ArrayList<ResolveInfo> queryIntentActivitiesForAllProfiles(Intent intent) {
        List<UserInfo> profiles = this.mUserManager.getEnabledProfiles(this.mParentUser.getIdentifier());
        ArrayList<ResolveInfo> resolveInfos = new ArrayList<>();
        int numProfiles = profiles.size();
        for (int i = 0; i < numProfiles; i++) {
            resolveInfos.addAll(this.mSettingsManager.getSettingsForUser(profiles.get(i).id).queryIntentActivities(intent));
        }
        return resolveInfos;
    }

    private boolean isForwardMatch(ResolveInfo match) {
        return match.getComponentInfo().name.equals(IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE);
    }

    private ArrayList<ResolveInfo> preferHighPriority(ArrayList<ResolveInfo> matches) {
        SparseArray<ArrayList<ResolveInfo>> highestPriorityMatchesByUserId = new SparseArray<>();
        SparseIntArray highestPriorityByUserId = new SparseIntArray();
        ArrayList<ResolveInfo> forwardMatches = new ArrayList<>();
        int numMatches = matches.size();
        for (int matchNum = 0; matchNum < numMatches; matchNum++) {
            ResolveInfo match = matches.get(matchNum);
            if (isForwardMatch(match)) {
                forwardMatches.add(match);
            } else {
                if (highestPriorityByUserId.indexOfKey(match.targetUserId) < 0) {
                    highestPriorityByUserId.put(match.targetUserId, Integer.MIN_VALUE);
                    highestPriorityMatchesByUserId.put(match.targetUserId, new ArrayList<>());
                }
                int highestPriority = highestPriorityByUserId.get(match.targetUserId);
                ArrayList<ResolveInfo> highestPriorityMatches = highestPriorityMatchesByUserId.get(match.targetUserId);
                if (match.priority == highestPriority) {
                    highestPriorityMatches.add(match);
                } else if (match.priority > highestPriority) {
                    highestPriorityByUserId.put(match.targetUserId, match.priority);
                    highestPriorityMatches.clear();
                    highestPriorityMatches.add(match);
                }
            }
        }
        ArrayList<ResolveInfo> combinedMatches = new ArrayList<>(forwardMatches);
        int numMatchArrays = highestPriorityMatchesByUserId.size();
        for (int matchArrayNum = 0; matchArrayNum < numMatchArrays; matchArrayNum++) {
            combinedMatches.addAll(highestPriorityMatchesByUserId.valueAt(matchArrayNum));
        }
        return combinedMatches;
    }

    private ArrayList<ResolveInfo> removeForwardIntentIfNotNeeded(ArrayList<ResolveInfo> rawMatches) {
        int numRawMatches = rawMatches.size();
        int numParentActivityMatches = 0;
        int numNonParentActivityMatches = 0;
        for (int i = 0; i < numRawMatches; i++) {
            ResolveInfo rawMatch = rawMatches.get(i);
            if (!isForwardMatch(rawMatch)) {
                if (UserHandle.getUserHandleForUid(rawMatch.activityInfo.applicationInfo.uid).equals(this.mParentUser)) {
                    numParentActivityMatches++;
                } else {
                    numNonParentActivityMatches++;
                }
            }
        }
        if (numParentActivityMatches == 0 || numNonParentActivityMatches == 0) {
            ArrayList<ResolveInfo> matches = new ArrayList<>(numParentActivityMatches + numNonParentActivityMatches);
            for (int i2 = 0; i2 < numRawMatches; i2++) {
                ResolveInfo rawMatch2 = rawMatches.get(i2);
                if (!isForwardMatch(rawMatch2)) {
                    matches.add(rawMatch2);
                }
            }
            return matches;
        }
        return rawMatches;
    }

    private ArrayList<ResolveInfo> getDeviceMatchesLocked(UsbDevice device, Intent intent) {
        ArrayList<ResolveInfo> matches = new ArrayList<>();
        List<ResolveInfo> resolveInfos = queryIntentActivitiesForAllProfiles(intent);
        int count = resolveInfos.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (packageMatchesLocked(resolveInfo, device, null)) {
                matches.add(resolveInfo);
            }
        }
        return removeForwardIntentIfNotNeeded(preferHighPriority(matches));
    }

    private ArrayList<ResolveInfo> getAccessoryMatchesLocked(UsbAccessory accessory, Intent intent) {
        ArrayList<ResolveInfo> matches = new ArrayList<>();
        List<ResolveInfo> resolveInfos = queryIntentActivitiesForAllProfiles(intent);
        int count = resolveInfos.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (packageMatchesLocked(resolveInfo, null, accessory)) {
                matches.add(resolveInfo);
            }
        }
        return removeForwardIntentIfNotNeeded(preferHighPriority(matches));
    }

    public void deviceAttached(UsbDevice device) {
        Intent intent = createDeviceAttachedIntent(device);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        resolveActivity(intent, device, true);
    }

    public void deviceAttachedForStorage(UsbDevice device, String deviceAddress, byte[] descriptors) {
        Intent intent = createDeviceAttachedIntentForStorage(device, deviceAddress, descriptors);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        resolveActivity(intent, device, true);
    }

    private void resolveActivity(Intent intent, UsbDevice device, boolean showMtpNotification) {
        ArrayList<ResolveInfo> matches;
        ActivityInfo defaultActivity;
        synchronized (this.mLock) {
            matches = getDeviceMatchesLocked(device, intent);
            defaultActivity = getDefaultActivityLocked(matches, this.mDevicePreferenceMap.get(new DeviceFilter(device)));
        }
        if (showMtpNotification && MtpNotificationManager.shouldShowNotification(this.mPackageManager, device) && defaultActivity == null) {
            this.mMtpNotificationManager.showNotification(device);
        } else {
            resolveActivity(intent, matches, defaultActivity, device, null);
        }
    }

    public void deviceAttachedForFixedHandler(UsbDevice device, ComponentName component) {
        Intent intent = createDeviceAttachedIntent(device);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.of(ActivityManager.getCurrentUser()));
        try {
            ApplicationInfo appInfo = this.mPackageManager.getApplicationInfoAsUser(component.getPackageName(), 0, this.mParentUser.getIdentifier());
            this.mSettingsManager.getSettingsForUser(UserHandle.getUserId(appInfo.uid)).grantDevicePermission(device, appInfo.uid);
            Intent activityIntent = new Intent(intent);
            activityIntent.setComponent(component);
            try {
                this.mContext.startActivityAsUser(activityIntent, this.mParentUser);
            } catch (ActivityNotFoundException e) {
                String str = TAG;
                Slog.e(str, "unable to start activity " + activityIntent);
            }
        } catch (PackageManager.NameNotFoundException e2) {
            String str2 = TAG;
            Slog.e(str2, "Default USB handling package (" + component.getPackageName() + ") not found  for user " + this.mParentUser);
        }
    }

    public void deviceAttachedForFixedHandlerForStorage(UsbDevice device, ComponentName component, String deviceAddress, byte[] descriptors) {
        Intent intent = createDeviceAttachedIntentForStorage(device, deviceAddress, descriptors);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.of(ActivityManager.getCurrentUser()));
        try {
            ApplicationInfo appInfo = this.mPackageManager.getApplicationInfoAsUser(component.getPackageName(), 0, this.mParentUser.getIdentifier());
            this.mSettingsManager.getSettingsForUser(UserHandle.getUserId(appInfo.uid)).grantDevicePermission(device, appInfo.uid);
            Intent activityIntent = new Intent(intent);
            activityIntent.setComponent(component);
            try {
                this.mContext.startActivityAsUser(activityIntent, this.mParentUser);
            } catch (ActivityNotFoundException e) {
                String str = TAG;
                Slog.e(str, "unable to start activity " + activityIntent);
            }
        } catch (PackageManager.NameNotFoundException e2) {
            String str2 = TAG;
            Slog.e(str2, "Default USB handling package (" + component.getPackageName() + ") not found  for user " + this.mParentUser);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void usbDeviceRemoved(UsbDevice device) {
        this.mMtpNotificationManager.hideNotification(device.getDeviceId());
    }

    public void accessoryAttached(UsbAccessory accessory) {
        ArrayList<ResolveInfo> matches;
        ActivityInfo defaultActivity;
        Intent intent = new Intent("android.hardware.usb.action.USB_ACCESSORY_ATTACHED");
        intent.putExtra("accessory", accessory);
        intent.addFlags(285212672);
        synchronized (this.mLock) {
            matches = getAccessoryMatchesLocked(accessory, intent);
            defaultActivity = getDefaultActivityLocked(matches, this.mAccessoryPreferenceMap.get(new AccessoryFilter(accessory)));
        }
        resolveActivity(intent, matches, defaultActivity, null, accessory);
    }

    private void resolveActivity(Intent intent, ArrayList<ResolveInfo> matches, ActivityInfo defaultActivity, UsbDevice device, UsbAccessory accessory) {
        if (matches.size() == 0) {
            if (accessory != null) {
                this.mUsbHandlerManager.showUsbAccessoryUriActivity(accessory, this.mParentUser);
            }
        } else if (defaultActivity != null) {
            UsbUserSettingsManager defaultRIUserSettings = this.mSettingsManager.getSettingsForUser(UserHandle.getUserId(defaultActivity.applicationInfo.uid));
            if (device != null) {
                defaultRIUserSettings.grantDevicePermission(device, defaultActivity.applicationInfo.uid);
            } else if (accessory != null) {
                defaultRIUserSettings.grantAccessoryPermission(accessory, defaultActivity.applicationInfo.uid);
            }
            try {
                intent.setComponent(new ComponentName(defaultActivity.packageName, defaultActivity.name));
                UserHandle user = UserHandle.getUserHandleForUid(defaultActivity.applicationInfo.uid);
                this.mContext.startActivityAsUser(intent, user);
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "startActivity failed", e);
            }
        } else if (matches.size() == 1) {
            this.mUsbHandlerManager.confirmUsbHandler(matches.get(0), device, accessory);
        } else {
            this.mUsbHandlerManager.selectUsbHandler(matches, this.mParentUser, intent);
        }
    }

    private ActivityInfo getDefaultActivityLocked(ArrayList<ResolveInfo> matches, UserPackage userPackage) {
        ActivityInfo activityInfo;
        if (userPackage != null) {
            Iterator<ResolveInfo> it = matches.iterator();
            while (it.hasNext()) {
                ResolveInfo info = it.next();
                if (info.activityInfo != null && userPackage.equals(new UserPackage(info.activityInfo.packageName, UserHandle.getUserHandleForUid(info.activityInfo.applicationInfo.uid)))) {
                    return info.activityInfo;
                }
            }
        }
        if (matches.size() == 1 && (activityInfo = matches.get(0).activityInfo) != null) {
            if (this.mDisablePermissionDialogs) {
                return activityInfo;
            }
            if (activityInfo.applicationInfo != null && (1 & activityInfo.applicationInfo.flags) != 0) {
                return activityInfo;
            }
        }
        return null;
    }

    @GuardedBy({"mLock"})
    private boolean clearCompatibleMatchesLocked(UserPackage userPackage, DeviceFilter filter) {
        ArrayList<DeviceFilter> keysToRemove = new ArrayList<>();
        for (DeviceFilter device : this.mDevicePreferenceMap.keySet()) {
            if (filter.contains(device)) {
                UserPackage currentMatch = this.mDevicePreferenceMap.get(device);
                if (!currentMatch.equals(userPackage)) {
                    keysToRemove.add(device);
                }
            }
        }
        if (!keysToRemove.isEmpty()) {
            Iterator<DeviceFilter> it = keysToRemove.iterator();
            while (it.hasNext()) {
                DeviceFilter keyToRemove = it.next();
                this.mDevicePreferenceMap.remove(keyToRemove);
            }
        }
        return !keysToRemove.isEmpty();
    }

    @GuardedBy({"mLock"})
    private boolean clearCompatibleMatchesLocked(UserPackage userPackage, AccessoryFilter filter) {
        ArrayList<AccessoryFilter> keysToRemove = new ArrayList<>();
        for (AccessoryFilter accessory : this.mAccessoryPreferenceMap.keySet()) {
            if (filter.contains(accessory)) {
                UserPackage currentMatch = this.mAccessoryPreferenceMap.get(accessory);
                if (!currentMatch.equals(userPackage)) {
                    keysToRemove.add(accessory);
                }
            }
        }
        if (!keysToRemove.isEmpty()) {
            Iterator<AccessoryFilter> it = keysToRemove.iterator();
            while (it.hasNext()) {
                AccessoryFilter keyToRemove = it.next();
                this.mAccessoryPreferenceMap.remove(keyToRemove);
            }
        }
        return !keysToRemove.isEmpty();
    }

    /* JADX WARN: Code restructure failed: missing block: B:32:0x006e, code lost:
        if (r0 == null) goto L32;
     */
    @com.android.internal.annotations.GuardedBy({"mLock"})
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private boolean handlePackageAddedLocked(com.android.server.usb.UsbProfileGroupSettingsManager.UserPackage r7, android.content.pm.ActivityInfo r8, java.lang.String r9) {
        /*
            r6 = this;
            r0 = 0
            r1 = 0
            android.content.pm.PackageManager r2 = r6.mPackageManager     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            android.content.res.XmlResourceParser r2 = r8.loadXmlMetaData(r2, r9)     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            r0 = r2
            if (r0 != 0) goto L12
            r2 = 0
            if (r0 == 0) goto L11
            r0.close()
        L11:
            return r2
        L12:
            com.android.internal.util.XmlUtils.nextElement(r0)     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
        L15:
            int r2 = r0.getEventType()     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            r3 = 1
            if (r2 == r3) goto L4b
            java.lang.String r2 = r0.getName()     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            java.lang.String r3 = "usb-device"
            boolean r3 = r3.equals(r2)     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            if (r3 == 0) goto L34
            android.hardware.usb.DeviceFilter r3 = android.hardware.usb.DeviceFilter.read(r0)     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            boolean r4 = r6.clearCompatibleMatchesLocked(r7, r3)     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            if (r4 == 0) goto L33
            r1 = 1
        L33:
            goto L47
        L34:
            java.lang.String r3 = "usb-accessory"
            boolean r3 = r3.equals(r2)     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            if (r3 == 0) goto L47
            android.hardware.usb.AccessoryFilter r3 = android.hardware.usb.AccessoryFilter.read(r0)     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            boolean r4 = r6.clearCompatibleMatchesLocked(r7, r3)     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            if (r4 == 0) goto L47
            r1 = 1
        L47:
            com.android.internal.util.XmlUtils.nextElement(r0)     // Catch: java.lang.Throwable -> L50 java.lang.Exception -> L52
            goto L15
        L4b:
        L4c:
            r0.close()
            goto L71
        L50:
            r2 = move-exception
            goto L72
        L52:
            r2 = move-exception
            java.lang.String r3 = com.android.server.usb.UsbProfileGroupSettingsManager.TAG     // Catch: java.lang.Throwable -> L50
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L50
            r4.<init>()     // Catch: java.lang.Throwable -> L50
            java.lang.String r5 = "Unable to load component info "
            r4.append(r5)     // Catch: java.lang.Throwable -> L50
            java.lang.String r5 = r8.toString()     // Catch: java.lang.Throwable -> L50
            r4.append(r5)     // Catch: java.lang.Throwable -> L50
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L50
            android.util.Slog.w(r3, r4, r2)     // Catch: java.lang.Throwable -> L50
            if (r0 == 0) goto L71
            goto L4c
        L71:
            return r1
        L72:
            if (r0 == 0) goto L77
            r0.close()
        L77:
            throw r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbProfileGroupSettingsManager.handlePackageAddedLocked(com.android.server.usb.UsbProfileGroupSettingsManager$UserPackage, android.content.pm.ActivityInfo, java.lang.String):boolean");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackageAdded(UserPackage userPackage) {
        synchronized (this.mLock) {
            boolean changed = false;
            try {
                try {
                    PackageInfo info = this.mPackageManager.getPackageInfoAsUser(userPackage.packageName, 129, userPackage.user.getIdentifier());
                    ActivityInfo[] activities = info.activities;
                    if (activities == null) {
                        return;
                    }
                    for (int i = 0; i < activities.length; i++) {
                        if (handlePackageAddedLocked(userPackage, activities[i], "android.hardware.usb.action.USB_DEVICE_ATTACHED")) {
                            changed = true;
                        }
                        if (handlePackageAddedLocked(userPackage, activities[i], "android.hardware.usb.action.USB_ACCESSORY_ATTACHED")) {
                            changed = true;
                        }
                    }
                    if (changed) {
                        scheduleWriteSettingsLocked();
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    String str = TAG;
                    Slog.e(str, "handlePackageUpdate could not find package " + userPackage, e);
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    private int getSerial(UserHandle user) {
        return this.mUserManager.getUserSerialNumber(user.getIdentifier());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDevicePackage(UsbDevice device, String packageName, UserHandle user) {
        DeviceFilter filter = new DeviceFilter(device);
        synchronized (this.mLock) {
            boolean changed = true;
            if (packageName == null) {
                if (this.mDevicePreferenceMap.remove(filter) == null) {
                    changed = false;
                }
            } else {
                UserPackage userPackage = new UserPackage(packageName, user);
                changed = true ^ userPackage.equals(this.mDevicePreferenceMap.get(filter));
                if (changed) {
                    this.mDevicePreferenceMap.put(filter, userPackage);
                }
            }
            if (changed) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAccessoryPackage(UsbAccessory accessory, String packageName, UserHandle user) {
        AccessoryFilter filter = new AccessoryFilter(accessory);
        synchronized (this.mLock) {
            boolean changed = true;
            if (packageName == null) {
                if (this.mAccessoryPreferenceMap.remove(filter) == null) {
                    changed = false;
                }
            } else {
                UserPackage userPackage = new UserPackage(packageName, user);
                changed = true ^ userPackage.equals(this.mAccessoryPreferenceMap.get(filter));
                if (changed) {
                    this.mAccessoryPreferenceMap.put(filter, userPackage);
                }
            }
            if (changed) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasDefaults(String packageName, UserHandle user) {
        UserPackage userPackage = new UserPackage(packageName, user);
        synchronized (this.mLock) {
            if (this.mDevicePreferenceMap.values().contains(userPackage)) {
                return true;
            }
            return this.mAccessoryPreferenceMap.values().contains(userPackage);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void clearDefaults(String packageName, UserHandle user) {
        UserPackage userPackage = new UserPackage(packageName, user);
        synchronized (this.mLock) {
            if (clearPackageDefaultsLocked(userPackage)) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    private boolean clearPackageDefaultsLocked(UserPackage userPackage) {
        boolean cleared = false;
        synchronized (this.mLock) {
            if (this.mDevicePreferenceMap.containsValue(userPackage)) {
                DeviceFilter[] keys = (DeviceFilter[]) this.mDevicePreferenceMap.keySet().toArray(new DeviceFilter[0]);
                for (DeviceFilter key : keys) {
                    if (userPackage.equals(this.mDevicePreferenceMap.get(key))) {
                        this.mDevicePreferenceMap.remove(key);
                        cleared = true;
                    }
                }
            }
            if (this.mAccessoryPreferenceMap.containsValue(userPackage)) {
                AccessoryFilter[] keys2 = (AccessoryFilter[]) this.mAccessoryPreferenceMap.keySet().toArray(new AccessoryFilter[0]);
                for (AccessoryFilter key2 : keys2) {
                    if (userPackage.equals(this.mAccessoryPreferenceMap.get(key2))) {
                        this.mAccessoryPreferenceMap.remove(key2);
                        cleared = true;
                    }
                }
            }
        }
        return cleared;
    }

    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);
        synchronized (this.mLock) {
            dump.write("parent_user_id", 1120986464257L, this.mParentUser.getIdentifier());
            for (DeviceFilter filter : this.mDevicePreferenceMap.keySet()) {
                long devicePrefToken = dump.start("device_preferences", 2246267895810L);
                filter.dump(dump, "filter", 1146756268033L);
                this.mDevicePreferenceMap.get(filter).dump(dump, "user_package", 1146756268034L);
                dump.end(devicePrefToken);
            }
            for (AccessoryFilter filter2 : this.mAccessoryPreferenceMap.keySet()) {
                long accessoryPrefToken = dump.start("accessory_preferences", 2246267895811L);
                filter2.dump(dump, "filter", 1146756268033L);
                this.mAccessoryPreferenceMap.get(filter2).dump(dump, "user_package", 1146756268034L);
                dump.end(accessoryPrefToken);
            }
        }
        dump.end(token);
    }

    private static Intent createDeviceAttachedIntent(UsbDevice device) {
        Intent intent = new Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED");
        intent.putExtra("device", device);
        intent.addFlags(285212672);
        return intent;
    }

    private static Intent createDeviceAttachedIntentForStorage(UsbDevice device, String deviceAddress, byte[] descriptors) {
        Intent intent = new Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED");
        intent.putExtra("device", device);
        UsbDescriptorParser parser = new UsbDescriptorParser(deviceAddress, descriptors);
        boolean hasStorage = parser.hasStorageInterface();
        if (hasStorage) {
            intent.addFlags(285212672);
        } else {
            intent.addFlags(285212673);
        }
        return intent;
    }
}
