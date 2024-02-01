package com.android.server.notification;

import android.app.Person;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LruCache;
import android.util.Slog;
import com.android.server.pm.PackageManagerService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/* loaded from: classes.dex */
public class ValidateNotificationPeople implements NotificationSignalExtractor {
    private static final boolean ENABLE_PEOPLE_VALIDATOR = true;
    private static final int MAX_PEOPLE = 10;
    static final float NONE = 0.0f;
    private static final int PEOPLE_CACHE_SIZE = 200;
    private static final String SETTING_ENABLE_PEOPLE_VALIDATOR = "validate_notification_people_enabled";
    static final float STARRED_CONTACT = 1.0f;
    static final float VALID_CONTACT = 0.5f;
    private Context mBaseContext;
    protected boolean mEnabled;
    private int mEvictionCount;
    private Handler mHandler;
    private ContentObserver mObserver;
    private LruCache<String, LookupResult> mPeopleCache;
    private NotificationUsageStats mUsageStats;
    private Map<Integer, Context> mUserToContextMap;
    private static final String TAG = "ValidateNoPeople";
    private static final boolean VERBOSE = Log.isLoggable(TAG, 2);
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static final String[] LOOKUP_PROJECTION = {"_id", "starred"};

    static /* synthetic */ int access$108(ValidateNotificationPeople x0) {
        int i = x0.mEvictionCount;
        x0.mEvictionCount = i + 1;
        return i;
    }

