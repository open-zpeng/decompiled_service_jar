package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.fingerprint.Fingerprint;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
/* loaded from: classes.dex */
class FingerprintsUserState {
    private static final String ATTR_DEVICE_ID = "deviceId";
    private static final String ATTR_FINGER_ID = "fingerId";
    private static final String ATTR_GROUP_ID = "groupId";
    private static final String ATTR_NAME = "name";
    private static final String FINGERPRINT_FILE = "settings_fingerprint.xml";
    private static final String TAG = "FingerprintState";
    private static final String TAG_FINGERPRINT = "fingerprint";
    private static final String TAG_FINGERPRINTS = "fingerprints";
    private final Context mCtx;
    private final File mFile;
    @GuardedBy("this")
    private final ArrayList<Fingerprint> mFingerprints = new ArrayList<>();
    private final Runnable mWriteStateRunnable = new Runnable() { // from class: com.android.server.fingerprint.FingerprintsUserState.1
        @Override // java.lang.Runnable
        public void run() {
            FingerprintsUserState.this.doWriteState();
        }
    };

    public FingerprintsUserState(Context ctx, int userId) {
        this.mFile = getFileForUser(userId);
        this.mCtx = ctx;
        synchronized (this) {
            readStateSyncLocked();
        }
    }

    public void addFingerprint(int fingerId, int groupId) {
        synchronized (this) {
            this.mFingerprints.add(new Fingerprint(getUniqueName(), groupId, fingerId, 0L));
            scheduleWriteStateLocked();
        }
    }

    public void removeFingerprint(int fingerId) {
        synchronized (this) {
            int i = 0;
            while (true) {
                if (i >= this.mFingerprints.size()) {
                    break;
                } else if (this.mFingerprints.get(i).getFingerId() != fingerId) {
                    i++;
                } else {
                    this.mFingerprints.remove(i);
                    scheduleWriteStateLocked();
                    break;
                }
            }
        }
    }

    public void renameFingerprint(int fingerId, CharSequence name) {
        synchronized (this) {
            int i = 0;
            while (true) {
                if (i >= this.mFingerprints.size()) {
                    break;
                } else if (this.mFingerprints.get(i).getFingerId() != fingerId) {
                    i++;
                } else {
                    Fingerprint old = this.mFingerprints.get(i);
                    this.mFingerprints.set(i, new Fingerprint(name, old.getGroupId(), old.getFingerId(), old.getDeviceId()));
                    scheduleWriteStateLocked();
                    break;
                }
            }
        }
    }

    public List<Fingerprint> getFingerprints() {
        ArrayList<Fingerprint> copy;
        synchronized (this) {
            copy = getCopy(this.mFingerprints);
        }
        return copy;
    }

    private String getUniqueName() {
        int guess = 1;
        while (true) {
            String name = this.mCtx.getString(17039928, Integer.valueOf(guess));
            if (isUnique(name)) {
                return name;
            }
            guess++;
        }
    }

    private boolean isUnique(String name) {
        Iterator<Fingerprint> it = this.mFingerprints.iterator();
        while (it.hasNext()) {
            Fingerprint fp = it.next();
            if (fp.getName().equals(name)) {
                return false;
            }
        }
        return true;
    }

    private static File getFileForUser(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), FINGERPRINT_FILE);
    }

    private void scheduleWriteStateLocked() {
        AsyncTask.execute(this.mWriteStateRunnable);
    }

    private ArrayList<Fingerprint> getCopy(ArrayList<Fingerprint> array) {
        ArrayList<Fingerprint> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Fingerprint fp = array.get(i);
            result.add(new Fingerprint(fp.getName(), fp.getGroupId(), fp.getFingerId(), fp.getDeviceId()));
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void doWriteState() {
        ArrayList<Fingerprint> fingerprints;
        AtomicFile destination = new AtomicFile(this.mFile);
        synchronized (this) {
            fingerprints = getCopy(this.mFingerprints);
        }
        FileOutputStream out = null;
        try {
            out = destination.startWrite();
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, "utf-8");
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_FINGERPRINTS);
            int count = fingerprints.size();
            for (int i = 0; i < count; i++) {
                Fingerprint fp = fingerprints.get(i);
                serializer.startTag(null, TAG_FINGERPRINT);
                serializer.attribute(null, ATTR_FINGER_ID, Integer.toString(fp.getFingerId()));
                serializer.attribute(null, "name", fp.getName().toString());
                serializer.attribute(null, ATTR_GROUP_ID, Integer.toString(fp.getGroupId()));
                serializer.attribute(null, ATTR_DEVICE_ID, Long.toString(fp.getDeviceId()));
                serializer.endTag(null, TAG_FINGERPRINT);
            }
            serializer.endTag(null, TAG_FINGERPRINTS);
            serializer.endDocument();
            destination.finishWrite(out);
        } finally {
        }
    }

    @GuardedBy("this")
    private void readStateSyncLocked() {
        if (!this.mFile.exists()) {
            return;
        }
        try {
            FileInputStream in = new FileInputStream(this.mFile);
            try {
                try {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(in, null);
                    parseStateLocked(parser);
                } catch (IOException | XmlPullParserException e) {
                    throw new IllegalStateException("Failed parsing settings file: " + this.mFile, e);
                }
            } finally {
                IoUtils.closeQuietly(in);
            }
        } catch (FileNotFoundException e2) {
            Slog.i(TAG, "No fingerprint state");
        }
    }

    @GuardedBy("this")
    private void parseStateLocked(XmlPullParser parser) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type != 1) {
                if (type != 3 || parser.getDepth() > outerDepth) {
                    if (type != 3 && type != 4) {
                        String tagName = parser.getName();
                        if (tagName.equals(TAG_FINGERPRINTS)) {
                            parseFingerprintsLocked(parser);
                        }
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    @GuardedBy("this")
    private void parseFingerprintsLocked(XmlPullParser parser) throws IOException, XmlPullParserException {
        XmlPullParser xmlPullParser = parser;
        int outerDepth = parser.getDepth();
        while (true) {
            int type = parser.next();
            if (type == 1 || (type == 3 && parser.getDepth() <= outerDepth)) {
                break;
            }
            if (type != 3 && type != 4) {
                String tagName = parser.getName();
                if (tagName.equals(TAG_FINGERPRINT)) {
                    String name = xmlPullParser.getAttributeValue(null, "name");
                    String groupId = xmlPullParser.getAttributeValue(null, ATTR_GROUP_ID);
                    String fingerId = xmlPullParser.getAttributeValue(null, ATTR_FINGER_ID);
                    String deviceId = xmlPullParser.getAttributeValue(null, ATTR_DEVICE_ID);
                    this.mFingerprints.add(new Fingerprint(name, Integer.parseInt(groupId), Integer.parseInt(fingerId), Integer.parseInt(deviceId)));
                }
            }
            xmlPullParser = parser;
        }
    }
}
