package com.android.server.audio;

import android.app.AppOpsManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.IAudioFocusDispatcher;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.server.audio.AudioEventLogger;
import com.android.server.backup.BackupManagerConstants;
import com.android.server.slice.SliceClientPermissions;
import com.android.xpeng.audio.xpAudio;
import com.xiaopeng.xui.xuiaudio.xuiAudioPolicyTrigger;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
/* loaded from: classes.dex */
public class MediaFocusControl implements PlayerFocusEnforcer {
    static final boolean DEBUG = true;
    static final int DUCKING_IN_APP_SDK_LEVEL = 25;
    static final boolean ENFORCE_DUCKING = false;
    static final boolean ENFORCE_DUCKING_FOR_NEW = true;
    static final boolean ENFORCE_MUTING_FOR_RING_OR_CALL = true;
    private static final int MAX_STACK_SIZE = 100;
    private static final int RING_CALL_MUTING_ENFORCEMENT_DELAY_MS = 100;
    private static final String TAG = "MediaFocusControl";
    private final AppOpsManager mAppOps;
    private final Context mContext;
    @GuardedBy("mExtFocusChangeLock")
    private long mExtFocusChangeCounter;
    private PlayerFocusEnforcer mFocusEnforcer;
    private xpAudio mXpAudio;
    private xuiAudioPolicyTrigger mxuiAudioPolicyTrigger;
    private static final AudioAttributes DEFAULT_ATTRIBUTES = new AudioAttributes.Builder().build();
    private static final boolean newPolicyOpen = AudioManager.newPolicyOpen;
    private static final Object mAudioFocusLock = new Object();
    private static final AudioEventLogger mEventLogger = new AudioEventLogger(50, "focus commands as seen by MediaFocusControl");
    private static final int[] USAGES_TO_MUTE_IN_RING_OR_CALL = {1, 14};
    private boolean mRingOrCallActive = false;
    private final Object mExtFocusChangeLock = new Object();
    private String lastFocusAppName = BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
    private final Stack<FocusRequester> mFocusStack = new Stack<>();
    private boolean mNotifyFocusOwnerOnDuck = true;
    private ArrayList<IAudioPolicyCallback> mFocusFollowers = new ArrayList<>();
    private IAudioPolicyCallback mFocusPolicy = null;
    private HashMap<String, FocusRequester> mFocusOwnersForFocusPolicy = new HashMap<>();
    List<String> mAudioFocusPackageNameList = new ArrayList();

