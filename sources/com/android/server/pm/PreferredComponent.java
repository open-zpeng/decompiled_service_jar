package com.android.server.pm;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.util.Slog;
import com.android.internal.util.XmlUtils;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
public class PreferredComponent {
    private static final String ATTR_ALWAYS = "always";
    private static final String ATTR_MATCH = "match";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_SET = "set";
    private static final String TAG_SET = "set";
    public boolean mAlways;
    private final Callbacks mCallbacks;
    public final ComponentName mComponent;
    public final int mMatch;
    private String mParseError;
    final String[] mSetClasses;
    final String[] mSetComponents;
    final String[] mSetPackages;
    final String mShortComponent;

    /* loaded from: classes.dex */
    public interface Callbacks {
        boolean onReadTag(String str, XmlPullParser xmlPullParser) throws XmlPullParserException, IOException;
    }

    public PreferredComponent(Callbacks callbacks, int match, ComponentName[] set, ComponentName component, boolean always) {
        this.mCallbacks = callbacks;
        this.mMatch = 268369920 & match;
        this.mComponent = component;
        this.mAlways = always;
        this.mShortComponent = component.flattenToShortString();
        this.mParseError = null;
        if (set != null) {
            int N = set.length;
            String[] myPackages = new String[N];
            String[] myClasses = new String[N];
            String[] myComponents = new String[N];
            for (int i = 0; i < N; i++) {
                ComponentName cn = set[i];
                if (cn == null) {
                    this.mSetPackages = null;
                    this.mSetClasses = null;
                    this.mSetComponents = null;
                    return;
                }
                myPackages[i] = cn.getPackageName().intern();
                myClasses[i] = cn.getClassName().intern();
                myComponents[i] = cn.flattenToShortString();
            }
            this.mSetPackages = myPackages;
            this.mSetClasses = myClasses;
            this.mSetComponents = myComponents;
            return;
        }
        this.mSetPackages = null;
        this.mSetClasses = null;
        this.mSetComponents = null;
    }

