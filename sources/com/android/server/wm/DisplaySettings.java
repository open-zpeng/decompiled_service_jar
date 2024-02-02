package com.android.server.wm;

import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public class DisplaySettings {
    private static final String TAG = "WindowManager";
    private final HashMap<String, Entry> mEntries = new HashMap<>();
    private final AtomicFile mFile;

    /* loaded from: classes.dex */
    public static class Entry {
        public final String name;
        public int overscanBottom;
        public int overscanLeft;
        public int overscanRight;
        public int overscanTop;

        public Entry(String _name) {
            this.name = _name;
        }
    }

    public DisplaySettings() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        this.mFile = new AtomicFile(new File(systemDir, "display_settings.xml"), "wm-displays");
    }

    /* JADX WARN: Code restructure failed: missing block: B:4:0x000b, code lost:
        if (r0 == null) goto L11;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void getOverscanLocked(java.lang.String r3, java.lang.String r4, android.graphics.Rect r5) {
        /*
            r2 = this;
            if (r4 == 0) goto Ld
            java.util.HashMap<java.lang.String, com.android.server.wm.DisplaySettings$Entry> r0 = r2.mEntries
            java.lang.Object r0 = r0.get(r4)
            com.android.server.wm.DisplaySettings$Entry r0 = (com.android.server.wm.DisplaySettings.Entry) r0
            r1 = r0
            if (r0 != 0) goto L16
        Ld:
            java.util.HashMap<java.lang.String, com.android.server.wm.DisplaySettings$Entry> r0 = r2.mEntries
            java.lang.Object r0 = r0.get(r3)
            r1 = r0
            com.android.server.wm.DisplaySettings$Entry r1 = (com.android.server.wm.DisplaySettings.Entry) r1
        L16:
            r0 = r1
            if (r0 == 0) goto L2a
            int r1 = r0.overscanLeft
            r5.left = r1
            int r1 = r0.overscanTop
            r5.top = r1
            int r1 = r0.overscanRight
            r5.right = r1
            int r1 = r0.overscanBottom
            r5.bottom = r1
            goto L2e
        L2a:
            r1 = 0
            r5.set(r1, r1, r1, r1)
        L2e:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.DisplaySettings.getOverscanLocked(java.lang.String, java.lang.String, android.graphics.Rect):void");
    }

    public void setOverscanLocked(String uniqueId, String name, int left, int top, int right, int bottom) {
        if (left == 0 && top == 0 && right == 0 && bottom == 0) {
            this.mEntries.remove(uniqueId);
            this.mEntries.remove(name);
            return;
        }
        Entry entry = this.mEntries.get(uniqueId);
        if (entry == null) {
            entry = new Entry(uniqueId);
            this.mEntries.put(uniqueId, entry);
        }
        entry.overscanLeft = left;
        entry.overscanTop = top;
        entry.overscanRight = right;
        entry.overscanBottom = bottom;
    }

    public void readSettingsLocked() {
        int type;
        try {
            FileInputStream stream = this.mFile.openRead();
            try {
                try {
                    try {
                        try {
                            try {
                                try {
                                    try {
                                        try {
                                            XmlPullParser parser = Xml.newPullParser();
                                            parser.setInput(stream, StandardCharsets.UTF_8.name());
                                            while (true) {
                                                type = parser.next();
                                                if (type == 2 || type == 1) {
                                                    break;
                                                }
                                            }
                                            if (type != 2) {
                                                throw new IllegalStateException("no start tag found");
                                            }
                                            int outerDepth = parser.getDepth();
                                            while (true) {
                                                int type2 = parser.next();
                                                if (type2 == 1 || (type2 == 3 && parser.getDepth() <= outerDepth)) {
                                                    break;
                                                } else if (type2 != 3 && type2 != 4) {
                                                    String tagName = parser.getName();
                                                    if (tagName.equals("display")) {
                                                        readDisplay(parser);
                                                    } else {
                                                        Slog.w(TAG, "Unknown element under <display-settings>: " + parser.getName());
                                                        XmlUtils.skipCurrentTag(parser);
                                                    }
                                                }
                                            }
                                            if (1 == 0) {
                                                this.mEntries.clear();
                                            }
                                            stream.close();
                                        } catch (IOException e) {
                                            Slog.w(TAG, "Failed parsing " + e);
                                            if (0 == 0) {
                                                this.mEntries.clear();
                                            }
                                            stream.close();
                                        }
                                    } catch (XmlPullParserException e2) {
                                        Slog.w(TAG, "Failed parsing " + e2);
                                        if (0 == 0) {
                                            this.mEntries.clear();
                                        }
                                        stream.close();
                                    }
                                } catch (NullPointerException e3) {
                                    Slog.w(TAG, "Failed parsing " + e3);
                                    if (0 == 0) {
                                        this.mEntries.clear();
                                    }
                                    stream.close();
                                }
                            } catch (IllegalStateException e4) {
                                Slog.w(TAG, "Failed parsing " + e4);
                                if (0 == 0) {
                                    this.mEntries.clear();
                                }
                                stream.close();
                            }
                        } catch (IndexOutOfBoundsException e5) {
                            Slog.w(TAG, "Failed parsing " + e5);
                            if (0 == 0) {
                                this.mEntries.clear();
                            }
                            stream.close();
                        }
                    } catch (IOException e6) {
                    }
                } catch (NumberFormatException e7) {
                    Slog.w(TAG, "Failed parsing " + e7);
                    if (0 == 0) {
                        this.mEntries.clear();
                    }
                    stream.close();
                }
            } catch (Throwable th) {
                if (0 == 0) {
                    this.mEntries.clear();
                }
                try {
                    stream.close();
                } catch (IOException e8) {
                }
                throw th;
            }
        } catch (FileNotFoundException e9) {
            Slog.i(TAG, "No existing display settings " + this.mFile.getBaseFile() + "; starting empty");
        }
    }

    private int getIntAttribute(XmlPullParser parser, String name) {
        try {
            String str = parser.getAttributeValue(null, name);
            if (str != null) {
                return Integer.parseInt(str);
            }
            return 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void readDisplay(XmlPullParser parser) throws NumberFormatException, XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, Settings.ATTR_NAME);
        if (name != null) {
            Entry entry = new Entry(name);
            entry.overscanLeft = getIntAttribute(parser, "overscanLeft");
            entry.overscanTop = getIntAttribute(parser, "overscanTop");
            entry.overscanRight = getIntAttribute(parser, "overscanRight");
            entry.overscanBottom = getIntAttribute(parser, "overscanBottom");
            this.mEntries.put(name, entry);
        }
        XmlUtils.skipCurrentTag(parser);
    }

    public void writeSettingsLocked() {
        try {
            FileOutputStream stream = this.mFile.startWrite();
            try {
                FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
                fastXmlSerializer.setOutput(stream, StandardCharsets.UTF_8.name());
                fastXmlSerializer.startDocument(null, true);
                fastXmlSerializer.startTag(null, "display-settings");
                for (Entry entry : this.mEntries.values()) {
                    fastXmlSerializer.startTag(null, "display");
                    fastXmlSerializer.attribute(null, Settings.ATTR_NAME, entry.name);
                    if (entry.overscanLeft != 0) {
                        fastXmlSerializer.attribute(null, "overscanLeft", Integer.toString(entry.overscanLeft));
                    }
                    if (entry.overscanTop != 0) {
                        fastXmlSerializer.attribute(null, "overscanTop", Integer.toString(entry.overscanTop));
                    }
                    if (entry.overscanRight != 0) {
                        fastXmlSerializer.attribute(null, "overscanRight", Integer.toString(entry.overscanRight));
                    }
                    if (entry.overscanBottom != 0) {
                        fastXmlSerializer.attribute(null, "overscanBottom", Integer.toString(entry.overscanBottom));
                    }
                    fastXmlSerializer.endTag(null, "display");
                }
                fastXmlSerializer.endTag(null, "display-settings");
                fastXmlSerializer.endDocument();
                this.mFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write display settings, restoring backup.", e);
                this.mFile.failWrite(stream);
            }
        } catch (IOException e2) {
            Slog.w(TAG, "Failed to write display settings: " + e2);
        }
    }
}
