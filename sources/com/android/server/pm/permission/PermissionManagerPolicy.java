package com.android.server.pm.permission;

import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.os.Environment;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import com.android.server.LocalServices;
import com.android.server.pm.Settings;
import com.xiaopeng.app.xpPackageInfo;
import com.xiaopeng.server.app.xpPackageManagerService;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
/* loaded from: classes.dex */
public class PermissionManagerPolicy {
    private static final boolean DEBUG = true;
    private static final String TAG = "PermissionManagerPolicy";
    private final Context mContext;
    private final Handler mHandler;
    private ArrayList<String> mPermissionList = new ArrayList<>();
    private HashMap<String, String> mPermissionGroupList = new HashMap<>();
    private HashMap<String, String> mPmGroupIndexList = new HashMap<>();
    private final PackageManagerInternal mPackageManagerInt = (PackageManagerInternal) LocalServices.getService(PackageManagerInternal.class);

    /* JADX INFO: Access modifiers changed from: package-private */
    public PermissionManagerPolicy(Context context, Handler handler) {
        this.mContext = context;
        this.mHandler = handler;
        initPermissionPolicy(this.mContext, this.mHandler);
    }

    public void initPermissionPolicy(final Context context, Handler handler) {
        if (context != null && handler != null) {
            handler.post(new Runnable() { // from class: com.android.server.pm.permission.PermissionManagerPolicy.1
                @Override // java.lang.Runnable
                public void run() {
                    ArrayList<String> list = PermissionManagerPolicy.loadPolicy(context);
                    if (list != null) {
                        PermissionManagerPolicy.this.mPermissionList.clear();
                        PermissionManagerPolicy.this.mPermissionList.addAll(list);
                    }
                    HashMap<String, String> listPms = PermissionManagerPolicy.loadPermissionGroup(context, true);
                    HashMap<String, String> listIndex = PermissionManagerPolicy.loadPermissionGroup(context, false);
                    if (listPms != null && listIndex != null) {
                        PermissionManagerPolicy.this.mPermissionGroupList.clear();
                        PermissionManagerPolicy.this.mPermissionGroupList = listPms;
                        PermissionManagerPolicy.this.mPmGroupIndexList.clear();
                        PermissionManagerPolicy.this.mPmGroupIndexList = listIndex;
                    }
                }
            });
        }
    }

