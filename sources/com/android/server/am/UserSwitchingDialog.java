package com.android.server.am;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.os.UserManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;
import com.android.internal.annotations.GuardedBy;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class UserSwitchingDialog extends AlertDialog implements ViewTreeObserver.OnWindowShownListener {
    private static final int MSG_START_USER = 1;
    private static final String TAG = "ActivityManagerUserSwitchingDialog";
    private static final int WINDOW_SHOWN_TIMEOUT_MS = 3000;
    protected final Context mContext;
    private final Handler mHandler;
    protected final UserInfo mNewUser;
    protected final UserInfo mOldUser;
    private final ActivityManagerService mService;
    @GuardedBy("this")
    private boolean mStartedUser;
    private final String mSwitchingFromSystemUserMessage;
    private final String mSwitchingToSystemUserMessage;
    private final int mUserId;

    public UserSwitchingDialog(ActivityManagerService service, Context context, UserInfo oldUser, UserInfo newUser, boolean aboveSystem, String switchingFromSystemUserMessage, String switchingToSystemUserMessage) {
        super(context);
        this.mHandler = new Handler() { // from class: com.android.server.am.UserSwitchingDialog.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                if (msg.what == 1) {
                    UserSwitchingDialog.this.startUser();
                }
            }
        };
        this.mContext = context;
        this.mService = service;
        this.mUserId = newUser.id;
        this.mOldUser = oldUser;
        this.mNewUser = newUser;
        this.mSwitchingFromSystemUserMessage = switchingFromSystemUserMessage;
        this.mSwitchingToSystemUserMessage = switchingToSystemUserMessage;
        inflateContent();
        if (aboveSystem) {
            getWindow().setType(2010);
        }
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.privateFlags = 272;
        getWindow().setAttributes(attrs);
    }

    void inflateContent() {
        String viewMessage;
        setCancelable(false);
        Resources res = getContext().getResources();
        View view = LayoutInflater.from(getContext()).inflate(17367323, (ViewGroup) null);
        String viewMessage2 = null;
        if (UserManager.isSplitSystemUser() && this.mNewUser.id == 0) {
            viewMessage = res.getString(17041034, this.mOldUser.name);
        } else if (UserManager.isDeviceInDemoMode(this.mContext)) {
            if (this.mOldUser.isDemo()) {
                viewMessage = res.getString(17039802);
            } else {
                viewMessage = res.getString(17039803);
            }
        } else {
            if (this.mOldUser.id == 0) {
                viewMessage2 = this.mSwitchingFromSystemUserMessage;
            } else if (this.mNewUser.id == 0) {
                viewMessage2 = this.mSwitchingToSystemUserMessage;
            }
            if (viewMessage2 == null) {
                viewMessage = res.getString(17041037, this.mNewUser.name);
            } else {
                viewMessage = viewMessage2;
            }
        }
        ((TextView) view.findViewById(16908299)).setText(viewMessage);
        setView(view);
    }

    @Override // android.app.Dialog
    public void show() {
        super.show();
        View decorView = getWindow().getDecorView();
        if (decorView != null) {
            decorView.getViewTreeObserver().addOnWindowShownListener(this);
        }
        this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(1), 3000L);
    }

    public void onWindowShown() {
        startUser();
    }

    void startUser() {
        synchronized (this) {
            if (!this.mStartedUser) {
                this.mService.mUserController.startUserInForeground(this.mUserId);
                dismiss();
                this.mStartedUser = true;
                View decorView = getWindow().getDecorView();
                if (decorView != null) {
                    decorView.getViewTreeObserver().removeOnWindowShownListener(this);
                }
                this.mHandler.removeMessages(1);
            }
        }
    }
}
