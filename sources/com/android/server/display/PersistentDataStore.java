package com.android.server.display;

import android.graphics.Point;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.WifiDisplay;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.Xml;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class PersistentDataStore {
    private static final String ATTR_DEVICE_ADDRESS = "deviceAddress";
    private static final String ATTR_DEVICE_ALIAS = "deviceAlias";
    private static final String ATTR_DEVICE_NAME = "deviceName";
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_TIME_STAMP = "timestamp";
    private static final String ATTR_UNIQUE_ID = "unique-id";
    private static final String ATTR_USER_SERIAL = "user-serial";
    static final String TAG = "DisplayManager";
    private static final String TAG_BRIGHTNESS_CONFIGURATION = "brightness-configuration";
    private static final String TAG_BRIGHTNESS_CONFIGURATIONS = "brightness-configurations";
    private static final String TAG_COLOR_MODE = "color-mode";
    private static final String TAG_DISPLAY = "display";
    private static final String TAG_DISPLAY_MANAGER_STATE = "display-manager-state";
    private static final String TAG_DISPLAY_STATES = "display-states";
    private static final String TAG_REMEMBERED_WIFI_DISPLAYS = "remembered-wifi-displays";
    private static final String TAG_STABLE_DEVICE_VALUES = "stable-device-values";
    private static final String TAG_STABLE_DISPLAY_HEIGHT = "stable-display-height";
    private static final String TAG_STABLE_DISPLAY_WIDTH = "stable-display-width";
    private static final String TAG_WIFI_DISPLAY = "wifi-display";
    private BrightnessConfigurations mBrightnessConfigurations;
    private boolean mDirty;
    private final HashMap<String, DisplayState> mDisplayStates;
    private Injector mInjector;
    private boolean mLoaded;
    private ArrayList<WifiDisplay> mRememberedWifiDisplays;
    private final StableDeviceValues mStableDeviceValues;

    public PersistentDataStore() {
        this(new Injector());
    }

    @VisibleForTesting
    PersistentDataStore(Injector injector) {
        this.mRememberedWifiDisplays = new ArrayList<>();
        this.mDisplayStates = new HashMap<>();
        this.mStableDeviceValues = new StableDeviceValues();
        this.mBrightnessConfigurations = new BrightnessConfigurations();
        this.mInjector = injector;
    }

    public void saveIfNeeded() {
        if (this.mDirty) {
            save();
            this.mDirty = false;
        }
    }

    public WifiDisplay getRememberedWifiDisplay(String deviceAddress) {
        loadIfNeeded();
        int index = findRememberedWifiDisplay(deviceAddress);
        if (index >= 0) {
            return this.mRememberedWifiDisplays.get(index);
        }
        return null;
    }

    public WifiDisplay[] getRememberedWifiDisplays() {
        loadIfNeeded();
        ArrayList<WifiDisplay> arrayList = this.mRememberedWifiDisplays;
        return (WifiDisplay[]) arrayList.toArray(new WifiDisplay[arrayList.size()]);
    }

    public WifiDisplay applyWifiDisplayAlias(WifiDisplay display) {
        if (display != null) {
            loadIfNeeded();
            String alias = null;
            int index = findRememberedWifiDisplay(display.getDeviceAddress());
            if (index >= 0) {
                alias = this.mRememberedWifiDisplays.get(index).getDeviceAlias();
            }
            if (!Objects.equals(display.getDeviceAlias(), alias)) {
                return new WifiDisplay(display.getDeviceAddress(), display.getDeviceName(), alias, display.isAvailable(), display.canConnect(), display.isRemembered());
            }
        }
        return display;
    }

    public WifiDisplay[] applyWifiDisplayAliases(WifiDisplay[] displays) {
        WifiDisplay[] results = displays;
        if (results != null) {
            int count = displays.length;
            for (int i = 0; i < count; i++) {
                WifiDisplay result = applyWifiDisplayAlias(displays[i]);
                if (result != displays[i]) {
                    if (results == displays) {
                        results = new WifiDisplay[count];
                        System.arraycopy(displays, 0, results, 0, count);
                    }
                    results[i] = result;
                }
            }
        }
        return results;
    }

    public boolean rememberWifiDisplay(WifiDisplay display) {
        loadIfNeeded();
        int index = findRememberedWifiDisplay(display.getDeviceAddress());
        if (index >= 0) {
            WifiDisplay other = this.mRememberedWifiDisplays.get(index);
            if (other.equals(display)) {
                return false;
            }
            this.mRememberedWifiDisplays.set(index, display);
        } else {
            this.mRememberedWifiDisplays.add(display);
        }
        setDirty();
        return true;
    }

    public boolean forgetWifiDisplay(String deviceAddress) {
        loadIfNeeded();
        int index = findRememberedWifiDisplay(deviceAddress);
        if (index >= 0) {
            this.mRememberedWifiDisplays.remove(index);
            setDirty();
            return true;
        }
        return false;
    }

    private int findRememberedWifiDisplay(String deviceAddress) {
        int count = this.mRememberedWifiDisplays.size();
        for (int i = 0; i < count; i++) {
            if (this.mRememberedWifiDisplays.get(i).getDeviceAddress().equals(deviceAddress)) {
                return i;
            }
        }
        return -1;
    }

    public int getColorMode(DisplayDevice device) {
        DisplayState state;
        if (device.hasStableUniqueId() && (state = getDisplayState(device.getUniqueId(), false)) != null) {
            return state.getColorMode();
        }
        return -1;
    }

    public boolean setColorMode(DisplayDevice device, int colorMode) {
        if (device.hasStableUniqueId()) {
            DisplayState state = getDisplayState(device.getUniqueId(), true);
            if (state.setColorMode(colorMode)) {
                setDirty();
                return true;
            }
            return false;
        }
        return false;
    }

    public Point getStableDisplaySize() {
        loadIfNeeded();
        return this.mStableDeviceValues.getDisplaySize();
    }

    public void setStableDisplaySize(Point size) {
        loadIfNeeded();
        if (this.mStableDeviceValues.setDisplaySize(size)) {
            setDirty();
        }
    }

    public void setBrightnessConfigurationForUser(BrightnessConfiguration c, int userSerial, String packageName) {
        loadIfNeeded();
        if (this.mBrightnessConfigurations.setBrightnessConfigurationForUser(c, userSerial, packageName)) {
            setDirty();
        }
    }

    public BrightnessConfiguration getBrightnessConfiguration(int userSerial) {
        loadIfNeeded();
        return this.mBrightnessConfigurations.getBrightnessConfiguration(userSerial);
    }

    private DisplayState getDisplayState(String uniqueId, boolean createIfAbsent) {
        loadIfNeeded();
        DisplayState state = this.mDisplayStates.get(uniqueId);
        if (state == null && createIfAbsent) {
            DisplayState state2 = new DisplayState();
            this.mDisplayStates.put(uniqueId, state2);
            setDirty();
            return state2;
        }
        return state;
    }

    public void loadIfNeeded() {
        if (!this.mLoaded) {
            load();
            this.mLoaded = true;
        }
    }

    private void setDirty() {
        this.mDirty = true;
    }

    private void clearState() {
        this.mRememberedWifiDisplays.clear();
    }

    private void load() {
        InputStream is;
        clearState();
        try {
            try {
                is = this.mInjector.openRead();
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(new BufferedInputStream(is), StandardCharsets.UTF_8.name());
                loadFromXml(parser);
            } catch (IOException ex) {
                Slog.w(TAG, "Failed to load display manager persistent store data.", ex);
                clearState();
            } catch (XmlPullParserException ex2) {
                Slog.w(TAG, "Failed to load display manager persistent store data.", ex2);
                clearState();
            } finally {
                IoUtils.closeQuietly(is);
            }
        } catch (FileNotFoundException e) {
        }
    }

    private void save() {
        try {
            OutputStream os = this.mInjector.startWrite();
            FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
            fastXmlSerializer.setOutput(new BufferedOutputStream(os), StandardCharsets.UTF_8.name());
            saveToXml(fastXmlSerializer);
            fastXmlSerializer.flush();
            this.mInjector.finishWrite(os, true);
        } catch (IOException ex) {
            Slog.w(TAG, "Failed to save display manager persistent store data.", ex);
        }
    }

    private void loadFromXml(XmlPullParser parser) throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, TAG_DISPLAY_MANAGER_STATE);
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_REMEMBERED_WIFI_DISPLAYS)) {
                loadRememberedWifiDisplaysFromXml(parser);
            }
            if (parser.getName().equals(TAG_DISPLAY_STATES)) {
                loadDisplaysFromXml(parser);
            }
            if (parser.getName().equals(TAG_STABLE_DEVICE_VALUES)) {
                this.mStableDeviceValues.loadFromXml(parser);
            }
            if (parser.getName().equals(TAG_BRIGHTNESS_CONFIGURATIONS)) {
                this.mBrightnessConfigurations.loadFromXml(parser);
            }
        }
    }

    private void loadRememberedWifiDisplaysFromXml(XmlPullParser parser) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_WIFI_DISPLAY)) {
                String deviceAddress = parser.getAttributeValue(null, ATTR_DEVICE_ADDRESS);
                String deviceName = parser.getAttributeValue(null, ATTR_DEVICE_NAME);
                String deviceAlias = parser.getAttributeValue(null, ATTR_DEVICE_ALIAS);
                if (deviceAddress == null || deviceName == null) {
                    throw new XmlPullParserException("Missing deviceAddress or deviceName attribute on wifi-display.");
                }
                if (findRememberedWifiDisplay(deviceAddress) >= 0) {
                    throw new XmlPullParserException("Found duplicate wifi display device address.");
                }
                this.mRememberedWifiDisplays.add(new WifiDisplay(deviceAddress, deviceName, deviceAlias, false, false, false));
            }
        }
    }

    private void loadDisplaysFromXml(XmlPullParser parser) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_DISPLAY)) {
                String uniqueId = parser.getAttributeValue(null, ATTR_UNIQUE_ID);
                if (uniqueId == null) {
                    throw new XmlPullParserException("Missing unique-id attribute on display.");
                }
                if (this.mDisplayStates.containsKey(uniqueId)) {
                    throw new XmlPullParserException("Found duplicate display.");
                }
                DisplayState state = new DisplayState();
                state.loadFromXml(parser);
                this.mDisplayStates.put(uniqueId, state);
            }
        }
    }

    private void saveToXml(XmlSerializer serializer) throws IOException {
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, TAG_DISPLAY_MANAGER_STATE);
        serializer.startTag(null, TAG_REMEMBERED_WIFI_DISPLAYS);
        Iterator<WifiDisplay> it = this.mRememberedWifiDisplays.iterator();
        while (it.hasNext()) {
            WifiDisplay display = it.next();
            serializer.startTag(null, TAG_WIFI_DISPLAY);
            serializer.attribute(null, ATTR_DEVICE_ADDRESS, display.getDeviceAddress());
            serializer.attribute(null, ATTR_DEVICE_NAME, display.getDeviceName());
            if (display.getDeviceAlias() != null) {
                serializer.attribute(null, ATTR_DEVICE_ALIAS, display.getDeviceAlias());
            }
            serializer.endTag(null, TAG_WIFI_DISPLAY);
        }
        serializer.endTag(null, TAG_REMEMBERED_WIFI_DISPLAYS);
        serializer.startTag(null, TAG_DISPLAY_STATES);
        for (Map.Entry<String, DisplayState> entry : this.mDisplayStates.entrySet()) {
            String uniqueId = entry.getKey();
            DisplayState state = entry.getValue();
            serializer.startTag(null, TAG_DISPLAY);
            serializer.attribute(null, ATTR_UNIQUE_ID, uniqueId);
            state.saveToXml(serializer);
            serializer.endTag(null, TAG_DISPLAY);
        }
        serializer.endTag(null, TAG_DISPLAY_STATES);
        serializer.startTag(null, TAG_STABLE_DEVICE_VALUES);
        this.mStableDeviceValues.saveToXml(serializer);
        serializer.endTag(null, TAG_STABLE_DEVICE_VALUES);
        serializer.startTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);
        this.mBrightnessConfigurations.saveToXml(serializer);
        serializer.endTag(null, TAG_BRIGHTNESS_CONFIGURATIONS);
        serializer.endTag(null, TAG_DISPLAY_MANAGER_STATE);
        serializer.endDocument();
    }

    public void dump(PrintWriter pw) {
        pw.println("PersistentDataStore");
        pw.println("  mLoaded=" + this.mLoaded);
        pw.println("  mDirty=" + this.mDirty);
        pw.println("  RememberedWifiDisplays:");
        int i = 0;
        Iterator<WifiDisplay> it = this.mRememberedWifiDisplays.iterator();
        while (it.hasNext()) {
            WifiDisplay display = it.next();
            pw.println("    " + i + ": " + display);
            i++;
        }
        pw.println("  DisplayStates:");
        int i2 = 0;
        for (Map.Entry<String, DisplayState> entry : this.mDisplayStates.entrySet()) {
            pw.println("    " + i2 + ": " + entry.getKey());
            entry.getValue().dump(pw, "      ");
            i2++;
        }
        pw.println("  StableDeviceValues:");
        this.mStableDeviceValues.dump(pw, "      ");
        pw.println("  BrightnessConfigurations:");
        this.mBrightnessConfigurations.dump(pw, "      ");
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class DisplayState {
        private int mColorMode;

        private DisplayState() {
        }

        public boolean setColorMode(int colorMode) {
            if (colorMode == this.mColorMode) {
                return false;
            }
            this.mColorMode = colorMode;
            return true;
        }

        public int getColorMode() {
            return this.mColorMode;
        }

        public void loadFromXml(XmlPullParser parser) throws IOException, XmlPullParserException {
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (parser.getName().equals(PersistentDataStore.TAG_COLOR_MODE)) {
                    String value = parser.nextText();
                    this.mColorMode = Integer.parseInt(value);
                }
            }
        }

        public void saveToXml(XmlSerializer serializer) throws IOException {
            serializer.startTag(null, PersistentDataStore.TAG_COLOR_MODE);
            serializer.text(Integer.toString(this.mColorMode));
            serializer.endTag(null, PersistentDataStore.TAG_COLOR_MODE);
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "ColorMode=" + this.mColorMode);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class StableDeviceValues {
        private int mHeight;
        private int mWidth;

        private StableDeviceValues() {
        }

        /* JADX INFO: Access modifiers changed from: private */
        public Point getDisplaySize() {
            return new Point(this.mWidth, this.mHeight);
        }

        public boolean setDisplaySize(Point r) {
            if (this.mWidth != r.x || this.mHeight != r.y) {
                this.mWidth = r.x;
                this.mHeight = r.y;
                return true;
            }
            return false;
        }

        public void loadFromXml(XmlPullParser parser) throws IOException, XmlPullParserException {
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                String name = parser.getName();
                char c = 65535;
                int hashCode = name.hashCode();
                if (hashCode != -1635792540) {
                    if (hashCode == 1069578729 && name.equals(PersistentDataStore.TAG_STABLE_DISPLAY_WIDTH)) {
                        c = 0;
                    }
                } else if (name.equals(PersistentDataStore.TAG_STABLE_DISPLAY_HEIGHT)) {
                    c = 1;
                }
                if (c == 0) {
                    this.mWidth = loadIntValue(parser);
                } else if (c == 1) {
                    this.mHeight = loadIntValue(parser);
                }
            }
        }

        private static int loadIntValue(XmlPullParser parser) throws IOException, XmlPullParserException {
            try {
                String value = parser.nextText();
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public void saveToXml(XmlSerializer serializer) throws IOException {
            if (this.mWidth > 0 && this.mHeight > 0) {
                serializer.startTag(null, PersistentDataStore.TAG_STABLE_DISPLAY_WIDTH);
                serializer.text(Integer.toString(this.mWidth));
                serializer.endTag(null, PersistentDataStore.TAG_STABLE_DISPLAY_WIDTH);
                serializer.startTag(null, PersistentDataStore.TAG_STABLE_DISPLAY_HEIGHT);
                serializer.text(Integer.toString(this.mHeight));
                serializer.endTag(null, PersistentDataStore.TAG_STABLE_DISPLAY_HEIGHT);
            }
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "StableDisplayWidth=" + this.mWidth);
            pw.println(prefix + "StableDisplayHeight=" + this.mHeight);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class BrightnessConfigurations {
        private SparseArray<BrightnessConfiguration> mConfigurations = new SparseArray<>();
        private SparseLongArray mTimeStamps = new SparseLongArray();
        private SparseArray<String> mPackageNames = new SparseArray<>();

        /* JADX INFO: Access modifiers changed from: private */
        public boolean setBrightnessConfigurationForUser(BrightnessConfiguration c, int userSerial, String packageName) {
            BrightnessConfiguration currentConfig = this.mConfigurations.get(userSerial);
            if (currentConfig != c) {
                if (currentConfig == null || !currentConfig.equals(c)) {
                    if (c != null) {
                        if (packageName == null) {
                            this.mPackageNames.remove(userSerial);
                        } else {
                            this.mPackageNames.put(userSerial, packageName);
                        }
                        this.mTimeStamps.put(userSerial, System.currentTimeMillis());
                        this.mConfigurations.put(userSerial, c);
                        return true;
                    }
                    this.mPackageNames.remove(userSerial);
                    this.mTimeStamps.delete(userSerial);
                    this.mConfigurations.remove(userSerial);
                    return true;
                }
                return false;
            }
            return false;
        }

        public BrightnessConfiguration getBrightnessConfiguration(int userSerial) {
            return this.mConfigurations.get(userSerial);
        }

        public void loadFromXml(XmlPullParser parser) throws IOException, XmlPullParserException {
            int userSerial;
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if (PersistentDataStore.TAG_BRIGHTNESS_CONFIGURATION.equals(parser.getName())) {
                    try {
                        userSerial = Integer.parseInt(parser.getAttributeValue(null, PersistentDataStore.ATTR_USER_SERIAL));
                    } catch (NumberFormatException nfe) {
                        Slog.e(PersistentDataStore.TAG, "Failed to read in brightness configuration", nfe);
                        userSerial = -1;
                    }
                    String packageName = parser.getAttributeValue(null, PersistentDataStore.ATTR_PACKAGE_NAME);
                    String timeStampString = parser.getAttributeValue(null, "timestamp");
                    long timeStamp = -1;
                    if (timeStampString != null) {
                        try {
                            timeStamp = Long.parseLong(timeStampString);
                        } catch (NumberFormatException e) {
                        }
                    }
                    try {
                        BrightnessConfiguration config = BrightnessConfiguration.loadFromXml(parser);
                        if (userSerial >= 0 && config != null) {
                            this.mConfigurations.put(userSerial, config);
                            if (timeStamp != -1) {
                                this.mTimeStamps.put(userSerial, timeStamp);
                            }
                            if (packageName != null) {
                                this.mPackageNames.put(userSerial, packageName);
                            }
                        }
                    } catch (IllegalArgumentException iae) {
                        Slog.e(PersistentDataStore.TAG, "Failed to load brightness configuration!", iae);
                    }
                }
            }
        }

        public void saveToXml(XmlSerializer serializer) throws IOException {
            for (int i = 0; i < this.mConfigurations.size(); i++) {
                int userSerial = this.mConfigurations.keyAt(i);
                BrightnessConfiguration config = this.mConfigurations.valueAt(i);
                serializer.startTag(null, PersistentDataStore.TAG_BRIGHTNESS_CONFIGURATION);
                serializer.attribute(null, PersistentDataStore.ATTR_USER_SERIAL, Integer.toString(userSerial));
                String packageName = this.mPackageNames.get(userSerial);
                if (packageName != null) {
                    serializer.attribute(null, PersistentDataStore.ATTR_PACKAGE_NAME, packageName);
                }
                long timestamp = this.mTimeStamps.get(userSerial, -1L);
                if (timestamp != -1) {
                    serializer.attribute(null, "timestamp", Long.toString(timestamp));
                }
                config.saveToXml(serializer);
                serializer.endTag(null, PersistentDataStore.TAG_BRIGHTNESS_CONFIGURATION);
            }
        }

        public void dump(PrintWriter pw, String prefix) {
            for (int i = 0; i < this.mConfigurations.size(); i++) {
                int userSerial = this.mConfigurations.keyAt(i);
                long time = this.mTimeStamps.get(userSerial, -1L);
                String packageName = this.mPackageNames.get(userSerial);
                pw.println(prefix + "User " + userSerial + ":");
                if (time != -1) {
                    pw.println(prefix + "  set at: " + TimeUtils.formatForLogging(time));
                }
                if (packageName != null) {
                    pw.println(prefix + "  set by: " + packageName);
                }
                pw.println(prefix + "  " + this.mConfigurations.valueAt(i));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class Injector {
        private final AtomicFile mAtomicFile = new AtomicFile(new File("/data/system/display-manager-state.xml"), "display-state");

        public InputStream openRead() throws FileNotFoundException {
            return this.mAtomicFile.openRead();
        }

        public OutputStream startWrite() throws IOException {
            return this.mAtomicFile.startWrite();
        }

        public void finishWrite(OutputStream os, boolean success) {
            if (!(os instanceof FileOutputStream)) {
                throw new IllegalArgumentException("Unexpected OutputStream as argument: " + os);
            }
            FileOutputStream fos = (FileOutputStream) os;
            if (success) {
                this.mAtomicFile.finishWrite(fos);
            } else {
                this.mAtomicFile.failWrite(fos);
            }
        }
    }
}
