package com.android.server.pm.permission;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageList;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;
import com.android.server.backup.BackupManagerService;
import com.android.server.pm.PackageManagerService;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public final class DefaultPermissionGrantPolicy {
    private static final String ACTION_TRACK = "com.android.fitness.TRACK";
    private static final String ATTR_FIXED = "fixed";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_PACKAGE = "package";
    private static final String AUDIO_MIME_TYPE = "audio/mpeg";
    private static final Set<String> CALENDAR_PERMISSIONS;
    private static final Set<String> CAMERA_PERMISSIONS;
    private static final Set<String> COARSE_LOCATION_PERMISSIONS;
    private static final Set<String> CONTACTS_PERMISSIONS;
    private static final boolean DEBUG = false;
    private static final int DEFAULT_FLAGS = 794624;
    private static final Set<String> LOCATION_PERMISSIONS;
    private static final Set<String> MICROPHONE_PERMISSIONS;
    private static final int MSG_READ_DEFAULT_PERMISSION_EXCEPTIONS = 1;
    private static final Set<String> PHONE_PERMISSIONS = new ArraySet();
    private static final Set<String> SENSORS_PERMISSIONS;
    private static final Set<String> SMS_PERMISSIONS;
    private static final Set<String> STORAGE_PERMISSIONS;
    private static final String TAG = "DefaultPermGrantPolicy";
    private static final String TAG_EXCEPTION = "exception";
    private static final String TAG_EXCEPTIONS = "exceptions";
    private static final String TAG_PERMISSION = "permission";
    private final Context mContext;
    private PackageManagerInternal.PackagesProvider mDialerAppPackagesProvider;
    private ArrayMap<String, List<DefaultPermissionGrant>> mGrantExceptions;
    private final Handler mHandler;
    private PackageManagerInternal.PackagesProvider mLocationPackagesProvider;
    private final DefaultPermissionGrantedCallback mPermissionGrantedCallback;
    private final PermissionManagerService mPermissionManager;
    private PackageManagerInternal.PackagesProvider mSimCallManagerPackagesProvider;
    private PackageManagerInternal.PackagesProvider mSmsAppPackagesProvider;
    private PackageManagerInternal.SyncAdapterPackagesProvider mSyncAdapterPackagesProvider;
    private PackageManagerInternal.PackagesProvider mUseOpenWifiAppPackagesProvider;
    private PackageManagerInternal.PackagesProvider mVoiceInteractionPackagesProvider;
    private final Object mLock = new Object();
    private final PackageManagerInternal mServiceInternal = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);

    /* loaded from: classes.dex */
    public interface DefaultPermissionGrantedCallback {
        void onDefaultRuntimePermissionsGranted(int i);
    }

    static {
        PHONE_PERMISSIONS.add("android.permission.READ_PHONE_STATE");
        PHONE_PERMISSIONS.add("android.permission.CALL_PHONE");
        PHONE_PERMISSIONS.add("android.permission.READ_CALL_LOG");
        PHONE_PERMISSIONS.add("android.permission.WRITE_CALL_LOG");
        PHONE_PERMISSIONS.add("com.android.voicemail.permission.ADD_VOICEMAIL");
        PHONE_PERMISSIONS.add("android.permission.USE_SIP");
        PHONE_PERMISSIONS.add("android.permission.PROCESS_OUTGOING_CALLS");
        CONTACTS_PERMISSIONS = new ArraySet();
        CONTACTS_PERMISSIONS.add("android.permission.READ_CONTACTS");
        CONTACTS_PERMISSIONS.add("android.permission.WRITE_CONTACTS");
        CONTACTS_PERMISSIONS.add("android.permission.GET_ACCOUNTS");
        LOCATION_PERMISSIONS = new ArraySet();
        LOCATION_PERMISSIONS.add("android.permission.ACCESS_FINE_LOCATION");
        LOCATION_PERMISSIONS.add("android.permission.ACCESS_COARSE_LOCATION");
        COARSE_LOCATION_PERMISSIONS = new ArraySet();
        COARSE_LOCATION_PERMISSIONS.add("android.permission.ACCESS_COARSE_LOCATION");
        CALENDAR_PERMISSIONS = new ArraySet();
        CALENDAR_PERMISSIONS.add("android.permission.READ_CALENDAR");
        CALENDAR_PERMISSIONS.add("android.permission.WRITE_CALENDAR");
        SMS_PERMISSIONS = new ArraySet();
        SMS_PERMISSIONS.add("android.permission.SEND_SMS");
        SMS_PERMISSIONS.add("android.permission.RECEIVE_SMS");
        SMS_PERMISSIONS.add("android.permission.READ_SMS");
        SMS_PERMISSIONS.add("android.permission.RECEIVE_WAP_PUSH");
        SMS_PERMISSIONS.add("android.permission.RECEIVE_MMS");
        SMS_PERMISSIONS.add("android.permission.READ_CELL_BROADCASTS");
        MICROPHONE_PERMISSIONS = new ArraySet();
        MICROPHONE_PERMISSIONS.add("android.permission.RECORD_AUDIO");
        CAMERA_PERMISSIONS = new ArraySet();
        CAMERA_PERMISSIONS.add("android.permission.CAMERA");
        SENSORS_PERMISSIONS = new ArraySet();
        SENSORS_PERMISSIONS.add("android.permission.BODY_SENSORS");
        STORAGE_PERMISSIONS = new ArraySet();
        STORAGE_PERMISSIONS.add("android.permission.READ_EXTERNAL_STORAGE");
        STORAGE_PERMISSIONS.add("android.permission.WRITE_EXTERNAL_STORAGE");
    }

    public DefaultPermissionGrantPolicy(Context context, Looper looper, DefaultPermissionGrantedCallback callback, PermissionManagerService permissionManager) {
        this.mContext = context;
        this.mHandler = new Handler(looper) { // from class: com.android.server.pm.permission.DefaultPermissionGrantPolicy.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                if (msg.what == 1) {
                    synchronized (DefaultPermissionGrantPolicy.this.mLock) {
                        if (DefaultPermissionGrantPolicy.this.mGrantExceptions == null) {
                            DefaultPermissionGrantPolicy.this.mGrantExceptions = DefaultPermissionGrantPolicy.this.readDefaultPermissionExceptionsLocked();
                        }
                    }
                }
            }
        };
        this.mPermissionGrantedCallback = callback;
        this.mPermissionManager = permissionManager;
    }

    public void setLocationPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
        synchronized (this.mLock) {
            this.mLocationPackagesProvider = provider;
        }
    }

    public void setVoiceInteractionPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
        synchronized (this.mLock) {
            this.mVoiceInteractionPackagesProvider = provider;
        }
    }

    public void setSmsAppPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
        synchronized (this.mLock) {
            this.mSmsAppPackagesProvider = provider;
        }
    }

    public void setDialerAppPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
        synchronized (this.mLock) {
            this.mDialerAppPackagesProvider = provider;
        }
    }

    public void setSimCallManagerPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
        synchronized (this.mLock) {
            this.mSimCallManagerPackagesProvider = provider;
        }
    }

    public void setUseOpenWifiAppPackagesProvider(PackageManagerInternal.PackagesProvider provider) {
        synchronized (this.mLock) {
            this.mUseOpenWifiAppPackagesProvider = provider;
        }
    }

    public void setSyncAdapterPackagesProvider(PackageManagerInternal.SyncAdapterPackagesProvider provider) {
        synchronized (this.mLock) {
            this.mSyncAdapterPackagesProvider = provider;
        }
    }

    public void grantDefaultPermissions(int userId) {
        grantPermissionsToSysComponentsAndPrivApps(userId);
        grantDefaultSystemHandlerPermissions(userId);
        grantDefaultPermissionExceptions(userId);
    }

    private void grantRuntimePermissionsForPackage(int userId, PackageParser.Package pkg) {
        Set<String> permissions = new ArraySet<>();
        Iterator it = pkg.requestedPermissions.iterator();
        while (it.hasNext()) {
            String permission = (String) it.next();
            BasePermission bp = this.mPermissionManager.getPermission(permission);
            if (bp != null && bp.isRuntime()) {
                permissions.add(permission);
            }
        }
        if (!permissions.isEmpty()) {
            grantRuntimePermissions(pkg, permissions, true, userId);
        }
    }

    private void grantAllRuntimePermissions(int userId) {
        Log.i(TAG, "Granting all runtime permissions for user " + userId);
        PackageList packageList = this.mServiceInternal.getPackageList();
        for (String packageName : packageList.getPackageNames()) {
            PackageParser.Package pkg = this.mServiceInternal.getPackage(packageName);
            if (pkg != null) {
                grantRuntimePermissionsForPackage(userId, pkg);
            }
        }
    }

    public void scheduleReadDefaultPermissionExceptions() {
        this.mHandler.sendEmptyMessage(1);
    }

    private void grantPermissionsToSysComponentsAndPrivApps(int userId) {
        Log.i(TAG, "Granting permissions to platform components for user " + userId);
        PackageList packageList = this.mServiceInternal.getPackageList();
        for (String packageName : packageList.getPackageNames()) {
            PackageParser.Package pkg = this.mServiceInternal.getPackage(packageName);
            if (pkg != null && isSysComponentOrPersistentPlatformSignedPrivApp(pkg) && doesPackageSupportRuntimePermissions(pkg) && !pkg.requestedPermissions.isEmpty()) {
                grantRuntimePermissionsForPackage(userId, pkg);
            }
        }
    }

    private void grantDefaultSystemHandlerPermissions(int userId) {
        PackageManagerInternal.PackagesProvider locationPackagesProvider;
        PackageManagerInternal.PackagesProvider voiceInteractionPackagesProvider;
        PackageManagerInternal.PackagesProvider smsAppPackagesProvider;
        PackageManagerInternal.PackagesProvider dialerAppPackagesProvider;
        PackageManagerInternal.PackagesProvider simCallManagerPackagesProvider;
        PackageManagerInternal.PackagesProvider useOpenWifiAppPackagesProvider;
        PackageManagerInternal.SyncAdapterPackagesProvider syncAdapterPackagesProvider;
        boolean z;
        boolean z2;
        PackageParser.Package browserPackage;
        int i;
        PackageParser.Package textClassifierPackage;
        List<PackageParser.Package> contactsSyncAdapters;
        PackageParser.Package contactsPackage;
        List<PackageParser.Package> calendarSyncAdapters;
        Log.i(TAG, "Granting permissions to default platform handlers for user " + userId);
        synchronized (this.mLock) {
            locationPackagesProvider = this.mLocationPackagesProvider;
            voiceInteractionPackagesProvider = this.mVoiceInteractionPackagesProvider;
            smsAppPackagesProvider = this.mSmsAppPackagesProvider;
            dialerAppPackagesProvider = this.mDialerAppPackagesProvider;
            simCallManagerPackagesProvider = this.mSimCallManagerPackagesProvider;
            useOpenWifiAppPackagesProvider = this.mUseOpenWifiAppPackagesProvider;
            syncAdapterPackagesProvider = this.mSyncAdapterPackagesProvider;
        }
        String[] voiceInteractPackageNames = voiceInteractionPackagesProvider != null ? voiceInteractionPackagesProvider.getPackages(userId) : null;
        String[] locationPackageNames = locationPackagesProvider != null ? locationPackagesProvider.getPackages(userId) : null;
        String[] smsAppPackageNames = smsAppPackagesProvider != null ? smsAppPackagesProvider.getPackages(userId) : null;
        String[] dialerAppPackageNames = dialerAppPackagesProvider != null ? dialerAppPackagesProvider.getPackages(userId) : null;
        String[] simCallManagerPackageNames = simCallManagerPackagesProvider != null ? simCallManagerPackagesProvider.getPackages(userId) : null;
        String[] useOpenWifiAppPackageNames = useOpenWifiAppPackagesProvider != null ? useOpenWifiAppPackagesProvider.getPackages(userId) : null;
        String[] contactsSyncAdapterPackages = syncAdapterPackagesProvider != null ? syncAdapterPackagesProvider.getPackages("com.android.contacts", userId) : null;
        String[] calendarSyncAdapterPackages = syncAdapterPackagesProvider != null ? syncAdapterPackagesProvider.getPackages("com.android.calendar", userId) : null;
        String installerPackageName = this.mServiceInternal.getKnownPackageName(2, userId);
        PackageParser.Package installerPackage = getSystemPackage(installerPackageName);
        if (installerPackage != null && doesPackageSupportRuntimePermissions(installerPackage)) {
            grantRuntimePermissions(installerPackage, STORAGE_PERMISSIONS, true, userId);
        }
        String verifierPackageName = this.mServiceInternal.getKnownPackageName(3, userId);
        PackageParser.Package verifierPackage = getSystemPackage(verifierPackageName);
        if (verifierPackage != null && doesPackageSupportRuntimePermissions(verifierPackage)) {
            grantRuntimePermissions(verifierPackage, STORAGE_PERMISSIONS, true, userId);
            grantRuntimePermissions(verifierPackage, PHONE_PERMISSIONS, false, userId);
            grantRuntimePermissions(verifierPackage, SMS_PERMISSIONS, false, userId);
        }
        String setupWizardPackageName = this.mServiceInternal.getKnownPackageName(1, userId);
        PackageParser.Package setupPackage = getSystemPackage(setupWizardPackageName);
        if (setupPackage != null && doesPackageSupportRuntimePermissions(setupPackage)) {
            grantRuntimePermissions(setupPackage, PHONE_PERMISSIONS, userId);
            grantRuntimePermissions(setupPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(setupPackage, LOCATION_PERMISSIONS, userId);
            grantRuntimePermissions(setupPackage, CAMERA_PERMISSIONS, userId);
        }
        Intent cameraIntent = new Intent("android.media.action.IMAGE_CAPTURE");
        PackageParser.Package cameraPackage = getDefaultSystemHandlerActivityPackage(cameraIntent, userId);
        if (cameraPackage != null && doesPackageSupportRuntimePermissions(cameraPackage)) {
            grantRuntimePermissions(cameraPackage, CAMERA_PERMISSIONS, userId);
            grantRuntimePermissions(cameraPackage, MICROPHONE_PERMISSIONS, userId);
            grantRuntimePermissions(cameraPackage, STORAGE_PERMISSIONS, userId);
        }
        PackageParser.Package mediaStorePackage = getDefaultProviderAuthorityPackage("media", userId);
        if (mediaStorePackage != null) {
            z = true;
            grantRuntimePermissions(mediaStorePackage, STORAGE_PERMISSIONS, true, userId);
            grantRuntimePermissions(mediaStorePackage, PHONE_PERMISSIONS, true, userId);
        } else {
            z = true;
        }
        PackageParser.Package downloadsPackage = getDefaultProviderAuthorityPackage("downloads", userId);
        if (downloadsPackage != null) {
            grantRuntimePermissions(downloadsPackage, STORAGE_PERMISSIONS, z, userId);
        }
        Intent downloadsUiIntent = new Intent("android.intent.action.VIEW_DOWNLOADS");
        PackageParser.Package downloadsUiPackage = getDefaultSystemHandlerActivityPackage(downloadsUiIntent, userId);
        if (downloadsUiPackage == null || !doesPackageSupportRuntimePermissions(downloadsUiPackage)) {
            z2 = true;
        } else {
            z2 = true;
            grantRuntimePermissions(downloadsUiPackage, STORAGE_PERMISSIONS, true, userId);
        }
        PackageParser.Package storagePackage = getDefaultProviderAuthorityPackage("com.android.externalstorage.documents", userId);
        if (storagePackage != null) {
            grantRuntimePermissions(storagePackage, STORAGE_PERMISSIONS, z2, userId);
        }
        PackageParser.Package containerPackage = getSystemPackage(PackageManagerService.DEFAULT_CONTAINER_PACKAGE);
        if (containerPackage != null) {
            grantRuntimePermissions(containerPackage, STORAGE_PERMISSIONS, z2, userId);
        }
        Intent certInstallerIntent = new Intent("android.credentials.INSTALL");
        PackageParser.Package certInstallerPackage = getDefaultSystemHandlerActivityPackage(certInstallerIntent, userId);
        if (certInstallerPackage != null && doesPackageSupportRuntimePermissions(certInstallerPackage)) {
            grantRuntimePermissions(certInstallerPackage, STORAGE_PERMISSIONS, true, userId);
        }
        if (dialerAppPackageNames == null) {
            Intent dialerIntent = new Intent("android.intent.action.DIAL");
            PackageParser.Package dialerPackage = getDefaultSystemHandlerActivityPackage(dialerIntent, userId);
            if (dialerPackage != null) {
                grantDefaultPermissionsToDefaultSystemDialerApp(dialerPackage, userId);
            }
        } else {
            int length = dialerAppPackageNames.length;
            int i2 = 0;
            while (i2 < length) {
                int i3 = length;
                String dialerAppPackageName = dialerAppPackageNames[i2];
                PackageParser.Package certInstallerPackage2 = certInstallerPackage;
                PackageParser.Package certInstallerPackage3 = getSystemPackage(dialerAppPackageName);
                if (certInstallerPackage3 != null) {
                    grantDefaultPermissionsToDefaultSystemDialerApp(certInstallerPackage3, userId);
                }
                i2++;
                length = i3;
                certInstallerPackage = certInstallerPackage2;
            }
        }
        if (simCallManagerPackageNames != null) {
            int length2 = simCallManagerPackageNames.length;
            int i4 = 0;
            while (i4 < length2) {
                String simCallManagerPackageName = simCallManagerPackageNames[i4];
                int i5 = length2;
                PackageParser.Package simCallManagerPackage = getSystemPackage(simCallManagerPackageName);
                if (simCallManagerPackage != null) {
                    grantDefaultPermissionsToDefaultSimCallManager(simCallManagerPackage, userId);
                }
                i4++;
                length2 = i5;
            }
        }
        if (useOpenWifiAppPackageNames != null) {
            int length3 = useOpenWifiAppPackageNames.length;
            int i6 = 0;
            while (i6 < length3) {
                String useOpenWifiPackageName = useOpenWifiAppPackageNames[i6];
                int i7 = length3;
                PackageParser.Package useOpenWifiPackage = getSystemPackage(useOpenWifiPackageName);
                if (useOpenWifiPackage != null) {
                    grantDefaultPermissionsToDefaultSystemUseOpenWifiApp(useOpenWifiPackage, userId);
                }
                i6++;
                length3 = i7;
            }
        }
        if (smsAppPackageNames == null) {
            Intent smsIntent = new Intent("android.intent.action.MAIN");
            smsIntent.addCategory("android.intent.category.APP_MESSAGING");
            PackageParser.Package smsPackage = getDefaultSystemHandlerActivityPackage(smsIntent, userId);
            if (smsPackage != null) {
                grantDefaultPermissionsToDefaultSystemSmsApp(smsPackage, userId);
            }
        } else {
            int length4 = smsAppPackageNames.length;
            int i8 = 0;
            while (i8 < length4) {
                String smsPackageName = smsAppPackageNames[i8];
                int i9 = length4;
                PackageParser.Package smsPackage2 = getSystemPackage(smsPackageName);
                if (smsPackage2 != null) {
                    grantDefaultPermissionsToDefaultSystemSmsApp(smsPackage2, userId);
                }
                i8++;
                length4 = i9;
            }
        }
        Intent cbrIntent = new Intent("android.provider.Telephony.SMS_CB_RECEIVED");
        PackageParser.Package cbrPackage = getDefaultSystemHandlerActivityPackage(cbrIntent, userId);
        if (cbrPackage != null && doesPackageSupportRuntimePermissions(cbrPackage)) {
            grantRuntimePermissions(cbrPackage, SMS_PERMISSIONS, userId);
        }
        Intent carrierProvIntent = new Intent("android.provider.Telephony.SMS_CARRIER_PROVISION");
        PackageParser.Package carrierProvPackage = getDefaultSystemHandlerServicePackage(carrierProvIntent, userId);
        if (carrierProvPackage != null && doesPackageSupportRuntimePermissions(carrierProvPackage)) {
            grantRuntimePermissions(carrierProvPackage, SMS_PERMISSIONS, false, userId);
        }
        Intent calendarIntent = new Intent("android.intent.action.MAIN");
        calendarIntent.addCategory("android.intent.category.APP_CALENDAR");
        PackageParser.Package calendarPackage = getDefaultSystemHandlerActivityPackage(calendarIntent, userId);
        if (calendarPackage != null && doesPackageSupportRuntimePermissions(calendarPackage)) {
            grantRuntimePermissions(calendarPackage, CALENDAR_PERMISSIONS, userId);
            grantRuntimePermissions(calendarPackage, CONTACTS_PERMISSIONS, userId);
        }
        PackageParser.Package calendarProviderPackage = getDefaultProviderAuthorityPackage("com.android.calendar", userId);
        if (calendarProviderPackage != null) {
            grantRuntimePermissions(calendarProviderPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(calendarProviderPackage, CALENDAR_PERMISSIONS, true, userId);
            grantRuntimePermissions(calendarProviderPackage, STORAGE_PERMISSIONS, userId);
        }
        List<PackageParser.Package> calendarSyncAdapters2 = getHeadlessSyncAdapterPackages(calendarSyncAdapterPackages, userId);
        int calendarSyncAdapterCount = calendarSyncAdapters2.size();
        int i10 = 0;
        while (true) {
            int i11 = i10;
            String[] calendarSyncAdapterPackages2 = calendarSyncAdapterPackages;
            if (i11 >= calendarSyncAdapterCount) {
                break;
            }
            PackageParser.Package calendarProviderPackage2 = calendarProviderPackage;
            PackageParser.Package calendarSyncAdapter = calendarSyncAdapters2.get(i11);
            if (doesPackageSupportRuntimePermissions(calendarSyncAdapter)) {
                calendarSyncAdapters = calendarSyncAdapters2;
                grantRuntimePermissions(calendarSyncAdapter, CALENDAR_PERMISSIONS, userId);
            } else {
                calendarSyncAdapters = calendarSyncAdapters2;
            }
            i10 = i11 + 1;
            calendarSyncAdapterPackages = calendarSyncAdapterPackages2;
            calendarProviderPackage = calendarProviderPackage2;
            calendarSyncAdapters2 = calendarSyncAdapters;
        }
        Intent contactsIntent = new Intent("android.intent.action.MAIN");
        contactsIntent.addCategory("android.intent.category.APP_CONTACTS");
        PackageParser.Package contactsPackage2 = getDefaultSystemHandlerActivityPackage(contactsIntent, userId);
        if (contactsPackage2 != null && doesPackageSupportRuntimePermissions(contactsPackage2)) {
            grantRuntimePermissions(contactsPackage2, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(contactsPackage2, PHONE_PERMISSIONS, userId);
        }
        List<PackageParser.Package> contactsSyncAdapters2 = getHeadlessSyncAdapterPackages(contactsSyncAdapterPackages, userId);
        int contactsSyncAdapterCount = contactsSyncAdapters2.size();
        int i12 = 0;
        while (true) {
            int i13 = i12;
            String[] contactsSyncAdapterPackages2 = contactsSyncAdapterPackages;
            if (i13 >= contactsSyncAdapterCount) {
                break;
            }
            int contactsSyncAdapterCount2 = contactsSyncAdapterCount;
            PackageParser.Package contactsSyncAdapter = contactsSyncAdapters2.get(i13);
            if (doesPackageSupportRuntimePermissions(contactsSyncAdapter)) {
                contactsPackage = contactsPackage2;
                grantRuntimePermissions(contactsSyncAdapter, CONTACTS_PERMISSIONS, userId);
            } else {
                contactsPackage = contactsPackage2;
            }
            i12 = i13 + 1;
            contactsSyncAdapterPackages = contactsSyncAdapterPackages2;
            contactsSyncAdapterCount = contactsSyncAdapterCount2;
            contactsPackage2 = contactsPackage;
        }
        PackageParser.Package contactsProviderPackage = getDefaultProviderAuthorityPackage("com.android.contacts", userId);
        if (contactsProviderPackage != null) {
            grantRuntimePermissions(contactsProviderPackage, CONTACTS_PERMISSIONS, true, userId);
            grantRuntimePermissions(contactsProviderPackage, PHONE_PERMISSIONS, true, userId);
            grantRuntimePermissions(contactsProviderPackage, STORAGE_PERMISSIONS, userId);
        }
        Intent deviceProvisionIntent = new Intent("android.app.action.PROVISION_MANAGED_DEVICE");
        PackageParser.Package deviceProvisionPackage = getDefaultSystemHandlerActivityPackage(deviceProvisionIntent, userId);
        if (deviceProvisionPackage != null && doesPackageSupportRuntimePermissions(deviceProvisionPackage)) {
            grantRuntimePermissions(deviceProvisionPackage, CONTACTS_PERMISSIONS, userId);
        }
        Intent mapsIntent = new Intent("android.intent.action.MAIN");
        mapsIntent.addCategory("android.intent.category.APP_MAPS");
        PackageParser.Package mapsPackage = getDefaultSystemHandlerActivityPackage(mapsIntent, userId);
        if (mapsPackage != null && doesPackageSupportRuntimePermissions(mapsPackage)) {
            grantRuntimePermissions(mapsPackage, LOCATION_PERMISSIONS, userId);
        }
        Intent galleryIntent = new Intent("android.intent.action.MAIN");
        galleryIntent.addCategory("android.intent.category.APP_GALLERY");
        PackageParser.Package galleryPackage = getDefaultSystemHandlerActivityPackage(galleryIntent, userId);
        if (galleryPackage != null && doesPackageSupportRuntimePermissions(galleryPackage)) {
            grantRuntimePermissions(galleryPackage, STORAGE_PERMISSIONS, userId);
        }
        Intent emailIntent = new Intent("android.intent.action.MAIN");
        emailIntent.addCategory("android.intent.category.APP_EMAIL");
        PackageParser.Package emailPackage = getDefaultSystemHandlerActivityPackage(emailIntent, userId);
        if (emailPackage != null && doesPackageSupportRuntimePermissions(emailPackage)) {
            grantRuntimePermissions(emailPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(emailPackage, CALENDAR_PERMISSIONS, userId);
        }
        PackageParser.Package browserPackage2 = null;
        String defaultBrowserPackage = this.mServiceInternal.getKnownPackageName(4, userId);
        if (defaultBrowserPackage != null) {
            PackageParser.Package browserPackage3 = getPackage(defaultBrowserPackage);
            browserPackage2 = browserPackage3;
        }
        if (browserPackage2 == null) {
            Intent browserIntent = new Intent("android.intent.action.MAIN");
            browserIntent.addCategory("android.intent.category.APP_BROWSER");
            PackageParser.Package browserPackage4 = getDefaultSystemHandlerActivityPackage(browserIntent, userId);
            browserPackage = browserPackage4;
        } else {
            browserPackage = browserPackage2;
        }
        if (browserPackage != null && doesPackageSupportRuntimePermissions(browserPackage)) {
            grantRuntimePermissions(browserPackage, LOCATION_PERMISSIONS, userId);
        }
        if (voiceInteractPackageNames != null) {
            int length5 = voiceInteractPackageNames.length;
            int i14 = 0;
            while (i14 < length5) {
                int i15 = length5;
                String voiceInteractPackageName = voiceInteractPackageNames[i14];
                PackageParser.Package deviceProvisionPackage2 = deviceProvisionPackage;
                PackageParser.Package deviceProvisionPackage3 = getSystemPackage(voiceInteractPackageName);
                if (deviceProvisionPackage3 != null && doesPackageSupportRuntimePermissions(deviceProvisionPackage3)) {
                    grantRuntimePermissions(deviceProvisionPackage3, CONTACTS_PERMISSIONS, userId);
                    grantRuntimePermissions(deviceProvisionPackage3, CALENDAR_PERMISSIONS, userId);
                    grantRuntimePermissions(deviceProvisionPackage3, MICROPHONE_PERMISSIONS, userId);
                    grantRuntimePermissions(deviceProvisionPackage3, PHONE_PERMISSIONS, userId);
                    grantRuntimePermissions(deviceProvisionPackage3, SMS_PERMISSIONS, userId);
                    grantRuntimePermissions(deviceProvisionPackage3, LOCATION_PERMISSIONS, userId);
                }
                i14++;
                length5 = i15;
                deviceProvisionPackage = deviceProvisionPackage2;
            }
        }
        if (ActivityManager.isLowRamDeviceStatic()) {
            Intent globalSearchIntent = new Intent("android.search.action.GLOBAL_SEARCH");
            PackageParser.Package globalSearchPickerPackage = getDefaultSystemHandlerActivityPackage(globalSearchIntent, userId);
            if (globalSearchPickerPackage != null && doesPackageSupportRuntimePermissions(globalSearchPickerPackage)) {
                grantRuntimePermissions(globalSearchPickerPackage, MICROPHONE_PERMISSIONS, false, userId);
                grantRuntimePermissions(globalSearchPickerPackage, LOCATION_PERMISSIONS, false, userId);
            }
        }
        Intent voiceRecoIntent = new Intent("android.speech.RecognitionService");
        voiceRecoIntent.addCategory("android.intent.category.DEFAULT");
        PackageParser.Package voiceRecoPackage = getDefaultSystemHandlerServicePackage(voiceRecoIntent, userId);
        if (voiceRecoPackage != null && doesPackageSupportRuntimePermissions(voiceRecoPackage)) {
            grantRuntimePermissions(voiceRecoPackage, MICROPHONE_PERMISSIONS, userId);
        }
        if (locationPackageNames != null) {
            int length6 = locationPackageNames.length;
            int i16 = 0;
            while (i16 < length6) {
                PackageParser.Package voiceRecoPackage2 = voiceRecoPackage;
                String packageName = locationPackageNames[i16];
                int i17 = length6;
                PackageParser.Package locationPackage = getSystemPackage(packageName);
                if (locationPackage == null || !doesPackageSupportRuntimePermissions(locationPackage)) {
                    contactsSyncAdapters = contactsSyncAdapters2;
                } else {
                    grantRuntimePermissions(locationPackage, CONTACTS_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, CALENDAR_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, MICROPHONE_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, PHONE_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, SMS_PERMISSIONS, userId);
                    contactsSyncAdapters = contactsSyncAdapters2;
                    grantRuntimePermissions(locationPackage, LOCATION_PERMISSIONS, true, userId);
                    grantRuntimePermissions(locationPackage, CAMERA_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, SENSORS_PERMISSIONS, userId);
                    grantRuntimePermissions(locationPackage, STORAGE_PERMISSIONS, userId);
                }
                i16++;
                voiceRecoPackage = voiceRecoPackage2;
                length6 = i17;
                contactsSyncAdapters2 = contactsSyncAdapters;
            }
        }
        Intent musicIntent = new Intent("android.intent.action.VIEW");
        musicIntent.addCategory("android.intent.category.DEFAULT");
        musicIntent.setDataAndType(Uri.fromFile(new File("foo.mp3")), AUDIO_MIME_TYPE);
        PackageParser.Package musicPackage = getDefaultSystemHandlerActivityPackage(musicIntent, userId);
        if (musicPackage != null && doesPackageSupportRuntimePermissions(musicPackage)) {
            grantRuntimePermissions(musicPackage, STORAGE_PERMISSIONS, userId);
        }
        Intent homeIntent = new Intent("android.intent.action.MAIN");
        homeIntent.addCategory("android.intent.category.HOME");
        homeIntent.addCategory("android.intent.category.LAUNCHER_APP");
        PackageParser.Package homePackage = getDefaultSystemHandlerActivityPackage(homeIntent, userId);
        if (homePackage == null || !doesPackageSupportRuntimePermissions(homePackage)) {
            i = 0;
        } else {
            i = 0;
            grantRuntimePermissions(homePackage, LOCATION_PERMISSIONS, false, userId);
        }
        if (this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.watch", i)) {
            Intent wearHomeIntent = new Intent("android.intent.action.MAIN");
            wearHomeIntent.addCategory("android.intent.category.HOME_MAIN");
            PackageParser.Package wearHomePackage = getDefaultSystemHandlerActivityPackage(wearHomeIntent, userId);
            if (wearHomePackage != null && doesPackageSupportRuntimePermissions(wearHomePackage)) {
                grantRuntimePermissions(wearHomePackage, CONTACTS_PERMISSIONS, false, userId);
                grantRuntimePermissions(wearHomePackage, PHONE_PERMISSIONS, true, userId);
                grantRuntimePermissions(wearHomePackage, MICROPHONE_PERMISSIONS, false, userId);
                grantRuntimePermissions(wearHomePackage, LOCATION_PERMISSIONS, false, userId);
            }
            Intent trackIntent = new Intent(ACTION_TRACK);
            PackageParser.Package trackPackage = getDefaultSystemHandlerActivityPackage(trackIntent, userId);
            if (trackPackage != null && doesPackageSupportRuntimePermissions(trackPackage)) {
                grantRuntimePermissions(trackPackage, SENSORS_PERMISSIONS, false, userId);
                grantRuntimePermissions(trackPackage, LOCATION_PERMISSIONS, false, userId);
            }
        }
        PackageParser.Package printSpoolerPackage = getSystemPackage("com.android.printspooler");
        if (printSpoolerPackage != null && doesPackageSupportRuntimePermissions(printSpoolerPackage)) {
            grantRuntimePermissions(printSpoolerPackage, LOCATION_PERMISSIONS, true, userId);
        }
        Intent emergencyInfoIntent = new Intent("android.telephony.action.EMERGENCY_ASSISTANCE");
        PackageParser.Package emergencyInfoPckg = getDefaultSystemHandlerActivityPackage(emergencyInfoIntent, userId);
        if (emergencyInfoPckg != null && doesPackageSupportRuntimePermissions(emergencyInfoPckg)) {
            grantRuntimePermissions(emergencyInfoPckg, CONTACTS_PERMISSIONS, true, userId);
            grantRuntimePermissions(emergencyInfoPckg, PHONE_PERMISSIONS, true, userId);
        }
        Intent nfcTagIntent = new Intent("android.intent.action.VIEW");
        nfcTagIntent.setType("vnd.android.cursor.item/ndef_msg");
        PackageParser.Package nfcTagPkg = getDefaultSystemHandlerActivityPackage(nfcTagIntent, userId);
        if (nfcTagPkg != null && doesPackageSupportRuntimePermissions(nfcTagPkg)) {
            grantRuntimePermissions(nfcTagPkg, CONTACTS_PERMISSIONS, false, userId);
            grantRuntimePermissions(nfcTagPkg, PHONE_PERMISSIONS, false, userId);
        }
        Intent storageManagerIntent = new Intent("android.os.storage.action.MANAGE_STORAGE");
        PackageParser.Package storageManagerPckg = getDefaultSystemHandlerActivityPackage(storageManagerIntent, userId);
        if (storageManagerPckg != null && doesPackageSupportRuntimePermissions(storageManagerPckg)) {
            grantRuntimePermissions(storageManagerPckg, STORAGE_PERMISSIONS, true, userId);
        }
        PackageParser.Package companionDeviceDiscoveryPackage = getSystemPackage("com.android.companiondevicemanager");
        if (companionDeviceDiscoveryPackage != null && doesPackageSupportRuntimePermissions(companionDeviceDiscoveryPackage)) {
            grantRuntimePermissions(companionDeviceDiscoveryPackage, LOCATION_PERMISSIONS, true, userId);
        }
        Intent ringtonePickerIntent = new Intent("android.intent.action.RINGTONE_PICKER");
        PackageParser.Package ringtonePickerPackage = getDefaultSystemHandlerActivityPackage(ringtonePickerIntent, userId);
        if (ringtonePickerPackage != null && doesPackageSupportRuntimePermissions(ringtonePickerPackage)) {
            grantRuntimePermissions(ringtonePickerPackage, STORAGE_PERMISSIONS, true, userId);
        }
        String textClassifierPackageName = this.mContext.getPackageManager().getSystemTextClassifierPackageName();
        if (!TextUtils.isEmpty(textClassifierPackageName) && (textClassifierPackage = getSystemPackage(textClassifierPackageName)) != null && doesPackageSupportRuntimePermissions(textClassifierPackage)) {
            grantRuntimePermissions(textClassifierPackage, PHONE_PERMISSIONS, false, userId);
            grantRuntimePermissions(textClassifierPackage, SMS_PERMISSIONS, false, userId);
            grantRuntimePermissions(textClassifierPackage, CALENDAR_PERMISSIONS, false, userId);
            grantRuntimePermissions(textClassifierPackage, LOCATION_PERMISSIONS, false, userId);
            grantRuntimePermissions(textClassifierPackage, CONTACTS_PERMISSIONS, false, userId);
        }
        PackageParser.Package sharedStorageBackupPackage = getSystemPackage(BackupManagerService.SHARED_BACKUP_AGENT_PACKAGE);
        if (sharedStorageBackupPackage != null) {
            grantRuntimePermissions(sharedStorageBackupPackage, STORAGE_PERMISSIONS, true, userId);
        }
        if (this.mPermissionGrantedCallback != null) {
            this.mPermissionGrantedCallback.onDefaultRuntimePermissionsGranted(userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSystemDialerApp(PackageParser.Package dialerPackage, int userId) {
        if (doesPackageSupportRuntimePermissions(dialerPackage)) {
            boolean isPhonePermFixed = this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.watch", 0);
            grantRuntimePermissions(dialerPackage, PHONE_PERMISSIONS, isPhonePermFixed, userId);
            grantRuntimePermissions(dialerPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(dialerPackage, SMS_PERMISSIONS, userId);
            grantRuntimePermissions(dialerPackage, MICROPHONE_PERMISSIONS, userId);
            grantRuntimePermissions(dialerPackage, CAMERA_PERMISSIONS, userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSystemSmsApp(PackageParser.Package smsPackage, int userId) {
        if (doesPackageSupportRuntimePermissions(smsPackage)) {
            grantRuntimePermissions(smsPackage, PHONE_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, CONTACTS_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, SMS_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, STORAGE_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, MICROPHONE_PERMISSIONS, userId);
            grantRuntimePermissions(smsPackage, CAMERA_PERMISSIONS, userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSystemUseOpenWifiApp(PackageParser.Package useOpenWifiPackage, int userId) {
        if (doesPackageSupportRuntimePermissions(useOpenWifiPackage)) {
            grantRuntimePermissions(useOpenWifiPackage, COARSE_LOCATION_PERMISSIONS, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultSmsApp(String packageName, int userId) {
        PackageParser.Package smsPackage;
        Log.i(TAG, "Granting permissions to default sms app for user:" + userId);
        if (packageName != null && (smsPackage = getPackage(packageName)) != null && doesPackageSupportRuntimePermissions(smsPackage)) {
            grantRuntimePermissions(smsPackage, PHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, CONTACTS_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, SMS_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, STORAGE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, MICROPHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(smsPackage, CAMERA_PERMISSIONS, false, true, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultDialerApp(String packageName, int userId) {
        PackageParser.Package dialerPackage;
        Log.i(TAG, "Granting permissions to default dialer app for user:" + userId);
        if (packageName != null && (dialerPackage = getPackage(packageName)) != null && doesPackageSupportRuntimePermissions(dialerPackage)) {
            grantRuntimePermissions(dialerPackage, PHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(dialerPackage, CONTACTS_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(dialerPackage, SMS_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(dialerPackage, MICROPHONE_PERMISSIONS, false, true, userId);
            grantRuntimePermissions(dialerPackage, CAMERA_PERMISSIONS, false, true, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultUseOpenWifiApp(String packageName, int userId) {
        PackageParser.Package useOpenWifiPackage;
        Log.i(TAG, "Granting permissions to default Use Open WiFi app for user:" + userId);
        if (packageName != null && (useOpenWifiPackage = getPackage(packageName)) != null && doesPackageSupportRuntimePermissions(useOpenWifiPackage)) {
            grantRuntimePermissions(useOpenWifiPackage, COARSE_LOCATION_PERMISSIONS, false, true, userId);
        }
    }

    private void grantDefaultPermissionsToDefaultSimCallManager(PackageParser.Package simCallManagerPackage, int userId) {
        Log.i(TAG, "Granting permissions to sim call manager for user:" + userId);
        if (doesPackageSupportRuntimePermissions(simCallManagerPackage)) {
            grantRuntimePermissions(simCallManagerPackage, PHONE_PERMISSIONS, userId);
            grantRuntimePermissions(simCallManagerPackage, MICROPHONE_PERMISSIONS, userId);
        }
    }

    public void grantDefaultPermissionsToDefaultSimCallManager(String packageName, int userId) {
        PackageParser.Package simCallManagerPackage;
        if (packageName != null && (simCallManagerPackage = getPackage(packageName)) != null) {
            grantDefaultPermissionsToDefaultSimCallManager(simCallManagerPackage, userId);
        }
    }

    public void grantDefaultPermissionsToEnabledCarrierApps(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled carrier apps for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package carrierPackage = getSystemPackage(packageName);
            if (carrierPackage != null && doesPackageSupportRuntimePermissions(carrierPackage)) {
                grantRuntimePermissions(carrierPackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissions(carrierPackage, LOCATION_PERMISSIONS, userId);
                grantRuntimePermissions(carrierPackage, SMS_PERMISSIONS, userId);
            }
        }
    }

    public void grantDefaultPermissionsToEnabledImsServices(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled ImsServices for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package imsServicePackage = getSystemPackage(packageName);
            if (imsServicePackage != null && doesPackageSupportRuntimePermissions(imsServicePackage)) {
                grantRuntimePermissions(imsServicePackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissions(imsServicePackage, MICROPHONE_PERMISSIONS, userId);
                grantRuntimePermissions(imsServicePackage, LOCATION_PERMISSIONS, userId);
                grantRuntimePermissions(imsServicePackage, CAMERA_PERMISSIONS, userId);
                grantRuntimePermissions(imsServicePackage, CONTACTS_PERMISSIONS, userId);
            }
        }
    }

    public void grantDefaultPermissionsToEnabledTelephonyDataServices(String[] packageNames, int userId) {
        Log.i(TAG, "Granting permissions to enabled data services for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package dataServicePackage = getSystemPackage(packageName);
            if (dataServicePackage != null && doesPackageSupportRuntimePermissions(dataServicePackage)) {
                grantRuntimePermissions(dataServicePackage, PHONE_PERMISSIONS, true, userId);
                grantRuntimePermissions(dataServicePackage, LOCATION_PERMISSIONS, true, userId);
            }
        }
    }

    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(String[] packageNames, int userId) {
        Log.i(TAG, "Revoking permissions from disabled data services for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package dataServicePackage = getSystemPackage(packageName);
            if (dataServicePackage != null && doesPackageSupportRuntimePermissions(dataServicePackage)) {
                revokeRuntimePermissions(dataServicePackage, PHONE_PERMISSIONS, true, userId);
                revokeRuntimePermissions(dataServicePackage, LOCATION_PERMISSIONS, true, userId);
            }
        }
    }

    public void grantDefaultPermissionsToActiveLuiApp(String packageName, int userId) {
        PackageParser.Package luiAppPackage;
        Log.i(TAG, "Granting permissions to active LUI app for user:" + userId);
        if (packageName != null && (luiAppPackage = getSystemPackage(packageName)) != null && doesPackageSupportRuntimePermissions(luiAppPackage)) {
            grantRuntimePermissions(luiAppPackage, CAMERA_PERMISSIONS, true, userId);
        }
    }

    public void revokeDefaultPermissionsFromLuiApps(String[] packageNames, int userId) {
        Log.i(TAG, "Revoke permissions from LUI apps for user:" + userId);
        if (packageNames == null) {
            return;
        }
        for (String packageName : packageNames) {
            PackageParser.Package luiAppPackage = getSystemPackage(packageName);
            if (luiAppPackage != null && doesPackageSupportRuntimePermissions(luiAppPackage)) {
                revokeRuntimePermissions(luiAppPackage, CAMERA_PERMISSIONS, true, userId);
            }
        }
    }

    public void grantDefaultPermissionsToDefaultBrowser(String packageName, int userId) {
        PackageParser.Package browserPackage;
        Log.i(TAG, "Granting permissions to default browser for user:" + userId);
        if (packageName != null && (browserPackage = getSystemPackage(packageName)) != null && doesPackageSupportRuntimePermissions(browserPackage)) {
            grantRuntimePermissions(browserPackage, LOCATION_PERMISSIONS, false, false, userId);
        }
    }

    private PackageParser.Package getDefaultSystemHandlerActivityPackage(Intent intent, int userId) {
        ResolveInfo handler = this.mServiceInternal.resolveIntent(intent, intent.resolveType(this.mContext.getContentResolver()), (int) DEFAULT_FLAGS, userId, false, Binder.getCallingUid());
        if (handler == null || handler.activityInfo == null || this.mServiceInternal.isResolveActivityComponent(handler.activityInfo)) {
            return null;
        }
        return getSystemPackage(handler.activityInfo.packageName);
    }

    private PackageParser.Package getDefaultSystemHandlerServicePackage(Intent intent, int userId) {
        List<ResolveInfo> handlers = this.mServiceInternal.queryIntentServices(intent, (int) DEFAULT_FLAGS, Binder.getCallingUid(), userId);
        if (handlers == null) {
            return null;
        }
        int handlerCount = handlers.size();
        for (int i = 0; i < handlerCount; i++) {
            ResolveInfo handler = handlers.get(i);
            PackageParser.Package handlerPackage = getSystemPackage(handler.serviceInfo.packageName);
            if (handlerPackage != null) {
                return handlerPackage;
            }
        }
        return null;
    }

    private List<PackageParser.Package> getHeadlessSyncAdapterPackages(String[] syncAdapterPackageNames, int userId) {
        PackageParser.Package syncAdapterPackage;
        List<PackageParser.Package> syncAdapterPackages = new ArrayList<>();
        Intent homeIntent = new Intent("android.intent.action.MAIN");
        homeIntent.addCategory("android.intent.category.LAUNCHER");
        for (String syncAdapterPackageName : syncAdapterPackageNames) {
            homeIntent.setPackage(syncAdapterPackageName);
            ResolveInfo homeActivity = this.mServiceInternal.resolveIntent(homeIntent, homeIntent.resolveType(this.mContext.getContentResolver()), (int) DEFAULT_FLAGS, userId, false, Binder.getCallingUid());
            if (homeActivity == null && (syncAdapterPackage = getSystemPackage(syncAdapterPackageName)) != null) {
                syncAdapterPackages.add(syncAdapterPackage);
            }
        }
        return syncAdapterPackages;
    }

    private PackageParser.Package getDefaultProviderAuthorityPackage(String authority, int userId) {
        ProviderInfo provider = this.mServiceInternal.resolveContentProvider(authority, (int) DEFAULT_FLAGS, userId);
        if (provider != null) {
            return getSystemPackage(provider.packageName);
        }
        return null;
    }

    private PackageParser.Package getPackage(String packageName) {
        return this.mServiceInternal.getPackage(packageName);
    }

    private PackageParser.Package getSystemPackage(String packageName) {
        PackageParser.Package pkg = getPackage(packageName);
        if (pkg == null || !pkg.isSystem() || isSysComponentOrPersistentPlatformSignedPrivApp(pkg)) {
            return null;
        }
        return pkg;
    }

    private void grantRuntimePermissions(PackageParser.Package pkg, Set<String> permissions, int userId) {
        grantRuntimePermissions(pkg, permissions, false, false, userId);
    }

    private void grantRuntimePermissions(PackageParser.Package pkg, Set<String> permissions, boolean systemFixed, int userId) {
        grantRuntimePermissions(pkg, permissions, systemFixed, false, userId);
    }

    private void revokeRuntimePermissions(PackageParser.Package pkg, Set<String> permissions, boolean systemFixed, int userId) {
        if (pkg.requestedPermissions.isEmpty()) {
            return;
        }
        Set<String> revokablePermissions = new ArraySet<>(pkg.requestedPermissions);
        for (String permission : permissions) {
            if (revokablePermissions.contains(permission)) {
                int flags = this.mServiceInternal.getPermissionFlagsTEMP(permission, pkg.packageName, userId);
                if ((flags & 32) != 0 && (flags & 4) == 0 && ((flags & 16) == 0 || systemFixed)) {
                    this.mServiceInternal.revokeRuntimePermission(pkg.packageName, permission, userId, false);
                    this.mServiceInternal.updatePermissionFlagsTEMP(permission, pkg.packageName, 32, 0, userId);
                }
            }
        }
    }

    private void grantRuntimePermissions(PackageParser.Package pkg, Set<String> permissions, boolean systemFixed, boolean ignoreSystemPackage, int userId) {
        String permission;
        PackageParser.Package disabledPkg;
        if (pkg.requestedPermissions.isEmpty()) {
            return;
        }
        List<String> requestedPermissions = pkg.requestedPermissions;
        Set<String> grantablePermissions = null;
        if (!ignoreSystemPackage && pkg.isUpdatedSystemApp() && (disabledPkg = this.mServiceInternal.getDisabledPackage(pkg.packageName)) != null) {
            if (disabledPkg.requestedPermissions.isEmpty()) {
                return;
            }
            if (!requestedPermissions.equals(disabledPkg.requestedPermissions)) {
                grantablePermissions = new ArraySet<>(requestedPermissions);
                requestedPermissions = disabledPkg.requestedPermissions;
            }
        }
        List<String> requestedPermissions2 = requestedPermissions;
        Set<String> grantablePermissions2 = grantablePermissions;
        int grantablePermissionCount = requestedPermissions2.size();
        int i = 0;
        while (true) {
            int i2 = i;
            if (i2 < grantablePermissionCount) {
                String permission2 = requestedPermissions2.get(i2);
                if ((grantablePermissions2 == null || grantablePermissions2.contains(permission2)) && permissions.contains(permission2)) {
                    int flags = this.mServiceInternal.getPermissionFlagsTEMP(permission2, pkg.packageName, userId);
                    if (flags == 0 || ignoreSystemPackage) {
                        if ((flags & 4) == 0) {
                            this.mServiceInternal.grantRuntimePermission(pkg.packageName, permission2, userId, false);
                            int newFlags = 32;
                            if (systemFixed) {
                                newFlags = 32 | 16;
                            }
                            int newFlags2 = newFlags;
                            permission = permission2;
                            this.mServiceInternal.updatePermissionFlagsTEMP(permission2, pkg.packageName, newFlags2, newFlags2, userId);
                        }
                    } else {
                        permission = permission2;
                    }
                    if ((flags & 32) != 0 && (flags & 16) != 0 && !systemFixed) {
                        this.mServiceInternal.updatePermissionFlagsTEMP(permission, pkg.packageName, 16, 0, userId);
                    }
                }
                i = i2 + 1;
            } else {
                return;
            }
        }
    }

    private boolean isSysComponentOrPersistentPlatformSignedPrivApp(PackageParser.Package pkg) {
        if (UserHandle.getAppId(pkg.applicationInfo.uid) < 10000) {
            return true;
        }
        if (pkg.isPrivileged()) {
            PackageParser.Package disabledPkg = this.mServiceInternal.getDisabledPackage(pkg.packageName);
            if (disabledPkg != null) {
                if ((disabledPkg.applicationInfo.flags & 8) == 0) {
                    return false;
                }
            } else if ((pkg.applicationInfo.flags & 8) == 0) {
                return false;
            }
            String systemPackageName = this.mServiceInternal.getKnownPackageName(0, 0);
            PackageParser.Package systemPackage = getPackage(systemPackageName);
            return pkg.mSigningDetails.hasAncestorOrSelf(systemPackage.mSigningDetails) || systemPackage.mSigningDetails.checkCapability(pkg.mSigningDetails, 4);
        }
        return false;
    }

    private void grantDefaultPermissionExceptions(int userId) {
        this.mHandler.removeMessages(1);
        synchronized (this.mLock) {
            if (this.mGrantExceptions == null) {
                this.mGrantExceptions = readDefaultPermissionExceptionsLocked();
            }
        }
        int exceptionCount = this.mGrantExceptions.size();
        Set<String> permissions = null;
        int i = 0;
        while (i < exceptionCount) {
            String packageName = this.mGrantExceptions.keyAt(i);
            PackageParser.Package pkg = getSystemPackage(packageName);
            List<DefaultPermissionGrant> permissionGrants = this.mGrantExceptions.valueAt(i);
            int permissionGrantCount = permissionGrants.size();
            Set<String> permissions2 = permissions;
            for (int j = 0; j < permissionGrantCount; j++) {
                DefaultPermissionGrant permissionGrant = permissionGrants.get(j);
                if (permissions2 == null) {
                    permissions2 = new ArraySet<>();
                } else {
                    permissions2.clear();
                }
                permissions2.add(permissionGrant.name);
                grantRuntimePermissions(pkg, permissions2, permissionGrant.fixed, userId);
            }
            i++;
            permissions = permissions2;
        }
    }

    private File[] getDefaultPermissionFiles() {
        ArrayList<File> ret = new ArrayList<>();
        File dir = new File(Environment.getRootDirectory(), "etc/default-permissions");
        if (dir.isDirectory() && dir.canRead()) {
            Collections.addAll(ret, dir.listFiles());
        }
        File dir2 = new File(Environment.getVendorDirectory(), "etc/default-permissions");
        if (dir2.isDirectory() && dir2.canRead()) {
            Collections.addAll(ret, dir2.listFiles());
        }
        File dir3 = new File(Environment.getOdmDirectory(), "etc/default-permissions");
        if (dir3.isDirectory() && dir3.canRead()) {
            Collections.addAll(ret, dir3.listFiles());
        }
        File dir4 = new File(Environment.getProductDirectory(), "etc/default-permissions");
        if (dir4.isDirectory() && dir4.canRead()) {
            Collections.addAll(ret, dir4.listFiles());
        }
        if (this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.embedded", 0)) {
            File dir5 = new File(Environment.getOemDirectory(), "etc/default-permissions");
            if (dir5.isDirectory() && dir5.canRead()) {
                Collections.addAll(ret, dir5.listFiles());
            }
        }
        if (ret.isEmpty()) {
            return null;
        }
        return (File[]) ret.toArray(new File[0]);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public ArrayMap<String, List<DefaultPermissionGrant>> readDefaultPermissionExceptionsLocked() {
        File[] files = getDefaultPermissionFiles();
        if (files == null) {
            return new ArrayMap<>(0);
        }
        ArrayMap<String, List<DefaultPermissionGrant>> grantExceptions = new ArrayMap<>();
        for (File file : files) {
            if (!file.getPath().endsWith(".xml")) {
                Slog.i(TAG, "Non-xml file " + file + " in " + file.getParent() + " directory, ignoring");
            } else if (!file.canRead()) {
                Slog.w(TAG, "Default permissions file " + file + " cannot be read");
            } else {
                try {
                    InputStream str = new BufferedInputStream(new FileInputStream(file));
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(str, null);
                    parse(parser, grantExceptions);
                    str.close();
                } catch (IOException | XmlPullParserException e) {
                    Slog.w(TAG, "Error reading default permissions file " + file, e);
                }
            }
        }
        return grantExceptions;
    }

    private void parse(XmlPullParser parser, Map<String, List<DefaultPermissionGrant>> outGrantExceptions) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        if (TAG_EXCEPTIONS.equals(parser.getName())) {
                            parseExceptions(parser, outGrantExceptions);
                        } else {
                            Log.e(TAG, "Unknown tag " + parser.getName());
                        }
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void parseExceptions(XmlPullParser parser, Map<String, List<DefaultPermissionGrant>> outGrantExceptions) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        if (TAG_EXCEPTION.equals(parser.getName())) {
                            String packageName = parser.getAttributeValue(null, "package");
                            List<DefaultPermissionGrant> packageExceptions = outGrantExceptions.get(packageName);
                            if (packageExceptions == null) {
                                PackageParser.Package pkg = getSystemPackage(packageName);
                                if (pkg == null) {
                                    Log.w(TAG, "Unknown package:" + packageName);
                                    XmlUtils.skipCurrentTag(parser);
                                } else if (!doesPackageSupportRuntimePermissions(pkg)) {
                                    Log.w(TAG, "Skipping non supporting runtime permissions package:" + packageName);
                                    XmlUtils.skipCurrentTag(parser);
                                } else {
                                    packageExceptions = new ArrayList();
                                    outGrantExceptions.put(packageName, packageExceptions);
                                }
                            }
                            parsePermission(parser, packageExceptions);
                        } else {
                            Log.e(TAG, "Unknown tag " + parser.getName() + "under <exceptions>");
                        }
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private void parsePermission(XmlPullParser parser, List<DefaultPermissionGrant> outPackageExceptions) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        if (TAG_PERMISSION.contains(parser.getName())) {
                            String name = parser.getAttributeValue(null, "name");
                            if (name == null) {
                                Log.w(TAG, "Mandatory name attribute missing for permission tag");
                                XmlUtils.skipCurrentTag(parser);
                            } else {
                                boolean fixed = XmlUtils.readBooleanAttribute(parser, ATTR_FIXED);
                                DefaultPermissionGrant exception = new DefaultPermissionGrant(name, fixed);
                                outPackageExceptions.add(exception);
                            }
                        } else {
                            Log.e(TAG, "Unknown tag " + parser.getName() + "under <exception>");
                        }
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private static boolean doesPackageSupportRuntimePermissions(PackageParser.Package pkg) {
        return pkg.applicationInfo.targetSdkVersion > 22;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class DefaultPermissionGrant {
        final boolean fixed;
        final String name;

        public DefaultPermissionGrant(String name, boolean fixed) {
            this.name = name;
            this.fixed = fixed;
        }
    }
}
