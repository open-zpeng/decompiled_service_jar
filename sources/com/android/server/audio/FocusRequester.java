package com.android.server.audio;

import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.IAudioFocusDispatcher;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.server.audio.MediaFocusControl;
import java.io.PrintWriter;
import java.util.NoSuchElementException;
/* loaded from: classes.dex */
public class FocusRequester {
    private static final boolean DEBUG = true;
    private static final String TAG = "MediaFocusControl";
    private final AudioAttributes mAttributes;
    private final int mCallingUid;
    private final String mClientId;
    private MediaFocusControl.AudioFocusDeathHandler mDeathHandler;
    private final MediaFocusControl mFocusController;
    private IAudioFocusDispatcher mFocusDispatcher;
    private final int mFocusGainRequest;
    private int mFocusLossReceived = 0;
    private boolean mFocusLossWasNotified = true;
    private final int mGrantFlags;
    private final String mPackageName;
    private final int mSdkTarget;
    private final IBinder mSourceRef;

    /* JADX INFO: Access modifiers changed from: package-private */
    public FocusRequester(AudioAttributes aa, int focusRequest, int grantFlags, IAudioFocusDispatcher afl, IBinder source, String id, MediaFocusControl.AudioFocusDeathHandler hdlr, String pn, int uid, MediaFocusControl ctlr, int sdk) {
        this.mAttributes = aa;
        this.mFocusDispatcher = afl;
        this.mSourceRef = source;
        this.mClientId = id;
        this.mDeathHandler = hdlr;
        this.mPackageName = pn;
        this.mCallingUid = uid;
        this.mFocusGainRequest = focusRequest;
        this.mGrantFlags = grantFlags;
        this.mFocusController = ctlr;
        this.mSdkTarget = sdk;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public FocusRequester(AudioFocusInfo afi, IAudioFocusDispatcher afl, IBinder source, MediaFocusControl.AudioFocusDeathHandler hdlr, MediaFocusControl ctlr) {
        this.mAttributes = afi.getAttributes();
        this.mClientId = afi.getClientId();
        this.mPackageName = afi.getPackageName();
        this.mCallingUid = afi.getClientUid();
        this.mFocusGainRequest = afi.getGainRequest();
        this.mGrantFlags = afi.getFlags();
        this.mSdkTarget = afi.getSdkTarget();
        this.mFocusDispatcher = afl;
        this.mSourceRef = source;
        this.mDeathHandler = hdlr;
        this.mFocusController = ctlr;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSameClient(String otherClient) {
        try {
            return this.mClientId.compareTo(otherClient) == 0;
        } catch (NullPointerException e) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isLockedFocusOwner() {
        return (this.mGrantFlags & 4) != 0;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSameBinder(IBinder ib) {
        return this.mSourceRef != null && this.mSourceRef.equals(ib);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSameDispatcher(IAudioFocusDispatcher fd) {
        return this.mFocusDispatcher != null && this.mFocusDispatcher.equals(fd);
    }

    boolean hasSamePackage(String pack) {
        try {
            return this.mPackageName.compareTo(pack) == 0;
        } catch (NullPointerException e) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasSameUid(int uid) {
        return this.mCallingUid == uid;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getClientUid() {
        return this.mCallingUid;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getClientId() {
        return this.mClientId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getGainRequest() {
        return this.mFocusGainRequest;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getGrantFlags() {
        return this.mGrantFlags;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AudioAttributes getAudioAttributes() {
        return this.mAttributes;
    }

    int getSdkTarget() {
        return this.mSdkTarget;
    }

    private static String focusChangeToString(int focus) {
        switch (focus) {
            case -3:
                return "LOSS_TRANSIENT_CAN_DUCK";
            case -2:
                return "LOSS_TRANSIENT";
            case -1:
                return "LOSS";
            case 0:
                return "none";
            case 1:
                return "GAIN";
            case 2:
                return "GAIN_TRANSIENT";
            case 3:
                return "GAIN_TRANSIENT_MAY_DUCK";
            case 4:
                return "GAIN_TRANSIENT_EXCLUSIVE";
            default:
                return "[invalid focus change" + focus + "]";
        }
    }

    private String focusGainToString() {
        return focusChangeToString(this.mFocusGainRequest);
    }

    private String focusLossToString() {
        return focusChangeToString(this.mFocusLossReceived);
    }

    private static String flagsToString(int flags) {
        String msg = new String();
        if ((flags & 1) != 0) {
            msg = msg + "DELAY_OK";
        }
        if ((flags & 4) != 0) {
            if (!msg.isEmpty()) {
                msg = msg + "|";
            }
            msg = msg + "LOCK";
        }
        if ((flags & 2) != 0) {
            if (!msg.isEmpty()) {
                msg = msg + "|";
            }
            return msg + "PAUSES_ON_DUCKABLE_LOSS";
        }
        return msg;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw) {
        pw.println("  source:" + this.mSourceRef + " -- pack: " + this.mPackageName + " -- client: " + this.mClientId + " -- gain: " + focusGainToString() + " -- flags: " + flagsToString(this.mGrantFlags) + " -- loss: " + focusLossToString() + " -- notified: " + this.mFocusLossWasNotified + " -- uid: " + this.mCallingUid + " -- attr: " + this.mAttributes + " -- sdk:" + this.mSdkTarget);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void release() {
        IBinder srcRef = this.mSourceRef;
        MediaFocusControl.AudioFocusDeathHandler deathHdlr = this.mDeathHandler;
        if (srcRef != null && deathHdlr != null) {
            try {
                srcRef.unlinkToDeath(deathHdlr, 0);
            } catch (NoSuchElementException e) {
            }
        }
        this.mDeathHandler = null;
        this.mFocusDispatcher = null;
    }

    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    /* JADX WARN: Removed duplicated region for block: B:12:0x0013 A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:13:0x0014 A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:17:0x001b A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:18:0x001c A[RETURN] */
    /* JADX WARN: Removed duplicated region for block: B:19:0x001d A[RETURN] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private int focusLossForGainRequest(int r4) {
        /*
            r3 = this;
            r0 = -2
            r1 = -1
            switch(r4) {
                case 1: goto L6;
                case 2: goto Ld;
                case 3: goto L15;
                case 4: goto Ld;
                default: goto L5;
            }
        L5:
            goto L1f
        L6:
            int r2 = r3.mFocusLossReceived
            switch(r2) {
                case -3: goto Lc;
                case -2: goto Lc;
                case -1: goto Lc;
                case 0: goto Lc;
                default: goto Lb;
            }
        Lb:
            goto Ld
        Lc:
            return r1
        Ld:
            int r2 = r3.mFocusLossReceived
            switch(r2) {
                case -3: goto L14;
                case -2: goto L14;
                case -1: goto L13;
                case 0: goto L14;
                default: goto L12;
            }
        L12:
            goto L15
        L13:
            return r1
        L14:
            return r0
        L15:
            int r2 = r3.mFocusLossReceived
            switch(r2) {
                case -3: goto L1d;
                case -2: goto L1c;
                case -1: goto L1b;
                case 0: goto L1d;
                default: goto L1a;
            }
        L1a:
            goto L1f
        L1b:
            return r1
        L1c:
            return r0
        L1d:
            r0 = -3
            return r0
        L1f:
            java.lang.String r0 = "MediaFocusControl"
            java.lang.StringBuilder r1 = new java.lang.StringBuilder
            r1.<init>()
            java.lang.String r2 = "focusLossForGainRequest() for invalid focus request "
            r1.append(r2)
            r1.append(r4)
            java.lang.String r1 = r1.toString()
            android.util.Log.e(r0, r1)
            r0 = 0
            return r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.audio.FocusRequester.focusLossForGainRequest(int):int");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("MediaFocusControl.mAudioFocusLock")
    public boolean handleFocusLossFromGain(int focusGain, FocusRequester frWinner, boolean forceDuck) {
        int focusLoss = focusLossForGainRequest(focusGain);
        handleFocusLoss(focusLoss, frWinner, forceDuck);
        return focusLoss == -1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("MediaFocusControl.mAudioFocusLock")
    public void handleFocusGain(int focusGain) {
        try {
            this.mFocusLossReceived = 0;
            this.mFocusController.notifyExtPolicyFocusGrant_syncAf(toAudioFocusInfo(), 1);
            IAudioFocusDispatcher fd = this.mFocusDispatcher;
            if (fd != null) {
                Log.v(TAG, "dispatching " + focusChangeToString(focusGain) + " to " + this.mClientId);
                if (this.mFocusLossWasNotified) {
                    fd.dispatchAudioFocusChange(focusGain, this.mClientId);
                }
            }
            this.mFocusController.unduckPlayers(this);
        } catch (RemoteException e) {
            Log.e(TAG, "Failure to signal gain of audio focus due to: ", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("MediaFocusControl.mAudioFocusLock")
    public void handleFocusGainFromRequest(int focusRequestResult) {
        if (focusRequestResult == 1) {
            this.mFocusController.unduckPlayers(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @GuardedBy("MediaFocusControl.mAudioFocusLock")
    public void handleFocusLoss(int focusLoss, FocusRequester frWinner, boolean forceDuck) {
        try {
            if (focusLoss != this.mFocusLossReceived) {
                this.mFocusLossReceived = focusLoss;
                this.mFocusLossWasNotified = false;
                if (!this.mFocusController.mustNotifyFocusOwnerOnDuck() && this.mFocusLossReceived == -3 && (this.mGrantFlags & 2) == 0) {
                    Log.v(TAG, "NOT dispatching " + focusChangeToString(this.mFocusLossReceived) + " to " + this.mClientId + ", to be handled externally");
                    this.mFocusController.notifyExtPolicyFocusLoss_syncAf(toAudioFocusInfo(), false);
                } else if (0 != 0) {
                    Log.v(TAG, "NOT dispatching " + focusChangeToString(this.mFocusLossReceived) + " to " + this.mClientId + ", ducking implemented by framework");
                    this.mFocusController.notifyExtPolicyFocusLoss_syncAf(toAudioFocusInfo(), false);
                } else {
                    IAudioFocusDispatcher fd = this.mFocusDispatcher;
                    if (fd != null) {
                        Log.v(TAG, "dispatching " + focusChangeToString(this.mFocusLossReceived) + " to " + this.mClientId);
                        this.mFocusController.notifyExtPolicyFocusLoss_syncAf(toAudioFocusInfo(), true);
                        this.mFocusLossWasNotified = true;
                        fd.dispatchAudioFocusChange(this.mFocusLossReceived, this.mClientId);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failure to signal loss of audio focus due to:", e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int dispatchFocusChange(int focusChange) {
        if (this.mFocusDispatcher == null) {
            Log.e(TAG, "dispatchFocusChange: no focus dispatcher");
            return 0;
        } else if (focusChange == 0) {
            Log.v(TAG, "dispatchFocusChange: AUDIOFOCUS_NONE");
            return 0;
        } else {
            if ((focusChange == 3 || focusChange == 4 || focusChange == 2 || focusChange == 1) && this.mFocusGainRequest != focusChange) {
                Log.w(TAG, "focus gain was requested with " + this.mFocusGainRequest + ", dispatching " + focusChange);
            } else if (focusChange == -3 || focusChange == -2 || focusChange == -1) {
                this.mFocusLossReceived = focusChange;
            }
            try {
                this.mFocusDispatcher.dispatchAudioFocusChange(focusChange, this.mClientId);
                return 1;
            } catch (RemoteException e) {
                Log.e(TAG, "dispatchFocusChange: error talking to focus listener " + this.mClientId, e);
                return 0;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dispatchFocusResultFromExtPolicy(int requestResult) {
        if (this.mFocusDispatcher == null) {
            Log.e(TAG, "dispatchFocusResultFromExtPolicy: no focus dispatcher");
        }
        Log.v(TAG, "dispatching result" + requestResult + " to " + this.mClientId);
        try {
            this.mFocusDispatcher.dispatchFocusResultFromExtPolicy(requestResult, this.mClientId);
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchFocusResultFromExtPolicy: error talking to focus listener" + this.mClientId, e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AudioFocusInfo toAudioFocusInfo() {
        return new AudioFocusInfo(this.mAttributes, this.mCallingUid, this.mClientId, this.mPackageName, this.mFocusGainRequest, this.mFocusLossReceived, this.mGrantFlags, this.mSdkTarget);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getPackageName() {
        return this.mPackageName;
    }
}
