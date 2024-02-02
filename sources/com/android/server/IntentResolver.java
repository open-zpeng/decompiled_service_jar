package com.android.server;

import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.FastImmutableArraySet;
import android.util.LogPrinter;
import android.util.MutableInt;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import com.android.internal.util.FastPrintWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
/* loaded from: classes.dex */
public abstract class IntentResolver<F extends IntentFilter, R> {
    private static final boolean DEBUG = false;
    private static final String TAG = "IntentResolver";
    private static final boolean localLOGV = false;
    private static final boolean localVerificationLOGV = false;
    private static final Comparator mResolvePrioritySorter = new Comparator() { // from class: com.android.server.IntentResolver.1
        @Override // java.util.Comparator
        public int compare(Object o1, Object o2) {
            int q1 = ((IntentFilter) o1).getPriority();
            int q2 = ((IntentFilter) o2).getPriority();
            if (q1 > q2) {
                return -1;
            }
            return q1 < q2 ? 1 : 0;
        }
    };
    private final ArraySet<F> mFilters = new ArraySet<>();
    private final ArrayMap<String, F[]> mTypeToFilter = new ArrayMap<>();
    private final ArrayMap<String, F[]> mBaseTypeToFilter = new ArrayMap<>();
    private final ArrayMap<String, F[]> mWildTypeToFilter = new ArrayMap<>();
    private final ArrayMap<String, F[]> mSchemeToFilter = new ArrayMap<>();
    private final ArrayMap<String, F[]> mActionToFilter = new ArrayMap<>();
    private final ArrayMap<String, F[]> mTypedActionToFilter = new ArrayMap<>();

    protected abstract boolean isPackageForFilter(String str, F f);

    protected abstract F[] newArray(int i);

    public void addFilter(F f) {
        this.mFilters.add(f);
        int numS = register_intent_filter(f, f.schemesIterator(), this.mSchemeToFilter, "      Scheme: ");
        int numT = register_mime_types(f, "      Type: ");
        if (numS == 0 && numT == 0) {
            register_intent_filter(f, f.actionsIterator(), this.mActionToFilter, "      Action: ");
        }
        if (numT != 0) {
            register_intent_filter(f, f.actionsIterator(), this.mTypedActionToFilter, "      TypedAction: ");
        }
    }

    public static boolean filterEquals(IntentFilter f1, IntentFilter f2) {
        int s1 = f1.countActions();
        int s2 = f2.countActions();
        if (s1 != s2) {
            return false;
        }
        for (int i = 0; i < s1; i++) {
            if (!f2.hasAction(f1.getAction(i))) {
                return false;
            }
        }
        int s12 = f1.countCategories();
        int s22 = f2.countCategories();
        if (s12 != s22) {
            return false;
        }
        for (int i2 = 0; i2 < s12; i2++) {
            if (!f2.hasCategory(f1.getCategory(i2))) {
                return false;
            }
        }
        int s13 = f1.countDataTypes();
        int s23 = f2.countDataTypes();
        if (s13 != s23) {
            return false;
        }
        for (int i3 = 0; i3 < s13; i3++) {
            if (!f2.hasExactDataType(f1.getDataType(i3))) {
                return false;
            }
        }
        int s14 = f1.countDataSchemes();
        int s24 = f2.countDataSchemes();
        if (s14 != s24) {
            return false;
        }
        for (int i4 = 0; i4 < s14; i4++) {
            if (!f2.hasDataScheme(f1.getDataScheme(i4))) {
                return false;
            }
        }
        int s15 = f1.countDataAuthorities();
        int s25 = f2.countDataAuthorities();
        if (s15 != s25) {
            return false;
        }
        for (int i5 = 0; i5 < s15; i5++) {
            if (!f2.hasDataAuthority(f1.getDataAuthority(i5))) {
                return false;
            }
        }
        int s16 = f1.countDataPaths();
        int s26 = f2.countDataPaths();
        if (s16 != s26) {
            return false;
        }
        for (int i6 = 0; i6 < s16; i6++) {
            if (!f2.hasDataPath(f1.getDataPath(i6))) {
                return false;
            }
        }
        int s17 = f1.countDataSchemeSpecificParts();
        int s27 = f2.countDataSchemeSpecificParts();
        if (s17 != s27) {
            return false;
        }
        for (int i7 = 0; i7 < s17; i7++) {
            if (!f2.hasDataSchemeSpecificPart(f1.getDataSchemeSpecificPart(i7))) {
                return false;
            }
        }
        return true;
    }

