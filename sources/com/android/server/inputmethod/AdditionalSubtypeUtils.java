package com.android.server.inputmethod;

import android.os.Environment;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.view.inputmethod.InputMethodSubtype;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/* loaded from: classes.dex */
final class AdditionalSubtypeUtils {
    private static final String ADDITIONAL_SUBTYPES_FILE_NAME = "subtypes.xml";
    private static final String ATTR_ICON = "icon";
    private static final String ATTR_ID = "id";
    private static final String ATTR_IME_SUBTYPE_EXTRA_VALUE = "imeSubtypeExtraValue";
    private static final String ATTR_IME_SUBTYPE_ID = "subtypeId";
    private static final String ATTR_IME_SUBTYPE_LANGUAGE_TAG = "languageTag";
    private static final String ATTR_IME_SUBTYPE_LOCALE = "imeSubtypeLocale";
    private static final String ATTR_IME_SUBTYPE_MODE = "imeSubtypeMode";
    private static final String ATTR_IS_ASCII_CAPABLE = "isAsciiCapable";
    private static final String ATTR_IS_AUXILIARY = "isAuxiliary";
    private static final String ATTR_LABEL = "label";
    private static final String INPUT_METHOD_PATH = "inputmethod";
    private static final String NODE_IMI = "imi";
    private static final String NODE_SUBTYPE = "subtype";
    private static final String NODE_SUBTYPES = "subtypes";
    private static final String SYSTEM_PATH = "system";
    private static final String TAG = "AdditionalSubtypeUtils";

    private AdditionalSubtypeUtils() {
    }

    private static File getInputMethodDir(int userId) {
        File systemDir;
        if (userId == 0) {
            systemDir = new File(Environment.getDataDirectory(), SYSTEM_PATH);
        } else {
            systemDir = Environment.getUserSystemDirectory(userId);
        }
        return new File(systemDir, INPUT_METHOD_PATH);
    }

