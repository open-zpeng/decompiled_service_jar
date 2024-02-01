package com.android.server.pm;

import android.app.Person;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.LocusId;
import android.content.pm.PackageInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Resources;
import android.os.PersistableBundle;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.ShareTargetInfo;
import com.android.server.pm.ShortcutService;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ShortcutPackage extends ShortcutPackageItem {
    private static final String ATTR_ACTIVITY = "activity";
    private static final String ATTR_BITMAP_PATH = "bitmap-path";
    private static final String ATTR_CALL_COUNT = "call-count";
    private static final String ATTR_DISABLED_MESSAGE = "dmessage";
    private static final String ATTR_DISABLED_MESSAGE_RES_ID = "dmessageid";
    private static final String ATTR_DISABLED_MESSAGE_RES_NAME = "dmessagename";
    private static final String ATTR_DISABLED_REASON = "disabled-reason";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_ICON_RES_ID = "icon-res";
    private static final String ATTR_ICON_RES_NAME = "icon-resname";
    private static final String ATTR_ID = "id";
    private static final String ATTR_INTENT_LEGACY = "intent";
    private static final String ATTR_INTENT_NO_EXTRA = "intent-base";
    private static final String ATTR_LAST_RESET = "last-reset";
    private static final String ATTR_LOCUS_ID = "locus-id";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_NAME_XMLUTILS = "name";
    private static final String ATTR_PERSON_IS_BOT = "is-bot";
    private static final String ATTR_PERSON_IS_IMPORTANT = "is-important";
    private static final String ATTR_PERSON_KEY = "key";
    private static final String ATTR_PERSON_NAME = "name";
    private static final String ATTR_PERSON_URI = "uri";
    private static final String ATTR_RANK = "rank";
    private static final String ATTR_TEXT = "text";
    private static final String ATTR_TEXT_RES_ID = "textid";
    private static final String ATTR_TEXT_RES_NAME = "textname";
    private static final String ATTR_TIMESTAMP = "timestamp";
    private static final String ATTR_TITLE = "title";
    private static final String ATTR_TITLE_RES_ID = "titleid";
    private static final String ATTR_TITLE_RES_NAME = "titlename";
    private static final String KEY_BITMAPS = "bitmaps";
    private static final String KEY_BITMAP_BYTES = "bitmapBytes";
    private static final String KEY_DYNAMIC = "dynamic";
    private static final String KEY_MANIFEST = "manifest";
    private static final String KEY_PINNED = "pinned";
    private static final String NAME_CATEGORIES = "categories";
    private static final String TAG = "ShortcutService";
    private static final String TAG_CATEGORIES = "categories";
    private static final String TAG_EXTRAS = "extras";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_INTENT_EXTRAS_LEGACY = "intent-extras";
    private static final String TAG_PERSON = "person";
    static final String TAG_ROOT = "package";
    private static final String TAG_SHARE_TARGET = "share-target";
    private static final String TAG_SHORTCUT = "shortcut";
    private static final String TAG_STRING_ARRAY_XMLUTILS = "string-array";
    private static final String TAG_VERIFY = "ShortcutService.verify";
    private int mApiCallCount;
    private long mLastKnownForegroundElapsedTime;
    private long mLastResetTime;
    private final int mPackageUid;
    private final ArrayList<ShareTargetInfo> mShareTargets;
    final Comparator<ShortcutInfo> mShortcutRankComparator;
    final Comparator<ShortcutInfo> mShortcutTypeAndRankComparator;
    private final ArrayMap<String, ShortcutInfo> mShortcuts;

    private ShortcutPackage(ShortcutUser shortcutUser, int packageUserId, String packageName, ShortcutPackageInfo spi) {
        super(shortcutUser, packageUserId, packageName, spi != null ? spi : ShortcutPackageInfo.newEmpty());
        this.mShortcuts = new ArrayMap<>();
        this.mShareTargets = new ArrayList<>(0);
        this.mShortcutTypeAndRankComparator = new Comparator() { // from class: com.android.server.pm.-$$Lambda$ShortcutPackage$ZN-r6tS0M7WKGK6nbXyJZPwNRGc
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return ShortcutPackage.lambda$new$1((ShortcutInfo) obj, (ShortcutInfo) obj2);
            }
        };
        this.mShortcutRankComparator = new Comparator() { // from class: com.android.server.pm.-$$Lambda$ShortcutPackage$hEXnzlESoRjagj8Pd9f4PrqudKE
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return ShortcutPackage.lambda$new$2((ShortcutInfo) obj, (ShortcutInfo) obj2);
            }
        };
        this.mPackageUid = shortcutUser.mService.injectGetPackageUid(packageName, packageUserId);
    }

    public ShortcutPackage(ShortcutUser shortcutUser, int packageUserId, String packageName) {
        this(shortcutUser, packageUserId, packageName, null);
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    public int getOwnerUserId() {
        return getPackageUserId();
    }

    public int getPackageUid() {
        return this.mPackageUid;
    }

    public Resources getPackageResources() {
        return this.mShortcutUser.mService.injectGetResourcesForApplicationAsUser(getPackageName(), getPackageUserId());
    }

    public int getShortcutCount() {
        return this.mShortcuts.size();
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    protected boolean canRestoreAnyVersion() {
        return false;
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    protected void onRestored(int restoreBlockReason) {
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            si.clearFlags(4096);
            si.setDisabledReason(restoreBlockReason);
            if (restoreBlockReason != 0) {
                si.addFlags(64);
            }
        }
        refreshPinnedFlags();
    }

    public ShortcutInfo findShortcutById(String id) {
        return this.mShortcuts.get(id);
    }

    public boolean isShortcutExistsAndInvisibleToPublisher(String id) {
        ShortcutInfo si = findShortcutById(id);
        return (si == null || si.isVisibleToPublisher()) ? false : true;
    }

    public boolean isShortcutExistsAndVisibleToPublisher(String id) {
        ShortcutInfo si = findShortcutById(id);
        return si != null && si.isVisibleToPublisher();
    }

    private void ensureNotImmutable(ShortcutInfo shortcut, boolean ignoreInvisible) {
        if (shortcut != null && shortcut.isImmutable()) {
            if (!ignoreInvisible || shortcut.isVisibleToPublisher()) {
                throw new IllegalArgumentException("Manifest shortcut ID=" + shortcut.getId() + " may not be manipulated via APIs");
            }
        }
    }

    public void ensureNotImmutable(String id, boolean ignoreInvisible) {
        ensureNotImmutable(this.mShortcuts.get(id), ignoreInvisible);
    }

    public void ensureImmutableShortcutsNotIncludedWithIds(List<String> shortcutIds, boolean ignoreInvisible) {
        for (int i = shortcutIds.size() - 1; i >= 0; i--) {
            ensureNotImmutable(shortcutIds.get(i), ignoreInvisible);
        }
    }

    public void ensureImmutableShortcutsNotIncluded(List<ShortcutInfo> shortcuts, boolean ignoreInvisible) {
        for (int i = shortcuts.size() - 1; i >= 0; i--) {
            ensureNotImmutable(shortcuts.get(i).getId(), ignoreInvisible);
        }
    }

    private ShortcutInfo forceDeleteShortcutInner(String id) {
        ShortcutInfo shortcut = this.mShortcuts.remove(id);
        if (shortcut != null) {
            this.mShortcutUser.mService.removeIconLocked(shortcut);
            shortcut.clearFlags(35);
        }
        return shortcut;
    }

    private void forceReplaceShortcutInner(ShortcutInfo newShortcut) {
        ShortcutService s = this.mShortcutUser.mService;
        forceDeleteShortcutInner(newShortcut.getId());
        s.saveIconAndFixUpShortcutLocked(newShortcut);
        s.fixUpShortcutResourceNamesAndValues(newShortcut);
        this.mShortcuts.put(newShortcut.getId(), newShortcut);
    }

    public void addOrReplaceDynamicShortcut(ShortcutInfo newShortcut) {
        boolean wasPinned;
        Preconditions.checkArgument(newShortcut.isEnabled(), "add/setDynamicShortcuts() cannot publish disabled shortcuts");
        newShortcut.addFlags(1);
        ShortcutInfo oldShortcut = this.mShortcuts.get(newShortcut.getId());
        if (oldShortcut == null) {
            wasPinned = false;
        } else {
            oldShortcut.ensureUpdatableWith(newShortcut, false);
            wasPinned = oldShortcut.isPinned();
        }
        if (wasPinned) {
            newShortcut.addFlags(2);
        }
        forceReplaceShortcutInner(newShortcut);
    }

    private void removeOrphans() {
        ArrayList<String> removeList = null;
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            if (!si.isAlive()) {
                if (removeList == null) {
                    removeList = new ArrayList<>();
                }
                removeList.add(si.getId());
            }
        }
        if (removeList != null) {
            for (int i2 = removeList.size() - 1; i2 >= 0; i2--) {
                forceDeleteShortcutInner(removeList.get(i2));
            }
        }
    }

    public void deleteAllDynamicShortcuts(boolean ignoreInvisible) {
        long now = this.mShortcutUser.mService.injectCurrentTimeMillis();
        boolean changed = false;
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            if (si.isDynamic() && (!ignoreInvisible || si.isVisibleToPublisher())) {
                changed = true;
                si.setTimestamp(now);
                si.clearFlags(1);
                si.setRank(0);
            }
        }
        if (changed) {
            removeOrphans();
        }
    }

    public boolean deleteDynamicWithId(String shortcutId, boolean ignoreInvisible) {
        ShortcutInfo removed = deleteOrDisableWithId(shortcutId, false, false, ignoreInvisible, 0);
        return removed == null;
    }

    private boolean disableDynamicWithId(String shortcutId, boolean ignoreInvisible, int disabledReason) {
        ShortcutInfo disabled = deleteOrDisableWithId(shortcutId, true, false, ignoreInvisible, disabledReason);
        return disabled == null;
    }

    public void disableWithId(String shortcutId, String disabledMessage, int disabledMessageResId, boolean overrideImmutable, boolean ignoreInvisible, int disabledReason) {
        ShortcutInfo disabled = deleteOrDisableWithId(shortcutId, true, overrideImmutable, ignoreInvisible, disabledReason);
        if (disabled != null) {
            if (disabledMessage != null) {
                disabled.setDisabledMessage(disabledMessage);
            } else if (disabledMessageResId != 0) {
                disabled.setDisabledMessageResId(disabledMessageResId);
                this.mShortcutUser.mService.fixUpShortcutResourceNamesAndValues(disabled);
            }
        }
    }

    private ShortcutInfo deleteOrDisableWithId(String shortcutId, boolean disable, boolean overrideImmutable, boolean ignoreInvisible, int disabledReason) {
        boolean z = disable == (disabledReason != 0);
        Preconditions.checkState(z, "disable and disabledReason disagree: " + disable + " vs " + disabledReason);
        ShortcutInfo oldShortcut = this.mShortcuts.get(shortcutId);
        if (oldShortcut == null || (!oldShortcut.isEnabled() && ignoreInvisible && !oldShortcut.isVisibleToPublisher())) {
            return null;
        }
        if (!overrideImmutable) {
            ensureNotImmutable(oldShortcut, true);
        }
        if (oldShortcut.isPinned()) {
            oldShortcut.setRank(0);
            oldShortcut.clearFlags(33);
            if (disable) {
                oldShortcut.addFlags(64);
                if (oldShortcut.getDisabledReason() == 0) {
                    oldShortcut.setDisabledReason(disabledReason);
                }
            }
            oldShortcut.setTimestamp(this.mShortcutUser.mService.injectCurrentTimeMillis());
            if (this.mShortcutUser.mService.isDummyMainActivity(oldShortcut.getActivity())) {
                oldShortcut.setActivity(null);
            }
            return oldShortcut;
        }
        forceDeleteShortcutInner(shortcutId);
        return null;
    }

    public void enableWithId(String shortcutId) {
        ShortcutInfo shortcut = this.mShortcuts.get(shortcutId);
        if (shortcut != null) {
            ensureNotImmutable(shortcut, true);
            shortcut.clearFlags(64);
            shortcut.setDisabledReason(0);
        }
    }

    public void updateInvisibleShortcutForPinRequestWith(ShortcutInfo shortcut) {
        ShortcutInfo source = this.mShortcuts.get(shortcut.getId());
        Preconditions.checkNotNull(source);
        this.mShortcutUser.mService.validateShortcutForPinRequest(shortcut);
        shortcut.addFlags(2);
        forceReplaceShortcutInner(shortcut);
        adjustRanks();
    }

    public void refreshPinnedFlags() {
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            this.mShortcuts.valueAt(i).clearFlags(2);
        }
        this.mShortcutUser.forAllLaunchers(new Consumer() { // from class: com.android.server.pm.-$$Lambda$ShortcutPackage$ibOAVgfKWMZFYSeVV_hLNx6jogk
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ShortcutPackage.this.lambda$refreshPinnedFlags$0$ShortcutPackage((ShortcutLauncher) obj);
            }
        });
        removeOrphans();
    }

    public /* synthetic */ void lambda$refreshPinnedFlags$0$ShortcutPackage(ShortcutLauncher launcherShortcuts) {
        ArraySet<String> pinned = launcherShortcuts.getPinnedShortcutIds(getPackageName(), getPackageUserId());
        if (pinned == null || pinned.size() == 0) {
            return;
        }
        for (int i = pinned.size() - 1; i >= 0; i--) {
            String id = pinned.valueAt(i);
            ShortcutInfo si = this.mShortcuts.get(id);
            if (si != null) {
                si.addFlags(2);
            }
        }
    }

    public int getApiCallCount(boolean unlimited) {
        ShortcutService s = this.mShortcutUser.mService;
        if (s.isUidForegroundLocked(this.mPackageUid) || this.mLastKnownForegroundElapsedTime < s.getUidLastForegroundElapsedTimeLocked(this.mPackageUid) || unlimited) {
            this.mLastKnownForegroundElapsedTime = s.injectElapsedRealtime();
            resetRateLimiting();
        }
        long last = s.getLastResetTimeLocked();
        long now = s.injectCurrentTimeMillis();
        if (ShortcutService.isClockValid(now) && this.mLastResetTime > now) {
            Slog.w(TAG, "Clock rewound");
            this.mLastResetTime = now;
            this.mApiCallCount = 0;
            return this.mApiCallCount;
        }
        if (this.mLastResetTime < last) {
            this.mApiCallCount = 0;
            this.mLastResetTime = last;
        }
        return this.mApiCallCount;
    }

    public boolean tryApiCall(boolean unlimited) {
        ShortcutService s = this.mShortcutUser.mService;
        if (getApiCallCount(unlimited) >= s.mMaxUpdatesPerInterval) {
            return false;
        }
        this.mApiCallCount++;
        s.scheduleSaveUser(getOwnerUserId());
        return true;
    }

    public void resetRateLimiting() {
        if (this.mApiCallCount > 0) {
            this.mApiCallCount = 0;
            this.mShortcutUser.mService.scheduleSaveUser(getOwnerUserId());
        }
    }

    public void resetRateLimitingForCommandLineNoSaving() {
        this.mApiCallCount = 0;
        this.mLastResetTime = 0L;
    }

    public void findAll(List<ShortcutInfo> result, Predicate<ShortcutInfo> query, int cloneFlag) {
        findAll(result, query, cloneFlag, null, 0, false);
    }

    public void findAll(List<ShortcutInfo> result, Predicate<ShortcutInfo> query, int cloneFlag, String callingLauncher, int launcherUserId, boolean getPinnedByAnyLauncher) {
        if (getPackageInfo().isShadow()) {
            return;
        }
        ShortcutService s = this.mShortcutUser.mService;
        ArraySet<String> pinnedByCallerSet = callingLauncher == null ? null : s.getLauncherShortcutsLocked(callingLauncher, getPackageUserId(), launcherUserId).getPinnedShortcutIds(getPackageName(), getPackageUserId());
        for (int i = 0; i < this.mShortcuts.size(); i++) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            boolean isPinnedByCaller = callingLauncher == null || (pinnedByCallerSet != null && pinnedByCallerSet.contains(si.getId()));
            if (getPinnedByAnyLauncher || !si.isFloating() || isPinnedByCaller) {
                ShortcutInfo clone = si.clone(cloneFlag);
                if (!getPinnedByAnyLauncher && !isPinnedByCaller) {
                    clone.clearFlags(2);
                }
                if (query == null || query.test(clone)) {
                    if (!isPinnedByCaller) {
                        clone.clearFlags(2);
                    }
                    result.add(clone);
                }
            }
        }
    }

    public void resetThrottling() {
        this.mApiCallCount = 0;
    }

    public List<ShortcutManager.ShareShortcutInfo> getMatchingShareTargets(IntentFilter filter) {
        List<ShareTargetInfo> matchedTargets = new ArrayList<>();
        for (int i = 0; i < this.mShareTargets.size(); i++) {
            ShareTargetInfo target = this.mShareTargets.get(i);
            ShareTargetInfo.TargetData[] targetDataArr = target.mTargetData;
            int length = targetDataArr.length;
            int i2 = 0;
            while (true) {
                if (i2 < length) {
                    ShareTargetInfo.TargetData data = targetDataArr[i2];
                    if (!filter.hasDataType(data.mMimeType)) {
                        i2++;
                    } else {
                        matchedTargets.add(target);
                        break;
                    }
                }
            }
        }
        if (matchedTargets.isEmpty()) {
            return new ArrayList();
        }
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        findAll(shortcuts, $$Lambda$vv6Ko6L2p38nn3EYcL5PZxcyRyk.INSTANCE, 9);
        List<ShortcutManager.ShareShortcutInfo> result = new ArrayList<>();
        for (int i3 = 0; i3 < shortcuts.size(); i3++) {
            Set<String> categories = shortcuts.get(i3).getCategories();
            if (categories != null && !categories.isEmpty()) {
                int j = 0;
                while (true) {
                    if (j < matchedTargets.size()) {
                        boolean hasAllCategories = true;
                        ShareTargetInfo target2 = matchedTargets.get(j);
                        int q = 0;
                        while (true) {
                            if (q < target2.mCategories.length) {
                                if (categories.contains(target2.mCategories[q])) {
                                    q++;
                                } else {
                                    hasAllCategories = false;
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                        if (!hasAllCategories) {
                            j++;
                        } else {
                            result.add(new ShortcutManager.ShareShortcutInfo(shortcuts.get(i3), new ComponentName(getPackageName(), target2.mTargetClass)));
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    public boolean hasShareTargets() {
        return !this.mShareTargets.isEmpty();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getSharingShortcutCount() {
        if (this.mShortcuts.isEmpty() || this.mShareTargets.isEmpty()) {
            return 0;
        }
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        findAll(shortcuts, $$Lambda$vv6Ko6L2p38nn3EYcL5PZxcyRyk.INSTANCE, 27);
        int sharingShortcutCount = 0;
        for (int i = 0; i < shortcuts.size(); i++) {
            Set<String> categories = shortcuts.get(i).getCategories();
            if (categories != null && !categories.isEmpty()) {
                int j = 0;
                while (true) {
                    if (j < this.mShareTargets.size()) {
                        boolean hasAllCategories = true;
                        ShareTargetInfo target = this.mShareTargets.get(j);
                        int q = 0;
                        while (true) {
                            if (q < target.mCategories.length) {
                                if (categories.contains(target.mCategories[q])) {
                                    q++;
                                } else {
                                    hasAllCategories = false;
                                    break;
                                }
                            } else {
                                break;
                            }
                        }
                        if (!hasAllCategories) {
                            j++;
                        } else {
                            sharingShortcutCount++;
                            break;
                        }
                    }
                }
            }
        }
        return sharingShortcutCount;
    }

    public ArraySet<String> getUsedBitmapFiles() {
        ArraySet<String> usedFiles = new ArraySet<>(this.mShortcuts.size());
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            if (si.getBitmapPath() != null) {
                usedFiles.add(getFileName(si.getBitmapPath()));
            }
        }
        return usedFiles;
    }

    private static String getFileName(String path) {
        int sep = path.lastIndexOf(File.separatorChar);
        if (sep == -1) {
            return path;
        }
        return path.substring(sep + 1);
    }

    private boolean areAllActivitiesStillEnabled() {
        if (this.mShortcuts.size() == 0) {
            return true;
        }
        ShortcutService s = this.mShortcutUser.mService;
        ArrayList<ComponentName> checked = new ArrayList<>(4);
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            ComponentName activity = si.getActivity();
            if (!checked.contains(activity)) {
                checked.add(activity);
                if (activity != null && !s.injectIsActivityEnabledAndExported(activity, getOwnerUserId())) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean rescanPackageIfNeeded(boolean isNewApp, boolean forceRescan) {
        char c;
        PackageInfo pi;
        ShortcutService s = this.mShortcutUser.mService;
        long start = s.getStatStartTime();
        try {
            PackageInfo pi2 = this.mShortcutUser.mService.getPackageInfo(getPackageName(), getPackageUserId());
            char c2 = 0;
            if (pi2 == null) {
                return false;
            }
            if (!isNewApp && !forceRescan && getPackageInfo().getVersionCode() == pi2.getLongVersionCode() && getPackageInfo().getLastUpdateTime() == pi2.lastUpdateTime) {
                if (areAllActivitiesStillEnabled()) {
                    return false;
                }
            }
            s.logDurationStat(14, start);
            List<ShortcutInfo> newManifestShortcutList = null;
            try {
                newManifestShortcutList = ShortcutParser.parseShortcuts(this.mShortcutUser.mService, getPackageName(), getPackageUserId(), this.mShareTargets);
            } catch (IOException | XmlPullParserException e) {
                Slog.e(TAG, "Failed to load shortcuts from AndroidManifest.xml.", e);
            }
            int manifestShortcutSize = newManifestShortcutList == null ? 0 : newManifestShortcutList.size();
            if (isNewApp && manifestShortcutSize == 0) {
                return false;
            }
            getPackageInfo().updateFromPackageInfo(pi2);
            long newVersionCode = getPackageInfo().getVersionCode();
            for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
                ShortcutInfo si = this.mShortcuts.valueAt(i);
                if (si.getDisabledReason() == 100 && getPackageInfo().getBackupSourceVersionCode() <= newVersionCode) {
                    Slog.i(TAG, String.format("Restoring shortcut: %s", si.getId()));
                    si.clearFlags(64);
                    si.setDisabledReason(0);
                }
            }
            if (!isNewApp) {
                Resources publisherRes = null;
                int i2 = this.mShortcuts.size() - 1;
                while (i2 >= 0) {
                    ShortcutInfo si2 = this.mShortcuts.valueAt(i2);
                    if (!si2.isDynamic()) {
                        c = c2;
                    } else if (si2.getActivity() == null) {
                        s.wtf("null activity detected.");
                        c = c2;
                    } else if (s.injectIsMainActivity(si2.getActivity(), getPackageUserId())) {
                        c = c2;
                    } else {
                        Object[] objArr = new Object[2];
                        objArr[c2] = getPackageName();
                        objArr[1] = si2.getId();
                        Slog.w(TAG, String.format("%s is no longer main activity. Disabling shorcut %s.", objArr));
                        c = 0;
                        if (disableDynamicWithId(si2.getId(), false, 2)) {
                            pi = pi2;
                            i2--;
                            pi2 = pi;
                            c2 = c;
                        }
                    }
                    if (si2.hasAnyResources()) {
                        if (!si2.isOriginallyFromManifest()) {
                            if (publisherRes == null) {
                                Resources publisherRes2 = getPackageResources();
                                if (publisherRes2 == null) {
                                    break;
                                }
                                publisherRes = publisherRes2;
                            }
                            si2.lookupAndFillInResourceIds(publisherRes);
                        }
                        pi = pi2;
                        si2.setTimestamp(s.injectCurrentTimeMillis());
                    } else {
                        pi = pi2;
                    }
                    i2--;
                    pi2 = pi;
                    c2 = c;
                }
            }
            publishManifestShortcuts(newManifestShortcutList);
            if (newManifestShortcutList != null) {
                pushOutExcessShortcuts();
            }
            s.verifyStates();
            s.packageShortcutsChanged(getPackageName(), getPackageUserId());
            return true;
        } finally {
            s.logDurationStat(14, start);
        }
    }

    private boolean publishManifestShortcuts(List<ShortcutInfo> newManifestShortcutList) {
        boolean changed = false;
        ArraySet<String> toDisableList = null;
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            if (si.isManifestShortcut()) {
                if (toDisableList == null) {
                    toDisableList = new ArraySet<>();
                }
                toDisableList.add(si.getId());
            }
        }
        if (newManifestShortcutList != null) {
            int newListSize = newManifestShortcutList.size();
            for (int i2 = 0; i2 < newListSize; i2++) {
                changed = true;
                ShortcutInfo newShortcut = newManifestShortcutList.get(i2);
                boolean newDisabled = !newShortcut.isEnabled();
                String id = newShortcut.getId();
                ShortcutInfo oldShortcut = this.mShortcuts.get(id);
                boolean wasPinned = false;
                if (oldShortcut != null) {
                    if (!oldShortcut.isOriginallyFromManifest()) {
                        Slog.e(TAG, "Shortcut with ID=" + newShortcut.getId() + " exists but is not from AndroidManifest.xml, not updating.");
                    } else if (oldShortcut.isPinned()) {
                        wasPinned = true;
                        newShortcut.addFlags(2);
                    }
                }
                if (!newDisabled || wasPinned) {
                    forceReplaceShortcutInner(newShortcut);
                    if (!newDisabled && toDisableList != null) {
                        toDisableList.remove(id);
                    }
                }
            }
        }
        if (toDisableList != null) {
            for (int i3 = toDisableList.size() - 1; i3 >= 0; i3--) {
                changed = true;
                disableWithId(toDisableList.valueAt(i3), null, 0, true, false, 2);
            }
            removeOrphans();
        }
        adjustRanks();
        return changed;
    }

    private boolean pushOutExcessShortcuts() {
        ShortcutService service = this.mShortcutUser.mService;
        int maxShortcuts = service.getMaxActivityShortcuts();
        ArrayMap<ComponentName, ArrayList<ShortcutInfo>> all = sortShortcutsToActivities();
        for (int outer = all.size() - 1; outer >= 0; outer--) {
            ArrayList<ShortcutInfo> list = all.valueAt(outer);
            if (list.size() > maxShortcuts) {
                Collections.sort(list, this.mShortcutTypeAndRankComparator);
                for (int inner = list.size() - 1; inner >= maxShortcuts; inner--) {
                    ShortcutInfo shortcut = list.get(inner);
                    if (shortcut.isManifestShortcut()) {
                        service.wtf("Found manifest shortcuts in excess list.");
                    } else {
                        deleteDynamicWithId(shortcut.getId(), true);
                    }
                }
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ int lambda$new$1(ShortcutInfo a, ShortcutInfo b) {
        if (a.isManifestShortcut() && !b.isManifestShortcut()) {
            return -1;
        }
        if (!a.isManifestShortcut() && b.isManifestShortcut()) {
            return 1;
        }
        return Integer.compare(a.getRank(), b.getRank());
    }

    private ArrayMap<ComponentName, ArrayList<ShortcutInfo>> sortShortcutsToActivities() {
        ArrayMap<ComponentName, ArrayList<ShortcutInfo>> activitiesToShortcuts = new ArrayMap<>();
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            if (!si.isFloating()) {
                ComponentName activity = si.getActivity();
                if (activity == null) {
                    this.mShortcutUser.mService.wtf("null activity detected.");
                } else {
                    ArrayList<ShortcutInfo> list = activitiesToShortcuts.get(activity);
                    if (list == null) {
                        list = new ArrayList<>();
                        activitiesToShortcuts.put(activity, list);
                    }
                    list.add(si);
                }
            }
        }
        return activitiesToShortcuts;
    }

    private void incrementCountForActivity(ArrayMap<ComponentName, Integer> counts, ComponentName cn, int increment) {
        Integer oldValue = counts.get(cn);
        if (oldValue == null) {
            oldValue = 0;
        }
        counts.put(cn, Integer.valueOf(oldValue.intValue() + increment));
    }

    public void enforceShortcutCountsBeforeOperation(List<ShortcutInfo> newList, int operation) {
        ShortcutService service = this.mShortcutUser.mService;
        ArrayMap<ComponentName, Integer> counts = new ArrayMap<>(4);
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo shortcut = this.mShortcuts.valueAt(i);
            if (shortcut.isManifestShortcut()) {
                incrementCountForActivity(counts, shortcut.getActivity(), 1);
            } else if (shortcut.isDynamic() && operation != 0) {
                incrementCountForActivity(counts, shortcut.getActivity(), 1);
            }
        }
        int i2 = newList.size();
        for (int i3 = i2 - 1; i3 >= 0; i3--) {
            ShortcutInfo newShortcut = newList.get(i3);
            ComponentName newActivity = newShortcut.getActivity();
            if (newActivity == null) {
                if (operation != 2) {
                    service.wtf("Activity must not be null at this point");
                }
            } else {
                ShortcutInfo original = this.mShortcuts.get(newShortcut.getId());
                if (original == null) {
                    if (operation != 2) {
                        incrementCountForActivity(counts, newActivity, 1);
                    }
                } else if (!original.isFloating() || operation != 2) {
                    if (operation != 0) {
                        ComponentName oldActivity = original.getActivity();
                        if (!original.isFloating()) {
                            incrementCountForActivity(counts, oldActivity, -1);
                        }
                    }
                    incrementCountForActivity(counts, newActivity, 1);
                }
            }
        }
        int i4 = counts.size();
        for (int i5 = i4 - 1; i5 >= 0; i5--) {
            service.enforceMaxActivityShortcuts(counts.valueAt(i5).intValue());
        }
    }

    public void resolveResourceStrings() {
        ShortcutService s = this.mShortcutUser.mService;
        boolean changed = false;
        Resources publisherRes = null;
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            if (si.hasStringResources()) {
                changed = true;
                if (publisherRes == null && (publisherRes = getPackageResources()) == null) {
                    break;
                }
                si.resolveResourceStrings(publisherRes);
                si.setTimestamp(s.injectCurrentTimeMillis());
            }
        }
        if (changed) {
            s.packageShortcutsChanged(getPackageName(), getPackageUserId());
        }
    }

    public void clearAllImplicitRanks() {
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            si.clearImplicitRankAndRankChangedFlag();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ int lambda$new$2(ShortcutInfo a, ShortcutInfo b) {
        int ret = Integer.compare(a.getRank(), b.getRank());
        if (ret != 0) {
            return ret;
        }
        if (a.isRankChanged() != b.isRankChanged()) {
            return a.isRankChanged() ? -1 : 1;
        }
        int ret2 = Integer.compare(a.getImplicitRank(), b.getImplicitRank());
        if (ret2 != 0) {
            return ret2;
        }
        return a.getId().compareTo(b.getId());
    }

    public void adjustRanks() {
        ShortcutService s = this.mShortcutUser.mService;
        long now = s.injectCurrentTimeMillis();
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            if (si.isFloating() && si.getRank() != 0) {
                si.setTimestamp(now);
                si.setRank(0);
            }
        }
        ArrayMap<ComponentName, ArrayList<ShortcutInfo>> all = sortShortcutsToActivities();
        for (int outer = all.size() - 1; outer >= 0; outer--) {
            ArrayList<ShortcutInfo> list = all.valueAt(outer);
            Collections.sort(list, this.mShortcutRankComparator);
            int thisRank = 0;
            int size = list.size();
            for (int i2 = 0; i2 < size; i2++) {
                ShortcutInfo si2 = list.get(i2);
                if (!si2.isManifestShortcut()) {
                    if (!si2.isDynamic()) {
                        s.wtf("Non-dynamic shortcut found.");
                    } else {
                        int rank = thisRank + 1;
                        if (si2.getRank() != thisRank) {
                            si2.setTimestamp(now);
                            si2.setRank(thisRank);
                        }
                        thisRank = rank;
                    }
                }
            }
        }
    }

    public boolean hasNonManifestShortcuts() {
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            if (!si.isDeclaredInManifest()) {
                return true;
            }
        }
        return false;
    }

    public void dump(PrintWriter pw, String prefix, ShortcutService.DumpFilter filter) {
        pw.println();
        pw.print(prefix);
        pw.print("Package: ");
        pw.print(getPackageName());
        pw.print("  UID: ");
        pw.print(this.mPackageUid);
        pw.println();
        pw.print(prefix);
        pw.print("  ");
        pw.print("Calls: ");
        pw.print(getApiCallCount(false));
        pw.println();
        pw.print(prefix);
        pw.print("  ");
        pw.print("Last known FG: ");
        pw.print(this.mLastKnownForegroundElapsedTime);
        pw.println();
        pw.print(prefix);
        pw.print("  ");
        pw.print("Last reset: [");
        pw.print(this.mLastResetTime);
        pw.print("] ");
        pw.print(ShortcutService.formatTime(this.mLastResetTime));
        pw.println();
        ShortcutPackageInfo packageInfo = getPackageInfo();
        packageInfo.dump(pw, prefix + "  ");
        pw.println();
        pw.print(prefix);
        pw.println("  Shortcuts:");
        long totalBitmapSize = 0;
        ArrayMap<String, ShortcutInfo> shortcuts = this.mShortcuts;
        int size = shortcuts.size();
        for (int i = 0; i < size; i++) {
            ShortcutInfo si = shortcuts.valueAt(i);
            pw.println(si.toDumpString(prefix + "    "));
            if (si.getBitmapPath() != null) {
                long len = new File(si.getBitmapPath()).length();
                pw.print(prefix);
                pw.print("      ");
                pw.print("bitmap size=");
                pw.println(len);
                totalBitmapSize += len;
            }
        }
        pw.print(prefix);
        pw.print("  ");
        pw.print("Total bitmap size: ");
        pw.print(totalBitmapSize);
        pw.print(" (");
        pw.print(Formatter.formatFileSize(this.mShortcutUser.mService.mContext, totalBitmapSize));
        pw.println(")");
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    public JSONObject dumpCheckin(boolean clear) throws JSONException {
        JSONObject result = super.dumpCheckin(clear);
        int numDynamic = 0;
        int numPinned = 0;
        int numManifest = 0;
        int numBitmaps = 0;
        long totalBitmapSize = 0;
        ArrayMap<String, ShortcutInfo> shortcuts = this.mShortcuts;
        int size = shortcuts.size();
        for (int i = 0; i < size; i++) {
            ShortcutInfo si = shortcuts.valueAt(i);
            if (si.isDynamic()) {
                numDynamic++;
            }
            if (si.isDeclaredInManifest()) {
                numManifest++;
            }
            if (si.isPinned()) {
                numPinned++;
            }
            if (si.getBitmapPath() != null) {
                numBitmaps++;
                totalBitmapSize += new File(si.getBitmapPath()).length();
            }
        }
        result.put(KEY_DYNAMIC, numDynamic);
        result.put(KEY_MANIFEST, numManifest);
        result.put(KEY_PINNED, numPinned);
        result.put(KEY_BITMAPS, numBitmaps);
        result.put(KEY_BITMAP_BYTES, totalBitmapSize);
        return result;
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    public void saveToXml(XmlSerializer out, boolean forBackup) throws IOException, XmlPullParserException {
        int size = this.mShortcuts.size();
        int shareTargetSize = this.mShareTargets.size();
        if (size == 0 && shareTargetSize == 0 && this.mApiCallCount == 0) {
            return;
        }
        out.startTag(null, "package");
        ShortcutService.writeAttr(out, Settings.ATTR_NAME, getPackageName());
        ShortcutService.writeAttr(out, ATTR_CALL_COUNT, this.mApiCallCount);
        ShortcutService.writeAttr(out, ATTR_LAST_RESET, this.mLastResetTime);
        getPackageInfo().saveToXml(this.mShortcutUser.mService, out, forBackup);
        for (int j = 0; j < size; j++) {
            saveShortcut(out, this.mShortcuts.valueAt(j), forBackup, getPackageInfo().isBackupAllowed());
        }
        if (!forBackup) {
            for (int j2 = 0; j2 < shareTargetSize; j2++) {
                this.mShareTargets.get(j2).saveToXml(out);
            }
        }
        out.endTag(null, "package");
    }

    private void saveShortcut(XmlSerializer out, ShortcutInfo si, boolean forBackup, boolean appSupportsBackup) throws IOException, XmlPullParserException {
        ShortcutService s = this.mShortcutUser.mService;
        if (forBackup && (!si.isPinned() || !si.isEnabled())) {
            return;
        }
        boolean shouldBackupDetails = !forBackup || appSupportsBackup;
        if (si.isIconPendingSave()) {
            s.removeIconLocked(si);
        }
        out.startTag(null, TAG_SHORTCUT);
        ShortcutService.writeAttr(out, ATTR_ID, si.getId());
        ShortcutService.writeAttr(out, ATTR_ACTIVITY, si.getActivity());
        ShortcutService.writeAttr(out, ATTR_TITLE, si.getTitle());
        ShortcutService.writeAttr(out, ATTR_TITLE_RES_ID, si.getTitleResId());
        ShortcutService.writeAttr(out, ATTR_TITLE_RES_NAME, si.getTitleResName());
        ShortcutService.writeAttr(out, ATTR_TEXT, si.getText());
        ShortcutService.writeAttr(out, ATTR_TEXT_RES_ID, si.getTextResId());
        ShortcutService.writeAttr(out, ATTR_TEXT_RES_NAME, si.getTextResName());
        if (shouldBackupDetails) {
            ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE, si.getDisabledMessage());
            ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE_RES_ID, si.getDisabledMessageResourceId());
            ShortcutService.writeAttr(out, ATTR_DISABLED_MESSAGE_RES_NAME, si.getDisabledMessageResName());
        }
        ShortcutService.writeAttr(out, ATTR_DISABLED_REASON, si.getDisabledReason());
        ShortcutService.writeAttr(out, "timestamp", si.getLastChangedTimestamp());
        LocusId locusId = si.getLocusId();
        if (locusId != null) {
            ShortcutService.writeAttr(out, ATTR_LOCUS_ID, si.getLocusId().getId());
        }
        if (!forBackup) {
            ShortcutService.writeAttr(out, ATTR_RANK, si.getRank());
            ShortcutService.writeAttr(out, "flags", si.getFlags());
            ShortcutService.writeAttr(out, ATTR_ICON_RES_ID, si.getIconResourceId());
            ShortcutService.writeAttr(out, ATTR_ICON_RES_NAME, si.getIconResName());
            ShortcutService.writeAttr(out, ATTR_BITMAP_PATH, si.getBitmapPath());
        } else {
            int flags = si.getFlags() & (-2062);
            ShortcutService.writeAttr(out, "flags", flags);
            long packageVersionCode = getPackageInfo().getVersionCode();
            if (packageVersionCode == 0) {
                s.wtf("Package version code should be available at this point.");
            }
        }
        if (shouldBackupDetails) {
            Set<String> cat = si.getCategories();
            if (cat != null && cat.size() > 0) {
                out.startTag(null, "categories");
                XmlUtils.writeStringArrayXml((String[]) cat.toArray(new String[cat.size()]), "categories", out);
                out.endTag(null, "categories");
            }
            if (!forBackup) {
                Person[] persons = si.getPersons();
                if (!ArrayUtils.isEmpty(persons)) {
                    for (Person p : persons) {
                        out.startTag(null, TAG_PERSON);
                        ShortcutService.writeAttr(out, Settings.ATTR_NAME, p.getName());
                        ShortcutService.writeAttr(out, ATTR_PERSON_URI, p.getUri());
                        ShortcutService.writeAttr(out, ATTR_PERSON_KEY, p.getKey());
                        ShortcutService.writeAttr(out, ATTR_PERSON_IS_BOT, p.isBot());
                        ShortcutService.writeAttr(out, ATTR_PERSON_IS_IMPORTANT, p.isImportant());
                        out.endTag(null, TAG_PERSON);
                    }
                }
            }
            Intent[] intentsNoExtras = si.getIntentsNoExtras();
            PersistableBundle[] intentsExtras = si.getIntentPersistableExtrases();
            int numIntents = intentsNoExtras.length;
            for (int i = 0; i < numIntents; i++) {
                out.startTag(null, "intent");
                ShortcutService.writeAttr(out, ATTR_INTENT_NO_EXTRA, intentsNoExtras[i]);
                ShortcutService.writeTagExtra(out, TAG_EXTRAS, intentsExtras[i]);
                out.endTag(null, "intent");
            }
            ShortcutService.writeTagExtra(out, TAG_EXTRAS, si.getExtras());
        }
        out.endTag(null, TAG_SHORTCUT);
    }

    public static ShortcutPackage loadFromXml(ShortcutService s, ShortcutUser shortcutUser, XmlPullParser parser, boolean fromBackup) throws IOException, XmlPullParserException {
        String packageName = ShortcutService.parseStringAttribute(parser, Settings.ATTR_NAME);
        ShortcutPackage ret = new ShortcutPackage(shortcutUser, shortcutUser.getUserId(), packageName);
        ret.mApiCallCount = ShortcutService.parseIntAttribute(parser, ATTR_CALL_COUNT);
        ret.mLastResetTime = ShortcutService.parseLongAttribute(parser, ATTR_LAST_RESET);
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type == 1 || (type == 3 && parser.getDepth() <= outerDepth)) {
                break;
            } else if (type == 2) {
                int depth = parser.getDepth();
                String tag = parser.getName();
                if (depth == outerDepth + 1) {
                    char c = 65535;
                    int hashCode = tag.hashCode();
                    if (hashCode != -1923478059) {
                        if (hashCode != -1680817345) {
                            if (hashCode == -342500282 && tag.equals(TAG_SHORTCUT)) {
                                c = 1;
                            }
                        } else if (tag.equals(TAG_SHARE_TARGET)) {
                            c = 2;
                        }
                    } else if (tag.equals("package-info")) {
                        c = 0;
                    }
                    if (c == 0) {
                        ret.getPackageInfo().loadFromXml(parser, fromBackup);
                    } else if (c != 1) {
                        if (c == 2) {
                            ret.mShareTargets.add(ShareTargetInfo.loadFromXml(parser));
                        }
                    } else {
                        ShortcutInfo si = parseShortcut(parser, packageName, shortcutUser.getUserId(), fromBackup);
                        ret.mShortcuts.put(si.getId(), si);
                    }
                }
                ShortcutService.warnForInvalidTag(depth, tag);
            }
        }
        return ret;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:63:0x01aa, code lost:
        if (r15 == null) goto L12;
     */
    /* JADX WARN: Code restructure failed: missing block: B:64:0x01ac, code lost:
        android.content.pm.ShortcutInfo.setIntentExtras(r15, r1);
        r2.clear();
        r2.add(r15);
     */
    /* JADX WARN: Code restructure failed: missing block: B:65:0x01b5, code lost:
        if (r7 != 0) goto L24;
     */
    /* JADX WARN: Code restructure failed: missing block: B:67:0x01b9, code lost:
        if ((r9 & 64) == 0) goto L24;
     */
    /* JADX WARN: Code restructure failed: missing block: B:68:0x01bb, code lost:
        r50 = 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:69:0x01bf, code lost:
        r50 = r7;
     */
    /* JADX WARN: Code restructure failed: missing block: B:70:0x01c1, code lost:
        if (r61 == false) goto L23;
     */
    /* JADX WARN: Code restructure failed: missing block: B:71:0x01c3, code lost:
        r51 = r9 | 4096;
     */
    /* JADX WARN: Code restructure failed: missing block: B:72:0x01c8, code lost:
        r51 = r9;
     */
    /* JADX WARN: Code restructure failed: missing block: B:73:0x01ca, code lost:
        if (r11 != null) goto L19;
     */
    /* JADX WARN: Code restructure failed: missing block: B:75:0x01cd, code lost:
        r3 = new android.content.LocusId(r11);
     */
    /* JADX WARN: Code restructure failed: missing block: B:76:0x01d2, code lost:
        r34 = r3;
     */
    /* JADX WARN: Code restructure failed: missing block: B:77:0x022e, code lost:
        return new android.content.pm.ShortcutInfo(r60, r6, r59, r35, null, r36, r37, r38, r39, r40, r41, r42, r43, r44, r4, (android.content.Intent[]) r2.toArray(new android.content.Intent[r2.size()]), r25, r49, r45, r51, r16, r47, r48, r50, (android.app.Person[]) r5.toArray(new android.app.Person[r5.size()]), r34);
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private static android.content.pm.ShortcutInfo parseShortcut(org.xmlpull.v1.XmlPullParser r58, java.lang.String r59, int r60, boolean r61) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
        /*
            Method dump skipped, instructions count: 586
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutPackage.parseShortcut(org.xmlpull.v1.XmlPullParser, java.lang.String, int, boolean):android.content.pm.ShortcutInfo");
    }

    /* JADX WARN: Code restructure failed: missing block: B:22:0x004b, code lost:
        return r0;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private static android.content.Intent parseIntent(org.xmlpull.v1.XmlPullParser r8) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException {
        /*
            java.lang.String r0 = "intent-base"
            android.content.Intent r0 = com.android.server.pm.ShortcutService.parseIntentAttribute(r8, r0)
            int r1 = r8.getDepth()
        La:
            int r2 = r8.next()
            r3 = r2
            r4 = 1
            if (r2 == r4) goto L4b
            r2 = 3
            if (r3 != r2) goto L1b
            int r2 = r8.getDepth()
            if (r2 <= r1) goto L4b
        L1b:
            r2 = 2
            if (r3 == r2) goto L1f
            goto La
        L1f:
            int r2 = r8.getDepth()
            java.lang.String r4 = r8.getName()
            r5 = -1
            int r6 = r4.hashCode()
            r7 = -1289032093(0xffffffffb32aee63, float:-3.979802E-8)
            if (r6 == r7) goto L32
        L31:
            goto L3b
        L32:
            java.lang.String r6 = "extras"
            boolean r6 = r4.equals(r6)
            if (r6 == 0) goto L31
            r5 = 0
        L3b:
            if (r5 != 0) goto L46
        L3e:
            android.os.PersistableBundle r5 = android.os.PersistableBundle.restoreFromXml(r8)
            android.content.pm.ShortcutInfo.setIntentExtras(r0, r5)
            goto La
        L46:
            java.io.IOException r5 = com.android.server.pm.ShortcutService.throwForInvalidTag(r2, r4)
            throw r5
        L4b:
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.pm.ShortcutPackage.parseIntent(org.xmlpull.v1.XmlPullParser):android.content.Intent");
    }

    private static Person parsePerson(XmlPullParser parser) throws IOException, XmlPullParserException {
        CharSequence name = ShortcutService.parseStringAttribute(parser, Settings.ATTR_NAME);
        String uri = ShortcutService.parseStringAttribute(parser, ATTR_PERSON_URI);
        String key = ShortcutService.parseStringAttribute(parser, ATTR_PERSON_KEY);
        boolean isBot = ShortcutService.parseBooleanAttribute(parser, ATTR_PERSON_IS_BOT);
        boolean isImportant = ShortcutService.parseBooleanAttribute(parser, ATTR_PERSON_IS_IMPORTANT);
        Person.Builder builder = new Person.Builder();
        builder.setName(name).setUri(uri).setKey(key).setBot(isBot).setImportant(isImportant);
        return builder.build();
    }

    @VisibleForTesting
    List<ShortcutInfo> getAllShortcutsForTest() {
        return new ArrayList(this.mShortcuts.values());
    }

    @VisibleForTesting
    List<ShareTargetInfo> getAllShareTargetsForTest() {
        return new ArrayList(this.mShareTargets);
    }

    @Override // com.android.server.pm.ShortcutPackageItem
    public void verifyStates() {
        super.verifyStates();
        boolean failed = false;
        ShortcutService s = this.mShortcutUser.mService;
        ArrayMap<ComponentName, ArrayList<ShortcutInfo>> all = sortShortcutsToActivities();
        for (int outer = all.size() - 1; outer >= 0; outer--) {
            ArrayList<ShortcutInfo> list = all.valueAt(outer);
            if (list.size() > this.mShortcutUser.mService.getMaxActivityShortcuts()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": activity " + all.keyAt(outer) + " has " + all.valueAt(outer).size() + " shortcuts.");
            }
            Collections.sort(list, new Comparator() { // from class: com.android.server.pm.-$$Lambda$ShortcutPackage$DImOsVxMicPEAJPTxf_RRXuc70I
                @Override // java.util.Comparator
                public final int compare(Object obj, Object obj2) {
                    int compare;
                    compare = Integer.compare(((ShortcutInfo) obj).getRank(), ((ShortcutInfo) obj2).getRank());
                    return compare;
                }
            });
            ArrayList<ShortcutInfo> dynamicList = new ArrayList<>(list);
            dynamicList.removeIf(new Predicate() { // from class: com.android.server.pm.-$$Lambda$ShortcutPackage$Uf55CaKs9xv-osb2umPmXq3W2lM
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return ShortcutPackage.lambda$verifyStates$4((ShortcutInfo) obj);
                }
            });
            ArrayList<ShortcutInfo> manifestList = new ArrayList<>(list);
            dynamicList.removeIf(new Predicate() { // from class: com.android.server.pm.-$$Lambda$ShortcutPackage$9YSAfuJJkDxYR6ZL5AWyxpKsC_Y
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return ShortcutPackage.lambda$verifyStates$5((ShortcutInfo) obj);
                }
            });
            verifyRanksSequential(dynamicList);
            verifyRanksSequential(manifestList);
        }
        for (int i = this.mShortcuts.size() - 1; i >= 0; i--) {
            ShortcutInfo si = this.mShortcuts.valueAt(i);
            if (!si.isDeclaredInManifest() && !si.isDynamic() && !si.isPinned()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " is not manifest, dynamic or pinned.");
            }
            if (si.isDeclaredInManifest() && si.isDynamic()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " is both dynamic and manifest at the same time.");
            }
            if (si.getActivity() == null && !si.isFloating()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " has null activity, but not floating.");
            }
            if ((si.isDynamic() || si.isManifestShortcut()) && !si.isEnabled()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " is not floating, but is disabled.");
            }
            if (si.isFloating() && si.getRank() != 0) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " is floating, but has rank=" + si.getRank());
            }
            if (si.getIcon() != null) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " still has an icon");
            }
            if (si.hasAdaptiveBitmap() && !si.hasIconFile()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " has adaptive bitmap but was not saved to a file.");
            }
            if (si.hasIconFile() && si.hasIconResource()) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " has both resource and bitmap icons");
            }
            if (si.isEnabled() != (si.getDisabledReason() == 0)) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " isEnabled() and getDisabledReason() disagree: " + si.isEnabled() + " vs " + si.getDisabledReason());
            }
            if (si.getDisabledReason() == 100 && getPackageInfo().getBackupSourceVersionCode() == -1) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " RESTORED_VERSION_LOWER with no backup source version code.");
            }
            if (s.isDummyMainActivity(si.getActivity())) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " has a dummy target activity");
            }
        }
        if (failed) {
            throw new IllegalStateException("See logcat for errors");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$verifyStates$4(ShortcutInfo si) {
        return !si.isDynamic();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$verifyStates$5(ShortcutInfo si) {
        return !si.isManifestShortcut();
    }

    private boolean verifyRanksSequential(List<ShortcutInfo> list) {
        boolean failed = false;
        for (int i = 0; i < list.size(); i++) {
            ShortcutInfo si = list.get(i);
            if (si.getRank() != i) {
                failed = true;
                Log.e(TAG_VERIFY, "Package " + getPackageName() + ": shortcut " + si.getId() + " rank=" + si.getRank() + " but expected to be " + i);
            }
        }
        return failed;
    }
}