    private ArrayList<F> collectFilters(F[] array, IntentFilter matching) {
        F cur;
        ArrayList<F> res = null;
        if (array != null) {
            for (int i = 0; i < array.length && (cur = array[i]) != null; i++) {
                if (filterEquals(cur, matching)) {
                    if (res == null) {
                        res = new ArrayList<>();
                    }
                    res.add(cur);
                }
            }
        }
        return res;
    }

    public ArrayList<F> findFilters(IntentFilter matching) {
        if (matching.countDataSchemes() == 1) {
            return collectFilters(this.mSchemeToFilter.get(matching.getDataScheme(0)), matching);
        }
        if (matching.countDataTypes() != 0 && matching.countActions() == 1) {
            return collectFilters(this.mTypedActionToFilter.get(matching.getAction(0)), matching);
        }
        if (matching.countDataTypes() == 0 && matching.countDataSchemes() == 0 && matching.countActions() == 1) {
            return collectFilters(this.mActionToFilter.get(matching.getAction(0)), matching);
        }
        ArrayList<F> res = null;
        Iterator<F> it = this.mFilters.iterator();
        while (it.hasNext()) {
            F cur = it.next();
            if (filterEquals(cur, matching)) {
                if (res == null) {
                    res = new ArrayList<>();
                }
                res.add(cur);
            }
        }
        return res;
    }

    public void removeFilter(F f) {
        removeFilterInternal(f);
        this.mFilters.remove(f);
    }