    public PreferredComponent(Callbacks callbacks, XmlPullParser parser) throws XmlPullParserException, IOException {
        String matchStr;
        this.mCallbacks = callbacks;
        String str = null;
        this.mShortComponent = parser.getAttributeValue(null, "name");
        this.mComponent = ComponentName.unflattenFromString(this.mShortComponent);
        if (this.mComponent == null) {
            this.mParseError = "Bad activity name " + this.mShortComponent;
        }
        String matchStr2 = parser.getAttributeValue(null, ATTR_MATCH);
        this.mMatch = matchStr2 != null ? Integer.parseInt(matchStr2, 16) : 0;
        String setCountStr = parser.getAttributeValue(null, "set");
        int setCount = setCountStr != null ? Integer.parseInt(setCountStr) : 0;
        String alwaysStr = parser.getAttributeValue(null, ATTR_ALWAYS);
        int i = 1;
        this.mAlways = alwaysStr != null ? Boolean.parseBoolean(alwaysStr) : true;
        String[] myPackages = setCount > 0 ? new String[setCount] : null;
        String[] myClasses = setCount > 0 ? new String[setCount] : null;
        String[] myComponents = setCount > 0 ? new String[setCount] : null;
        int setPos = 0;
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != i && (type != 3 || parser.getDepth() > outerDepth)) {
                if (type == 3) {
                    matchStr = matchStr2;
                } else if (type == 4) {
                    matchStr = matchStr2;
                } else {
                    String tagName = parser.getName();
                    if (tagName.equals("set")) {
                        String name = parser.getAttributeValue(str, "name");
                        if (name == null) {
                            if (this.mParseError == null) {
                                this.mParseError = "No name in set tag in preferred activity " + this.mShortComponent;
                            }
                        } else if (setPos < setCount) {
                            ComponentName cn = ComponentName.unflattenFromString(name);
                            if (cn != null) {
                                matchStr = matchStr2;
                                myPackages[setPos] = cn.getPackageName();
                                myClasses[setPos] = cn.getClassName();
                                myComponents[setPos] = name;
                                setPos++;
                            } else if (this.mParseError == null) {
                                StringBuilder sb = new StringBuilder();
                                matchStr = matchStr2;
                                sb.append("Bad set name ");
                                sb.append(name);
                                sb.append(" in preferred activity ");
                                sb.append(this.mShortComponent);
                                this.mParseError = sb.toString();
                            } else {
                                matchStr = matchStr2;
                            }
                            XmlUtils.skipCurrentTag(parser);
                        } else if (this.mParseError == null) {
                            this.mParseError = "Too many set tags in preferred activity " + this.mShortComponent;
                        }
                        matchStr = matchStr2;
                        XmlUtils.skipCurrentTag(parser);
                    } else {
                        matchStr = matchStr2;
                        if (!this.mCallbacks.onReadTag(tagName, parser)) {
                            Slog.w("PreferredComponent", "Unknown element: " + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                }
                matchStr2 = matchStr;
                str = null;
                i = 1;
            }
        }
        if (setPos != setCount && this.mParseError == null) {
            this.mParseError = "Not enough set tags (expected " + setCount + " but found " + setPos + ") in " + this.mShortComponent;
        }
        this.mSetPackages = myPackages;
        this.mSetClasses = myClasses;
        this.mSetComponents = myComponents;
    }

    public String getParseError() {
        return this.mParseError;
    }

    public void writeToXml(XmlSerializer serializer, boolean full) throws IOException {
        int NS = this.mSetClasses != null ? this.mSetClasses.length : 0;
        serializer.attribute(null, "name", this.mShortComponent);
        if (full) {
            if (this.mMatch != 0) {
                serializer.attribute(null, ATTR_MATCH, Integer.toHexString(this.mMatch));
            }
            serializer.attribute(null, ATTR_ALWAYS, Boolean.toString(this.mAlways));
            serializer.attribute(null, "set", Integer.toString(NS));
            for (int s = 0; s < NS; s++) {
                serializer.startTag(null, "set");
                serializer.attribute(null, "name", this.mSetComponents[s]);
                serializer.endTag(null, "set");
            }
        }
    }

    public boolean sameSet(List<ResolveInfo> query) {
        if (this.mSetPackages == null) {
            return query == null;
        } else if (query == null) {
            return false;
        } else {
            int NQ = query.size();
            int NS = this.mSetPackages.length;
            int numMatch = 0;
            for (int numMatch2 = 0; numMatch2 < NQ; numMatch2++) {
                ResolveInfo ri = query.get(numMatch2);
                ActivityInfo ai = ri.activityInfo;
                boolean good = false;
                int j = 0;
                while (true) {
                    if (j >= NS) {
                        break;
                    } else if (!this.mSetPackages[j].equals(ai.packageName) || !this.mSetClasses[j].equals(ai.name)) {
                        j++;
                    } else {
                        numMatch++;
                        good = true;
                        break;
                    }
                }
                if (!good) {
                    return false;
                }
            }
            return numMatch == NS;
        }
    }

    public boolean sameSet(ComponentName[] comps) {
        if (this.mSetPackages == null) {
            return false;
        }
        int NS = this.mSetPackages.length;
        int numMatch = 0;
        for (ComponentName cn : comps) {
            boolean good = false;
            int j = 0;
            while (true) {
                if (j >= NS) {
                    break;
                } else if (!this.mSetPackages[j].equals(cn.getPackageName()) || !this.mSetClasses[j].equals(cn.getClassName())) {
                    j++;
                } else {
                    numMatch++;
                    good = true;
                    break;
                }
            }
            if (!good) {
                return false;
            }
        }
        return numMatch == NS;
    }

    public boolean isSuperset(List<ResolveInfo> query) {
        if (this.mSetPackages == null) {
            return query == null;
        } else if (query == null) {
            return true;
        } else {
            int NQ = query.size();
            int NS = this.mSetPackages.length;
            if (NS < NQ) {
                return false;
            }
            for (int i = 0; i < NQ; i++) {
                ResolveInfo ri = query.get(i);
                ActivityInfo ai = ri.activityInfo;
                boolean foundMatch = false;
                int j = 0;
                while (true) {
                    if (j >= NS) {
                        break;
                    } else if (!this.mSetPackages[j].equals(ai.packageName) || !this.mSetClasses[j].equals(ai.name)) {
                        j++;
                    } else {
                        foundMatch = true;
                        break;
                    }
                }
                if (!foundMatch) {
                    return false;
                }
            }
            return true;
        }
    }

    public ComponentName[] discardObsoleteComponents(List<ResolveInfo> query) {
        if (this.mSetPackages == null || query == null) {
            return new ComponentName[0];
        }
        int NQ = query.size();
        int NS = this.mSetPackages.length;
        ArrayList<ComponentName> aliveComponents = new ArrayList<>();
        for (int i = 0; i < NQ; i++) {
            ResolveInfo ri = query.get(i);
            ActivityInfo ai = ri.activityInfo;
            int j = 0;
            while (true) {
                if (j >= NS) {
                    break;
                } else if (!this.mSetPackages[j].equals(ai.packageName) || !this.mSetClasses[j].equals(ai.name)) {
                    j++;
                } else {
                    aliveComponents.add(new ComponentName(this.mSetPackages[j], this.mSetClasses[j]));
                    break;
                }
            }
        }
        return (ComponentName[]) aliveComponents.toArray(new ComponentName[aliveComponents.size()]);
    }

    public void dump(PrintWriter out, String prefix, Object ident) {
        out.print(prefix);
        out.print(Integer.toHexString(System.identityHashCode(ident)));
        out.print(' ');
        out.println(this.mShortComponent);
        out.print(prefix);
        out.print(" mMatch=0x");
        out.print(Integer.toHexString(this.mMatch));
        out.print(" mAlways=");
        out.println(this.mAlways);
        if (this.mSetComponents != null) {
            out.print(prefix);
            out.println("  Selected from:");
            for (int i = 0; i < this.mSetComponents.length; i++) {
                out.print(prefix);
                out.print("    ");
                out.println(this.mSetComponents[i]);
            }
        }
    }
}
