package com.android.server.firewall;

import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;
import com.android.server.EventLogTags;
import com.android.server.IntentResolver;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public class IntentFirewall {
    private static final int LOG_PACKAGES_MAX_LENGTH = 150;
    private static final int LOG_PACKAGES_SUFFICIENT_LENGTH = 125;
    private static final File RULES_DIR = new File(Environment.getDataSystemDirectory(), "ifw");
    static final String TAG = "IntentFirewall";
    private static final String TAG_ACTIVITY = "activity";
    private static final String TAG_BROADCAST = "broadcast";
    private static final String TAG_RULES = "rules";
    private static final String TAG_SERVICE = "service";
    private static final int TYPE_ACTIVITY = 0;
    private static final int TYPE_BROADCAST = 1;
    private static final int TYPE_SERVICE = 2;
    private static final HashMap<String, FilterFactory> factoryMap;
    private final AMSInterface mAms;
    final FirewallHandler mHandler;
    private final RuleObserver mObserver;
    private FirewallIntentResolver mActivityResolver = new FirewallIntentResolver();
    private FirewallIntentResolver mBroadcastResolver = new FirewallIntentResolver();
    private FirewallIntentResolver mServiceResolver = new FirewallIntentResolver();

    /* loaded from: classes.dex */
    public interface AMSInterface {
        int checkComponentPermission(String str, int i, int i2, int i3, boolean z);

        Object getAMSLock();
    }

    static {
        int i = 0;
        FilterFactory[] factories = {AndFilter.FACTORY, OrFilter.FACTORY, NotFilter.FACTORY, StringFilter.ACTION, StringFilter.COMPONENT, StringFilter.COMPONENT_NAME, StringFilter.COMPONENT_PACKAGE, StringFilter.DATA, StringFilter.HOST, StringFilter.MIME_TYPE, StringFilter.SCHEME, StringFilter.PATH, StringFilter.SSP, CategoryFilter.FACTORY, SenderFilter.FACTORY, SenderPackageFilter.FACTORY, SenderPermissionFilter.FACTORY, PortFilter.FACTORY};
        factoryMap = new HashMap<>((factories.length * 4) / 3);
        while (true) {
            int i2 = i;
            int i3 = factories.length;
            if (i2 < i3) {
                FilterFactory factory = factories[i2];
                factoryMap.put(factory.getTagName(), factory);
                i = i2 + 1;
            } else {
                return;
            }
        }
    }

    public IntentFirewall(AMSInterface ams, Handler handler) {
        this.mAms = ams;
        this.mHandler = new FirewallHandler(handler.getLooper());
        File rulesDir = getRulesDir();
        rulesDir.mkdirs();
        readRulesDir(rulesDir);
        this.mObserver = new RuleObserver(rulesDir);
        this.mObserver.startWatching();
    }

    public boolean checkStartActivity(Intent intent, int callerUid, int callerPid, String resolvedType, ApplicationInfo resolvedApp) {
        return checkIntent(this.mActivityResolver, intent.getComponent(), 0, intent, callerUid, callerPid, resolvedType, resolvedApp.uid);
    }

    public boolean checkService(ComponentName resolvedService, Intent intent, int callerUid, int callerPid, String resolvedType, ApplicationInfo resolvedApp) {
        return checkIntent(this.mServiceResolver, resolvedService, 2, intent, callerUid, callerPid, resolvedType, resolvedApp.uid);
    }

    public boolean checkBroadcast(Intent intent, int callerUid, int callerPid, String resolvedType, int receivingUid) {
        return checkIntent(this.mBroadcastResolver, intent.getComponent(), 1, intent, callerUid, callerPid, resolvedType, receivingUid);
    }

    public boolean checkIntent(FirewallIntentResolver resolver, ComponentName resolvedComponent, int intentType, Intent intent, int callerUid, int callerPid, String resolvedType, int receivingUid) {
        List<Rule> candidateRules = resolver.queryIntent(intent, resolvedType, false, 0);
        if (candidateRules == null) {
            candidateRules = new ArrayList();
        }
        List<Rule> candidateRules2 = candidateRules;
        resolver.queryByComponent(resolvedComponent, candidateRules2);
        boolean log = false;
        boolean block = false;
        int i = 0;
        while (true) {
            int i2 = i;
            int i3 = candidateRules2.size();
            if (i2 >= i3) {
                break;
            }
            Rule rule = candidateRules2.get(i2);
            if (rule.matches(this, resolvedComponent, intent, callerUid, callerPid, resolvedType, receivingUid)) {
                block |= rule.getBlock();
                log |= rule.getLog();
                if (block && log) {
                    break;
                }
            }
            i = i2 + 1;
        }
        if (log) {
            logIntent(intentType, intent, callerUid, resolvedType);
        }
        return !block;
    }

    private static void logIntent(int intentType, Intent intent, int callerUid, String resolvedType) {
        int i;
        ComponentName cn = intent.getComponent();
        String shortComponent = null;
        if (cn != null) {
            shortComponent = cn.flattenToShortString();
        }
        String shortComponent2 = shortComponent;
        String callerPackages = null;
        int callerPackageCount = 0;
        IPackageManager pm = AppGlobals.getPackageManager();
        if (pm != null) {
            i = callerUid;
            try {
                String[] callerPackagesArray = pm.getPackagesForUid(i);
                if (callerPackagesArray != null) {
                    callerPackageCount = callerPackagesArray.length;
                    callerPackages = joinPackages(callerPackagesArray);
                }
            } catch (RemoteException ex) {
                Slog.e(TAG, "Remote exception while retrieving packages", ex);
            }
        } else {
            i = callerUid;
        }
        int i2 = i;
        EventLogTags.writeIfwIntentMatched(intentType, shortComponent2, i2, callerPackageCount, callerPackages, intent.getAction(), resolvedType, intent.getDataString(), intent.getFlags());
    }

    private static String joinPackages(String[] packages) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String pkg : packages) {
            if (sb.length() + pkg.length() + 1 < 150) {
                if (!first) {
                    sb.append(',');
                } else {
                    first = false;
                }
                sb.append(pkg);
            } else if (sb.length() >= LOG_PACKAGES_SUFFICIENT_LENGTH) {
                return sb.toString();
            }
        }
        int i = sb.length();
        if (i == 0 && packages.length > 0) {
            String pkg2 = packages[0];
            return pkg2.substring((pkg2.length() - 150) + 1) + '-';
        }
        return null;
    }

    public static File getRulesDir() {
        return RULES_DIR;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void readRulesDir(File rulesDir) {
        FirewallIntentResolver[] resolvers = new FirewallIntentResolver[3];
        for (int i = 0; i < resolvers.length; i++) {
            resolvers[i] = new FirewallIntentResolver();
        }
        File[] files = rulesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".xml")) {
                    readRules(file, resolvers);
                }
            }
        }
        Slog.i(TAG, "Read new rules (A:" + resolvers[0].filterSet().size() + " B:" + resolvers[1].filterSet().size() + " S:" + resolvers[2].filterSet().size() + ")");
        synchronized (this.mAms.getAMSLock()) {
            this.mActivityResolver = resolvers[0];
            this.mBroadcastResolver = resolvers[1];
            this.mServiceResolver = resolvers[2];
        }
    }

    private void readRules(File rulesFile, FirewallIntentResolver[] resolvers) {
        List<List<Rule>> rulesByType = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            rulesByType.add(new ArrayList<>());
        }
        try {
            FileInputStream fis = new FileInputStream(rulesFile);
            try {
                try {
                    try {
                        XmlPullParser parser = Xml.newPullParser();
                        parser.setInput(fis, null);
                        XmlUtils.beginDocument(parser, TAG_RULES);
                        int outerDepth = parser.getDepth();
                        while (true) {
                            int outerDepth2 = outerDepth;
                            if (XmlUtils.nextElementWithin(parser, outerDepth2)) {
                                int ruleType = -1;
                                String tagName = parser.getName();
                                if (tagName.equals(TAG_ACTIVITY)) {
                                    ruleType = 0;
                                } else if (tagName.equals(TAG_BROADCAST)) {
                                    ruleType = 1;
                                } else if (tagName.equals(TAG_SERVICE)) {
                                    ruleType = 2;
                                }
                                int ruleType2 = ruleType;
                                if (ruleType2 != -1) {
                                    Rule rule = new Rule();
                                    List<Rule> rules = rulesByType.get(ruleType2);
                                    try {
                                        rule.readFromXml(parser);
                                        rules.add(rule);
                                    } catch (XmlPullParserException ex) {
                                        Slog.e(TAG, "Error reading an intent firewall rule from " + rulesFile, ex);
                                    }
                                }
                                outerDepth = outerDepth2;
                            } else {
                                try {
                                    break;
                                } catch (IOException ex2) {
                                    Slog.e(TAG, "Error while closing " + rulesFile, ex2);
                                }
                            }
                        }
                        fis.close();
                        for (int ruleType3 = 0; ruleType3 < rulesByType.size(); ruleType3++) {
                            List<Rule> rules2 = rulesByType.get(ruleType3);
                            FirewallIntentResolver resolver = resolvers[ruleType3];
                            for (int ruleIndex = 0; ruleIndex < rules2.size(); ruleIndex++) {
                                Rule rule2 = rules2.get(ruleIndex);
                                for (int i2 = 0; i2 < rule2.getIntentFilterCount(); i2++) {
                                    resolver.addFilter(rule2.getIntentFilter(i2));
                                }
                                for (int i3 = 0; i3 < rule2.getComponentFilterCount(); i3++) {
                                    resolver.addComponentFilter(rule2.getComponentFilter(i3), rule2);
                                }
                            }
                        }
                    } catch (IOException ex3) {
                        Slog.e(TAG, "Error reading intent firewall rules from " + rulesFile, ex3);
                        try {
                            fis.close();
                        } catch (IOException ex4) {
                            Slog.e(TAG, "Error while closing " + rulesFile, ex4);
                        }
                    }
                } catch (XmlPullParserException ex5) {
                    Slog.e(TAG, "Error reading intent firewall rules from " + rulesFile, ex5);
                    try {
                        fis.close();
                    } catch (IOException ex6) {
                        Slog.e(TAG, "Error while closing " + rulesFile, ex6);
                    }
                }
            } catch (Throwable ex7) {
                try {
                    fis.close();
                } catch (IOException ex8) {
                    Slog.e(TAG, "Error while closing " + rulesFile, ex8);
                }
                throw ex7;
            }
        } catch (FileNotFoundException e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static Filter parseFilter(XmlPullParser parser) throws IOException, XmlPullParserException {
        String elementName = parser.getName();
        FilterFactory factory = factoryMap.get(elementName);
        if (factory == null) {
            throw new XmlPullParserException("Unknown element in filter list: " + elementName);
        }
        return factory.newFilter(parser);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class Rule extends AndFilter {
        private static final String ATTR_BLOCK = "block";
        private static final String ATTR_LOG = "log";
        private static final String ATTR_NAME = "name";
        private static final String TAG_COMPONENT_FILTER = "component-filter";
        private static final String TAG_INTENT_FILTER = "intent-filter";
        private boolean block;
        private boolean log;
        private final ArrayList<ComponentName> mComponentFilters;
        private final ArrayList<FirewallIntentFilter> mIntentFilters;

        private Rule() {
            this.mIntentFilters = new ArrayList<>(1);
            this.mComponentFilters = new ArrayList<>(0);
        }

        @Override // com.android.server.firewall.FilterList
        public Rule readFromXml(XmlPullParser parser) throws IOException, XmlPullParserException {
            this.block = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_BLOCK));
            this.log = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_LOG));
            super.readFromXml(parser);
            return this;
        }

        @Override // com.android.server.firewall.FilterList
        protected void readChild(XmlPullParser parser) throws IOException, XmlPullParserException {
            String currentTag = parser.getName();
            if (currentTag.equals(TAG_INTENT_FILTER)) {
                FirewallIntentFilter intentFilter = new FirewallIntentFilter(this);
                intentFilter.readFromXml(parser);
                this.mIntentFilters.add(intentFilter);
            } else if (currentTag.equals(TAG_COMPONENT_FILTER)) {
                String componentStr = parser.getAttributeValue(null, "name");
                if (componentStr == null) {
                    throw new XmlPullParserException("Component name must be specified.", parser, null);
                }
                ComponentName componentName = ComponentName.unflattenFromString(componentStr);
                if (componentName == null) {
                    throw new XmlPullParserException("Invalid component name: " + componentStr);
                }
                this.mComponentFilters.add(componentName);
            } else {
                super.readChild(parser);
            }
        }

        public int getIntentFilterCount() {
            return this.mIntentFilters.size();
        }

        public FirewallIntentFilter getIntentFilter(int index) {
            return this.mIntentFilters.get(index);
        }

        public int getComponentFilterCount() {
            return this.mComponentFilters.size();
        }

        public ComponentName getComponentFilter(int index) {
            return this.mComponentFilters.get(index);
        }

        public boolean getBlock() {
            return this.block;
        }

        public boolean getLog() {
            return this.log;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class FirewallIntentFilter extends IntentFilter {
        private final Rule rule;

        public FirewallIntentFilter(Rule rule) {
            this.rule = rule;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class FirewallIntentResolver extends IntentResolver<FirewallIntentFilter, Rule> {
        private final ArrayMap<ComponentName, Rule[]> mRulesByComponent;

        private FirewallIntentResolver() {
            this.mRulesByComponent = new ArrayMap<>(0);
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean allowFilterResult(FirewallIntentFilter filter, List<Rule> dest) {
            return !dest.contains(filter.rule);
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public boolean isPackageForFilter(String packageName, FirewallIntentFilter filter) {
            return true;
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public FirewallIntentFilter[] newArray(int size) {
            return new FirewallIntentFilter[size];
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.IntentResolver
        public Rule newResult(FirewallIntentFilter filter, int match, int userId) {
            return filter.rule;
        }

        @Override // com.android.server.IntentResolver
        protected void sortResults(List<Rule> results) {
        }

        public void queryByComponent(ComponentName componentName, List<Rule> candidateRules) {
            Rule[] rules = this.mRulesByComponent.get(componentName);
            if (rules != null) {
                candidateRules.addAll(Arrays.asList(rules));
            }
        }

        public void addComponentFilter(ComponentName componentName, Rule rule) {
            Rule[] rules = this.mRulesByComponent.get(componentName);
            this.mRulesByComponent.put(componentName, (Rule[]) ArrayUtils.appendElement(Rule.class, rules, rule));
        }
    }

    /* loaded from: classes.dex */
    private final class FirewallHandler extends Handler {
        public FirewallHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            IntentFirewall.this.readRulesDir(IntentFirewall.getRulesDir());
        }
    }

    /* loaded from: classes.dex */
    private class RuleObserver extends FileObserver {
        private static final int MONITORED_EVENTS = 968;

        public RuleObserver(File monitoredDir) {
            super(monitoredDir.getAbsolutePath(), (int) MONITORED_EVENTS);
        }

        @Override // android.os.FileObserver
        public void onEvent(int event, String path) {
            if (path.endsWith(".xml")) {
                IntentFirewall.this.mHandler.removeMessages(0);
                IntentFirewall.this.mHandler.sendEmptyMessageDelayed(0, 250L);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean checkComponentPermission(String permission, int pid, int uid, int owningUid, boolean exported) {
        return this.mAms.checkComponentPermission(permission, pid, uid, owningUid, exported) == 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean signaturesMatch(int uid1, int uid2) {
        try {
            IPackageManager pm = AppGlobals.getPackageManager();
            return pm.checkUidSignatures(uid1, uid2) == 0;
        } catch (RemoteException ex) {
            Slog.e(TAG, "Remote exception while checking signatures", ex);
            return false;
        }
    }
}
