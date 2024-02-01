package com.xiaopeng.xpaudiopolicy;

import android.util.Log;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.pm.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
/* loaded from: classes.dex */
class AmpAreaConfigParser {
    static final String TAG = "AmpAreaConfigParser";
    static String areaName;
    static int channelBits;

    AmpAreaConfigParser() {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static Map<String, Integer> parseConfigXml(String xmlPath) {
        File configFile = new File(xmlPath);
        if (configFile.exists()) {
            Map<String, Integer> map = new HashMap<>();
            FileInputStream fis = null;
            try {
                try {
                    try {
                        try {
                            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                            XmlPullParser xpp = factory.newPullParser();
                            fis = new FileInputStream(configFile);
                            xpp.setInput(fis, "utf-8");
                            String tag = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                            for (int eventType = xpp.getEventType(); eventType != 1; eventType = xpp.next()) {
                                if (eventType == 2) {
                                    tag = xpp.getName();
                                    if (tag.equals("area")) {
                                        areaName = xpp.getAttributeValue(null, Settings.ATTR_NAME);
                                        channelBits = 0;
                                    } else {
                                        tag.equals("channel");
                                    }
                                } else if (eventType == 4 && tag.equals("channel")) {
                                    int id = Integer.parseInt(xpp.getText());
                                    channelBits = (1 << (id - 1)) | channelBits;
                                } else if (eventType == 3) {
                                    tag = xpp.getName();
                                    if (tag.equals("area")) {
                                        map.put(areaName, Integer.valueOf(channelBits));
                                    } else if (tag.equals("channel")) {
                                        tag = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
                                    }
                                }
                            }
                            fis.close();
                        } catch (Throwable th) {
                            try {
                                fis.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            throw th;
                        }
                    } catch (XmlPullParserException e2) {
                        e2.printStackTrace();
                        fis.close();
                    }
                } catch (FileNotFoundException e3) {
                    e3.printStackTrace();
                    fis.close();
                } catch (IOException e4) {
                    e4.printStackTrace();
                    fis.close();
                }
            } catch (IOException e5) {
                e5.printStackTrace();
            }
            Log.d(TAG, "result=" + map);
            return map;
        }
        return null;
    }
}
