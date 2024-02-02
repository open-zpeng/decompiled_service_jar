package com.android.server.pm.dex;

import android.content.pm.ApplicationInfo;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.os.ClassLoaderFactory;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.PackageDexOptimizer;
import java.io.File;
import java.util.List;
/* loaded from: classes.dex */
public final class DexoptUtils {
    private static final String TAG = "DexoptUtils";

    private DexoptUtils() {
    }

    public static String[] getClassLoaderContexts(ApplicationInfo info, String[] sharedLibraries, boolean[] pathsWithCode) {
        String sharedLibrariesClassPath = encodeClasspath(sharedLibraries);
        String baseApkContextClassLoader = encodeClassLoader(sharedLibrariesClassPath, info.classLoaderName);
        int i = 1;
        if (info.getSplitCodePaths() == null) {
            return new String[]{baseApkContextClassLoader};
        }
        String[] splitRelativeCodePaths = getSplitRelativeCodePaths(info);
        String baseApkName = new File(info.getBaseCodePath()).getName();
        String sharedLibrariesAndBaseClassPath = encodeClasspath(sharedLibrariesClassPath, baseApkName);
        String[] classLoaderContexts = new String[splitRelativeCodePaths.length + 1];
        classLoaderContexts[0] = pathsWithCode[0] ? baseApkContextClassLoader : null;
        if (!info.requestsIsolatedSplitLoading() || info.splitDependencies == null) {
            String classpath = sharedLibrariesAndBaseClassPath;
            while (i < classLoaderContexts.length) {
                classLoaderContexts[i] = pathsWithCode[i] ? encodeClassLoader(classpath, info.classLoaderName) : null;
                classpath = encodeClasspath(classpath, splitRelativeCodePaths[i - 1]);
                i++;
            }
        } else {
            String[] splitClassLoaderEncodingCache = new String[splitRelativeCodePaths.length];
            for (int i2 = 0; i2 < splitRelativeCodePaths.length; i2++) {
                splitClassLoaderEncodingCache[i2] = encodeClassLoader(splitRelativeCodePaths[i2], info.splitClassLoaderNames[i2]);
            }
            String splitDependencyOnBase = encodeClassLoader(sharedLibrariesAndBaseClassPath, info.classLoaderName);
            SparseArray<int[]> splitDependencies = info.splitDependencies;
            for (int i3 = 1; i3 < splitDependencies.size(); i3++) {
                int splitIndex = splitDependencies.keyAt(i3);
                if (pathsWithCode[splitIndex]) {
                    getParentDependencies(splitIndex, splitClassLoaderEncodingCache, splitDependencies, classLoaderContexts, splitDependencyOnBase);
                }
            }
            while (i < classLoaderContexts.length) {
                String splitClassLoader = encodeClassLoader(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, info.splitClassLoaderNames[i - 1]);
                if (pathsWithCode[i]) {
                    classLoaderContexts[i] = classLoaderContexts[i] == null ? splitClassLoader : encodeClassLoaderChain(splitClassLoader, classLoaderContexts[i]);
                } else {
                    classLoaderContexts[i] = null;
                }
                i++;
            }
        }
        return classLoaderContexts;
    }

    private static String getParentDependencies(int index, String[] splitClassLoaderEncodingCache, SparseArray<int[]> splitDependencies, String[] classLoaderContexts, String splitDependencyOnBase) {
        if (index == 0) {
            return splitDependencyOnBase;
        }
        if (classLoaderContexts[index] != null) {
            return classLoaderContexts[index];
        }
        int parent = splitDependencies.get(index)[0];
        String parentDependencies = getParentDependencies(parent, splitClassLoaderEncodingCache, splitDependencies, classLoaderContexts, splitDependencyOnBase);
        String splitContext = parent == 0 ? parentDependencies : encodeClassLoaderChain(splitClassLoaderEncodingCache[parent - 1], parentDependencies);
        classLoaderContexts[index] = splitContext;
        return splitContext;
    }

