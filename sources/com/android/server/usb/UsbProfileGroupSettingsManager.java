package com.android.server.usb;

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
import android.hardware.usb.UsbInterface;
import android.net.util.NetworkConstants;
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
import com.xiaopeng.server.aftersales.AfterSalesService;
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
/* loaded from: classes.dex */
public class UsbProfileGroupSettingsManager {
    private static final boolean DEBUG = false;
    public static final String SUPPORT_IFACE_UMS = "Mass Storage";
    public static final String UNSUPPORT_IFACE_MIDI = "MIDI function";
    public static final String UNSUPPORT_IFACE_MTP = "MTP";
    public static final String UNSUPPORT_IFACE_PTP = "PTP";
    private final Context mContext;
    private final boolean mDisablePermissionDialogs;
    @GuardedBy("mLock")
    private boolean mIsWriteSettingsScheduled;
    private final MtpNotificationManager mMtpNotificationManager;
    private final PackageManager mPackageManager;
    private final UserHandle mParentUser;
    private final AtomicFile mSettingsFile;
    private final UsbSettingsManager mSettingsManager;
    private final UserManager mUserManager;
    private static final String TAG = UsbProfileGroupSettingsManager.class.getSimpleName();
    private static final File sSingleUserSettingsFile = new File("/data/system/usb_device_manager.xml");
    @GuardedBy("mLock")
    private final HashMap<DeviceFilter, UserPackage> mDevicePreferenceMap = new HashMap<>();
    @GuardedBy("mLock")
    private final HashMap<AccessoryFilter, UserPackage> mAccessoryPreferenceMap = new HashMap<>();
    private final Object mLock = new Object();
    MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

    /* JADX INFO: Access modifiers changed from: private */
    @Immutable
    /* loaded from: classes.dex */
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
            return (31 * result) + this.packageName.hashCode();
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