    /* JADX INFO: Access modifiers changed from: protected */
    public MediaFocusControl(Context cntxt, PlayerFocusEnforcer pfe) {
        this.mContext = cntxt;
        this.mAppOps = (AppOpsManager) this.mContext.getSystemService("appops");
        this.mFocusEnforcer = pfe;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void dump(PrintWriter pw) {
        pw.println("\nMediaFocusControl dump time: " + DateFormat.getTimeInstance().format(new Date()));
        dumpFocusStack(pw);
        pw.println("\n");
        mEventLogger.dump(pw);
    }

    @Override // com.android.server.audio.PlayerFocusEnforcer
    public boolean duckPlayers(FocusRequester winner, FocusRequester loser, boolean forceDuck) {
        return this.mFocusEnforcer.duckPlayers(winner, loser, forceDuck);
    }

    @Override // com.android.server.audio.PlayerFocusEnforcer
    public void unduckPlayers(FocusRequester winner) {
        this.mFocusEnforcer.unduckPlayers(winner);
    }

    @Override // com.android.server.audio.PlayerFocusEnforcer
    public void mutePlayersForCall(int[] usagesToMute) {
        this.mFocusEnforcer.mutePlayersForCall(usagesToMute);
    }

    @Override // com.android.server.audio.PlayerFocusEnforcer
    public void unmutePlayersForCall() {
        this.mFocusEnforcer.unmutePlayersForCall();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setXpAudio(xpAudio mAudio) {
        this.mXpAudio = mAudio;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setXuiAudio(xuiAudioPolicyTrigger mAudio) {
        this.mxuiAudioPolicyTrigger = mAudio;
    }

    private int checkStreamCanPlay(int streamType) {
        if (newPolicyOpen && this.mxuiAudioPolicyTrigger != null) {
            return this.mxuiAudioPolicyTrigger.checkStreamCanPlay(streamType);
        }
        if (!newPolicyOpen && this.mXpAudio != null) {
            return this.mXpAudio.checkStreamCanPlay(streamType);
        }
        return 2;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void discardAudioFocusOwner() {
        synchronized (mAudioFocusLock) {
            if (!this.mFocusStack.empty()) {
                FocusRequester exFocusOwner = this.mFocusStack.pop();
                exFocusOwner.handleFocusLoss(-1, null, false);
                exFocusOwner.release();
            }
        }
    }

    @GuardedBy("mAudioFocusLock")
    private void notifyTopOfAudioFocusStack() {
        if (!this.mFocusStack.empty() && canReassignAudioFocus()) {
            this.mFocusStack.peek().handleFocusGain(1);
        }
    }

    @GuardedBy("mAudioFocusLock")
    private void propagateFocusLossFromGain_syncAf(int focusGain, FocusRequester fr, boolean forceDuck) {
        List<String> clientsToRemove = new LinkedList<>();
        Iterator<FocusRequester> it = this.mFocusStack.iterator();
        while (it.hasNext()) {
            FocusRequester focusLoser = it.next();
            boolean isDefinitiveLoss = focusLoser.handleFocusLossFromGain(focusGain, fr, forceDuck);
            if (isDefinitiveLoss) {
                clientsToRemove.add(focusLoser.getClientId());
            }
        }
        for (String clientToRemove : clientsToRemove) {
            removeFocusStackEntry(clientToRemove, false, true);
        }
    }

    private void dumpFocusStack(PrintWriter pw) {
        pw.println("\nAudio Focus stack entries (last is top of stack):");
        synchronized (mAudioFocusLock) {
            Iterator<FocusRequester> stackIterator = this.mFocusStack.iterator();
            while (stackIterator.hasNext()) {
                stackIterator.next().dump(pw);
            }
            pw.println("\n");
            if (this.mFocusPolicy == null) {
                pw.println("No external focus policy\n");
            } else {
                pw.println("External focus policy: " + this.mFocusPolicy + ", focus owners:\n");
                dumpExtFocusPolicyFocusOwners(pw);
            }
        }
        pw.println("\n");
        pw.println(" Notify on duck:  " + this.mNotifyFocusOwnerOnDuck + "\n");
        pw.println(" In ring or call: " + this.mRingOrCallActive + "\n");
    }

    @GuardedBy("mAudioFocusLock")
    private void removeFocusStackEntry(String clientToRemove, boolean signal, boolean notifyFocusFollowers) {
        if (!this.mFocusStack.empty() && this.mFocusStack.peek().hasSameClient(clientToRemove)) {
            FocusRequester fr = this.mFocusStack.pop();
            fr.release();
            if (notifyFocusFollowers) {
                AudioFocusInfo afi = fr.toAudioFocusInfo();
                afi.clearLossReceived();
                notifyExtPolicyFocusLoss_syncAf(afi, false);
            }
            if (signal) {
                notifyTopOfAudioFocusStack();
                return;
            }
            return;
        }
        Iterator<FocusRequester> stackIterator = this.mFocusStack.iterator();
        while (stackIterator.hasNext()) {
            FocusRequester fr2 = stackIterator.next();
            if (fr2.hasSameClient(clientToRemove)) {
                Log.i(TAG, "AudioFocus  removeFocusStackEntry(): removing entry for " + clientToRemove);
                stackIterator.remove();
                fr2.release();
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mAudioFocusLock")
    public void removeFocusStackEntryOnDeath(IBinder cb) {
        boolean isTopOfStackForClientToRemove = !this.mFocusStack.isEmpty() && this.mFocusStack.peek().hasSameBinder(cb);
        Iterator<FocusRequester> stackIterator = this.mFocusStack.iterator();
        while (stackIterator.hasNext()) {
            FocusRequester fr = stackIterator.next();
            if (fr.hasSameBinder(cb)) {
                Log.i(TAG, "AudioFocus  removeFocusStackEntryOnDeath(): removing entry for " + cb);
                stackIterator.remove();
                fr.release();
            }
        }
        if (isTopOfStackForClientToRemove) {
            notifyTopOfAudioFocusStack();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    @GuardedBy("mAudioFocusLock")
    public void removeFocusEntryForExtPolicy(IBinder cb) {
        if (this.mFocusOwnersForFocusPolicy.isEmpty()) {
            return;
        }
        Set<Map.Entry<String, FocusRequester>> owners = this.mFocusOwnersForFocusPolicy.entrySet();
        Iterator<Map.Entry<String, FocusRequester>> ownerIterator = owners.iterator();
        while (ownerIterator.hasNext()) {
            Map.Entry<String, FocusRequester> owner = ownerIterator.next();
            FocusRequester fr = owner.getValue();
            if (fr.hasSameBinder(cb)) {
                ownerIterator.remove();
                fr.release();
                notifyExtFocusPolicyFocusAbandon_syncAf(fr.toAudioFocusInfo());
                return;
            }
        }
    }

    private boolean canReassignAudioFocus() {
        if (!this.mFocusStack.isEmpty() && isLockedFocusOwner(this.mFocusStack.peek())) {
            return false;
        }
        return true;
    }

    private boolean isLockedFocusOwner(FocusRequester fr) {
        return fr.hasSameClient("AudioFocus_For_Phone_Ring_And_Calls") || fr.isLockedFocusOwner();
    }

    @GuardedBy("mAudioFocusLock")
    private int pushBelowLockedFocusOwners(FocusRequester nfr) {
        int lastLockedFocusOwnerIndex = this.mFocusStack.size();
        for (int index = this.mFocusStack.size() - 1; index >= 0; index--) {
            if (isLockedFocusOwner(this.mFocusStack.elementAt(index))) {
                lastLockedFocusOwnerIndex = index;
            }
        }
        if (lastLockedFocusOwnerIndex == this.mFocusStack.size()) {
            Log.e(TAG, "No exclusive focus owner found in propagateFocusLossFromGain_syncAf()", new Exception());
            propagateFocusLossFromGain_syncAf(nfr.getGainRequest(), nfr, false);
            this.mFocusStack.push(nfr);
            return 1;
        }
        this.mFocusStack.insertElementAt(nfr, lastLockedFocusOwnerIndex);
        return 2;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* loaded from: classes.dex */
    public class AudioFocusDeathHandler implements IBinder.DeathRecipient {
        private IBinder mCb;

        AudioFocusDeathHandler(IBinder cb) {
            this.mCb = cb;
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (MediaFocusControl.mAudioFocusLock) {
                if (MediaFocusControl.this.mFocusPolicy != null) {
                    MediaFocusControl.this.removeFocusEntryForExtPolicy(this.mCb);
                } else {
                    MediaFocusControl.this.removeFocusStackEntryOnDeath(this.mCb);
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void setDuckingInExtPolicyAvailable(boolean available) {
        this.mNotifyFocusOwnerOnDuck = !available;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean mustNotifyFocusOwnerOnDuck() {
        return this.mNotifyFocusOwnerOnDuck;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addFocusFollower(IAudioPolicyCallback ff) {
        if (ff == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            boolean found = false;
            Iterator<IAudioPolicyCallback> it = this.mFocusFollowers.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                IAudioPolicyCallback pcb = it.next();
                if (pcb.asBinder().equals(ff.asBinder())) {
                    found = true;
                    break;
                }
            }
            if (found) {
                return;
            }
            this.mFocusFollowers.add(ff);
            notifyExtPolicyCurrentFocusAsync(ff);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeFocusFollower(IAudioPolicyCallback ff) {
        if (ff == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            Iterator<IAudioPolicyCallback> it = this.mFocusFollowers.iterator();
            while (true) {
                if (!it.hasNext()) {
                    break;
                }
                IAudioPolicyCallback pcb = it.next();
                if (pcb.asBinder().equals(ff.asBinder())) {
                    this.mFocusFollowers.remove(pcb);
                    break;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFocusPolicy(IAudioPolicyCallback policy) {
        if (policy == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            this.mFocusPolicy = policy;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void unsetFocusPolicy(IAudioPolicyCallback policy) {
        if (policy == null) {
            return;
        }
        synchronized (mAudioFocusLock) {
            if (this.mFocusPolicy == policy) {
                this.mFocusPolicy = null;
            }
        }
    }

    void notifyExtPolicyCurrentFocusAsync(final IAudioPolicyCallback pcb) {
        Thread thread = new Thread() { // from class: com.android.server.audio.MediaFocusControl.1
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                synchronized (MediaFocusControl.mAudioFocusLock) {
                    if (MediaFocusControl.this.mFocusStack.isEmpty()) {
                        return;
                    }
                    try {
                        pcb.notifyAudioFocusGrant(((FocusRequester) MediaFocusControl.this.mFocusStack.peek()).toAudioFocusInfo(), 1);
                    } catch (RemoteException e) {
                        Log.e(MediaFocusControl.TAG, "Can't call notifyAudioFocusGrant() on IAudioPolicyCallback " + pcb.asBinder(), e);
                    }
                }
            }
        };
        thread.start();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyExtPolicyFocusGrant_syncAf(AudioFocusInfo afi, int requestResult) {
        Iterator<IAudioPolicyCallback> it = this.mFocusFollowers.iterator();
        while (it.hasNext()) {
            IAudioPolicyCallback pcb = it.next();
            try {
                pcb.notifyAudioFocusGrant(afi, requestResult);
            } catch (RemoteException e) {
                Log.e(TAG, "Can't call notifyAudioFocusGrant() on IAudioPolicyCallback " + pcb.asBinder(), e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyExtPolicyFocusLoss_syncAf(AudioFocusInfo afi, boolean wasDispatched) {
        Iterator<IAudioPolicyCallback> it = this.mFocusFollowers.iterator();
        while (it.hasNext()) {
            IAudioPolicyCallback pcb = it.next();
            try {
                pcb.notifyAudioFocusLoss(afi, wasDispatched);
            } catch (RemoteException e) {
                Log.e(TAG, "Can't call notifyAudioFocusLoss() on IAudioPolicyCallback " + pcb.asBinder(), e);
            }
        }
    }

    boolean notifyExtFocusPolicyFocusRequest_syncAf(AudioFocusInfo afi, IAudioFocusDispatcher fd, IBinder cb) {
        if (this.mFocusPolicy == null) {
            return false;
        }
        Log.v(TAG, "notifyExtFocusPolicyFocusRequest client=" + afi.getClientId() + " dispatcher=" + fd);
        synchronized (this.mExtFocusChangeLock) {
            long j = this.mExtFocusChangeCounter;
            this.mExtFocusChangeCounter = 1 + j;
            afi.setGen(j);
        }
        FocusRequester existingFr = this.mFocusOwnersForFocusPolicy.get(afi.getClientId());
        if (existingFr == null) {
            AudioFocusDeathHandler hdlr = new AudioFocusDeathHandler(cb);
            this.mFocusOwnersForFocusPolicy.put(afi.getClientId(), new FocusRequester(afi, fd, cb, hdlr, this));
        } else if (!existingFr.hasSameDispatcher(fd)) {
            existingFr.release();
            AudioFocusDeathHandler hdlr2 = new AudioFocusDeathHandler(cb);
            this.mFocusOwnersForFocusPolicy.put(afi.getClientId(), new FocusRequester(afi, fd, cb, hdlr2, this));
        }
        try {
            this.mFocusPolicy.notifyAudioFocusRequest(afi, 1);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call notifyAudioFocusRequest() on IAudioPolicyCallback " + this.mFocusPolicy.asBinder(), e);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setFocusRequestResultFromExtPolicy(AudioFocusInfo afi, int requestResult) {
        synchronized (this.mExtFocusChangeLock) {
            if (afi.getGen() > this.mExtFocusChangeCounter) {
                return;
            }
            FocusRequester fr = this.mFocusOwnersForFocusPolicy.get(afi.getClientId());
            if (fr != null) {
                fr.dispatchFocusResultFromExtPolicy(requestResult);
            }
        }
    }

    boolean notifyExtFocusPolicyFocusAbandon_syncAf(AudioFocusInfo afi) {
        if (this.mFocusPolicy == null) {
            return false;
        }
        FocusRequester fr = this.mFocusOwnersForFocusPolicy.remove(afi.getClientId());
        if (fr != null) {
            fr.release();
        }
        try {
            this.mFocusPolicy.notifyAudioFocusAbandon(afi);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Can't call notifyAudioFocusAbandon() on IAudioPolicyCallback " + this.mFocusPolicy.asBinder(), e);
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int dispatchFocusChange(AudioFocusInfo afi, int focusChange) {
        FocusRequester fr;
        Log.v(TAG, "dispatchFocusChange " + focusChange + " to afi client=" + afi.getClientId());
        synchronized (mAudioFocusLock) {
            if (this.mFocusPolicy == null) {
                Log.v(TAG, "> failed: no focus policy");
                return 0;
            }
            if (focusChange == -1) {
                fr = this.mFocusOwnersForFocusPolicy.remove(afi.getClientId());
            } else {
                fr = this.mFocusOwnersForFocusPolicy.get(afi.getClientId());
            }
            if (fr == null) {
                Log.v(TAG, "> failed: no such focus requester known");
                return 0;
            }
            return fr.dispatchFocusChange(focusChange);
        }
    }

    private void dumpExtFocusPolicyFocusOwners(PrintWriter pw) {
        Set<Map.Entry<String, FocusRequester>> owners = this.mFocusOwnersForFocusPolicy.entrySet();
        for (Map.Entry<String, FocusRequester> owner : owners) {
            FocusRequester fr = owner.getValue();
            fr.dump(pw);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public int getCurrentAudioFocus() {
        synchronized (mAudioFocusLock) {
            if (this.mFocusStack.empty()) {
                return 0;
            }
            return this.mFocusStack.peek().getGainRequest();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public String getCurrentAudioFocusPackageName() {
        synchronized (mAudioFocusLock) {
            if (this.mFocusStack.empty()) {
                return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
            }
            return this.mFocusStack.peek().getPackageName();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public String getLastAudioFocusPackageName() {
        String str;
        synchronized (mAudioFocusLock) {
            str = this.lastFocusAppName;
        }
        return str;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public List<String> getAudioFocusPackageNameList() {
        synchronized (mAudioFocusLock) {
            if (this.mFocusStack.empty()) {
                return null;
            }
            this.mAudioFocusPackageNameList.clear();
            for (int index = this.mFocusStack.size() - 1; index >= 0; index--) {
                this.mAudioFocusPackageNameList.add(this.mFocusStack.elementAt(index).getPackageName());
            }
            return this.mAudioFocusPackageNameList;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public AudioAttributes getCurrentAudioFocusAttributes() {
        synchronized (mAudioFocusLock) {
            if (this.mFocusStack.empty()) {
                return DEFAULT_ATTRIBUTES;
            }
            return this.mFocusStack.peek().getAudioAttributes();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public static int getFocusRampTimeMs(int focusGain, AudioAttributes attr) {
        switch (attr.getUsage()) {
            case 1:
            case 14:
                return 1000;
            case 2:
            case 3:
            case 5:
            case 7:
            case 8:
            case 9:
            case 10:
            case 13:
                return SystemService.PHASE_SYSTEM_SERVICES_READY;
            case 4:
            case 6:
            case 11:
            case 12:
            case 16:
                return 700;
            case 15:
            default:
                return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r2v10, types: [int, boolean] */
    /* JADX WARN: Type inference failed for: r2v21 */
    /* JADX WARN: Type inference failed for: r2v9 */
    public int requestAudioFocus(AudioAttributes aa, int focusChangeHint, IBinder cb, IAudioFocusDispatcher fd, String clientId, String callingPackageName, int flags, int sdk, boolean forceDuck) {
        int i;
        AudioFocusInfo afiForExtPolicy;
        ?? r2;
        mEventLogger.log(new AudioEventLogger.StringEvent("requestAudioFocus() from uid/pid " + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid() + " clientId=" + clientId + " callingPack=" + callingPackageName + " req=" + focusChangeHint + " flags=0x" + Integer.toHexString(flags) + " sdk=" + sdk).printLog(TAG));
        if (aa != null) {
            if (checkStreamCanPlay(AudioAttributes.toXpStreamType(aa)) == 0) {
                Log.e(TAG, "streamtype:" + AudioAttributes.toXpStreamType(aa) + "try to request audio focus failed: has higher streamytype playing.");
                return 0;
            } else if (checkStreamCanPlay(AudioAttributes.toXpStreamType(aa)) == 1) {
                Log.e(TAG, "streamtype:" + AudioAttributes.toXpStreamType(aa) + "try to request audio focus ok: in maindriver mode");
                return 1;
            }
        }
        if (!cb.pingBinder()) {
            Log.e(TAG, " AudioFocus DOA client for requestAudioFocus(), aborting.");
            return 0;
        } else if (this.mAppOps.noteOp(32, Binder.getCallingUid(), callingPackageName) != 0) {
            return 0;
        } else {
            synchronized (mAudioFocusLock) {
                try {
                    try {
                        if (this.mFocusStack.size() > 100) {
                            Log.e(TAG, "Max AudioFocus stack size reached, failing requestAudioFocus()");
                            return 0;
                        }
                        boolean enteringRingOrCall = (!this.mRingOrCallActive) & ("AudioFocus_For_Phone_Ring_And_Calls".compareTo(clientId) == 0);
                        if (enteringRingOrCall) {
                            this.mRingOrCallActive = true;
                        }
                        if (this.mFocusPolicy != null) {
                            i = 100;
                            afiForExtPolicy = new AudioFocusInfo(aa, Binder.getCallingUid(), clientId, callingPackageName, focusChangeHint, 0, flags, sdk);
                        } else {
                            i = 100;
                            afiForExtPolicy = null;
                        }
                        AudioFocusInfo afiForExtPolicy2 = afiForExtPolicy;
                        boolean focusGrantDelayed = false;
                        if (!canReassignAudioFocus()) {
                            if ((flags & 1) == 0) {
                                return 0;
                            }
                            r2 = 0;
                            focusGrantDelayed = true;
                        } else {
                            r2 = 0;
                        }
                        boolean focusGrantDelayed2 = focusGrantDelayed;
                        if (notifyExtFocusPolicyFocusRequest_syncAf(afiForExtPolicy2, fd, cb)) {
                            return i;
                        }
                        AudioFocusDeathHandler afdh = new AudioFocusDeathHandler(cb);
                        try {
                            try {
                                try {
                                    cb.linkToDeath(afdh, r2);
                                    if (!this.mFocusStack.empty() && this.mFocusStack.peek().hasSameClient(clientId)) {
                                        FocusRequester fr = this.mFocusStack.peek();
                                        if (fr.getGainRequest() == focusChangeHint && fr.getGrantFlags() == flags) {
                                            cb.unlinkToDeath(afdh, r2);
                                            notifyExtPolicyFocusGrant_syncAf(fr.toAudioFocusInfo(), 1);
                                            return 1;
                                        } else if (!focusGrantDelayed2) {
                                            this.mFocusStack.pop();
                                            fr.release();
                                        }
                                    }
                                    removeFocusStackEntry(clientId, r2, r2);
                                    try {
                                        FocusRequester nfr = new FocusRequester(aa, focusChangeHint, flags, fd, cb, clientId, afdh, callingPackageName, Binder.getCallingUid(), this, sdk);
                                        if (focusGrantDelayed2) {
                                            int requestResult = pushBelowLockedFocusOwners(nfr);
                                            if (requestResult != 0) {
                                                notifyExtPolicyFocusGrant_syncAf(nfr.toAudioFocusInfo(), requestResult);
                                            }
                                            return requestResult;
                                        }
                                        if (!this.mFocusStack.empty()) {
                                            propagateFocusLossFromGain_syncAf(focusChangeHint, nfr, forceDuck);
                                        }
                                        this.mFocusStack.push(nfr);
                                        Log.d(TAG, "requestAudioFocus mFocusStack push :" + nfr.getAudioAttributes().getUsage() + " statcSize:" + this.mFocusStack.size());
                                        nfr.handleFocusGainFromRequest(1);
                                        notifyExtPolicyFocusGrant_syncAf(nfr.toAudioFocusInfo(), 1);
                                        if (true & enteringRingOrCall) {
                                            runAudioCheckerForRingOrCallAsync(true);
                                        }
                                        this.lastFocusAppName = callingPackageName;
                                        return 1;
                                    } catch (Throwable th) {
                                        e = th;
                                        throw e;
                                    }
                                } catch (RemoteException e) {
                                    StringBuilder sb = new StringBuilder();
                                    sb.append("AudioFocus  requestAudioFocus() could not link to ");
                                    sb.append(cb);
                                    sb.append(" binder death");
                                    Log.w(TAG, sb.toString());
                                    return r2;
                                }
                            } catch (Throwable th2) {
                                e = th2;
                            }
                        } catch (Throwable th3) {
                            e = th3;
                        }
                    } catch (Throwable th4) {
                        e = th4;
                    }
                } catch (Throwable th5) {
                    e = th5;
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public int abandonAudioFocus(IAudioFocusDispatcher fl, String clientId, AudioAttributes aa, String callingPackageName) {
        AudioEventLogger audioEventLogger = mEventLogger;
        audioEventLogger.log(new AudioEventLogger.StringEvent("abandonAudioFocus() from uid/pid " + Binder.getCallingUid() + SliceClientPermissions.SliceAuthority.DELIMITER + Binder.getCallingPid() + " clientId=" + clientId).printLog(TAG));
        try {
        } catch (ConcurrentModificationException cme) {
            Log.e(TAG, "FATAL EXCEPTION AudioFocus  abandonAudioFocus() caused " + cme);
            cme.printStackTrace();
        }
        synchronized (mAudioFocusLock) {
            if (this.mFocusPolicy != null) {
                AudioFocusInfo afi = new AudioFocusInfo(aa, Binder.getCallingUid(), clientId, callingPackageName, 0, 0, 0, 0);
                if (notifyExtFocusPolicyFocusAbandon_syncAf(afi)) {
                    return 1;
                }
            }
            boolean exitingRingOrCall = this.mRingOrCallActive & ("AudioFocus_For_Phone_Ring_And_Calls".compareTo(clientId) == 0);
            if (exitingRingOrCall) {
                this.mRingOrCallActive = false;
            }
            removeFocusStackEntry(clientId, true, true);
            Log.v(TAG, "abandonAudioFocus()  statcSize:" + this.mFocusStack.size());
            if (true & exitingRingOrCall) {
                runAudioCheckerForRingOrCallAsync(false);
            }
            return 1;
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void unregisterAudioFocusClient(String clientId) {
        synchronized (mAudioFocusLock) {
            removeFocusStackEntry(clientId, false, true);
        }
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.android.server.audio.MediaFocusControl$2] */
    private void runAudioCheckerForRingOrCallAsync(final boolean enteringRingOrCall) {
        new Thread() { // from class: com.android.server.audio.MediaFocusControl.2
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                if (enteringRingOrCall) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                synchronized (MediaFocusControl.mAudioFocusLock) {
                    if (MediaFocusControl.this.mRingOrCallActive) {
                        MediaFocusControl.this.mFocusEnforcer.mutePlayersForCall(MediaFocusControl.USAGES_TO_MUTE_IN_RING_OR_CALL);
                    } else {
                        MediaFocusControl.this.mFocusEnforcer.unmutePlayersForCall();
                    }
                }
            }
        }.start();
    }
}
