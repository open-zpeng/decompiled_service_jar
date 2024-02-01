package com.android.server;

import android.content.Context;
import android.hardware.ISensorPrivacyListener;
import android.hardware.ISensorPrivacyManager;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.SensorPrivacyService;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/* loaded from: classes.dex */
public final class SensorPrivacyService extends SystemService {
    private static final String SENSOR_PRIVACY_XML_FILE = "sensor_privacy.xml";
    private static final String TAG = "SensorPrivacyService";
    private static final String XML_ATTRIBUTE_ENABLED = "enabled";
    private static final String XML_TAG_SENSOR_PRIVACY = "sensor-privacy";
    private final SensorPrivacyServiceImpl mSensorPrivacyServiceImpl;

    public SensorPrivacyService(Context context) {
        super(context);
        this.mSensorPrivacyServiceImpl = new SensorPrivacyServiceImpl(context);
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishBinderService("sensor_privacy", this.mSensorPrivacyServiceImpl);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public class SensorPrivacyServiceImpl extends ISensorPrivacyManager.Stub {
        @GuardedBy({"mLock"})
        private final AtomicFile mAtomicFile;
        private final Context mContext;
        @GuardedBy({"mLock"})
        private boolean mEnabled;
        private final SensorPrivacyHandler mHandler;
        private final Object mLock = new Object();

        SensorPrivacyServiceImpl(Context context) {
            this.mContext = context;
            this.mHandler = new SensorPrivacyHandler(FgThread.get().getLooper(), this.mContext);
            File sensorPrivacyFile = new File(Environment.getDataSystemDirectory(), SensorPrivacyService.SENSOR_PRIVACY_XML_FILE);
            this.mAtomicFile = new AtomicFile(sensorPrivacyFile);
            synchronized (this.mLock) {
                this.mEnabled = readPersistedSensorPrivacyEnabledLocked();
            }
        }

        public void setSensorPrivacy(boolean enable) {
            enforceSensorPrivacyPermission();
            synchronized (this.mLock) {
                this.mEnabled = enable;
                FileOutputStream outputStream = null;
                try {
                    FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
                    outputStream = this.mAtomicFile.startWrite();
                    fastXmlSerializer.setOutput(outputStream, StandardCharsets.UTF_8.name());
                    fastXmlSerializer.startDocument(null, true);
                    fastXmlSerializer.startTag(null, SensorPrivacyService.XML_TAG_SENSOR_PRIVACY);
                    fastXmlSerializer.attribute(null, SensorPrivacyService.XML_ATTRIBUTE_ENABLED, String.valueOf(enable));
                    fastXmlSerializer.endTag(null, SensorPrivacyService.XML_TAG_SENSOR_PRIVACY);
                    fastXmlSerializer.endDocument();
                    this.mAtomicFile.finishWrite(outputStream);
                } catch (IOException e) {
                    Log.e(SensorPrivacyService.TAG, "Caught an exception persisting the sensor privacy state: ", e);
                    this.mAtomicFile.failWrite(outputStream);
                }
            }
            this.mHandler.onSensorPrivacyChanged(enable);
        }

        private void enforceSensorPrivacyPermission() {
            if (this.mContext.checkCallingOrSelfPermission("android.permission.MANAGE_SENSOR_PRIVACY") == 0) {
                return;
            }
            throw new SecurityException("Changing sensor privacy requires the following permission: android.permission.MANAGE_SENSOR_PRIVACY");
        }

        public boolean isSensorPrivacyEnabled() {
            boolean z;
            synchronized (this.mLock) {
                z = this.mEnabled;
            }
            return z;
        }

        private boolean readPersistedSensorPrivacyEnabledLocked() {
            if (!this.mAtomicFile.exists()) {
                return false;
            }
            try {
                FileInputStream inputStream = this.mAtomicFile.openRead();
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(inputStream, StandardCharsets.UTF_8.name());
                XmlUtils.beginDocument(parser, SensorPrivacyService.XML_TAG_SENSOR_PRIVACY);
                parser.next();
                parser.getName();
                boolean enabled = Boolean.valueOf(parser.getAttributeValue(null, SensorPrivacyService.XML_ATTRIBUTE_ENABLED)).booleanValue();
                if (inputStream != null) {
                    inputStream.close();
                    return enabled;
                }
                return enabled;
            } catch (IOException | XmlPullParserException e) {
                Log.e(SensorPrivacyService.TAG, "Caught an exception reading the state from storage: ", e);
                this.mAtomicFile.delete();
                return false;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void persistSensorPrivacyState() {
            synchronized (this.mLock) {
                FileOutputStream outputStream = null;
                try {
                    FastXmlSerializer fastXmlSerializer = new FastXmlSerializer();
                    outputStream = this.mAtomicFile.startWrite();
                    fastXmlSerializer.setOutput(outputStream, StandardCharsets.UTF_8.name());
                    fastXmlSerializer.startDocument(null, true);
                    fastXmlSerializer.startTag(null, SensorPrivacyService.XML_TAG_SENSOR_PRIVACY);
                    fastXmlSerializer.attribute(null, SensorPrivacyService.XML_ATTRIBUTE_ENABLED, String.valueOf(this.mEnabled));
                    fastXmlSerializer.endTag(null, SensorPrivacyService.XML_TAG_SENSOR_PRIVACY);
                    fastXmlSerializer.endDocument();
                    this.mAtomicFile.finishWrite(outputStream);
                } catch (IOException e) {
                    Log.e(SensorPrivacyService.TAG, "Caught an exception persisting the sensor privacy state: ", e);
                    this.mAtomicFile.failWrite(outputStream);
                }
            }
        }

        public void addSensorPrivacyListener(ISensorPrivacyListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener cannot be null");
            }
            this.mHandler.addListener(listener);
        }

        public void removeSensorPrivacyListener(ISensorPrivacyListener listener) {
            if (listener == null) {
                throw new NullPointerException("listener cannot be null");
            }
            this.mHandler.removeListener(listener);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SensorPrivacyHandler extends Handler {
        private static final int MESSAGE_SENSOR_PRIVACY_CHANGED = 1;
        private final Context mContext;
        private final ArrayMap<ISensorPrivacyListener, DeathRecipient> mDeathRecipients;
        private final Object mListenerLock;
        @GuardedBy({"mListenerLock"})
        private final RemoteCallbackList<ISensorPrivacyListener> mListeners;

        SensorPrivacyHandler(Looper looper, Context context) {
            super(looper);
            this.mListenerLock = new Object();
            this.mListeners = new RemoteCallbackList<>();
            this.mDeathRecipients = new ArrayMap<>();
            this.mContext = context;
        }

        public void onSensorPrivacyChanged(boolean enabled) {
            sendMessage(PooledLambda.obtainMessage(new BiConsumer() { // from class: com.android.server.-$$Lambda$2rlj96lJ7chZc-A-SbtixW5GQdw
                @Override // java.util.function.BiConsumer
                public final void accept(Object obj, Object obj2) {
                    ((SensorPrivacyService.SensorPrivacyHandler) obj).handleSensorPrivacyChanged(((Boolean) obj2).booleanValue());
                }
            }, this, Boolean.valueOf(enabled)));
            sendMessage(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.-$$Lambda$SensorPrivacyService$SensorPrivacyHandler$ctW6BcqPnLm_33mG1WatsFwFT7w
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((SensorPrivacyService.SensorPrivacyServiceImpl) obj).persistSensorPrivacyState();
                }
            }, SensorPrivacyService.this.mSensorPrivacyServiceImpl));
        }

        public void addListener(ISensorPrivacyListener listener) {
            synchronized (this.mListenerLock) {
                DeathRecipient deathRecipient = new DeathRecipient(listener);
                this.mDeathRecipients.put(listener, deathRecipient);
                this.mListeners.register(listener);
            }
        }

        public void removeListener(ISensorPrivacyListener listener) {
            synchronized (this.mListenerLock) {
                DeathRecipient deathRecipient = this.mDeathRecipients.remove(listener);
                if (deathRecipient != null) {
                    deathRecipient.destroy();
                }
                this.mListeners.unregister(listener);
            }
        }

        public void handleSensorPrivacyChanged(boolean enabled) {
            int count = this.mListeners.beginBroadcast();
            for (int i = 0; i < count; i++) {
                ISensorPrivacyListener listener = this.mListeners.getBroadcastItem(i);
                try {
                    listener.onSensorPrivacyChanged(enabled);
                } catch (RemoteException e) {
                    Log.e(SensorPrivacyService.TAG, "Caught an exception notifying listener " + listener + ": ", e);
                }
            }
            this.mListeners.finishBroadcast();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class DeathRecipient implements IBinder.DeathRecipient {
        private ISensorPrivacyListener mListener;

        DeathRecipient(ISensorPrivacyListener listener) {
            this.mListener = listener;
            try {
                this.mListener.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            SensorPrivacyService.this.mSensorPrivacyServiceImpl.removeSensorPrivacyListener(this.mListener);
        }

        public void destroy() {
            try {
                this.mListener.asBinder().unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
            }
        }
    }
}