    public int enforceGrantPermission(String packageName, String permission) {
        int bitIndex;
        if (!TextUtils.isEmpty(packageName)) {
            PackageParser.Package pkg = this.mPackageManagerInt.getPackage(packageName);
            boolean systemApp = (pkg == null || (pkg.applicationInfo.flags & 1) == 0) ? false : true;
            if (systemApp) {
                return 1;
            }
            if (this.mPermissionList != null) {
                if (this.mPermissionList.contains(packageName)) {
                    return 1;
                }
                Iterator<String> it = this.mPermissionList.iterator();
                while (it.hasNext()) {
                    String value = it.next();
                    if (!TextUtils.isEmpty(value) && value.contains("*")) {
                        String prefix = value.substring(0, value.length() - 1);
                        if (packageName.startsWith(prefix)) {
                            return 1;
                        }
                    }
                }
            }
            xpPackageInfo xpi = xpPackageManagerService.get(this.mContext).getXpPackageInfo(packageName);
            if (xpi != null && this.mPermissionGroupList.containsKey(permission) && (bitIndex = Integer.parseInt(this.mPmGroupIndexList.get(permission))) != 0) {
                if ((xpi.permissionGrant & bitIndex) == bitIndex) {
                    return 1;
                }
                return 2;
            } else if (xpi != null && (xpi.permissionGrant & 1) == 1) {
                return 1;
            }
        }
        return 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static HashMap<String, String> loadPermissionGroup(Context context, boolean isGetPmGroup) {
        HashMap<String, String> permissionList = new HashMap<>();
        File systemDir = new File(Environment.getRootDirectory(), "etc");
        File policyFile = new File(systemDir, "xp_permission_group.xml");
        FileInputStream fis = null;
        try {
            try {
                fis = new FileInputStream(policyFile);
                permissionList = parseXmlForPermissionGroup(context, fis, isGetPmGroup);
                fis.close();
            } catch (Exception e) {
            }
        } catch (Exception e2) {
            if (fis != null) {
                fis.close();
            }
        } catch (Throwable th) {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception e3) {
                }
            }
            throw th;
        }
        return permissionList;
    }

    private static HashMap<String, String> parseXmlForPermissionGroup(Context context, InputStream is, boolean isGetPmGroup) {
        XmlPullParser parser;
        HashMap<String, String> listPermissions = new HashMap<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            parser = factory.newPullParser();
        } catch (Exception e) {
            e = e;
        }
        try {
            parser.setInput(is, "utf-8");
            for (int eventType = parser.getEventType(); eventType != 1; eventType = parser.next()) {
                String nodeName = parser.getName();
                Log.i(TAG, "parsePermissionGroupXml nodeName=" + nodeName);
                if (!TextUtils.isEmpty(nodeName)) {
                    switch (eventType) {
                        case 2:
                            Log.i(TAG, "parsePermissionGroupXml start tag");
                            if (Settings.TAG_ITEM.equals(nodeName.toLowerCase())) {
                                String permissionName = parser.getAttributeValue(null, Settings.ATTR_NAME);
                                String permissionGroup = parser.getAttributeValue(null, "group");
                                String mIndex = parser.getAttributeValue(null, "bitIndex");
                                Log.i(TAG, "parsePermissionGroupXml start permissionName=" + permissionName + " permissionGroup=" + permissionGroup);
                                if (isGetPmGroup) {
                                    if (!TextUtils.isEmpty(permissionName) && !TextUtils.isEmpty(permissionGroup)) {
                                        listPermissions.put(permissionName, permissionGroup);
                                        break;
                                    }
                                } else if (!TextUtils.isEmpty(permissionName) && !TextUtils.isEmpty(mIndex)) {
                                    listPermissions.put(permissionName, mIndex);
                                    break;
                                }
                            }
                            break;
                        case 3:
                            Log.i(TAG, "parsePermissionGroupXml end tag");
                            break;
                    }
                }
            }
        } catch (Exception e2) {
            e = e2;
            Log.i(TAG, "parsePermissionGroupXml e=" + e);
            return listPermissions;
        }
        return listPermissions;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static ArrayList<String> loadPolicy(Context context) {
        ArrayList<String> list = new ArrayList<>();
        File systemDir = new File(Environment.getRootDirectory(), "etc");
        File policyFile = new File(systemDir, "xp_permission_policy.xml");
        FileInputStream fis = null;
        try {
            try {
                fis = new FileInputStream(policyFile);
                ArrayList<String> l = parseXml(context, fis);
                if (l != null) {
                    list.addAll(l);
                }
                fis.close();
            } catch (Exception e) {
            }
        } catch (Exception e2) {
            if (fis != null) {
                fis.close();
            }
        } catch (Throwable th) {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception e3) {
                }
            }
            throw th;
        }
        return list;
    }

    private static ArrayList<String> parseXml(Context context, InputStream is) {
        ArrayList<String> list = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(is, "utf-8");
            for (int eventType = parser.getEventType(); eventType != 1; eventType = parser.next()) {
                String nodeName = parser.getName();
                Log.i(TAG, "parsePermissionXml nodeName=" + nodeName);
                if (!TextUtils.isEmpty(nodeName)) {
                    switch (eventType) {
                        case 2:
                            Log.i(TAG, "parsePermissionXml start tag");
                            if (Settings.TAG_ITEM.equals(nodeName.toLowerCase())) {
                                String packageName = parser.getAttributeValue(null, Settings.ATTR_NAME);
                                Log.i(TAG, "parsePermissionXml start packageName=" + packageName);
                                if (!TextUtils.isEmpty(packageName)) {
                                    list.add(packageName);
                                    break;
                                }
                            }
                            break;
                        case 3:
                            Log.i(TAG, "parsePermissionXml end tag");
                            break;
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "parsePermissionXml e=" + e);
        }
        return list;
    }
}