    private static AtomicFile getAdditionalSubtypeFile(File inputMethodDir) {
        File subtypeFile = new File(inputMethodDir, ADDITIONAL_SUBTYPES_FILE_NAME);
        return new AtomicFile(subtypeFile, "input-subtypes");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Removed duplicated region for block: B:67:0x01b7  */
    /* JADX WARN: Removed duplicated region for block: B:87:? A[RETURN, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static void save(android.util.ArrayMap<java.lang.String, java.util.List<android.view.inputmethod.InputMethodSubtype>> r20, android.util.ArrayMap<java.lang.String, android.view.inputmethod.InputMethodInfo> r21, int r22) {
        /*
            Method dump skipped, instructions count: 443
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.inputmethod.AdditionalSubtypeUtils.save(android.util.ArrayMap, android.util.ArrayMap, int):void");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void load(ArrayMap<String, List<InputMethodSubtype>> allSubtypes, int userId) {
        int type;
        int i;
        int i2;
        String str;
        AtomicFile subtypesFile;
        int type2;
        String firstNodeName;
        int depth;
        String str2;
        AtomicFile subtypesFile2;
        int type3;
        String firstNodeName2;
        int depth2;
        String subtypeIdString = "1";
        allSubtypes.clear();
        AtomicFile subtypesFile3 = getAdditionalSubtypeFile(getInputMethodDir(userId));
        if (!subtypesFile3.exists()) {
            return;
        }
        try {
            try {
                FileInputStream fis = subtypesFile3.openRead();
                try {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(fis, StandardCharsets.UTF_8.name());
                    parser.getEventType();
                    do {
                        type = parser.next();
                        i = 1;
                        i2 = 2;
                        if (type == 2) {
                            break;
                        }
                    } while (type != 1);
                    String firstNodeName3 = parser.getName();
                    try {
                        if (!NODE_SUBTYPES.equals(firstNodeName3)) {
                            throw new XmlPullParserException("Xml doesn't start with subtypes");
                        }
                        int depth3 = parser.getDepth();
                        String currentImiId = null;
                        String str3 = null;
                        ArrayList<InputMethodSubtype> tempSubtypesArray = null;
                        while (true) {
                            int type4 = parser.next();
                            if (type4 == 3) {
                                try {
                                    if (parser.getDepth() <= depth3) {
                                        break;
                                    }
                                } catch (Throwable th) {
                                    throw th;
                                }
                            }
                            if (type4 == i) {
                                break;
                            }
                            if (type4 != i2) {
                                str = subtypeIdString;
                                subtypesFile = subtypesFile3;
                                type2 = type4;
                                firstNodeName = firstNodeName3;
                                depth = depth3;
                            } else {
                                String nodeName = parser.getName();
                                if (NODE_IMI.equals(nodeName)) {
                                    currentImiId = parser.getAttributeValue(str3, ATTR_ID);
                                    if (TextUtils.isEmpty(currentImiId)) {
                                        Slog.w(TAG, "Invalid imi id found in subtypes.xml");
                                    } else {
                                        tempSubtypesArray = new ArrayList<>();
                                        try {
                                            allSubtypes.put(currentImiId, tempSubtypesArray);
                                            str2 = subtypeIdString;
                                            subtypesFile2 = subtypesFile3;
                                            type3 = type4;
                                            firstNodeName2 = firstNodeName3;
                                            depth2 = depth3;
                                        } catch (Throwable th2) {
                                            throw th2;
                                        }
                                    }
                                } else {
                                    try {
                                        if (NODE_SUBTYPE.equals(nodeName)) {
                                            if (TextUtils.isEmpty(currentImiId)) {
                                                str = subtypeIdString;
                                                subtypesFile = subtypesFile3;
                                                type2 = type4;
                                                firstNodeName = firstNodeName3;
                                                depth = depth3;
                                            } else if (tempSubtypesArray == null) {
                                                str = subtypeIdString;
                                                subtypesFile = subtypesFile3;
                                                type2 = type4;
                                                firstNodeName = firstNodeName3;
                                                depth = depth3;
                                            } else {
                                                int icon = Integer.parseInt(parser.getAttributeValue(str3, ATTR_ICON));
                                                int label = Integer.parseInt(parser.getAttributeValue(str3, ATTR_LABEL));
                                                String imeSubtypeLocale = parser.getAttributeValue(str3, ATTR_IME_SUBTYPE_LOCALE);
                                                subtypesFile2 = subtypesFile3;
                                                String languageTag = parser.getAttributeValue(null, ATTR_IME_SUBTYPE_LANGUAGE_TAG);
                                                type3 = type4;
                                                String imeSubtypeMode = parser.getAttributeValue(null, ATTR_IME_SUBTYPE_MODE);
                                                firstNodeName2 = firstNodeName3;
                                                String imeSubtypeExtraValue = parser.getAttributeValue(null, ATTR_IME_SUBTYPE_EXTRA_VALUE);
                                                depth2 = depth3;
                                                boolean isAuxiliary = subtypeIdString.equals(String.valueOf(parser.getAttributeValue(null, ATTR_IS_AUXILIARY)));
                                                boolean isAsciiCapable = subtypeIdString.equals(String.valueOf(parser.getAttributeValue(null, ATTR_IS_ASCII_CAPABLE)));
                                                InputMethodSubtype.InputMethodSubtypeBuilder builder = new InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeNameResId(label).setSubtypeIconResId(icon).setSubtypeLocale(imeSubtypeLocale).setLanguageTag(languageTag).setSubtypeMode(imeSubtypeMode).setSubtypeExtraValue(imeSubtypeExtraValue).setIsAuxiliary(isAuxiliary).setIsAsciiCapable(isAsciiCapable);
                                                str2 = subtypeIdString;
                                                String subtypeIdString2 = parser.getAttributeValue(null, ATTR_IME_SUBTYPE_ID);
                                                if (subtypeIdString2 != null) {
                                                    builder.setSubtypeId(Integer.parseInt(subtypeIdString2));
                                                }
                                                tempSubtypesArray.add(builder.build());
                                            }
                                            Slog.w(TAG, "IME uninstalled or not valid.: " + currentImiId);
                                        } else {
                                            str2 = subtypeIdString;
                                            subtypesFile2 = subtypesFile3;
                                            type3 = type4;
                                            firstNodeName2 = firstNodeName3;
                                            depth2 = depth3;
                                        }
                                    } catch (Throwable th3) {
                                        th = th3;
                                        throw th;
                                    }
                                }
                                subtypesFile3 = subtypesFile2;
                                firstNodeName3 = firstNodeName2;
                                depth3 = depth2;
                                subtypeIdString = str2;
                                i = 1;
                                i2 = 2;
                                str3 = null;
                            }
                            subtypesFile3 = subtypesFile;
                            firstNodeName3 = firstNodeName;
                            depth3 = depth;
                            subtypeIdString = str;
                            i = 1;
                            i2 = 2;
                            str3 = null;
                        }
                        if (fis != null) {
                            fis.close();
                        }
                    } catch (Throwable th4) {
                    }
                } catch (Throwable th5) {
                    th = th5;
                }
            } catch (IOException | NumberFormatException | XmlPullParserException e) {
                e = e;
                Slog.w(TAG, "Error reading subtypes", e);
            }
        } catch (IOException | NumberFormatException | XmlPullParserException e2) {
            e = e2;
            Slog.w(TAG, "Error reading subtypes", e);
        }
    }
}