    void removeFilterInternal(F f) {
        int numS = unregister_intent_filter(f, f.schemesIterator(), this.mSchemeToFilter, "      Scheme: ");
        int numT = unregister_mime_types(f, "      Type: ");
        if (numS == 0 && numT == 0) {
            unregister_intent_filter(f, f.actionsIterator(), this.mActionToFilter, "      Action: ");
        }
        if (numT != 0) {
            unregister_intent_filter(f, f.actionsIterator(), this.mTypedActionToFilter, "      TypedAction: ");
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v2, types: [com.android.server.IntentResolver] */
    boolean dumpMap(PrintWriter out, String titlePrefix, String title, String prefix, ArrayMap<String, F[]> map, String packageName, boolean printFilter, boolean collapseDuplicates) {
        boolean printedSomething;
        Printer printer;
        String title2;
        Printer printer2;
        F filter;
        F filter2;
        String title3;
        Printer printer3;
        boolean printedSomething2;
        IntentResolver<F, R> intentResolver = this;
        PrintWriter printWriter = out;
        String eprefix = prefix + "  ";
        String fprefix = prefix + "    ";
        ArrayMap<Object, MutableInt> found = new ArrayMap<>();
        String title4 = title;
        Printer printer4 = null;
        boolean printedSomething3 = false;
        int mapi = 0;
        while (mapi < map.size()) {
            F[] a = map.valueAt(mapi);
            int N = a.length;
            boolean printedHeader = false;
            if (collapseDuplicates && !printFilter) {
                found.clear();
                int i = 0;
                while (true) {
                    int i2 = i;
                    if (i2 >= N || (filter = a[i2]) == null) {
                        break;
                    }
                    if (packageName != null) {
                        filter2 = filter;
                        if (!intentResolver.isPackageForFilter(packageName, filter2)) {
                            printedSomething2 = printedSomething3;
                            title3 = title4;
                            printer3 = printer4;
                            i = i2 + 1;
                            title4 = title3;
                            printer4 = printer3;
                            printedSomething3 = printedSomething2;
                        }
                    } else {
                        filter2 = filter;
                    }
                    title3 = title4;
                    Object label = intentResolver.filterToLabel(filter2);
                    int index = found.indexOfKey(label);
                    printer3 = printer4;
                    if (index < 0) {
                        printedSomething2 = printedSomething3;
                        found.put(label, new MutableInt(1));
                    } else {
                        printedSomething2 = printedSomething3;
                        found.valueAt(index).value++;
                    }
                    i = i2 + 1;
                    title4 = title3;
                    printer4 = printer3;
                    printedSomething3 = printedSomething2;
                }
                printedSomething = printedSomething3;
                printer = printer4;
                title2 = title4;
                for (int i3 = 0; i3 < found.size(); i3++) {
                    if (title2 != null) {
                        out.print(titlePrefix);
                        printWriter.println(title2);
                        title2 = null;
                    }
                    if (!printedHeader) {
                        printWriter.print(eprefix);
                        printWriter.print(map.keyAt(mapi));
                        printWriter.println(":");
                        printedHeader = true;
                    }
                    printedSomething = true;
                    intentResolver.dumpFilterLabel(printWriter, fprefix, found.keyAt(i3), found.valueAt(i3).value);
                }
            } else {
                printedSomething = printedSomething3;
                printer = printer4;
                title2 = title4;
                int i4 = 0;
                while (i4 < N) {
                    F filter3 = a[i4];
                    if (filter3 != null) {
                        if (packageName == null || intentResolver.isPackageForFilter(packageName, filter3)) {
                            if (title2 != null) {
                                out.print(titlePrefix);
                                printWriter.println(title2);
                                title2 = null;
                            }
                            if (!printedHeader) {
                                printWriter.print(eprefix);
                                printWriter.print(map.keyAt(mapi));
                                printWriter.println(":");
                                printedHeader = true;
                            }
                            intentResolver.dumpFilter(printWriter, fprefix, filter3);
                            if (!printFilter) {
                                printedSomething = true;
                            } else {
                                if (printer == null) {
                                    printer2 = new PrintWriterPrinter(printWriter);
                                } else {
                                    printer2 = printer;
                                }
                                filter3.dump(printer2, fprefix + "  ");
                                printedSomething = true;
                                printer = printer2;
                            }
                        }
                        i4++;
                        intentResolver = this;
                        printWriter = out;
                    }
                }
            }
            title4 = title2;
            printer4 = printer;
            printedSomething3 = printedSomething;
            mapi++;
            intentResolver = this;
            printWriter = out;
        }
        return printedSomething3;
    }

    void writeProtoMap(ProtoOutputStream proto, long fieldId, ArrayMap<String, F[]> map) {
        F[] valueAt;
        int N = map.size();
        for (int mapi = 0; mapi < N; mapi++) {
            long token = proto.start(fieldId);
            proto.write(1138166333441L, map.keyAt(mapi));
            for (F f : map.valueAt(mapi)) {
                if (f != null) {
                    proto.write(2237677961218L, f.toString());
                }
            }
            proto.end(token);
        }
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        writeProtoMap(proto, 2246267895809L, this.mTypeToFilter);
        writeProtoMap(proto, 2246267895810L, this.mBaseTypeToFilter);
        writeProtoMap(proto, 2246267895811L, this.mWildTypeToFilter);
        writeProtoMap(proto, 2246267895812L, this.mSchemeToFilter);
        writeProtoMap(proto, 2246267895813L, this.mActionToFilter);
        writeProtoMap(proto, 2246267895814L, this.mTypedActionToFilter);
        proto.end(token);
    }

    public boolean dump(PrintWriter out, String title, String prefix, String packageName, boolean printFilter, boolean collapseDuplicates) {
        String innerPrefix = prefix + "  ";
        String sepPrefix = "\n" + prefix;
        String curPrefix = title + "\n" + prefix;
        if (dumpMap(out, curPrefix, "Full MIME Types:", innerPrefix, this.mTypeToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "Base MIME Types:", innerPrefix, this.mBaseTypeToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "Wild MIME Types:", innerPrefix, this.mWildTypeToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "Schemes:", innerPrefix, this.mSchemeToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "Non-Data Actions:", innerPrefix, this.mActionToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        if (dumpMap(out, curPrefix, "MIME Typed Actions:", innerPrefix, this.mTypedActionToFilter, packageName, printFilter, collapseDuplicates)) {
            curPrefix = sepPrefix;
        }
        return curPrefix == sepPrefix;
    }

    /* loaded from: classes.dex */
    private class IteratorWrapper implements Iterator<F> {
        private F mCur;
        private final Iterator<F> mI;

        IteratorWrapper(Iterator<F> it) {
            this.mI = it;
        }

        @Override // java.util.Iterator
        public boolean hasNext() {
            return this.mI.hasNext();
        }

        @Override // java.util.Iterator
        public F next() {
            F next = this.mI.next();
            this.mCur = next;
            return next;
        }

        @Override // java.util.Iterator
        public void remove() {
            if (this.mCur != null) {
                IntentResolver.this.removeFilterInternal(this.mCur);
            }
            this.mI.remove();
        }
    }

    public Iterator<F> filterIterator() {
        return new IteratorWrapper(this.mFilters.iterator());
    }

    public Set<F> filterSet() {
        return Collections.unmodifiableSet(this.mFilters);
    }

    public List<R> queryIntentFromList(Intent intent, String resolvedType, boolean defaultOnly, ArrayList<F[]> listCut, int userId) {
        ArrayList<R> resultList = new ArrayList<>();
        int i = 0;
        boolean debug = (intent.getFlags() & 8) != 0;
        FastImmutableArraySet<String> categories = getFastIntentCategories(intent);
        String scheme = intent.getScheme();
        int N = listCut.size();
        while (true) {
            int i2 = i;
            if (i2 < N) {
                buildResolveList(intent, categories, debug, defaultOnly, resolvedType, scheme, listCut.get(i2), resultList, userId);
                i = i2 + 1;
            } else {
                filterResults(resultList);
                sortResults(resultList);
                return resultList;
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:41:0x0145  */
    /* JADX WARN: Removed duplicated region for block: B:42:0x0162  */
    /* JADX WARN: Removed duplicated region for block: B:52:0x01a1  */
    /* JADX WARN: Removed duplicated region for block: B:56:0x01c8  */
    /* JADX WARN: Removed duplicated region for block: B:58:0x01cc A[ADDED_TO_REGION] */
    /* JADX WARN: Removed duplicated region for block: B:63:0x01e3  */
    /* JADX WARN: Removed duplicated region for block: B:66:0x0205  */
    /* JADX WARN: Removed duplicated region for block: B:68:0x0217  */
    /* JADX WARN: Removed duplicated region for block: B:70:0x022b  */
    /* JADX WARN: Removed duplicated region for block: B:72:0x023e  */
    /* JADX WARN: Removed duplicated region for block: B:75:0x0258  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public java.util.List<R> queryIntent(android.content.Intent r22, java.lang.String r23, boolean r24, int r25) {
        /*
            Method dump skipped, instructions count: 647
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.IntentResolver.queryIntent(android.content.Intent, java.lang.String, boolean, int):java.util.List");
    }

    protected boolean allowFilterResult(F filter, List<R> dest) {
        return true;
    }

    protected boolean isFilterStopped(F filter, int userId) {
        return false;
    }

    protected boolean isFilterVerified(F filter) {
        return filter.isVerified();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* JADX WARN: Multi-variable type inference failed */
    public R newResult(F filter, int match, int userId) {
        return filter;
    }

    protected void sortResults(List<R> results) {
        Collections.sort(results, mResolvePrioritySorter);
    }

    protected void filterResults(List<R> results) {
    }

    protected void dumpFilter(PrintWriter out, String prefix, F filter) {
        out.print(prefix);
        out.println(filter);
    }

    protected Object filterToLabel(F filter) {
        return "IntentFilter";
    }

    protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
        out.print(prefix);
        out.print(label);
        out.print(": ");
        out.println(count);
    }

    private final void addFilter(ArrayMap<String, F[]> map, String name, F filter) {
        F[] array = map.get(name);
        if (array == null) {
            F[] array2 = newArray(2);
            map.put(name, array2);
            array2[0] = filter;
            return;
        }
        int N = array.length;
        int i = N;
        while (i > 0 && array[i - 1] == null) {
            i--;
        }
        if (i >= N) {
            F[] newa = newArray((N * 3) / 2);
            System.arraycopy(array, 0, newa, 0, N);
            newa[N] = filter;
            map.put(name, newa);
            return;
        }
        array[i] = filter;
    }

    private final int register_mime_types(F filter, String prefix) {
        Iterator<String> i = filter.typesIterator();
        if (i == null) {
            return 0;
        }
        int num = 0;
        while (i.hasNext()) {
            String name = i.next();
            num++;
            String baseName = name;
            int slashpos = name.indexOf(47);
            if (slashpos > 0) {
                baseName = name.substring(0, slashpos).intern();
            } else {
                name = name + "/*";
            }
            addFilter(this.mTypeToFilter, name, filter);
            if (slashpos > 0) {
                addFilter(this.mBaseTypeToFilter, baseName, filter);
            } else {
                addFilter(this.mWildTypeToFilter, baseName, filter);
            }
        }
        return num;
    }

    private final int unregister_mime_types(F filter, String prefix) {
        Iterator<String> i = filter.typesIterator();
        if (i == null) {
            return 0;
        }
        int num = 0;
        while (i.hasNext()) {
            String name = i.next();
            num++;
            String baseName = name;
            int slashpos = name.indexOf(47);
            if (slashpos > 0) {
                baseName = name.substring(0, slashpos).intern();
            } else {
                name = name + "/*";
            }
            remove_all_objects(this.mTypeToFilter, name, filter);
            if (slashpos > 0) {
                remove_all_objects(this.mBaseTypeToFilter, baseName, filter);
            } else {
                remove_all_objects(this.mWildTypeToFilter, baseName, filter);
            }
        }
        return num;
    }

    private final int register_intent_filter(F filter, Iterator<String> i, ArrayMap<String, F[]> dest, String prefix) {
        int num = 0;
        if (i == null) {
            return 0;
        }
        while (i.hasNext()) {
            String name = i.next();
            num++;
            addFilter(dest, name, filter);
        }
        return num;
    }

    private final int unregister_intent_filter(F filter, Iterator<String> i, ArrayMap<String, F[]> dest, String prefix) {
        int num = 0;
        if (i == null) {
            return 0;
        }
        while (i.hasNext()) {
            String name = i.next();
            num++;
            remove_all_objects(dest, name, filter);
        }
        return num;
    }

    private final void remove_all_objects(ArrayMap<String, F[]> map, String name, Object object) {
        F[] array = map.get(name);
        if (array != null) {
            int LAST = array.length - 1;
            while (LAST >= 0 && array[LAST] == null) {
                LAST--;
            }
            int LAST2 = LAST;
            while (LAST >= 0) {
                if (array[LAST] == object) {
                    int remain = LAST2 - LAST;
                    if (remain > 0) {
                        System.arraycopy(array, LAST + 1, array, LAST, remain);
                    }
                    array[LAST2] = null;
                    LAST2--;
                }
                LAST--;
            }
            if (LAST2 < 0) {
                map.remove(name);
            } else if (LAST2 < array.length / 2) {
                F[] newa = newArray(LAST2 + 2);
                System.arraycopy(array, 0, newa, 0, LAST2 + 1);
                map.put(name, newa);
            }
        }
    }

    private static FastImmutableArraySet<String> getFastIntentCategories(Intent intent) {
        Set<String> categories = intent.getCategories();
        if (categories == null) {
            return null;
        }
        return new FastImmutableArraySet<>((String[]) categories.toArray(new String[categories.size()]));
    }

    private void buildResolveList(Intent intent, FastImmutableArraySet<String> categories, boolean debug, boolean defaultOnly, String resolvedType, String scheme, F[] src, List<R> dest, int userId) {
        Printer logPrinter;
        FastPrintWriter fastPrintWriter;
        F filter;
        String action;
        int i;
        int N;
        Uri data;
        FastPrintWriter fastPrintWriter2;
        String packageName;
        Printer logPrinter2;
        String reason;
        F[] fArr = src;
        String action2 = intent.getAction();
        Uri data2 = intent.getData();
        String packageName2 = intent.getPackage();
        boolean excludingStopped = intent.isExcludingStopped();
        if (debug) {
            logPrinter = new LogPrinter(2, TAG, 3);
            fastPrintWriter = new FastPrintWriter(logPrinter);
        } else {
            logPrinter = null;
            fastPrintWriter = null;
        }
        Printer logPrinter3 = logPrinter;
        FastPrintWriter fastPrintWriter3 = fastPrintWriter;
        int N2 = fArr != null ? fArr.length : 0;
        boolean hasNonDefaults = false;
        int i2 = 0;
        while (true) {
            int i3 = i2;
            if (i3 < N2 && (filter = fArr[i3]) != null) {
                if (debug) {
                    Slog.v(TAG, "Matching against filter " + filter);
                }
                if (excludingStopped && isFilterStopped(filter, userId)) {
                    if (debug) {
                        Slog.v(TAG, "  Filter's target is stopped; skipping");
                    }
                } else if (packageName2 == null || isPackageForFilter(packageName2, filter)) {
                    if (filter.getAutoVerify() && debug) {
                        Slog.v(TAG, "  Filter verified: " + isFilterVerified(filter));
                        int authorities = filter.countDataAuthorities();
                        int z = 0;
                        while (z < authorities) {
                            Slog.v(TAG, "   " + filter.getDataAuthority(z).getHost());
                            z++;
                            authorities = authorities;
                            i3 = i3;
                        }
                    }
                    int i4 = i3;
                    if (allowFilterResult(filter, dest)) {
                        action = action2;
                        i = i4;
                        N = N2;
                        Uri uri = data2;
                        data = data2;
                        fastPrintWriter2 = fastPrintWriter3;
                        packageName = packageName2;
                        logPrinter2 = logPrinter3;
                        int match = filter.match(action2, resolvedType, scheme, uri, categories, TAG);
                        if (match >= 0) {
                            if (debug) {
                                Slog.v(TAG, "  Filter matched!  match=0x" + Integer.toHexString(match) + " hasDefault=" + filter.hasCategory("android.intent.category.DEFAULT"));
                            }
                            if (!defaultOnly || filter.hasCategory("android.intent.category.DEFAULT")) {
                                R oneResult = newResult(filter, match, userId);
                                if (oneResult != null) {
                                    dest.add(oneResult);
                                    if (debug) {
                                        dumpFilter(fastPrintWriter2, "    ", filter);
                                        fastPrintWriter2.flush();
                                        filter.dump(logPrinter2, "    ");
                                    }
                                }
                            } else {
                                hasNonDefaults = true;
                            }
                        } else if (debug) {
                            switch (match) {
                                case -4:
                                    reason = "category";
                                    break;
                                case -3:
                                    reason = "action";
                                    break;
                                case -2:
                                    reason = "data";
                                    break;
                                case -1:
                                    reason = "type";
                                    break;
                                default:
                                    reason = "unknown reason";
                                    break;
                            }
                            Slog.v(TAG, "  Filter did not match: " + reason);
                        }
                        i2 = i + 1;
                        fastPrintWriter3 = fastPrintWriter2;
                        logPrinter3 = logPrinter2;
                        N2 = N;
                        action2 = action;
                        data2 = data;
                        packageName2 = packageName;
                        fArr = src;
                    } else {
                        if (debug) {
                            Slog.v(TAG, "  Filter's target already added");
                        }
                        action = action2;
                        data = data2;
                        packageName = packageName2;
                        i = i4;
                        N = N2;
                        fastPrintWriter2 = fastPrintWriter3;
                        logPrinter2 = logPrinter3;
                        i2 = i + 1;
                        fastPrintWriter3 = fastPrintWriter2;
                        logPrinter3 = logPrinter2;
                        N2 = N;
                        action2 = action;
                        data2 = data;
                        packageName2 = packageName;
                        fArr = src;
                    }
                } else if (debug) {
                    Slog.v(TAG, "  Filter is not from package " + packageName2 + "; skipping");
                }
                i = i3;
                N = N2;
                action = action2;
                data = data2;
                packageName = packageName2;
                fastPrintWriter2 = fastPrintWriter3;
                logPrinter2 = logPrinter3;
                i2 = i + 1;
                fastPrintWriter3 = fastPrintWriter2;
                logPrinter3 = logPrinter2;
                N2 = N;
                action2 = action;
                data2 = data;
                packageName2 = packageName;
                fArr = src;
            }
        }
        if (debug && hasNonDefaults) {
            if (dest.size() == 0) {
                Slog.v(TAG, "resolveIntent failed: found match, but none with CATEGORY_DEFAULT");
            } else if (dest.size() > 1) {
                Slog.v(TAG, "resolveIntent: multiple matches, only some with CATEGORY_DEFAULT");
            }
        }
    }
}
