package com.android.server.pm.dex;

import android.os.Build;
import android.util.AtomicFile;
import android.util.Slog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastPrintWriter;
import com.android.server.pm.AbstractStatsBase;
import com.android.server.pm.PackageManagerServiceUtils;
import dalvik.system.VMRuntime;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import libcore.io.IoUtils;
/* loaded from: classes.dex */
public class PackageDexUsage extends AbstractStatsBase<Void> {
    private static final String CODE_PATH_LINE_CHAR = "+";
    private static final String DEX_LINE_CHAR = "#";
    private static final String LOADING_PACKAGE_CHAR = "@";
    private static final int PACKAGE_DEX_USAGE_SUPPORTED_VERSION_1 = 1;
    private static final int PACKAGE_DEX_USAGE_SUPPORTED_VERSION_2 = 2;
    private static final int PACKAGE_DEX_USAGE_VERSION = 2;
    private static final String PACKAGE_DEX_USAGE_VERSION_HEADER = "PACKAGE_MANAGER__PACKAGE_DEX_USAGE__";
    private static final String SPLIT_CHAR = ",";
    private static final String TAG = "PackageDexUsage";
    static final String UNKNOWN_CLASS_LOADER_CONTEXT = "=UnknownClassLoaderContext=";
    static final String UNSUPPORTED_CLASS_LOADER_CONTEXT = "=UnsupportedClassLoaderContext=";
    static final String VARIABLE_CLASS_LOADER_CONTEXT = "=VariableClassLoaderContext=";
    @GuardedBy("mPackageUseInfoMap")
    private final Map<String, PackageUseInfo> mPackageUseInfoMap;

    public PackageDexUsage() {
        super("package-dex-usage.list", "PackageDexUsage_DiskWriter", false);
        this.mPackageUseInfoMap = new HashMap();
    }

    public boolean record(String owningPackageName, String dexPath, int ownerUserId, String loaderIsa, boolean isUsedByOtherApps, boolean primaryOrSplit, String loadingPackageName, String classLoaderContext) {
        if (!PackageManagerServiceUtils.checkISA(loaderIsa)) {
            throw new IllegalArgumentException("loaderIsa " + loaderIsa + " is unsupported");
        } else if (classLoaderContext == null) {
            throw new IllegalArgumentException("Null classLoaderContext");
        } else {
            synchronized (this.mPackageUseInfoMap) {
                PackageUseInfo packageUseInfo = this.mPackageUseInfoMap.get(owningPackageName);
                boolean z = true;
                if (packageUseInfo == null) {
                    PackageUseInfo packageUseInfo2 = new PackageUseInfo();
                    if (!primaryOrSplit) {
                        DexUseInfo newData = new DexUseInfo(isUsedByOtherApps, ownerUserId, classLoaderContext, loaderIsa);
                        packageUseInfo2.mDexUseInfoMap.put(dexPath, newData);
                        maybeAddLoadingPackage(owningPackageName, loadingPackageName, newData.mLoadingPackages);
                    } else {
                        packageUseInfo2.mergeCodePathUsedByOtherApps(dexPath, isUsedByOtherApps, owningPackageName, loadingPackageName);
                    }
                    this.mPackageUseInfoMap.put(owningPackageName, packageUseInfo2);
                    return true;
                } else if (!primaryOrSplit) {
                    DexUseInfo newData2 = new DexUseInfo(isUsedByOtherApps, ownerUserId, classLoaderContext, loaderIsa);
                    boolean updateLoadingPackages = maybeAddLoadingPackage(owningPackageName, loadingPackageName, newData2.mLoadingPackages);
                    DexUseInfo existingData = (DexUseInfo) packageUseInfo.mDexUseInfoMap.get(dexPath);
                    if (existingData == null) {
                        packageUseInfo.mDexUseInfoMap.put(dexPath, newData2);
                        return true;
                    } else if (ownerUserId != existingData.mOwnerUserId) {
                        throw new IllegalArgumentException("Trying to change ownerUserId for  dex path " + dexPath + " from " + existingData.mOwnerUserId + " to " + ownerUserId);
                    } else {
                        if (!existingData.merge(newData2) && !updateLoadingPackages) {
                            z = false;
                        }
                        return z;
                    }
                } else {
                    return packageUseInfo.mergeCodePathUsedByOtherApps(dexPath, isUsedByOtherApps, owningPackageName, loadingPackageName);
                }
            }
        }
    }