    @Override // com.android.server.notification.NotificationSignalExtractor
    public void initialize(Context context, NotificationUsageStats usageStats) {
        if (DEBUG) {
            Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
        }
        this.mUserToContextMap = new ArrayMap();
        this.mBaseContext = context;
        this.mUsageStats = usageStats;
        this.mPeopleCache = new LruCache<>(200);
        this.mEnabled = 1 == Settings.Global.getInt(this.mBaseContext.getContentResolver(), SETTING_ENABLE_PEOPLE_VALIDATOR, 1);
        if (this.mEnabled) {
            this.mHandler = new Handler();
            this.mObserver = new ContentObserver(this.mHandler) { // from class: com.android.server.notification.ValidateNotificationPeople.1
                @Override // android.database.ContentObserver
                public void onChange(boolean selfChange, Uri uri, int userId) {
                    super.onChange(selfChange, uri, userId);
                    if ((ValidateNotificationPeople.DEBUG || ValidateNotificationPeople.this.mEvictionCount % 100 == 0) && ValidateNotificationPeople.VERBOSE) {
                        Slog.i(ValidateNotificationPeople.TAG, "mEvictionCount: " + ValidateNotificationPeople.this.mEvictionCount);
                    }
                    ValidateNotificationPeople.this.mPeopleCache.evictAll();
                    ValidateNotificationPeople.access$108(ValidateNotificationPeople.this);
                }
            };
            this.mBaseContext.getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, this.mObserver, -1);
        }
    }

    @Override // com.android.server.notification.NotificationSignalExtractor
    public RankingReconsideration process(NotificationRecord record) {
        if (!this.mEnabled) {
            if (VERBOSE) {
                Slog.i(TAG, "disabled");
            }
            return null;
        } else if (record == null || record.getNotification() == null) {
            if (VERBOSE) {
                Slog.i(TAG, "skipping empty notification");
            }
            return null;
        } else if (record.getUserId() == -1) {
            if (VERBOSE) {
                Slog.i(TAG, "skipping global notification");
            }
            return null;
        } else {
            Context context = getContextAsUser(record.getUser());
            if (context == null) {
                if (VERBOSE) {
                    Slog.i(TAG, "skipping notification that lacks a context");
                }
                return null;
            }
            return validatePeople(context, record);
        }
    }

    @Override // com.android.server.notification.NotificationSignalExtractor
    public void setConfig(RankingConfig config) {
    }

    @Override // com.android.server.notification.NotificationSignalExtractor
    public void setZenHelper(ZenModeHelper helper) {
    }

    public float getContactAffinity(UserHandle userHandle, Bundle extras, int timeoutMs, float timeoutAffinity) {
        if (DEBUG) {
            Slog.d(TAG, "checking affinity for " + userHandle);
        }
        if (extras == null) {
            return NONE;
        }
        String key = Long.toString(System.nanoTime());
        float[] affinityOut = new float[1];
        Context context = getContextAsUser(userHandle);
        if (context == null) {
            return NONE;
        }
        final PeopleRankingReconsideration prr = validatePeople(context, key, extras, null, affinityOut);
        float affinity = affinityOut[0];
        if (prr != null) {
            final Semaphore s = new Semaphore(0);
            AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() { // from class: com.android.server.notification.ValidateNotificationPeople.2
                @Override // java.lang.Runnable
                public void run() {
                    prr.work();
                    s.release();
                }
            });
            try {
                if (!s.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                    Slog.w(TAG, "Timeout while waiting for affinity: " + key + ". Returning timeoutAffinity=" + timeoutAffinity);
                    return timeoutAffinity;
                }
                return Math.max(prr.getContactAffinity(), affinity);
            } catch (InterruptedException e) {
                Slog.w(TAG, "InterruptedException while waiting for affinity: " + key + ". Returning affinity=" + affinity, e);
                return affinity;
            }
        }
        return affinity;
    }

    private Context getContextAsUser(UserHandle userHandle) {
        Context context = this.mUserToContextMap.get(Integer.valueOf(userHandle.getIdentifier()));
        if (context == null) {
            try {
                context = this.mBaseContext.createPackageContextAsUser(PackageManagerService.PLATFORM_PACKAGE_NAME, 0, userHandle);
                this.mUserToContextMap.put(Integer.valueOf(userHandle.getIdentifier()), context);
                return context;
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "failed to create package context for lookups", e);
                return context;
            }
        }
        return context;
    }

    private RankingReconsideration validatePeople(Context context, NotificationRecord record) {
        boolean z;
        String key = record.getKey();
        Bundle extras = record.getNotification().extras;
        float[] affinityOut = new float[1];
        PeopleRankingReconsideration rr = validatePeople(context, key, extras, record.getPeopleOverride(), affinityOut);
        boolean z2 = false;
        float affinity = affinityOut[0];
        record.setContactAffinity(affinity);
        if (rr == null) {
            NotificationUsageStats notificationUsageStats = this.mUsageStats;
            if (affinity > NONE) {
                z = true;
            } else {
                z = false;
            }
            if (affinity == 1.0f) {
                z2 = true;
            }
            notificationUsageStats.registerPeopleAffinity(record, z, z2, true);
        } else {
            rr.setRecord(record);
        }
        return rr;
    }

    /* JADX WARN: Removed duplicated region for block: B:29:0x0093 A[Catch: all -> 0x00a7, TryCatch #0 {, blocks: (B:19:0x0068, B:21:0x007a, B:24:0x0081, B:26:0x0085, B:29:0x0093, B:30:0x009c, B:31:0x009d, B:27:0x008e), top: B:50:0x0068 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private com.android.server.notification.ValidateNotificationPeople.PeopleRankingReconsideration validatePeople(android.content.Context r17, java.lang.String r18, android.os.Bundle r19, java.util.List<java.lang.String> r20, float[] r21) {
        /*
            Method dump skipped, instructions count: 249
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.ValidateNotificationPeople.validatePeople(android.content.Context, java.lang.String, android.os.Bundle, java.util.List, float[]):com.android.server.notification.ValidateNotificationPeople$PeopleRankingReconsideration");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String getCacheKey(int userId, String handle) {
        return Integer.toString(userId) + ":" + handle;
    }

    public static String[] getExtraPeople(Bundle extras) {
        String[] peopleList = getExtraPeopleForKey(extras, "android.people.list");
        String[] legacyPeople = getExtraPeopleForKey(extras, "android.people");
        return combineLists(legacyPeople, peopleList);
    }

    private static String[] combineLists(String[] first, String[] second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        ArraySet<String> people = new ArraySet<>(first.length + second.length);
        for (String person : first) {
            people.add(person);
        }
        for (String person2 : second) {
            people.add(person2);
        }
        return (String[]) people.toArray();
    }

    private static String[] getExtraPeopleForKey(Bundle extras, String key) {
        Object people = extras.get(key);
        if (people instanceof String[]) {
            return (String[]) people;
        }
        if (people instanceof ArrayList) {
            ArrayList arrayList = (ArrayList) people;
            if (arrayList.isEmpty()) {
                return null;
            }
            if (arrayList.get(0) instanceof String) {
                return (String[]) arrayList.toArray(new String[arrayList.size()]);
            }
            if (arrayList.get(0) instanceof CharSequence) {
                int N = arrayList.size();
                String[] array = new String[N];
                for (int i = 0; i < N; i++) {
                    array[i] = ((CharSequence) arrayList.get(i)).toString();
                }
                return array;
            } else if (arrayList.get(0) instanceof Person) {
                int N2 = arrayList.size();
                String[] array2 = new String[N2];
                for (int i2 = 0; i2 < N2; i2++) {
                    array2[i2] = ((Person) arrayList.get(i2)).resolveToLegacyUri();
                }
                return array2;
            } else {
                return null;
            }
        } else if (people instanceof String) {
            return new String[]{(String) people};
        } else {
            if (people instanceof char[]) {
                return new String[]{new String((char[]) people)};
            }
            if (people instanceof CharSequence) {
                return new String[]{((CharSequence) people).toString()};
            }
            if (people instanceof CharSequence[]) {
                CharSequence[] charSeqArray = (CharSequence[]) people;
                int N3 = charSeqArray.length;
                String[] array3 = new String[N3];
                for (int i3 = 0; i3 < N3; i3++) {
                    array3[i3] = charSeqArray[i3].toString();
                }
                return array3;
            }
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public LookupResult resolvePhoneContact(Context context, String number) {
        Uri phoneUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        return searchContacts(context, phoneUri);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public LookupResult resolveEmailContact(Context context, String email) {
        Uri numberUri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, Uri.encode(email));
        return searchContacts(context, numberUri);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code restructure failed: missing block: B:17:0x003b, code lost:
        if (0 == 0) goto L15;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public com.android.server.notification.ValidateNotificationPeople.LookupResult searchContacts(android.content.Context r10, android.net.Uri r11) {
        /*
            r9 = this;
            java.lang.String r0 = "ValidateNoPeople"
            com.android.server.notification.ValidateNotificationPeople$LookupResult r1 = new com.android.server.notification.ValidateNotificationPeople$LookupResult
            r1.<init>()
            r2 = 0
            android.content.ContentResolver r3 = r10.getContentResolver()     // Catch: java.lang.Throwable -> L34
            java.lang.String[] r5 = com.android.server.notification.ValidateNotificationPeople.LOOKUP_PROJECTION     // Catch: java.lang.Throwable -> L34
            r6 = 0
            r7 = 0
            r8 = 0
            r4 = r11
            android.database.Cursor r3 = r3.query(r4, r5, r6, r7, r8)     // Catch: java.lang.Throwable -> L34
            r2 = r3
            if (r2 != 0) goto L25
            java.lang.String r3 = "Null cursor from contacts query."
            android.util.Slog.w(r0, r3)     // Catch: java.lang.Throwable -> L34
            if (r2 == 0) goto L24
            r2.close()
        L24:
            return r1
        L25:
            boolean r3 = r2.moveToNext()     // Catch: java.lang.Throwable -> L34
            if (r3 == 0) goto L2f
            r1.mergeContact(r2)     // Catch: java.lang.Throwable -> L34
            goto L25
        L2f:
        L30:
            r2.close()
            goto L3e
        L34:
            r3 = move-exception
            java.lang.String r4 = "Problem getting content resolver or performing contacts query."
            android.util.Slog.w(r0, r4, r3)     // Catch: java.lang.Throwable -> L3f
            if (r2 == 0) goto L3e
            goto L30
        L3e:
            return r1
        L3f:
            r0 = move-exception
            if (r2 == 0) goto L45
            r2.close()
        L45:
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.notification.ValidateNotificationPeople.searchContacts(android.content.Context, android.net.Uri):com.android.server.notification.ValidateNotificationPeople$LookupResult");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class LookupResult {
        private static final long CONTACT_REFRESH_MILLIS = 3600000;
        private float mAffinity = ValidateNotificationPeople.NONE;
        private final long mExpireMillis = System.currentTimeMillis() + 3600000;

        public void mergeContact(Cursor cursor) {
            this.mAffinity = Math.max(this.mAffinity, 0.5f);
            int idIdx = cursor.getColumnIndex("_id");
            if (idIdx < 0) {
                Slog.i(ValidateNotificationPeople.TAG, "invalid cursor: no _ID");
            } else {
                int id = cursor.getInt(idIdx);
                if (ValidateNotificationPeople.DEBUG) {
                    Slog.d(ValidateNotificationPeople.TAG, "contact _ID is: " + id);
                }
            }
            int starIdx = cursor.getColumnIndex("starred");
            if (starIdx < 0) {
                if (ValidateNotificationPeople.DEBUG) {
                    Slog.d(ValidateNotificationPeople.TAG, "invalid cursor: no STARRED");
                    return;
                }
                return;
            }
            boolean isStarred = cursor.getInt(starIdx) != 0;
            if (isStarred) {
                this.mAffinity = Math.max(this.mAffinity, 1.0f);
            }
            if (ValidateNotificationPeople.DEBUG) {
                Slog.d(ValidateNotificationPeople.TAG, "contact STARRED is: " + isStarred);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean isExpired() {
            return this.mExpireMillis < System.currentTimeMillis();
        }

        private boolean isInvalid() {
            return this.mAffinity == ValidateNotificationPeople.NONE || isExpired();
        }

        public float getAffinity() {
            if (isInvalid()) {
                return ValidateNotificationPeople.NONE;
            }
            return this.mAffinity;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class PeopleRankingReconsideration extends RankingReconsideration {
        private static final long LOOKUP_TIME = 1000;
        private float mContactAffinity;
        private final Context mContext;
        private final LinkedList<String> mPendingLookups;
        private NotificationRecord mRecord;

        private PeopleRankingReconsideration(Context context, String key, LinkedList<String> pendingLookups) {
            super(key, 1000L);
            this.mContactAffinity = ValidateNotificationPeople.NONE;
            this.mContext = context;
            this.mPendingLookups = pendingLookups;
        }

        @Override // com.android.server.notification.RankingReconsideration
        public void work() {
            LookupResult lookupResult;
            if (ValidateNotificationPeople.VERBOSE) {
                Slog.i(ValidateNotificationPeople.TAG, "Executing: validation for: " + this.mKey);
            }
            long timeStartMs = System.currentTimeMillis();
            Iterator<String> it = this.mPendingLookups.iterator();
            while (it.hasNext()) {
                String handle = it.next();
                Uri uri = Uri.parse(handle);
                if ("tel".equals(uri.getScheme())) {
                    if (ValidateNotificationPeople.DEBUG) {
                        Slog.d(ValidateNotificationPeople.TAG, "checking telephone URI: " + handle);
                    }
                    lookupResult = ValidateNotificationPeople.this.resolvePhoneContact(this.mContext, uri.getSchemeSpecificPart());
                } else if ("mailto".equals(uri.getScheme())) {
                    if (ValidateNotificationPeople.DEBUG) {
                        Slog.d(ValidateNotificationPeople.TAG, "checking mailto URI: " + handle);
                    }
                    lookupResult = ValidateNotificationPeople.this.resolveEmailContact(this.mContext, uri.getSchemeSpecificPart());
                } else if (handle.startsWith(ContactsContract.Contacts.CONTENT_LOOKUP_URI.toString())) {
                    if (ValidateNotificationPeople.DEBUG) {
                        Slog.d(ValidateNotificationPeople.TAG, "checking lookup URI: " + handle);
                    }
                    lookupResult = ValidateNotificationPeople.this.searchContacts(this.mContext, uri);
                } else {
                    lookupResult = new LookupResult();
                    if (!com.android.server.pm.Settings.ATTR_NAME.equals(uri.getScheme())) {
                        Slog.w(ValidateNotificationPeople.TAG, "unsupported URI " + handle);
                    }
                }
                if (lookupResult != null) {
                    synchronized (ValidateNotificationPeople.this.mPeopleCache) {
                        String cacheKey = ValidateNotificationPeople.this.getCacheKey(this.mContext.getUserId(), handle);
                        ValidateNotificationPeople.this.mPeopleCache.put(cacheKey, lookupResult);
                    }
                    if (ValidateNotificationPeople.DEBUG) {
                        Slog.d(ValidateNotificationPeople.TAG, "lookup contactAffinity is " + lookupResult.getAffinity());
                    }
                    this.mContactAffinity = Math.max(this.mContactAffinity, lookupResult.getAffinity());
                } else if (ValidateNotificationPeople.DEBUG) {
                    Slog.d(ValidateNotificationPeople.TAG, "lookupResult is null");
                }
            }
            if (ValidateNotificationPeople.DEBUG) {
                Slog.d(ValidateNotificationPeople.TAG, "Validation finished in " + (System.currentTimeMillis() - timeStartMs) + "ms");
            }
            if (this.mRecord != null) {
                ValidateNotificationPeople.this.mUsageStats.registerPeopleAffinity(this.mRecord, this.mContactAffinity > ValidateNotificationPeople.NONE, this.mContactAffinity == 1.0f, false);
            }
        }

        @Override // com.android.server.notification.RankingReconsideration
        public void applyChangesLocked(NotificationRecord operand) {
            float affinityBound = operand.getContactAffinity();
            operand.setContactAffinity(Math.max(this.mContactAffinity, affinityBound));
            if (ValidateNotificationPeople.VERBOSE) {
                Slog.i(ValidateNotificationPeople.TAG, "final affinity: " + operand.getContactAffinity());
            }
        }

        public float getContactAffinity() {
            return this.mContactAffinity;
        }

        public void setRecord(NotificationRecord record) {
            this.mRecord = record;
        }
    }
}
