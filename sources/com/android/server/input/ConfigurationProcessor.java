package com.android.server.input;

import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.Settings;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;

/* loaded from: classes.dex */
class ConfigurationProcessor {
    private static final String TAG = "ConfigurationProcessor";

    ConfigurationProcessor() {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static List<String> processExcludedDeviceNames(InputStream xml) throws Exception {
        List<String> names = new ArrayList<>();
        InputStreamReader confReader = new InputStreamReader(xml);
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(confReader);
            XmlUtils.beginDocument(parser, "devices");
            while (true) {
                XmlUtils.nextElement(parser);
                if ("device".equals(parser.getName())) {
                    String name = parser.getAttributeValue(null, Settings.ATTR_NAME);
                    if (name != null) {
                        names.add(name);
                    }
                } else {
                    $closeResource(null, confReader);
                    return names;
                }
            }
        } finally {
        }
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    public static List<Pair<String, String>> processInputPortAssociations(InputStream xml) throws Exception {
        List<Pair<String, String>> associations = new ArrayList<>();
        InputStreamReader confReader = new InputStreamReader(xml);
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(confReader);
            XmlUtils.beginDocument(parser, "ports");
            while (true) {
                XmlUtils.nextElement(parser);
                String entryName = parser.getName();
                if ("port".equals(entryName)) {
                    String inputPort = parser.getAttributeValue(null, "input");
                    String displayPort = parser.getAttributeValue(null, "display");
                    if (TextUtils.isEmpty(inputPort) || TextUtils.isEmpty(displayPort)) {
                        Slog.wtf(TAG, "Ignoring incomplete entry");
                    } else {
                        try {
                            Integer.parseUnsignedInt(displayPort);
                            associations.add(new Pair<>(inputPort, displayPort));
                        } catch (NumberFormatException e) {
                            Slog.wtf(TAG, "Display port should be an integer");
                        }
                    }
                } else {
                    $closeResource(null, confReader);
                    return associations;
                }
            }
        } finally {
        }
    }
}