    /* loaded from: classes.dex */
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
    public UsbProfileGroupSettingsManager(Context context, UserHandle user, UsbSettingsManager settingsManager) {
        try {
            Context parentUserContext = context.createPackageContextAsUser(PackageManagerService.PLATFORM_PACKAGE_NAME, 0, user);
            this.mContext = context;
            this.mPackageManager = context.getPackageManager();
            this.mSettingsManager = settingsManager;
            this.mUserManager = (UserManager) context.getSystemService("user");
            this.mParentUser = user;
            this.mSettingsFile = new AtomicFile(new File(Environment.getUserSystemDirectory(user.getIdentifier()), "usb_device_manager.xml"), "usb-state");
            this.mDisablePermissionDialogs = context.getResources().getBoolean(17956932);
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
                    UsbProfileGroupSettingsManager.this.resolveActivity(UsbProfileGroupSettingsManager.createDeviceAttachedIntent(usbDevice), usbDevice, false);
                }
            });
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Missing android package");
        }
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

    @GuardedBy("mLock")
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

    @GuardedBy("mLock")
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

    @GuardedBy("mLock")
    private void scheduleWriteSettingsLocked() {
        if (this.mIsWriteSettingsScheduled) {
            return;
        }
        this.mIsWriteSettingsScheduled = true;
        AsyncTask.execute(new Runnable() { // from class: com.android.server.usb.-$$Lambda$UsbProfileGroupSettingsManager$_G1PjxMa22pAIRMzYCwyomX8uhk
            @Override // java.lang.Runnable
            public final void run() {
                UsbProfileGroupSettingsManager.lambda$scheduleWriteSettingsLocked$1(UsbProfileGroupSettingsManager.this);
            }
        });
    }

    public static /* synthetic */ void lambda$scheduleWriteSettingsLocked$1(UsbProfileGroupSettingsManager usbProfileGroupSettingsManager) {
        synchronized (usbProfileGroupSettingsManager.mLock) {
            FileOutputStream fos = null;
            try {
                fos = usbProfileGroupSettingsManager.mSettingsFile.startWrite();
                FastXmlSerializer serializer = new FastXmlSerializer();
                serializer.setOutput(fos, StandardCharsets.UTF_8.name());
                serializer.startDocument((String) null, true);
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                serializer.startTag((String) null, "settings");
                for (DeviceFilter filter : usbProfileGroupSettingsManager.mDevicePreferenceMap.keySet()) {
                    serializer.startTag((String) null, "preference");
                    serializer.attribute((String) null, "package", usbProfileGroupSettingsManager.mDevicePreferenceMap.get(filter).packageName);
                    serializer.attribute((String) null, "user", String.valueOf(usbProfileGroupSettingsManager.getSerial(usbProfileGroupSettingsManager.mDevicePreferenceMap.get(filter).user)));
                    filter.write(serializer);
                    serializer.endTag((String) null, "preference");
                }
                for (AccessoryFilter filter2 : usbProfileGroupSettingsManager.mAccessoryPreferenceMap.keySet()) {
                    serializer.startTag((String) null, "preference");
                    serializer.attribute((String) null, "package", usbProfileGroupSettingsManager.mAccessoryPreferenceMap.get(filter2).packageName);
                    serializer.attribute((String) null, "user", String.valueOf(usbProfileGroupSettingsManager.getSerial(usbProfileGroupSettingsManager.mAccessoryPreferenceMap.get(filter2).user)));
                    filter2.write(serializer);
                    serializer.endTag((String) null, "preference");
                }
                serializer.endTag((String) null, "settings");
                serializer.endDocument();
                usbProfileGroupSettingsManager.mSettingsFile.finishWrite(fos);
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write settings", e);
                if (fos != null) {
                    usbProfileGroupSettingsManager.mSettingsFile.failWrite(fos);
                }
            }
            usbProfileGroupSettingsManager.mIsWriteSettingsScheduled = false;
        }
    }

    /* JADX WARN: Code restructure failed: missing block: B:35:0x007d, code lost:
        if (r2 != null) goto L42;
     */
    /* JADX WARN: Code restructure failed: missing block: B:36:0x007f, code lost:
        r2.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:41:0x00a0, code lost:
        if (0 == 0) goto L43;
     */
    /* JADX WARN: Code restructure failed: missing block: B:43:0x00a3, code lost:
        return false;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private boolean packageMatchesLocked(android.content.pm.ResolveInfo r8, java.lang.String r9, android.hardware.usb.UsbDevice r10, android.hardware.usb.UsbAccessory r11) {
        /*
            r7 = this;
            boolean r0 = r7.isForwardMatch(r8)
            r1 = 1
            if (r0 == 0) goto L8
            return r1
        L8:
            android.content.pm.ActivityInfo r0 = r8.activityInfo
            r2 = 0
            r3 = 0
            android.content.pm.PackageManager r4 = r7.mPackageManager     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            android.content.res.XmlResourceParser r4 = r0.loadXmlMetaData(r4, r9)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            r2 = r4
            if (r2 != 0) goto L33
            java.lang.String r1 = com.android.server.usb.UsbProfileGroupSettingsManager.TAG     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            r4.<init>()     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            java.lang.String r5 = "no meta-data for "
            r4.append(r5)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            r4.append(r8)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            android.util.Slog.w(r1, r4)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            if (r2 == 0) goto L32
            r2.close()
        L32:
            return r3
        L33:
            com.android.internal.util.XmlUtils.nextElement(r2)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
        L36:
            int r4 = r2.getEventType()     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            if (r4 == r1) goto L7d
            java.lang.String r4 = r2.getName()     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            if (r10 == 0) goto L5d
            java.lang.String r5 = "usb-device"
            boolean r5 = r5.equals(r4)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            if (r5 == 0) goto L5d
            android.hardware.usb.DeviceFilter r5 = android.hardware.usb.DeviceFilter.read(r2)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            boolean r6 = r5.matches(r10)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            if (r6 == 0) goto L5c
        L56:
            if (r2 == 0) goto L5b
            r2.close()
        L5b:
            return r1
        L5c:
            goto L79
        L5d:
            if (r11 == 0) goto L79
            java.lang.String r5 = "usb-accessory"
            boolean r5 = r5.equals(r4)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            if (r5 == 0) goto L79
            android.hardware.usb.AccessoryFilter r5 = android.hardware.usb.AccessoryFilter.read(r2)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            boolean r6 = r5.matches(r11)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            if (r6 == 0) goto L79
        L73:
            if (r2 == 0) goto L78
            r2.close()
        L78:
            return r1
        L79:
            com.android.internal.util.XmlUtils.nextElement(r2)     // Catch: java.lang.Throwable -> L83 java.lang.Exception -> L85
            goto L36
        L7d:
            if (r2 == 0) goto La3
        L7f:
            r2.close()
            goto La3
        L83:
            r1 = move-exception
            goto La4
        L85:
            r1 = move-exception
            java.lang.String r4 = com.android.server.usb.UsbProfileGroupSettingsManager.TAG     // Catch: java.lang.Throwable -> L83
            java.lang.StringBuilder r5 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L83
            r5.<init>()     // Catch: java.lang.Throwable -> L83
            java.lang.String r6 = "Unable to load component info "
            r5.append(r6)     // Catch: java.lang.Throwable -> L83
            java.lang.String r6 = r8.toString()     // Catch: java.lang.Throwable -> L83
            r5.append(r6)     // Catch: java.lang.Throwable -> L83
            java.lang.String r5 = r5.toString()     // Catch: java.lang.Throwable -> L83
            android.util.Slog.w(r4, r5, r1)     // Catch: java.lang.Throwable -> L83
            if (r2 == 0) goto La3
            goto L7f
        La3:
            return r3
        La4:
            if (r2 == 0) goto La9
            r2.close()
        La9:
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbProfileGroupSettingsManager.packageMatchesLocked(android.content.pm.ResolveInfo, java.lang.String, android.hardware.usb.UsbDevice, android.hardware.usb.UsbAccessory):boolean");
    }

    private ArrayList<ResolveInfo> queryIntentActivitiesForAllProfiles(Intent intent) {
        List<UserInfo> profiles = this.mUserManager.getEnabledProfiles(this.mParentUser.getIdentifier());
        ArrayList<ResolveInfo> resolveInfos = new ArrayList<>();
        int numProfiles = profiles.size();
        for (int i = 0; i < numProfiles; i++) {
            resolveInfos.addAll(this.mPackageManager.queryIntentActivitiesAsUser(intent, 128, profiles.get(i).id));
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
        int numNonParentActivityMatches = 0;
        int numParentActivityMatches = 0;
        for (int numParentActivityMatches2 = 0; numParentActivityMatches2 < numRawMatches; numParentActivityMatches2++) {
            ResolveInfo rawMatch = rawMatches.get(numParentActivityMatches2);
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
            for (int i = 0; i < numRawMatches; i++) {
                ResolveInfo rawMatch2 = rawMatches.get(i);
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
            if (packageMatchesLocked(resolveInfo, intent.getAction(), device, null)) {
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
            if (packageMatchesLocked(resolveInfo, intent.getAction(), null, accessory)) {
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

    public boolean isUnsupportedDevice(UsbDevice device) {
        int numInterfaces = device.getInterfaceCount();
        boolean is_unsupport = false;
        for (int i = 0; i < numInterfaces; i++) {
            UsbInterface usbInterface = device.getInterface(i);
            String interfaceName = usbInterface.getName();
            if (UNSUPPORT_IFACE_MTP.equals(interfaceName) || UNSUPPORT_IFACE_PTP.equals(interfaceName) || UNSUPPORT_IFACE_MIDI.equals(interfaceName)) {
                is_unsupport = true;
            } else if (SUPPORT_IFACE_UMS.equals(interfaceName)) {
                return false;
            }
        }
        Slog.e(TAG, "isUnsupportedDevice: " + is_unsupport);
        return is_unsupport;
    }

    public void deviceAttachedForStorage(UsbDevice device, String deviceAddress, byte[] descriptors) {
        if (isUnsupportedDevice(device)) {
            return;
        }
        Intent intent = createDeviceAttachedIntentForStorage(device, deviceAddress, descriptors);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        resolveActivity(intent, device, true);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resolveActivity(Intent intent, UsbDevice device, boolean showMtpNotification) {
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
        this.mContext.sendBroadcast(intent);
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
        if (isUnsupportedDevice(device)) {
            return;
        }
        Intent intent = createDeviceAttachedIntentForStorage(device, deviceAddress, descriptors);
        this.mContext.sendBroadcast(intent);
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
        UserHandle user;
        String uri;
        if (matches.size() == 0) {
            if (accessory != null && (uri = accessory.getUri()) != null && uri.length() > 0) {
                Intent dialogIntent = new Intent();
                dialogIntent.setClassName(AfterSalesService.PackgeName.SYSTEMUI, "com.android.systemui.usb.UsbAccessoryUriActivity");
                dialogIntent.addFlags(268435456);
                dialogIntent.putExtra("accessory", accessory);
                dialogIntent.putExtra("uri", uri);
                try {
                    this.mContext.startActivityAsUser(dialogIntent, this.mParentUser);
                } catch (ActivityNotFoundException e) {
                    Slog.e(TAG, "unable to start UsbAccessoryUriActivity");
                }
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
                UserHandle user2 = UserHandle.getUserHandleForUid(defaultActivity.applicationInfo.uid);
                this.mContext.startActivityAsUser(intent, user2);
            } catch (ActivityNotFoundException e2) {
                Slog.e(TAG, "startActivity failed", e2);
            }
        } else {
            Intent resolverIntent = new Intent();
            resolverIntent.addFlags(268435456);
            if (matches.size() == 1) {
                ResolveInfo rInfo = matches.get(0);
                resolverIntent.setClassName(AfterSalesService.PackgeName.SYSTEMUI, "com.android.systemui.usb.UsbConfirmActivity");
                resolverIntent.putExtra("rinfo", rInfo);
                user = UserHandle.getUserHandleForUid(rInfo.activityInfo.applicationInfo.uid);
                if (device != null) {
                    resolverIntent.putExtra("device", device);
                } else {
                    resolverIntent.putExtra("accessory", accessory);
                }
            } else {
                user = this.mParentUser;
                resolverIntent.setClassName(AfterSalesService.PackgeName.SYSTEMUI, "com.android.systemui.usb.UsbResolverActivity");
                resolverIntent.putParcelableArrayListExtra("rlist", matches);
                resolverIntent.putExtra("android.intent.extra.INTENT", intent);
            }
            try {
                this.mContext.startActivityAsUser(resolverIntent, user);
            } catch (ActivityNotFoundException e3) {
                String str = TAG;
                Slog.e(str, "unable to start activity " + resolverIntent, e3);
            }
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

    @GuardedBy("mLock")
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

    @GuardedBy("mLock")
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

    /* JADX WARN: Code restructure failed: missing block: B:25:0x004d, code lost:
        if (r0 != null) goto L31;
     */
    /* JADX WARN: Code restructure failed: missing block: B:26:0x004f, code lost:
        r0.close();
     */
    /* JADX WARN: Code restructure failed: missing block: B:31:0x0070, code lost:
        if (r0 == null) goto L32;
     */
    /* JADX WARN: Code restructure failed: missing block: B:33:0x0073, code lost:
        return r2;
     */
    @com.android.internal.annotations.GuardedBy("mLock")
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private boolean handlePackageAddedLocked(com.android.server.usb.UsbProfileGroupSettingsManager.UserPackage r7, android.content.pm.ActivityInfo r8, java.lang.String r9) {
        /*
            r6 = this;
            r0 = 0
            r1 = 0
            r2 = r1
            android.content.pm.PackageManager r3 = r6.mPackageManager     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            android.content.res.XmlResourceParser r3 = r8.loadXmlMetaData(r3, r9)     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            r0 = r3
            if (r0 != 0) goto L12
            if (r0 == 0) goto L11
            r0.close()
        L11:
            return r1
        L12:
            com.android.internal.util.XmlUtils.nextElement(r0)     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
        L15:
            int r1 = r0.getEventType()     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            r3 = 1
            if (r1 == r3) goto L4d
            java.lang.String r1 = r0.getName()     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            java.lang.String r3 = "usb-device"
            boolean r3 = r3.equals(r1)     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            if (r3 == 0) goto L35
            android.hardware.usb.DeviceFilter r3 = android.hardware.usb.DeviceFilter.read(r0)     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            boolean r4 = r6.clearCompatibleMatchesLocked(r7, r3)     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            if (r4 == 0) goto L34
            r2 = 1
        L34:
            goto L49
        L35:
            java.lang.String r3 = "usb-accessory"
            boolean r3 = r3.equals(r1)     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            if (r3 == 0) goto L49
            android.hardware.usb.AccessoryFilter r3 = android.hardware.usb.AccessoryFilter.read(r0)     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            boolean r4 = r6.clearCompatibleMatchesLocked(r7, r3)     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            if (r4 == 0) goto L49
            r2 = 1
        L49:
            com.android.internal.util.XmlUtils.nextElement(r0)     // Catch: java.lang.Throwable -> L53 java.lang.Exception -> L55
            goto L15
        L4d:
            if (r0 == 0) goto L73
        L4f:
            r0.close()
            goto L73
        L53:
            r1 = move-exception
            goto L74
        L55:
            r1 = move-exception
            java.lang.String r3 = com.android.server.usb.UsbProfileGroupSettingsManager.TAG     // Catch: java.lang.Throwable -> L53
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> L53
            r4.<init>()     // Catch: java.lang.Throwable -> L53
            java.lang.String r5 = "Unable to load component info "
            r4.append(r5)     // Catch: java.lang.Throwable -> L53
            java.lang.String r5 = r8.toString()     // Catch: java.lang.Throwable -> L53
            r4.append(r5)     // Catch: java.lang.Throwable -> L53
            java.lang.String r4 = r4.toString()     // Catch: java.lang.Throwable -> L53
            android.util.Slog.w(r3, r4, r1)     // Catch: java.lang.Throwable -> L53
            if (r0 == 0) goto L73
            goto L4f
        L73:
            return r2
        L74:
            if (r0 == 0) goto L79
            r0.close()
        L79:
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.usb.UsbProfileGroupSettingsManager.handlePackageAddedLocked(com.android.server.usb.UsbProfileGroupSettingsManager$UserPackage, android.content.pm.ActivityInfo, java.lang.String):boolean");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handlePackageAdded(UserPackage userPackage) {
        synchronized (this.mLock) {
            boolean changed = false;
            try {
                try {
                    PackageInfo info = this.mPackageManager.getPackageInfoAsUser(userPackage.packageName, NetworkConstants.ICMPV6_ECHO_REPLY_TYPE, userPackage.user.getIdentifier());
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
                    Slog.e(TAG, "handlePackageUpdate could not find package " + userPackage, e);
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
            try {
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
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAccessoryPackage(UsbAccessory accessory, String packageName, UserHandle user) {
        AccessoryFilter filter = new AccessoryFilter(accessory);
        synchronized (this.mLock) {
            boolean changed = true;
            try {
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
            } catch (Throwable th) {
                throw th;
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
            try {
                if (this.mDevicePreferenceMap.containsValue(userPackage)) {
                    DeviceFilter[] keys = (DeviceFilter[]) this.mDevicePreferenceMap.keySet().toArray(new DeviceFilter[0]);
                    boolean cleared2 = false;
                    for (DeviceFilter key : keys) {
                        try {
                            if (userPackage.equals(this.mDevicePreferenceMap.get(key))) {
                                this.mDevicePreferenceMap.remove(key);
                                cleared2 = true;
                            }
                        } catch (Throwable th) {
                            th = th;
                            throw th;
                        }
                    }
                    cleared = cleared2;
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
                return cleared;
            } catch (Throwable th2) {
                th = th2;
            }
        }
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