    public void read() {
        read((PackageDexUsage) null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void maybeWriteAsync() {
        maybeWriteAsync(null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void writeNow() {
        writeInternal((Void) null);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.pm.AbstractStatsBase
    public void writeInternal(Void data) {
        AtomicFile file = getFile();
        FileOutputStream f = null;
        try {
            f = file.startWrite();
            OutputStreamWriter osw = new OutputStreamWriter(f);
            write(osw);
            osw.flush();
            file.finishWrite(f);
        } catch (IOException e) {
            if (f != null) {
                file.failWrite(f);
            }
            Slog.e(TAG, "Failed to write usage for dex files", e);
        }
    }

    void write(Writer out) {
        Map<String, PackageUseInfo> packageUseInfoMapClone = clonePackageUseInfoMap();
        FastPrintWriter fpw = new FastPrintWriter(out);
        fpw.print(PACKAGE_DEX_USAGE_VERSION_HEADER);
        int i = 2;
        fpw.println(2);
        for (Map.Entry<String, PackageUseInfo> pEntry : packageUseInfoMapClone.entrySet()) {
            String packageName = pEntry.getKey();
            PackageUseInfo packageUseInfo = pEntry.getValue();
            fpw.println(packageName);
            for (Map.Entry<String, Set<String>> codeEntry : packageUseInfo.mCodePathsUsedByOtherApps.entrySet()) {
                String codePath = codeEntry.getKey();
                Set<String> loadingPackages = codeEntry.getValue();
                fpw.println(CODE_PATH_LINE_CHAR + codePath);
                fpw.println(LOADING_PACKAGE_CHAR + String.join(SPLIT_CHAR, loadingPackages));
            }
            for (Map.Entry<String, DexUseInfo> dEntry : packageUseInfo.mDexUseInfoMap.entrySet()) {
                String dexPath = dEntry.getKey();
                DexUseInfo dexUseInfo = dEntry.getValue();
                fpw.println("#" + dexPath);
                CharSequence[] charSequenceArr = new CharSequence[i];
                charSequenceArr[0] = Integer.toString(dexUseInfo.mOwnerUserId);
                charSequenceArr[1] = writeBoolean(dexUseInfo.mIsUsedByOtherApps);
                fpw.print(String.join(SPLIT_CHAR, charSequenceArr));
                for (String isa : dexUseInfo.mLoaderIsas) {
                    fpw.print(SPLIT_CHAR + isa);
                }
                fpw.println();
                fpw.println(LOADING_PACKAGE_CHAR + String.join(SPLIT_CHAR, dexUseInfo.mLoadingPackages));
                fpw.println(dexUseInfo.getClassLoaderContext());
                i = 2;
            }
            i = 2;
        }
        fpw.flush();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.pm.AbstractStatsBase
    public void readInternal(Void data) {
        AtomicFile file = getFile();
        BufferedReader in = null;
        try {
            try {
                in = new BufferedReader(new InputStreamReader(file.openRead()));
                read((Reader) in);
            } catch (FileNotFoundException e) {
            } catch (IOException e2) {
                Slog.w(TAG, "Failed to parse package dex usage.", e2);
            }
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    void read(Reader reader) throws IOException {
        String[] strArr;
        String currentPackage;
        Set<String> loadingPackages;
        int ownerUserId;
        String[] elems;
        char c;
        Map<String, PackageUseInfo> data = new HashMap<>();
        BufferedReader in = new BufferedReader(reader);
        String versionLine = in.readLine();
        if (versionLine == null) {
            throw new IllegalStateException("No version line found.");
        }
        if (!versionLine.startsWith(PACKAGE_DEX_USAGE_VERSION_HEADER)) {
            throw new IllegalStateException("Invalid version line: " + versionLine);
        }
        int version = Integer.parseInt(versionLine.substring(PACKAGE_DEX_USAGE_VERSION_HEADER.length()));
        if (!isSupportedVersion(version)) {
            throw new IllegalStateException("Unexpected version: " + version);
        }
        Set<String> supportedIsas = new HashSet<>();
        char c2 = 0;
        for (String abi : Build.SUPPORTED_ABIS) {
            supportedIsas.add(VMRuntime.getInstructionSet(abi));
        }
        PackageUseInfo currentPackageData = null;
        String currentPackage2 = null;
        while (true) {
            String line = in.readLine();
            if (line != null) {
                if (line.startsWith("#")) {
                    if (currentPackage2 == null) {
                        throw new IllegalStateException("Malformed PackageDexUsage file. Expected package line before dex line.");
                    }
                    String dexPath = line.substring("#".length());
                    String line2 = in.readLine();
                    if (line2 == null) {
                        throw new IllegalStateException("Could not find dexUseInfo line");
                    }
                    String[] elems2 = line2.split(SPLIT_CHAR);
                    if (elems2.length < 3) {
                        throw new IllegalStateException("Invalid PackageDexUsage line: " + line2);
                    }
                    Set<String> loadingPackages2 = maybeReadLoadingPackages(in, version);
                    String classLoaderContext = maybeReadClassLoaderContext(in, version);
                    int ownerUserId2 = Integer.parseInt(elems2[c2]);
                    boolean isUsedByOtherApps = readBoolean(elems2[1]);
                    currentPackage = currentPackage2;
                    DexUseInfo dexUseInfo = new DexUseInfo(isUsedByOtherApps, ownerUserId2, classLoaderContext, null);
                    dexUseInfo.mLoadingPackages.addAll(loadingPackages2);
                    int i = 2;
                    while (true) {
                        int i2 = i;
                        boolean isUsedByOtherApps2 = isUsedByOtherApps;
                        if (i2 >= elems2.length) {
                            break;
                        }
                        String isa = elems2[i2];
                        if (supportedIsas.contains(isa)) {
                            loadingPackages = loadingPackages2;
                            ownerUserId = ownerUserId2;
                            dexUseInfo.mLoaderIsas.add(elems2[i2]);
                            elems = elems2;
                        } else {
                            loadingPackages = loadingPackages2;
                            ownerUserId = ownerUserId2;
                            StringBuilder sb = new StringBuilder();
                            elems = elems2;
                            sb.append("Unsupported ISA when parsing PackageDexUsage: ");
                            sb.append(isa);
                            Slog.wtf(TAG, sb.toString());
                        }
                        i = i2 + 1;
                        isUsedByOtherApps = isUsedByOtherApps2;
                        loadingPackages2 = loadingPackages;
                        ownerUserId2 = ownerUserId;
                        elems2 = elems;
                    }
                    if (supportedIsas.isEmpty()) {
                        Slog.wtf(TAG, "Ignore dexPath when parsing PackageDexUsage because of unsupported isas. dexPath=" + dexPath);
                    } else {
                        currentPackageData.mDexUseInfoMap.put(dexPath, dexUseInfo);
                    }
                } else {
                    currentPackage = currentPackage2;
                    if (line.startsWith(CODE_PATH_LINE_CHAR)) {
                        if (version < 2) {
                            throw new IllegalArgumentException("Unexpected code path line when parsing PackageDexUseData: " + line);
                        }
                        String codePath = line.substring(CODE_PATH_LINE_CHAR.length());
                        currentPackageData.mCodePathsUsedByOtherApps.put(codePath, maybeReadLoadingPackages(in, version));
                    } else {
                        if (version >= 2) {
                            PackageUseInfo currentPackageData2 = new PackageUseInfo();
                            currentPackage2 = line;
                            currentPackageData = currentPackageData2;
                            c = 0;
                        } else {
                            String[] elems3 = line.split(SPLIT_CHAR);
                            if (elems3.length != 2) {
                                throw new IllegalStateException("Invalid PackageDexUsage line: " + line);
                            }
                            c = 0;
                            currentPackage2 = elems3[0];
                            currentPackageData = new PackageUseInfo();
                            currentPackageData.mUsedByOtherAppsBeforeUpgrade = readBoolean(elems3[1]);
                        }
                        data.put(currentPackage2, currentPackageData);
                        c2 = c;
                    }
                }
                currentPackage2 = currentPackage;
                c2 = 0;
            } else {
                synchronized (this.mPackageUseInfoMap) {
                    this.mPackageUseInfoMap.clear();
                    this.mPackageUseInfoMap.putAll(data);
                }
                return;
            }
        }
    }

    private String maybeReadClassLoaderContext(BufferedReader in, int version) throws IOException {
        String context = null;
        if (version < 2 || (context = in.readLine()) != null) {
            return context == null ? UNKNOWN_CLASS_LOADER_CONTEXT : context;
        }
        throw new IllegalStateException("Could not find the classLoaderContext line.");
    }

    private Set<String> maybeReadLoadingPackages(BufferedReader in, int version) throws IOException {
        if (version >= 2) {
            String line = in.readLine();
            if (line == null) {
                throw new IllegalStateException("Could not find the loadingPackages line.");
            }
            if (line.length() == LOADING_PACKAGE_CHAR.length()) {
                return Collections.emptySet();
            }
            Set<String> result = new HashSet<>();
            Collections.addAll(result, line.substring(LOADING_PACKAGE_CHAR.length()).split(SPLIT_CHAR));
            return result;
        }
        return Collections.emptySet();
    }

    private boolean maybeAddLoadingPackage(String owningPackage, String loadingPackage, Set<String> loadingPackages) {
        return !owningPackage.equals(loadingPackage) && loadingPackages.add(loadingPackage);
    }

    private boolean isSupportedVersion(int version) {
        return version == 1 || version == 2;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void syncData(Map<String, Set<Integer>> packageToUsersMap, Map<String, Set<String>> packageToCodePaths) {
        synchronized (this.mPackageUseInfoMap) {
            Iterator<Map.Entry<String, PackageUseInfo>> pIt = this.mPackageUseInfoMap.entrySet().iterator();
            while (pIt.hasNext()) {
                Map.Entry<String, PackageUseInfo> pEntry = pIt.next();
                String packageName = pEntry.getKey();
                PackageUseInfo packageUseInfo = pEntry.getValue();
                Set<Integer> users = packageToUsersMap.get(packageName);
                if (users == null) {
                    pIt.remove();
                } else {
                    Iterator<Map.Entry<String, DexUseInfo>> dIt = packageUseInfo.mDexUseInfoMap.entrySet().iterator();
                    while (dIt.hasNext()) {
                        DexUseInfo dexUseInfo = dIt.next().getValue();
                        if (!users.contains(Integer.valueOf(dexUseInfo.mOwnerUserId))) {
                            dIt.remove();
                        }
                    }
                    Set<String> codePaths = packageToCodePaths.get(packageName);
                    Iterator<Map.Entry<String, Set<String>>> codeIt = packageUseInfo.mCodePathsUsedByOtherApps.entrySet().iterator();
                    while (codeIt.hasNext()) {
                        if (!codePaths.contains(codeIt.next().getKey())) {
                            codeIt.remove();
                        }
                    }
                    if (packageUseInfo.mUsedByOtherAppsBeforeUpgrade) {
                        for (String codePath : codePaths) {
                            packageUseInfo.mergeCodePathUsedByOtherApps(codePath, true, null, null);
                        }
                    } else if (!packageUseInfo.isAnyCodePathUsedByOtherApps() && packageUseInfo.mDexUseInfoMap.isEmpty()) {
                        pIt.remove();
                    }
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean clearUsedByOtherApps(String packageName) {
        synchronized (this.mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = this.mPackageUseInfoMap.get(packageName);
            if (packageUseInfo == null) {
                return false;
            }
            return packageUseInfo.clearCodePathUsedByOtherApps();
        }
    }

    public boolean removePackage(String packageName) {
        boolean z;
        synchronized (this.mPackageUseInfoMap) {
            z = this.mPackageUseInfoMap.remove(packageName) != null;
        }
        return z;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeUserPackage(String packageName, int userId) {
        synchronized (this.mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = this.mPackageUseInfoMap.get(packageName);
            if (packageUseInfo == null) {
                return false;
            }
            boolean updated = false;
            Iterator<Map.Entry<String, DexUseInfo>> dIt = packageUseInfo.mDexUseInfoMap.entrySet().iterator();
            while (dIt.hasNext()) {
                DexUseInfo dexUseInfo = dIt.next().getValue();
                if (dexUseInfo.mOwnerUserId == userId) {
                    dIt.remove();
                    updated = true;
                }
            }
            if (packageUseInfo.mDexUseInfoMap.isEmpty() && !packageUseInfo.isAnyCodePathUsedByOtherApps()) {
                this.mPackageUseInfoMap.remove(packageName);
                updated = true;
            }
            return updated;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean removeDexFile(String packageName, String dexFile, int userId) {
        synchronized (this.mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = this.mPackageUseInfoMap.get(packageName);
            if (packageUseInfo == null) {
                return false;
            }
            return removeDexFile(packageUseInfo, dexFile, userId);
        }
    }

    private boolean removeDexFile(PackageUseInfo packageUseInfo, String dexFile, int userId) {
        DexUseInfo dexUseInfo = (DexUseInfo) packageUseInfo.mDexUseInfoMap.get(dexFile);
        if (dexUseInfo == null || dexUseInfo.mOwnerUserId != userId) {
            return false;
        }
        packageUseInfo.mDexUseInfoMap.remove(dexFile);
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageUseInfo getPackageUseInfo(String packageName) {
        PackageUseInfo packageUseInfo;
        synchronized (this.mPackageUseInfoMap) {
            PackageUseInfo useInfo = this.mPackageUseInfoMap.get(packageName);
            packageUseInfo = useInfo == null ? null : new PackageUseInfo(useInfo);
        }
        return packageUseInfo;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Set<String> getAllPackagesWithSecondaryDexFiles() {
        Set<String> packages = new HashSet<>();
        synchronized (this.mPackageUseInfoMap) {
            for (Map.Entry<String, PackageUseInfo> entry : this.mPackageUseInfoMap.entrySet()) {
                if (!entry.getValue().mDexUseInfoMap.isEmpty()) {
                    packages.add(entry.getKey());
                }
            }
        }
        return packages;
    }

    public void clear() {
        synchronized (this.mPackageUseInfoMap) {
            this.mPackageUseInfoMap.clear();
        }
    }

    private Map<String, PackageUseInfo> clonePackageUseInfoMap() {
        Map<String, PackageUseInfo> clone = new HashMap<>();
        synchronized (this.mPackageUseInfoMap) {
            for (Map.Entry<String, PackageUseInfo> e : this.mPackageUseInfoMap.entrySet()) {
                clone.put(e.getKey(), new PackageUseInfo(e.getValue()));
            }
        }
        return clone;
    }

    private String writeBoolean(boolean bool) {
        return bool ? "1" : "0";
    }

    private boolean readBoolean(String bool) {
        if ("0".equals(bool)) {
            return false;
        }
        if ("1".equals(bool)) {
            return true;
        }
        throw new IllegalArgumentException("Unknown bool encoding: " + bool);
    }

    public String dump() {
        StringWriter sw = new StringWriter();
        write(sw);
        return sw.toString();
    }

    /* loaded from: classes.dex */
    public static class PackageUseInfo {
        private final Map<String, Set<String>> mCodePathsUsedByOtherApps;
        private final Map<String, DexUseInfo> mDexUseInfoMap;
        private boolean mUsedByOtherAppsBeforeUpgrade;

        public PackageUseInfo() {
            this.mCodePathsUsedByOtherApps = new HashMap();
            this.mDexUseInfoMap = new HashMap();
        }

        public PackageUseInfo(PackageUseInfo other) {
            this.mCodePathsUsedByOtherApps = new HashMap();
            for (Map.Entry<String, Set<String>> e : other.mCodePathsUsedByOtherApps.entrySet()) {
                this.mCodePathsUsedByOtherApps.put(e.getKey(), new HashSet(e.getValue()));
            }
            this.mDexUseInfoMap = new HashMap();
            for (Map.Entry<String, DexUseInfo> e2 : other.mDexUseInfoMap.entrySet()) {
                this.mDexUseInfoMap.put(e2.getKey(), new DexUseInfo(e2.getValue()));
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean mergeCodePathUsedByOtherApps(String codePath, boolean isUsedByOtherApps, String owningPackageName, String loadingPackage) {
            boolean newLoadingPackage;
            if (!isUsedByOtherApps) {
                return false;
            }
            boolean newCodePath = false;
            Set<String> loadingPackages = this.mCodePathsUsedByOtherApps.get(codePath);
            if (loadingPackages == null) {
                loadingPackages = new HashSet();
                this.mCodePathsUsedByOtherApps.put(codePath, loadingPackages);
                newCodePath = true;
            }
            if (loadingPackage == null || loadingPackage.equals(owningPackageName) || !loadingPackages.add(loadingPackage)) {
                newLoadingPackage = false;
            } else {
                newLoadingPackage = true;
            }
            if (!newCodePath && !newLoadingPackage) {
                return false;
            }
            return true;
        }

        public boolean isUsedByOtherApps(String codePath) {
            return this.mCodePathsUsedByOtherApps.containsKey(codePath);
        }

        public Map<String, DexUseInfo> getDexUseInfoMap() {
            return this.mDexUseInfoMap;
        }

        public Set<String> getLoadingPackages(String codePath) {
            return this.mCodePathsUsedByOtherApps.getOrDefault(codePath, null);
        }

        public boolean isAnyCodePathUsedByOtherApps() {
            return !this.mCodePathsUsedByOtherApps.isEmpty();
        }

        boolean clearCodePathUsedByOtherApps() {
            this.mUsedByOtherAppsBeforeUpgrade = true;
            if (this.mCodePathsUsedByOtherApps.isEmpty()) {
                return false;
            }
            this.mCodePathsUsedByOtherApps.clear();
            return true;
        }
    }

    /* loaded from: classes.dex */
    public static class DexUseInfo {
        private String mClassLoaderContext;
        private boolean mIsUsedByOtherApps;
        private final Set<String> mLoaderIsas;
        private final Set<String> mLoadingPackages;
        private final int mOwnerUserId;

        public DexUseInfo(boolean isUsedByOtherApps, int ownerUserId, String classLoaderContext, String loaderIsa) {
            this.mIsUsedByOtherApps = isUsedByOtherApps;
            this.mOwnerUserId = ownerUserId;
            this.mClassLoaderContext = classLoaderContext;
            this.mLoaderIsas = new HashSet();
            if (loaderIsa != null) {
                this.mLoaderIsas.add(loaderIsa);
            }
            this.mLoadingPackages = new HashSet();
        }

        public DexUseInfo(DexUseInfo other) {
            this.mIsUsedByOtherApps = other.mIsUsedByOtherApps;
            this.mOwnerUserId = other.mOwnerUserId;
            this.mClassLoaderContext = other.mClassLoaderContext;
            this.mLoaderIsas = new HashSet(other.mLoaderIsas);
            this.mLoadingPackages = new HashSet(other.mLoadingPackages);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public boolean merge(DexUseInfo dexUseInfo) {
            boolean oldIsUsedByOtherApps = this.mIsUsedByOtherApps;
            this.mIsUsedByOtherApps = this.mIsUsedByOtherApps || dexUseInfo.mIsUsedByOtherApps;
            boolean updateIsas = this.mLoaderIsas.addAll(dexUseInfo.mLoaderIsas);
            boolean updateLoadingPackages = this.mLoadingPackages.addAll(dexUseInfo.mLoadingPackages);
            String oldClassLoaderContext = this.mClassLoaderContext;
            if (PackageDexUsage.UNKNOWN_CLASS_LOADER_CONTEXT.equals(this.mClassLoaderContext)) {
                this.mClassLoaderContext = dexUseInfo.mClassLoaderContext;
            } else if (PackageDexUsage.UNSUPPORTED_CLASS_LOADER_CONTEXT.equals(dexUseInfo.mClassLoaderContext)) {
                this.mClassLoaderContext = PackageDexUsage.UNSUPPORTED_CLASS_LOADER_CONTEXT;
            } else if (!PackageDexUsage.UNSUPPORTED_CLASS_LOADER_CONTEXT.equals(this.mClassLoaderContext) && !Objects.equals(this.mClassLoaderContext, dexUseInfo.mClassLoaderContext)) {
                this.mClassLoaderContext = PackageDexUsage.VARIABLE_CLASS_LOADER_CONTEXT;
            }
            return updateIsas || oldIsUsedByOtherApps != this.mIsUsedByOtherApps || updateLoadingPackages || !Objects.equals(oldClassLoaderContext, this.mClassLoaderContext);
        }

        public boolean isUsedByOtherApps() {
            return this.mIsUsedByOtherApps;
        }

        public int getOwnerUserId() {
            return this.mOwnerUserId;
        }

        public Set<String> getLoaderIsas() {
            return this.mLoaderIsas;
        }

        public Set<String> getLoadingPackages() {
            return this.mLoadingPackages;
        }

        public String getClassLoaderContext() {
            return this.mClassLoaderContext;
        }

        public boolean isUnsupportedClassLoaderContext() {
            return PackageDexUsage.UNSUPPORTED_CLASS_LOADER_CONTEXT.equals(this.mClassLoaderContext);
        }

        public boolean isUnknownClassLoaderContext() {
            return PackageDexUsage.UNKNOWN_CLASS_LOADER_CONTEXT.equals(this.mClassLoaderContext);
        }

        public boolean isVariableClassLoaderContext() {
            return PackageDexUsage.VARIABLE_CLASS_LOADER_CONTEXT.equals(this.mClassLoaderContext);
        }
    }
}
