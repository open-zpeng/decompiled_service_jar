package com.android.server.am;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.BidiFormatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class AppErrorDialog extends BaseErrorDialog implements View.OnClickListener {
    static final int APP_INFO = 8;
    static final int CANCEL = 7;
    static final long DISMISS_TIMEOUT = 300000;
    static final int FORCE_QUIT = 1;
    static final int FORCE_QUIT_AND_REPORT = 2;
    static final int MUTE = 5;
    static final int RESTART = 3;
    static final int TIMEOUT = 6;
    private final Handler mHandler;
    private final boolean mIsRestartable;
    private final ProcessRecord mProc;
    private final BroadcastReceiver mReceiver;
    private final AppErrorResult mResult;
    private final ActivityManagerService mService;
    static int CANT_SHOW = -1;
    static int BACKGROUND_USER = -2;
    static int ALREADY_SHOWING = -3;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class Data {
        boolean isRestartableForService;
        ProcessRecord proc;
        boolean repeating;
        AppErrorResult result;
        int taskId;
    }

    public AppErrorDialog(Context context, ActivityManagerService service, Data data) {
        super(context);
        CharSequence name;
        this.mHandler = new Handler() { // from class: com.android.server.am.AppErrorDialog.1
            @Override // android.os.Handler
            public void handleMessage(Message msg) {
                AppErrorDialog.this.setResult(msg.what);
                AppErrorDialog.this.dismiss();
            }
        };
        this.mReceiver = new BroadcastReceiver() { // from class: com.android.server.am.AppErrorDialog.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                if ("android.intent.action.CLOSE_SYSTEM_DIALOGS".equals(intent.getAction())) {
                    AppErrorDialog.this.cancel();
                }
            }
        };
        Resources res = context.getResources();
        this.mService = service;
        this.mProc = data.proc;
        this.mResult = data.result;
        this.mIsRestartable = (data.taskId != -1 || data.isRestartableForService) && Settings.Global.getInt(context.getContentResolver(), "show_restart_in_crash_dialog", 0) != 0;
        BidiFormatter bidi = BidiFormatter.getInstance();
        if (this.mProc.pkgList.size() == 1 && (name = context.getPackageManager().getApplicationLabel(this.mProc.info)) != null) {
            setTitle(res.getString(data.repeating ? 17039471 : 17039470, bidi.unicodeWrap(name.toString()), bidi.unicodeWrap(this.mProc.info.processName)));
        } else {
            CharSequence name2 = this.mProc.processName;
            setTitle(res.getString(data.repeating ? 17039476 : 17039475, bidi.unicodeWrap(name2.toString())));
        }
        setCancelable(true);
        setCancelMessage(this.mHandler.obtainMessage(7));
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Application Error: " + this.mProc.info.processName);
        attrs.privateFlags = attrs.privateFlags | 272;
        getWindow().setAttributes(attrs);
        if (this.mProc.isPersistent()) {
            getWindow().setType(2010);
        }
        Handler handler = this.mHandler;
        handler.sendMessageDelayed(handler.obtainMessage(6), 300000L);
    }

    @Override // android.app.AlertDialog, android.app.Dialog
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout frame = (FrameLayout) findViewById(16908331);
        Context context = getContext();
        boolean showMute = true;
        LayoutInflater.from(context).inflate(17367093, (ViewGroup) frame, true);
        boolean hasReceiver = this.mProc.errorReportReceiver != null;
        TextView restart = (TextView) findViewById(16908806);
        restart.setOnClickListener(this);
        restart.setVisibility(this.mIsRestartable ? 0 : 8);
        TextView report = (TextView) findViewById(16908805);
        report.setOnClickListener(this);
        report.setVisibility(hasReceiver ? 0 : 8);
        TextView close = (TextView) findViewById(16908803);
        close.setOnClickListener(this);
        TextView appInfo = (TextView) findViewById(16908802);
        appInfo.setOnClickListener(this);
        if (Build.IS_USER || Settings.Global.getInt(context.getContentResolver(), "development_settings_enabled", 0) == 0 || Settings.Global.getInt(context.getContentResolver(), "show_mute_in_crash_dialog", 0) == 0) {
            showMute = false;
        }
        TextView mute = (TextView) findViewById(16908804);
        mute.setOnClickListener(this);
        mute.setVisibility(showMute ? 0 : 8);
        findViewById(16908953).setVisibility(0);
    }

    @Override // com.android.server.am.BaseErrorDialog, android.app.Dialog
    public void onStart() {
        super.onStart();
        getContext().registerReceiver(this.mReceiver, new IntentFilter("android.intent.action.CLOSE_SYSTEM_DIALOGS"));
    }

    @Override // android.app.Dialog
    protected void onStop() {
        super.onStop();
        getContext().unregisterReceiver(this.mReceiver);
    }

    @Override // android.app.Dialog, android.content.DialogInterface
    public void dismiss() {
        if (!this.mResult.mHasResult) {
            setResult(1);
        }
        super.dismiss();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setResult(int result) {
        synchronized (this.mService) {
            try {
                ActivityManagerService.boostPriorityForLockedSection();
                if (this.mProc != null && this.mProc.crashDialog == this) {
                    this.mProc.crashDialog = null;
                }
            } catch (Throwable th) {
                ActivityManagerService.resetPriorityAfterLockedSection();
                throw th;
            }
        }
        ActivityManagerService.resetPriorityAfterLockedSection();
        this.mResult.set(result);
        this.mHandler.removeMessages(6);
    }

    @Override // android.view.View.OnClickListener
    public void onClick(View v) {
        switch (v.getId()) {
            case 16908802:
                this.mHandler.obtainMessage(8).sendToTarget();
                return;
            case 16908803:
                this.mHandler.obtainMessage(1).sendToTarget();
                return;
            case 16908804:
                this.mHandler.obtainMessage(5).sendToTarget();
                return;
            case 16908805:
                this.mHandler.obtainMessage(2).sendToTarget();
                return;
            case 16908806:
                this.mHandler.obtainMessage(3).sendToTarget();
                return;
            default:
                return;
        }
    }
}