    private static String encodeClasspath(String[] classpathElements) {
        if (classpathElements == null || classpathElements.length == 0) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        StringBuilder sb = new StringBuilder();
        for (String element : classpathElements) {
            if (sb.length() != 0) {
                sb.append(":");
            }
            sb.append(element);
        }
        return sb.toString();
    }

    private static String encodeClasspath(String classpath, String newElement) {
        if (classpath.isEmpty()) {
            return newElement;
        }
        return classpath + ":" + newElement;
    }

    static String encodeClassLoader(String classpath, String classLoaderName) {
        if (classpath.equals(PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK)) {
            return classpath;
        }
        String classLoaderDexoptEncoding = classLoaderName;
        if (ClassLoaderFactory.isPathClassLoaderName(classLoaderName)) {
            classLoaderDexoptEncoding = "PCL";
        } else if (ClassLoaderFactory.isDelegateLastClassLoaderName(classLoaderName)) {
            classLoaderDexoptEncoding = "DLC";
        } else {
            Slog.wtf(TAG, "Unsupported classLoaderName: " + classLoaderName);
        }
        return classLoaderDexoptEncoding + "[" + classpath + "]";
    }

    static String encodeClassLoaderChain(String cl1, String cl2) {
        if (cl1.equals(PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK) || cl2.equals(PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK)) {
            return PackageDexOptimizer.SKIP_SHARED_LIBRARY_CHECK;
        }
        if (cl1.isEmpty()) {
            return cl2;
        }
        if (cl2.isEmpty()) {
            return cl1;
        }
        return cl1 + ";" + cl2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static String[] processContextForDexLoad(List<String> classLoadersNames, List<String> classPaths) {
        if (classLoadersNames.size() != classPaths.size()) {
            throw new IllegalArgumentException("The size of the class loader names and the dex paths do not match.");
        }
        if (classLoadersNames.isEmpty()) {
            throw new IllegalArgumentException("Empty classLoadersNames");
        }
        String parentContext = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        for (int i = 1; i < classLoadersNames.size(); i++) {
            if (!ClassLoaderFactory.isValidClassLoaderName(classLoadersNames.get(i))) {
                return null;
            }
            String classpath = encodeClasspath(classPaths.get(i).split(File.pathSeparator));
            parentContext = encodeClassLoaderChain(parentContext, encodeClassLoader(classpath, classLoadersNames.get(i)));
        }
        String loadingClassLoader = classLoadersNames.get(0);
        if (ClassLoaderFactory.isValidClassLoaderName(loadingClassLoader)) {
            String[] loadedDexPaths = classPaths.get(0).split(File.pathSeparator);
            String[] loadedDexPathsContext = new String[loadedDexPaths.length];
            String currentLoadedDexPathClasspath = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            for (int i2 = 0; i2 < loadedDexPaths.length; i2++) {
                String dexPath = loadedDexPaths[i2];
                String currentContext = encodeClassLoader(currentLoadedDexPathClasspath, loadingClassLoader);
                loadedDexPathsContext[i2] = encodeClassLoaderChain(currentContext, parentContext);
                currentLoadedDexPathClasspath = encodeClasspath(currentLoadedDexPathClasspath, dexPath);
            }
            return loadedDexPathsContext;
        }
        return null;
    }

    private static String[] getSplitRelativeCodePaths(ApplicationInfo info) {
        String baseCodePath = new File(info.getBaseCodePath()).getParent();
        String[] splitCodePaths = info.getSplitCodePaths();
        String[] splitRelativeCodePaths = new String[splitCodePaths.length];
        for (int i = 0; i < splitCodePaths.length; i++) {
            File pathFile = new File(splitCodePaths[i]);
            splitRelativeCodePaths[i] = pathFile.getName();
            String basePath = pathFile.getParent();
            if (!basePath.equals(baseCodePath)) {
                Slog.wtf(TAG, "Split paths have different base paths: " + basePath + " and " + baseCodePath);
            }
        }
        return splitRelativeCodePaths;
    }
}
