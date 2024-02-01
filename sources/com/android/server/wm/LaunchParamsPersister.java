package com.android.server.wm;

import android.content.ComponentName;
import android.content.pm.PackageList;
import android.content.pm.PackageManagerInternal;
import android.graphics.Rect;
import android.os.Environment;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.DisplayInfo;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.wm.LaunchParamsController;
import com.android.server.wm.LaunchParamsPersister;
import com.android.server.wm.PersisterQueue;
import com.android.server.wm.SharedDisplayContainer;
import com.xiaopeng.view.SharedDisplayManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class LaunchParamsPersister {
    private static final char ESCAPED_COMPONENT_SEPARATOR = '_';
    private static final String LAUNCH_PARAMS_DIRNAME = "launch_params";
    private static final String LAUNCH_PARAMS_FILE_SUFFIX = ".xml";
    private static final char ORIGINAL_COMPONENT_SEPARATOR = '/';
    private static final String TAG = "LaunchParamsPersister";
    private static final String TAG_LAUNCH_PARAMS = "launch_params";
    private final SparseArray<ArrayMap<ComponentName, PersistableLaunchParams>> mMap;
    private PackageList mPackageList;
    private final PersisterQueue mPersisterQueue;
    private final ActivityStackSupervisor mSupervisor;
    private final IntFunction<File> mUserFolderGetter;

    /* JADX INFO: Access modifiers changed from: package-private */
    public LaunchParamsPersister(PersisterQueue persisterQueue, ActivityStackSupervisor supervisor) {
        this(persisterQueue, supervisor, new IntFunction() { // from class: com.android.server.wm.-$$Lambda$OuObUsm0bB9g5X0kIXYkBYHvodY
            @Override // java.util.function.IntFunction
            public final Object apply(int i) {
                return Environment.getDataSystemCeDirectory(i);
            }
        });
    }

    @VisibleForTesting
    LaunchParamsPersister(PersisterQueue persisterQueue, ActivityStackSupervisor supervisor, IntFunction<File> userFolderGetter) {
        this.mMap = new SparseArray<>();
        this.mPersisterQueue = persisterQueue;
        this.mSupervisor = supervisor;
        this.mUserFolderGetter = userFolderGetter;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onSystemReady() {
        PackageManagerInternal pmi = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);
        this.mPackageList = pmi.getPackageList(new PackageListObserver());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onUnlockUser(int userId) {
        loadLaunchParams(userId);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onCleanupUser(int userId) {
        this.mMap.remove(userId);
    }

    private void loadLaunchParams(int userId) {
        File launchParamsFolder;
        Set<String> packages;
        File[] paramsFiles;
        List<File> filesToDelete = new ArrayList<>();
        File launchParamsFolder2 = getLaunchParamFolder(userId);
        if (!launchParamsFolder2.isDirectory()) {
            Slog.i(TAG, "Didn't find launch param folder for user " + userId);
            return;
        }
        Set<String> packages2 = new ArraySet<>(this.mPackageList.getPackageNames());
        File[] paramsFiles2 = launchParamsFolder2.listFiles();
        ArrayMap<ComponentName, PersistableLaunchParams> map = new ArrayMap<>(paramsFiles2.length);
        this.mMap.put(userId, map);
        int length = paramsFiles2.length;
        int i = 0;
        int i2 = 0;
        while (i2 < length) {
            File paramsFile = paramsFiles2[i2];
            if (!paramsFile.isFile()) {
                Slog.w(TAG, paramsFile.getAbsolutePath() + " is not a file.");
                launchParamsFolder = launchParamsFolder2;
                packages = packages2;
                paramsFiles = paramsFiles2;
            } else if (paramsFile.getName().endsWith(LAUNCH_PARAMS_FILE_SUFFIX)) {
                String paramsFileName = paramsFile.getName();
                String componentNameString = paramsFileName.substring(i, paramsFileName.length() - LAUNCH_PARAMS_FILE_SUFFIX.length()).replace(ESCAPED_COMPONENT_SEPARATOR, ORIGINAL_COMPONENT_SEPARATOR);
                ComponentName name = ComponentName.unflattenFromString(componentNameString);
                if (name == null) {
                    Slog.w(TAG, "Unexpected file name: " + paramsFileName);
                    filesToDelete.add(paramsFile);
                    launchParamsFolder = launchParamsFolder2;
                    packages = packages2;
                    paramsFiles = paramsFiles2;
                } else if (packages2.contains(name.getPackageName())) {
                    BufferedReader reader = null;
                    try {
                        reader = new BufferedReader(new FileReader(paramsFile));
                        PersistableLaunchParams params = new PersistableLaunchParams();
                        XmlPullParser parser = Xml.newPullParser();
                        parser.setInput(reader);
                        while (true) {
                            launchParamsFolder = launchParamsFolder2;
                            try {
                                int event = parser.next();
                                packages = packages2;
                                if (event == 1) {
                                    paramsFiles = paramsFiles2;
                                    break;
                                } else if (event == 3) {
                                    paramsFiles = paramsFiles2;
                                    break;
                                } else if (event != 2) {
                                    launchParamsFolder2 = launchParamsFolder;
                                    packages2 = packages;
                                } else {
                                    try {
                                        String tagName = parser.getName();
                                        if ("launch_params".equals(tagName)) {
                                            params.restoreFromXml(parser);
                                            launchParamsFolder2 = launchParamsFolder;
                                            packages2 = packages;
                                            paramsFiles2 = paramsFiles2;
                                        } else {
                                            StringBuilder sb = new StringBuilder();
                                            paramsFiles = paramsFiles2;
                                            try {
                                                try {
                                                    sb.append("Unexpected tag name: ");
                                                    sb.append(tagName);
                                                    Slog.w(TAG, sb.toString());
                                                    launchParamsFolder2 = launchParamsFolder;
                                                    packages2 = packages;
                                                    paramsFiles2 = paramsFiles;
                                                } catch (Throwable th) {
                                                    th = th;
                                                    IoUtils.closeQuietly(reader);
                                                    throw th;
                                                }
                                            } catch (Exception e) {
                                                e = e;
                                                Slog.w(TAG, "Failed to restore launch params for " + name, e);
                                                filesToDelete.add(paramsFile);
                                                IoUtils.closeQuietly(reader);
                                                i2++;
                                                launchParamsFolder2 = launchParamsFolder;
                                                packages2 = packages;
                                                paramsFiles2 = paramsFiles;
                                                i = 0;
                                            }
                                        }
                                    } catch (Exception e2) {
                                        e = e2;
                                        paramsFiles = paramsFiles2;
                                    } catch (Throwable th2) {
                                        th = th2;
                                    }
                                }
                            } catch (Exception e3) {
                                e = e3;
                                packages = packages2;
                                paramsFiles = paramsFiles2;
                            } catch (Throwable th3) {
                                th = th3;
                            }
                        }
                        map.put(name, params);
                    } catch (Exception e4) {
                        e = e4;
                        launchParamsFolder = launchParamsFolder2;
                        packages = packages2;
                        paramsFiles = paramsFiles2;
                    } catch (Throwable th4) {
                        th = th4;
                    }
                    IoUtils.closeQuietly(reader);
                } else {
                    filesToDelete.add(paramsFile);
                    launchParamsFolder = launchParamsFolder2;
                    packages = packages2;
                    paramsFiles = paramsFiles2;
                }
            } else {
                Slog.w(TAG, "Unexpected params file name: " + paramsFile.getName());
                filesToDelete.add(paramsFile);
                launchParamsFolder = launchParamsFolder2;
                packages = packages2;
                paramsFiles = paramsFiles2;
            }
            i2++;
            launchParamsFolder2 = launchParamsFolder;
            packages2 = packages;
            paramsFiles2 = paramsFiles;
            i = 0;
        }
        if (!filesToDelete.isEmpty()) {
            this.mPersisterQueue.addItem(new CleanUpComponentQueueItem(filesToDelete), true);
        }
        loadSharedLaunchParams(map);
    }

    void loadSharedLaunchParams(ArrayMap<ComponentName, PersistableLaunchParams> map) {
        if (map != null && SharedDisplayManager.enable()) {
            ArrayList<SharedDisplayContainer.LaunchParams> list = new ArrayList<>();
            for (Map.Entry<ComponentName, PersistableLaunchParams> entry : map.entrySet()) {
                if (entry != null) {
                    ComponentName component = entry.getKey();
                    PersistableLaunchParams params = entry.getValue();
                    if (component != null && params != null) {
                        String pkg = component.getPackageName();
                        int mode = params.mWindowingMode;
                        Rect bounds = params.mBounds;
                        SharedDisplayContainer.LaunchParams lp = SharedDisplayContainer.LaunchParams.create(pkg, mode, bounds);
                        if (lp != null) {
                            list.add(lp);
                        }
                    }
                }
            }
            ActivityStackSupervisor activityStackSupervisor = this.mSupervisor;
            if (activityStackSupervisor != null) {
                activityStackSupervisor.onLaunchParamsLoaded(list);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void saveTask(TaskRecord task) {
        ArrayMap<ComponentName, PersistableLaunchParams> map;
        PersistableLaunchParams params;
        ComponentName name = task.realActivity;
        int userId = task.userId;
        ArrayMap<ComponentName, PersistableLaunchParams> map2 = this.mMap.get(userId);
        if (map2 != null) {
            map = map2;
        } else {
            ArrayMap<ComponentName, PersistableLaunchParams> map3 = new ArrayMap<>();
            this.mMap.put(userId, map3);
            map = map3;
        }
        PersistableLaunchParams params2 = map.get(name);
        if (params2 != null) {
            params = params2;
        } else {
            PersistableLaunchParams params3 = new PersistableLaunchParams();
            map.put(name, params3);
            params = params3;
        }
        boolean changed = saveTaskToLaunchParam(task, params);
        if (changed) {
            this.mPersisterQueue.updateLastOrAddItem(new LaunchParamsWriteQueueItem(userId, name, params), false);
        }
    }

    private boolean saveTaskToLaunchParam(TaskRecord task, PersistableLaunchParams params) {
        ActivityStack stack = task.getStack();
        int displayId = stack.mDisplayId;
        ActivityDisplay display = this.mSupervisor.mRootActivityContainer.getActivityDisplay(displayId);
        DisplayInfo info = new DisplayInfo();
        display.mDisplay.getDisplayInfo(info);
        boolean changed = !Objects.equals(params.mDisplayUniqueId, info.uniqueId);
        params.mDisplayUniqueId = info.uniqueId;
        boolean changed2 = changed | (params.mWindowingMode != stack.getWindowingMode());
        params.mWindowingMode = stack.getWindowingMode();
        if (task.mLastNonFullscreenBounds != null) {
            boolean changed3 = changed2 | (true ^ Objects.equals(params.mBounds, task.mLastNonFullscreenBounds));
            params.mBounds.set(task.mLastNonFullscreenBounds);
            return changed3;
        }
        boolean changed4 = changed2 | (true ^ params.mBounds.isEmpty());
        params.mBounds.setEmpty();
        return changed4;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getLaunchParams(TaskRecord task, ActivityRecord activity, LaunchParamsController.LaunchParams outParams) {
        PersistableLaunchParams persistableParams;
        ComponentName name = task != null ? task.realActivity : activity.mActivityComponent;
        int userId = task != null ? task.userId : activity.mUserId;
        outParams.reset();
        Map<ComponentName, PersistableLaunchParams> map = this.mMap.get(userId);
        if (map == null || (persistableParams = map.get(name)) == null) {
            return;
        }
        ActivityDisplay display = this.mSupervisor.mRootActivityContainer.getActivityDisplay(persistableParams.mDisplayUniqueId);
        if (display != null) {
            outParams.mPreferredDisplayId = display.mDisplayId;
        }
        outParams.mWindowingMode = persistableParams.mWindowingMode;
        outParams.mBounds.set(persistableParams.mBounds);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeRecordForPackage(final String packageName) {
        List<File> fileToDelete = new ArrayList<>();
        for (int i = 0; i < this.mMap.size(); i++) {
            int userId = this.mMap.keyAt(i);
            File launchParamsFolder = getLaunchParamFolder(userId);
            ArrayMap<ComponentName, PersistableLaunchParams> map = this.mMap.valueAt(i);
            for (int j = map.size() - 1; j >= 0; j--) {
                ComponentName name = map.keyAt(j);
                if (name.getPackageName().equals(packageName)) {
                    map.removeAt(j);
                    fileToDelete.add(getParamFile(launchParamsFolder, name));
                }
            }
        }
        synchronized (this.mPersisterQueue) {
            this.mPersisterQueue.removeItems(new Predicate() { // from class: com.android.server.wm.-$$Lambda$LaunchParamsPersister$Rc1cXPLhXa2WPSr18Q9-Xc7SdV8
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    boolean equals;
                    equals = ((LaunchParamsPersister.LaunchParamsWriteQueueItem) obj).mComponentName.getPackageName().equals(packageName);
                    return equals;
                }
            }, LaunchParamsWriteQueueItem.class);
            this.mPersisterQueue.addItem(new CleanUpComponentQueueItem(fileToDelete), true);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public File getParamFile(File launchParamFolder, ComponentName name) {
        String componentNameString = name.flattenToShortString().replace(ORIGINAL_COMPONENT_SEPARATOR, ESCAPED_COMPONENT_SEPARATOR);
        return new File(launchParamFolder, componentNameString + LAUNCH_PARAMS_FILE_SUFFIX);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public File getLaunchParamFolder(int userId) {
        File userFolder = this.mUserFolderGetter.apply(userId);
        return new File(userFolder, "launch_params");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class PackageListObserver implements PackageManagerInternal.PackageListObserver {
        private PackageListObserver() {
        }

        public void onPackageAdded(String packageName, int uid) {
        }

        public void onPackageRemoved(String packageName, int uid) {
            LaunchParamsPersister.this.removeRecordForPackage(packageName);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class LaunchParamsWriteQueueItem implements PersisterQueue.WriteQueueItem<LaunchParamsWriteQueueItem> {
        private final ComponentName mComponentName;
        private PersistableLaunchParams mLaunchParams;
        private final int mUserId;

        private LaunchParamsWriteQueueItem(int userId, ComponentName componentName, PersistableLaunchParams launchParams) {
            this.mUserId = userId;
            this.mComponentName = componentName;
            this.mLaunchParams = launchParams;
        }

        private StringWriter saveParamsToXml() {
            StringWriter writer = new StringWriter();
            XmlSerializer serializer = new FastXmlSerializer();
            try {
                serializer.setOutput(writer);
                serializer.startDocument(null, true);
                serializer.startTag(null, "launch_params");
                this.mLaunchParams.saveToXml(serializer);
                serializer.endTag(null, "launch_params");
                serializer.endDocument();
                serializer.flush();
                return writer;
            } catch (IOException e) {
                return null;
            }
        }

        @Override // com.android.server.wm.PersisterQueue.WriteQueueItem
        public void process() {
            StringWriter writer = saveParamsToXml();
            File launchParamFolder = LaunchParamsPersister.this.getLaunchParamFolder(this.mUserId);
            if (launchParamFolder.isDirectory() || launchParamFolder.mkdirs()) {
                File launchParamFile = LaunchParamsPersister.this.getParamFile(launchParamFolder, this.mComponentName);
                AtomicFile atomicFile = new AtomicFile(launchParamFile);
                FileOutputStream stream = null;
                try {
                    stream = atomicFile.startWrite();
                    stream.write(writer.toString().getBytes());
                    atomicFile.finishWrite(stream);
                    return;
                } catch (Exception e) {
                    Slog.e(LaunchParamsPersister.TAG, "Failed to write param file for " + this.mComponentName, e);
                    if (stream != null) {
                        atomicFile.failWrite(stream);
                        return;
                    }
                    return;
                }
            }
            Slog.w(LaunchParamsPersister.TAG, "Failed to create folder for " + this.mUserId);
        }

        @Override // com.android.server.wm.PersisterQueue.WriteQueueItem
        public boolean matches(LaunchParamsWriteQueueItem item) {
            return this.mUserId == item.mUserId && this.mComponentName.equals(item.mComponentName);
        }

        @Override // com.android.server.wm.PersisterQueue.WriteQueueItem
        public void updateFrom(LaunchParamsWriteQueueItem item) {
            this.mLaunchParams = item.mLaunchParams;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class CleanUpComponentQueueItem implements PersisterQueue.WriteQueueItem {
        private final List<File> mComponentFiles;

        private CleanUpComponentQueueItem(List<File> componentFiles) {
            this.mComponentFiles = componentFiles;
        }

        @Override // com.android.server.wm.PersisterQueue.WriteQueueItem
        public void process() {
            for (File file : this.mComponentFiles) {
                if (!file.delete()) {
                    Slog.w(LaunchParamsPersister.TAG, "Failed to delete " + file.getAbsolutePath());
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class PersistableLaunchParams {
        private static final String ATTR_BOUNDS = "bounds";
        private static final String ATTR_DISPLAY_UNIQUE_ID = "display_unique_id";
        private static final String ATTR_WINDOWING_MODE = "windowing_mode";
        final Rect mBounds;
        String mDisplayUniqueId;
        int mWindowingMode;

        private PersistableLaunchParams() {
            this.mBounds = new Rect();
        }

        void saveToXml(XmlSerializer serializer) throws IOException {
            serializer.attribute(null, ATTR_DISPLAY_UNIQUE_ID, this.mDisplayUniqueId);
            serializer.attribute(null, ATTR_WINDOWING_MODE, Integer.toString(this.mWindowingMode));
            serializer.attribute(null, ATTR_BOUNDS, this.mBounds.flattenToString());
        }

        void restoreFromXml(XmlPullParser parser) {
            Rect bounds;
            for (int i = 0; i < parser.getAttributeCount(); i++) {
                String attrValue = parser.getAttributeValue(i);
                String attributeName = parser.getAttributeName(i);
                char c = 65535;
                int hashCode = attributeName.hashCode();
                if (hashCode != -1499361012) {
                    if (hashCode != -1383205195) {
                        if (hashCode == 748872656 && attributeName.equals(ATTR_WINDOWING_MODE)) {
                            c = 1;
                        }
                    } else if (attributeName.equals(ATTR_BOUNDS)) {
                        c = 2;
                    }
                } else if (attributeName.equals(ATTR_DISPLAY_UNIQUE_ID)) {
                    c = 0;
                }
                if (c == 0) {
                    this.mDisplayUniqueId = attrValue;
                } else if (c == 1) {
                    this.mWindowingMode = Integer.parseInt(attrValue);
                } else if (c == 2 && (bounds = Rect.unflattenFromString(attrValue)) != null) {
                    this.mBounds.set(bounds);
                }
            }
        }

        public String toString() {
            StringBuilder builder = new StringBuilder("PersistableLaunchParams{");
            builder.append("windowingMode=" + this.mWindowingMode);
            builder.append(" displayUniqueId=" + this.mDisplayUniqueId);
            builder.append(" bounds=" + this.mBounds);
            builder.append(" }");
            return builder.toString();
        }
    }
}
