package com.android.server;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSubtype;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.inputmethod.InputMethodUtils;
import com.android.internal.textservice.ISpellCheckerService;
import com.android.internal.textservice.ISpellCheckerServiceCallback;
import com.android.internal.textservice.ISpellCheckerSession;
import com.android.internal.textservice.ISpellCheckerSessionListener;
import com.android.internal.textservice.ITextServicesManager;
import com.android.internal.textservice.ITextServicesSessionListener;
import com.android.internal.textservice.LazyIntToIntMap;
import com.android.internal.util.DumpUtils;
import com.android.server.TextServicesManagerService;
import com.android.server.backup.BackupManagerConstants;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public class TextServicesManagerService extends ITextServicesManager.Stub {
    private static final boolean DBG = false;
    private static final String TAG = TextServicesManagerService.class.getSimpleName();
    private final Context mContext;
    private final UserManager mUserManager;
    private final SparseArray<TextServicesData> mUserData = new SparseArray<>();
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final LazyIntToIntMap mSpellCheckerOwnerUserIdMap = new LazyIntToIntMap(new IntUnaryOperator() { // from class: com.android.server.-$$Lambda$TextServicesManagerService$Gx5nx59gL-Y47MWUiJn5TqC2DLs
        @Override // java.util.function.IntUnaryOperator
        public final int applyAsInt(int i) {
            return TextServicesManagerService.lambda$new$0(TextServicesManagerService.this, i);
        }
    });
    private final TextServicesMonitor mMonitor = new TextServicesMonitor();

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class TextServicesData {
        private final Context mContext;
        private final ContentResolver mResolver;
        private final int mUserId;
        public int mUpdateCount = 0;
        private final HashMap<String, SpellCheckerInfo> mSpellCheckerMap = new HashMap<>();
        private final ArrayList<SpellCheckerInfo> mSpellCheckerList = new ArrayList<>();
        private final HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups = new HashMap<>();

        public TextServicesData(int userId, Context context) {
            this.mUserId = userId;
            this.mContext = context;
            this.mResolver = context.getContentResolver();
        }

        private void putString(String key, String str) {
            Settings.Secure.putStringForUser(this.mResolver, key, str, this.mUserId);
        }

        private String getString(String key, String defaultValue) {
            String result = Settings.Secure.getStringForUser(this.mResolver, key, this.mUserId);
            return result != null ? result : defaultValue;
        }

        private void putInt(String key, int value) {
            Settings.Secure.putIntForUser(this.mResolver, key, value, this.mUserId);
        }

        private int getInt(String key, int defaultValue) {
            return Settings.Secure.getIntForUser(this.mResolver, key, defaultValue, this.mUserId);
        }

        private boolean getBoolean(String key, boolean defaultValue) {
            return getInt(key, defaultValue ? 1 : 0) == 1;
        }

        private void putSelectedSpellChecker(String sciId) {
            putString("selected_spell_checker", sciId);
        }

        private void putSelectedSpellCheckerSubtype(int hashCode) {
            putInt("selected_spell_checker_subtype", hashCode);
        }

        private String getSelectedSpellChecker() {
            return getString("selected_spell_checker", BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        }

        public int getSelectedSpellCheckerSubtype(int defaultValue) {
            return getInt("selected_spell_checker_subtype", defaultValue);
        }

        public boolean isSpellCheckerEnabled() {
            return getBoolean("spell_checker_enabled", true);
        }

        public SpellCheckerInfo getCurrentSpellChecker() {
            String curSpellCheckerId = getSelectedSpellChecker();
            if (TextUtils.isEmpty(curSpellCheckerId)) {
                return null;
            }
            return this.mSpellCheckerMap.get(curSpellCheckerId);
        }

        public void setCurrentSpellChecker(SpellCheckerInfo sci) {
            if (sci != null) {
                putSelectedSpellChecker(sci.getId());
            } else {
                putSelectedSpellChecker(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            }
            putSelectedSpellCheckerSubtype(0);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void initializeTextServicesData() {
            this.mSpellCheckerList.clear();
            this.mSpellCheckerMap.clear();
            this.mUpdateCount++;
            PackageManager pm = this.mContext.getPackageManager();
            List<ResolveInfo> services = pm.queryIntentServicesAsUser(new Intent("android.service.textservice.SpellCheckerService"), 128, this.mUserId);
            int N = services.size();
            for (int i = 0; i < N; i++) {
                ResolveInfo ri = services.get(i);
                ServiceInfo si = ri.serviceInfo;
                ComponentName compName = new ComponentName(si.packageName, si.name);
                if (!"android.permission.BIND_TEXT_SERVICE".equals(si.permission)) {
                    Slog.w(TextServicesManagerService.TAG, "Skipping text service " + compName + ": it does not require the permission android.permission.BIND_TEXT_SERVICE");
                } else {
                    try {
                        SpellCheckerInfo sci = new SpellCheckerInfo(this.mContext, ri);
                        if (sci.getSubtypeCount() <= 0) {
                            Slog.w(TextServicesManagerService.TAG, "Skipping text service " + compName + ": it does not contain subtypes.");
                        } else {
                            this.mSpellCheckerList.add(sci);
                            this.mSpellCheckerMap.put(sci.getId(), sci);
                        }
                    } catch (IOException e) {
                        Slog.w(TextServicesManagerService.TAG, "Unable to load the spell checker " + compName, e);
                    } catch (XmlPullParserException e2) {
                        Slog.w(TextServicesManagerService.TAG, "Unable to load the spell checker " + compName, e2);
                    }
                }
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void dump(PrintWriter pw) {
            int spellCheckerIndex = 0;
            pw.println("  User #" + this.mUserId);
            pw.println("  Spell Checkers:");
            pw.println("  Spell Checkers: mUpdateCount=" + this.mUpdateCount);
            for (SpellCheckerInfo info : this.mSpellCheckerMap.values()) {
                pw.println("  Spell Checker #" + spellCheckerIndex);
                info.dump(pw, "    ");
                spellCheckerIndex++;
            }
            pw.println(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
            pw.println("  Spell Checker Bind Groups:");
            HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups = this.mSpellCheckerBindGroups;
            for (Map.Entry<String, SpellCheckerBindGroup> ent : spellCheckerBindGroups.entrySet()) {
                SpellCheckerBindGroup grp = ent.getValue();
                pw.println("    " + ent.getKey() + " " + grp + ":");
                StringBuilder sb = new StringBuilder();
                sb.append("      mInternalConnection=");
                sb.append(grp.mInternalConnection);
                pw.println(sb.toString());
                pw.println("      mSpellChecker=" + grp.mSpellChecker);
                pw.println("      mUnbindCalled=" + grp.mUnbindCalled);
                pw.println("      mConnected=" + grp.mConnected);
                int numPendingSessionRequests = grp.mPendingSessionRequests.size();
                for (int j = 0; j < numPendingSessionRequests; j++) {
                    SessionRequest req = (SessionRequest) grp.mPendingSessionRequests.get(j);
                    pw.println("      Pending Request #" + j + ":");
                    StringBuilder sb2 = new StringBuilder();
                    sb2.append("        mTsListener=");
                    sb2.append(req.mTsListener);
                    pw.println(sb2.toString());
                    pw.println("        mScListener=" + req.mScListener);
                    pw.println("        mScLocale=" + req.mLocale + " mUid=" + req.mUid);
                }
                int numOnGoingSessionRequests = grp.mOnGoingSessionRequests.size();
                for (int j2 = 0; j2 < numOnGoingSessionRequests; j2 = j2 + 1 + 1) {
                    SessionRequest req2 = (SessionRequest) grp.mOnGoingSessionRequests.get(j2);
                    pw.println("      On going Request #" + j2 + ":");
                    StringBuilder sb3 = new StringBuilder();
                    sb3.append("        mTsListener=");
                    sb3.append(req2.mTsListener);
                    pw.println(sb3.toString());
                    pw.println("        mScListener=" + req2.mScListener);
                    pw.println("        mScLocale=" + req2.mLocale + " mUid=" + req2.mUid);
                }
                int N = grp.mListeners.getRegisteredCallbackCount();
                for (int j3 = 0; j3 < N; j3++) {
                    ISpellCheckerSessionListener mScListener = grp.mListeners.getRegisteredCallbackItem(j3);
                    pw.println("      Listener #" + j3 + ":");
                    StringBuilder sb4 = new StringBuilder();
                    sb4.append("        mScListener=");
                    sb4.append(mScListener);
                    pw.println(sb4.toString());
                    pw.println("        mGroup=" + grp);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    public static final class Lifecycle extends SystemService {
        private TextServicesManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            this.mService = new TextServicesManagerService(context);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            publishBinderService("textservices", this.mService);
        }

        @Override // com.android.server.SystemService
        public void onStopUser(int userHandle) {
            this.mService.onStopUser(userHandle);
        }

        @Override // com.android.server.SystemService
        public void onUnlockUser(int userHandle) {
            this.mService.onUnlockUser(userHandle);
        }
    }

    void onStopUser(int userId) {
        synchronized (this.mLock) {
            this.mSpellCheckerOwnerUserIdMap.delete(userId);
            TextServicesData tsd = this.mUserData.get(userId);
            if (tsd == null) {
                return;
            }
            unbindServiceLocked(tsd);
            this.mUserData.remove(userId);
        }
    }

    void onUnlockUser(int userId) {
        synchronized (this.mLock) {
            initializeInternalStateLocked(userId);
        }
    }

    public TextServicesManagerService(Context context) {
        this.mContext = context;
        this.mUserManager = (UserManager) this.mContext.getSystemService(UserManager.class);
        this.mMonitor.register(context, null, UserHandle.ALL, true);
    }

    public static /* synthetic */ int lambda$new$0(TextServicesManagerService textServicesManagerService, int callingUserId) {
        long token = Binder.clearCallingIdentity();
        try {
            UserInfo parent = textServicesManagerService.mUserManager.getProfileParent(callingUserId);
            return parent != null ? parent.id : callingUserId;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void initializeInternalStateLocked(int userId) {
        if (userId != this.mSpellCheckerOwnerUserIdMap.get(userId)) {
            return;
        }
        TextServicesData tsd = this.mUserData.get(userId);
        if (tsd == null) {
            tsd = new TextServicesData(userId, this.mContext);
            this.mUserData.put(userId, tsd);
        }
        tsd.initializeTextServicesData();
        SpellCheckerInfo sci = tsd.getCurrentSpellChecker();
        if (sci == null) {
            SpellCheckerInfo sci2 = findAvailSystemSpellCheckerLocked(null, tsd);
            setCurrentSpellCheckerLocked(sci2, tsd);
        }
    }

    /* loaded from: classes.dex */
    private final class TextServicesMonitor extends PackageMonitor {
        private TextServicesMonitor() {
        }

        public void onSomePackagesChanged() {
            SpellCheckerInfo availSci;
            int userId = getChangingUserId();
            synchronized (TextServicesManagerService.this.mLock) {
                TextServicesData tsd = (TextServicesData) TextServicesManagerService.this.mUserData.get(userId);
                if (tsd == null) {
                    return;
                }
                SpellCheckerInfo sci = tsd.getCurrentSpellChecker();
                tsd.initializeTextServicesData();
                if (tsd.isSpellCheckerEnabled()) {
                    if (sci == null) {
                        TextServicesManagerService.this.setCurrentSpellCheckerLocked(TextServicesManagerService.this.findAvailSystemSpellCheckerLocked(null, tsd), tsd);
                    } else {
                        String packageName = sci.getPackageName();
                        int change = isPackageDisappearing(packageName);
                        if ((change == 3 || change == 2) && ((availSci = TextServicesManagerService.this.findAvailSystemSpellCheckerLocked(packageName, tsd)) == null || (availSci != null && !availSci.getId().equals(sci.getId())))) {
                            TextServicesManagerService.this.setCurrentSpellCheckerLocked(availSci, tsd);
                        }
                    }
                }
            }
        }
    }

    private boolean bindCurrentSpellCheckerService(Intent service, ServiceConnection conn, int flags, int userId) {
        if (service == null || conn == null) {
            String str = TAG;
            Slog.e(str, "--- bind failed: service = " + service + ", conn = " + conn + ", userId =" + userId);
            return false;
        }
        return this.mContext.bindServiceAsUser(service, conn, flags, UserHandle.of(userId));
    }

    private void unbindServiceLocked(TextServicesData tsd) {
        HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups = tsd.mSpellCheckerBindGroups;
        for (SpellCheckerBindGroup scbg : spellCheckerBindGroups.values()) {
            scbg.removeAllLocked();
        }
        spellCheckerBindGroups.clear();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public SpellCheckerInfo findAvailSystemSpellCheckerLocked(String prefPackage, TextServicesData tsd) {
        ArrayList<SpellCheckerInfo> spellCheckerList = new ArrayList<>();
        Iterator it = tsd.mSpellCheckerList.iterator();
        while (it.hasNext()) {
            SpellCheckerInfo sci = (SpellCheckerInfo) it.next();
            if ((1 & sci.getServiceInfo().applicationInfo.flags) != 0) {
                spellCheckerList.add(sci);
            }
        }
        int spellCheckersCount = spellCheckerList.size();
        if (spellCheckersCount == 0) {
            Slog.w(TAG, "no available spell checker services found");
            return null;
        }
        if (prefPackage != null) {
            for (int i = 0; i < spellCheckersCount; i++) {
                SpellCheckerInfo sci2 = spellCheckerList.get(i);
                if (prefPackage.equals(sci2.getPackageName())) {
                    return sci2;
                }
            }
        }
        Locale systemLocal = this.mContext.getResources().getConfiguration().locale;
        ArrayList<Locale> suitableLocales = InputMethodUtils.getSuitableLocalesForSpellChecker(systemLocal);
        int localeCount = suitableLocales.size();
        for (int localeIndex = 0; localeIndex < localeCount; localeIndex++) {
            Locale locale = suitableLocales.get(localeIndex);
            for (int spellCheckersIndex = 0; spellCheckersIndex < spellCheckersCount; spellCheckersIndex++) {
                SpellCheckerInfo info = spellCheckerList.get(spellCheckersIndex);
                int subtypeCount = info.getSubtypeCount();
                for (int subtypeIndex = 0; subtypeIndex < subtypeCount; subtypeIndex++) {
                    SpellCheckerSubtype subtype = info.getSubtypeAt(subtypeIndex);
                    Locale subtypeLocale = InputMethodUtils.constructLocaleFromString(subtype.getLocale());
                    if (locale.equals(subtypeLocale)) {
                        return info;
                    }
                }
            }
        }
        if (spellCheckersCount > 1) {
            Slog.w(TAG, "more than one spell checker service found, picking first");
        }
        return spellCheckerList.get(0);
    }

    public SpellCheckerInfo getCurrentSpellChecker(String locale) {
        int userId = UserHandle.getCallingUserId();
        synchronized (this.mLock) {
            TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
            if (tsd == null) {
                return null;
            }
            return tsd.getCurrentSpellChecker();
        }
    }

    public SpellCheckerSubtype getCurrentSpellCheckerSubtype(String locale, boolean allowImplicitlySelectedSubtype) {
        InputMethodSubtype currentInputMethodSubtype;
        int userId = UserHandle.getCallingUserId();
        synchronized (this.mLock) {
            TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
            if (tsd == null) {
                return null;
            }
            int subtypeHashCode = tsd.getSelectedSpellCheckerSubtype(0);
            SpellCheckerInfo sci = tsd.getCurrentSpellChecker();
            Locale systemLocale = this.mContext.getResources().getConfiguration().locale;
            if (sci == null || sci.getSubtypeCount() == 0) {
                return null;
            }
            if (subtypeHashCode == 0 && !allowImplicitlySelectedSubtype) {
                return null;
            }
            String candidateLocale = null;
            if (subtypeHashCode == 0) {
                InputMethodManager imm = (InputMethodManager) this.mContext.getSystemService(InputMethodManager.class);
                if (imm != null && (currentInputMethodSubtype = imm.getCurrentInputMethodSubtype()) != null) {
                    String localeString = currentInputMethodSubtype.getLocale();
                    if (!TextUtils.isEmpty(localeString)) {
                        candidateLocale = localeString;
                    }
                }
                if (candidateLocale == null) {
                    candidateLocale = systemLocale.toString();
                }
            }
            SpellCheckerSubtype candidate = null;
            for (int i = 0; i < sci.getSubtypeCount(); i++) {
                SpellCheckerSubtype scs = sci.getSubtypeAt(i);
                if (subtypeHashCode == 0) {
                    String scsLocale = scs.getLocale();
                    if (candidateLocale.equals(scsLocale)) {
                        return scs;
                    }
                    if (candidate == null && candidateLocale.length() >= 2 && scsLocale.length() >= 2 && candidateLocale.startsWith(scsLocale)) {
                        candidate = scs;
                    }
                } else if (scs.hashCode() == subtypeHashCode) {
                    return scs;
                }
            }
            return candidate;
        }
    }

    public void getSpellCheckerService(String sciId, String locale, ITextServicesSessionListener tsListener, ISpellCheckerSessionListener scListener, Bundle bundle) {
        if (TextUtils.isEmpty(sciId) || tsListener == null || scListener == null) {
            Slog.e(TAG, "getSpellCheckerService: Invalid input.");
            return;
        }
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this.mLock) {
            TextServicesData tsd = getDataFromCallingUserIdLocked(callingUserId);
            if (tsd == null) {
                return;
            }
            HashMap<String, SpellCheckerInfo> spellCheckerMap = tsd.mSpellCheckerMap;
            if (spellCheckerMap.containsKey(sciId)) {
                SpellCheckerInfo sci = spellCheckerMap.get(sciId);
                HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups = tsd.mSpellCheckerBindGroups;
                SpellCheckerBindGroup bindGroup = spellCheckerBindGroups.get(sciId);
                int uid = Binder.getCallingUid();
                if (bindGroup == null) {
                    long ident = Binder.clearCallingIdentity();
                    bindGroup = startSpellCheckerServiceInnerLocked(sci, tsd);
                    Binder.restoreCallingIdentity(ident);
                    if (bindGroup == null) {
                        return;
                    }
                }
                bindGroup.getISpellCheckerSessionOrQueueLocked(new SessionRequest(uid, locale, tsListener, scListener, bundle));
            }
        }
    }

    public boolean isSpellCheckerEnabled() {
        int userId = UserHandle.getCallingUserId();
        synchronized (this.mLock) {
            TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
            if (tsd == null) {
                return false;
            }
            return tsd.isSpellCheckerEnabled();
        }
    }

    private SpellCheckerBindGroup startSpellCheckerServiceInnerLocked(SpellCheckerInfo info, TextServicesData tsd) {
        String sciId = info.getId();
        InternalServiceConnection connection = new InternalServiceConnection(sciId, tsd.mSpellCheckerBindGroups);
        Intent serviceIntent = new Intent("android.service.textservice.SpellCheckerService");
        serviceIntent.setComponent(info.getComponent());
        if (!bindCurrentSpellCheckerService(serviceIntent, connection, 8388609, tsd.mUserId)) {
            Slog.e(TAG, "Failed to get a spell checker service.");
            return null;
        }
        SpellCheckerBindGroup group = new SpellCheckerBindGroup(connection);
        tsd.mSpellCheckerBindGroups.put(sciId, group);
        return group;
    }

    public SpellCheckerInfo[] getEnabledSpellCheckers() {
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this.mLock) {
            TextServicesData tsd = getDataFromCallingUserIdLocked(callingUserId);
            if (tsd == null) {
                return null;
            }
            ArrayList<SpellCheckerInfo> spellCheckerList = tsd.mSpellCheckerList;
            return (SpellCheckerInfo[]) spellCheckerList.toArray(new SpellCheckerInfo[spellCheckerList.size()]);
        }
    }

    public void finishSpellCheckerService(ISpellCheckerSessionListener listener) {
        int userId = UserHandle.getCallingUserId();
        synchronized (this.mLock) {
            TextServicesData tsd = getDataFromCallingUserIdLocked(userId);
            if (tsd == null) {
                return;
            }
            ArrayList<SpellCheckerBindGroup> removeList = new ArrayList<>();
            HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups = tsd.mSpellCheckerBindGroups;
            for (SpellCheckerBindGroup group : spellCheckerBindGroups.values()) {
                if (group != null) {
                    removeList.add(group);
                }
            }
            int removeSize = removeList.size();
            for (int i = 0; i < removeSize; i++) {
                removeList.get(i).removeListener(listener);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setCurrentSpellCheckerLocked(SpellCheckerInfo sci, TextServicesData tsd) {
        if (sci != null) {
            sci.getId();
        }
        long ident = Binder.clearCallingIdentity();
        try {
            tsd.setCurrentSpellChecker(sci);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            if (args.length == 0 || (args.length == 1 && args[0].equals("-a"))) {
                synchronized (this.mLock) {
                    pw.println("Current Text Services Manager state:");
                    pw.println("  Users:");
                    int numOfUsers = this.mUserData.size();
                    for (int i = 0; i < numOfUsers; i++) {
                        this.mUserData.valueAt(i).dump(pw);
                    }
                }
            } else if (args.length != 2 || !args[0].equals("--user")) {
                pw.println("Invalid arguments to text services.");
            } else {
                int userId = Integer.parseInt(args[1]);
                UserInfo userInfo = this.mUserManager.getUserInfo(userId);
                if (userInfo == null) {
                    pw.println("Non-existent user.");
                    return;
                }
                TextServicesData tsd = this.mUserData.get(userId);
                if (tsd == null) {
                    pw.println("User needs to unlock first.");
                    return;
                }
                synchronized (this.mLock) {
                    pw.println("Current Text Services Manager state:");
                    pw.println("  User " + userId + ":");
                    tsd.dump(pw);
                }
            }
        }
    }

    private TextServicesData getDataFromCallingUserIdLocked(int callingUserId) {
        SpellCheckerInfo info;
        int spellCheckerOwnerUserId = this.mSpellCheckerOwnerUserIdMap.get(callingUserId);
        TextServicesData data = this.mUserData.get(spellCheckerOwnerUserId);
        if (spellCheckerOwnerUserId != callingUserId) {
            if (data == null || (info = data.getCurrentSpellChecker()) == null) {
                return null;
            }
            ServiceInfo serviceInfo = info.getServiceInfo();
            if ((serviceInfo.applicationInfo.flags & 1) == 0) {
                return null;
            }
        }
        return data;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class SessionRequest {
        public final Bundle mBundle;
        public final String mLocale;
        public final ISpellCheckerSessionListener mScListener;
        public final ITextServicesSessionListener mTsListener;
        public final int mUid;

        SessionRequest(int uid, String locale, ITextServicesSessionListener tsListener, ISpellCheckerSessionListener scListener, Bundle bundle) {
            this.mUid = uid;
            this.mLocale = locale;
            this.mTsListener = tsListener;
            this.mScListener = scListener;
            this.mBundle = bundle;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SpellCheckerBindGroup {
        private boolean mConnected;
        private final InternalServiceConnection mInternalConnection;
        private ISpellCheckerService mSpellChecker;
        HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups;
        private boolean mUnbindCalled;
        private final String TAG = SpellCheckerBindGroup.class.getSimpleName();
        private final ArrayList<SessionRequest> mPendingSessionRequests = new ArrayList<>();
        private final ArrayList<SessionRequest> mOnGoingSessionRequests = new ArrayList<>();
        private final InternalDeathRecipients mListeners = new InternalDeathRecipients(this);

        public SpellCheckerBindGroup(InternalServiceConnection connection) {
            this.mInternalConnection = connection;
            this.mSpellCheckerBindGroups = connection.mSpellCheckerBindGroups;
        }

        public void onServiceConnectedLocked(ISpellCheckerService spellChecker) {
            if (this.mUnbindCalled) {
                return;
            }
            this.mSpellChecker = spellChecker;
            this.mConnected = true;
            try {
                int size = this.mPendingSessionRequests.size();
                for (int i = 0; i < size; i++) {
                    SessionRequest request = this.mPendingSessionRequests.get(i);
                    this.mSpellChecker.getISpellCheckerSession(request.mLocale, request.mScListener, request.mBundle, new ISpellCheckerServiceCallbackBinder(this, request));
                    this.mOnGoingSessionRequests.add(request);
                }
                this.mPendingSessionRequests.clear();
            } catch (RemoteException e) {
                removeAllLocked();
            }
            cleanLocked();
        }

        public void onServiceDisconnectedLocked() {
            this.mSpellChecker = null;
            this.mConnected = false;
        }

        public void removeListener(ISpellCheckerSessionListener listener) {
            synchronized (TextServicesManagerService.this.mLock) {
                this.mListeners.unregister(listener);
                final IBinder scListenerBinder = listener.asBinder();
                Predicate<SessionRequest> removeCondition = new Predicate() { // from class: com.android.server.-$$Lambda$TextServicesManagerService$SpellCheckerBindGroup$WPb2Qavn5gWhsY_rCdz_4UGBTAw
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        return TextServicesManagerService.SpellCheckerBindGroup.lambda$removeListener$0(scListenerBinder, (TextServicesManagerService.SessionRequest) obj);
                    }
                };
                this.mPendingSessionRequests.removeIf(removeCondition);
                this.mOnGoingSessionRequests.removeIf(removeCondition);
                cleanLocked();
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ boolean lambda$removeListener$0(IBinder scListenerBinder, SessionRequest request) {
            return request.mScListener.asBinder() == scListenerBinder;
        }

        private void cleanLocked() {
            if (this.mUnbindCalled || this.mListeners.getRegisteredCallbackCount() > 0 || !this.mPendingSessionRequests.isEmpty() || !this.mOnGoingSessionRequests.isEmpty()) {
                return;
            }
            String sciId = this.mInternalConnection.mSciId;
            SpellCheckerBindGroup cur = this.mSpellCheckerBindGroups.get(sciId);
            if (cur == this) {
                this.mSpellCheckerBindGroups.remove(sciId);
            }
            TextServicesManagerService.this.mContext.unbindService(this.mInternalConnection);
            this.mUnbindCalled = true;
        }

        public void removeAllLocked() {
            Slog.e(this.TAG, "Remove the spell checker bind unexpectedly.");
            int size = this.mListeners.getRegisteredCallbackCount();
            for (int i = size - 1; i >= 0; i--) {
                this.mListeners.unregister(this.mListeners.getRegisteredCallbackItem(i));
            }
            this.mPendingSessionRequests.clear();
            this.mOnGoingSessionRequests.clear();
            cleanLocked();
        }

        public void getISpellCheckerSessionOrQueueLocked(SessionRequest request) {
            if (this.mUnbindCalled) {
                return;
            }
            this.mListeners.register(request.mScListener);
            if (!this.mConnected) {
                this.mPendingSessionRequests.add(request);
                return;
            }
            try {
                this.mSpellChecker.getISpellCheckerSession(request.mLocale, request.mScListener, request.mBundle, new ISpellCheckerServiceCallbackBinder(this, request));
                this.mOnGoingSessionRequests.add(request);
            } catch (RemoteException e) {
                removeAllLocked();
            }
            cleanLocked();
        }

        void onSessionCreated(ISpellCheckerSession newSession, SessionRequest request) {
            synchronized (TextServicesManagerService.this.mLock) {
                if (this.mUnbindCalled) {
                    return;
                }
                if (this.mOnGoingSessionRequests.remove(request)) {
                    try {
                        request.mTsListener.onServiceConnected(newSession);
                    } catch (RemoteException e) {
                    }
                }
                cleanLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class InternalServiceConnection implements ServiceConnection {
        private final String mSciId;
        private final HashMap<String, SpellCheckerBindGroup> mSpellCheckerBindGroups;

        public InternalServiceConnection(String id, HashMap<String, SpellCheckerBindGroup> spellCheckerBindGroups) {
            this.mSciId = id;
            this.mSpellCheckerBindGroups = spellCheckerBindGroups;
        }

        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (TextServicesManagerService.this.mLock) {
                onServiceConnectedInnerLocked(name, service);
            }
        }

        private void onServiceConnectedInnerLocked(ComponentName name, IBinder service) {
            ISpellCheckerService spellChecker = ISpellCheckerService.Stub.asInterface(service);
            SpellCheckerBindGroup group = this.mSpellCheckerBindGroups.get(this.mSciId);
            if (group != null && this == group.mInternalConnection) {
                group.onServiceConnectedLocked(spellChecker);
            }
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            synchronized (TextServicesManagerService.this.mLock) {
                onServiceDisconnectedInnerLocked(name);
            }
        }

        private void onServiceDisconnectedInnerLocked(ComponentName name) {
            SpellCheckerBindGroup group = this.mSpellCheckerBindGroups.get(this.mSciId);
            if (group != null && this == group.mInternalConnection) {
                group.onServiceDisconnectedLocked();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class InternalDeathRecipients extends RemoteCallbackList<ISpellCheckerSessionListener> {
        private final SpellCheckerBindGroup mGroup;

        public InternalDeathRecipients(SpellCheckerBindGroup group) {
            this.mGroup = group;
        }

        @Override // android.os.RemoteCallbackList
        public void onCallbackDied(ISpellCheckerSessionListener listener) {
            this.mGroup.removeListener(listener);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class ISpellCheckerServiceCallbackBinder extends ISpellCheckerServiceCallback.Stub {
        private final SpellCheckerBindGroup mBindGroup;
        private final SessionRequest mRequest;

        ISpellCheckerServiceCallbackBinder(SpellCheckerBindGroup bindGroup, SessionRequest request) {
            this.mBindGroup = bindGroup;
            this.mRequest = request;
        }

        public void onSessionCreated(ISpellCheckerSession newSession) {
            this.mBindGroup.onSessionCreated(newSession, this.mRequest);
        }
    }
}
