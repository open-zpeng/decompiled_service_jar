package com.android.server.am;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class AppNotRespondingDialog extends BaseErrorDialog implements View.OnClickListener {
    public static final int ALREADY_SHOWING = -2;
    public static final int CANT_SHOW = -1;
    static final int FORCE_CLOSE = 1;
    private static final String TAG = "AppNotRespondingDialog";
    static final int WAIT = 2;
    static final int WAIT_AND_REPORT = 3;
    private final Handler mHandler;
    private final ProcessRecord mProc;
    private final ActivityManagerService mService;

    /* JADX WARN: Removed duplicated region for block: B:18:0x006a  */
    /* JADX WARN: Removed duplicated region for block: B:19:0x0086  */
    /* JADX WARN: Removed duplicated region for block: B:22:0x009d  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public AppNotRespondingDialog(com.android.server.am.ActivityManagerService r10, android.content.Context r11, com.android.server.am.AppNotRespondingDialog.Data r12) {
        /*
            r9 = this;
            r9.<init>(r11)
            com.android.server.am.AppNotRespondingDialog$1 r0 = new com.android.server.am.AppNotRespondingDialog$1
            r0.<init>()
            r9.mHandler = r0
            r9.mService = r10
            com.android.server.am.ProcessRecord r0 = r12.proc
            r9.mProc = r0
            android.content.res.Resources r0 = r11.getResources()
            r1 = 0
            r9.setCancelable(r1)
            android.content.pm.ApplicationInfo r2 = r12.aInfo
            if (r2 == 0) goto L27
            android.content.pm.ApplicationInfo r2 = r12.aInfo
            android.content.pm.PackageManager r3 = r11.getPackageManager()
            java.lang.CharSequence r2 = r2.loadLabel(r3)
            goto L28
        L27:
            r2 = 0
        L28:
            r3 = 0
            com.android.server.am.ProcessRecord r4 = r9.mProc
            com.android.server.am.ProcessRecord$PackageList r4 = r4.pkgList
            int r4 = r4.size()
            r5 = 1
            if (r4 != r5) goto L53
            android.content.pm.PackageManager r4 = r11.getPackageManager()
            com.android.server.am.ProcessRecord r6 = r9.mProc
            android.content.pm.ApplicationInfo r6 = r6.info
            java.lang.CharSequence r4 = r4.getApplicationLabel(r6)
            r3 = r4
            if (r4 == 0) goto L53
            if (r2 == 0) goto L4a
            r4 = 17039497(0x1040089, float:2.4244955E-38)
            goto L64
        L4a:
            r2 = r3
            com.android.server.am.ProcessRecord r4 = r9.mProc
            java.lang.String r3 = r4.processName
            r4 = 17039499(0x104008b, float:2.424496E-38)
            goto L64
        L53:
            if (r2 == 0) goto L5d
            com.android.server.am.ProcessRecord r4 = r9.mProc
            java.lang.String r3 = r4.processName
            r4 = 17039498(0x104008a, float:2.4244958E-38)
            goto L64
        L5d:
            com.android.server.am.ProcessRecord r4 = r9.mProc
            java.lang.String r2 = r4.processName
            r4 = 17039500(0x104008c, float:2.4244963E-38)
        L64:
            android.text.BidiFormatter r6 = android.text.BidiFormatter.getInstance()
            if (r3 == 0) goto L86
            r7 = 2
            java.lang.Object[] r7 = new java.lang.Object[r7]
            java.lang.String r8 = r2.toString()
            java.lang.String r8 = r6.unicodeWrap(r8)
            r7[r1] = r8
            java.lang.String r1 = r3.toString()
            java.lang.String r1 = r6.unicodeWrap(r1)
            r7[r5] = r1
            java.lang.String r1 = r0.getString(r4, r7)
            goto L96
        L86:
            java.lang.Object[] r5 = new java.lang.Object[r5]
            java.lang.String r7 = r2.toString()
            java.lang.String r7 = r6.unicodeWrap(r7)
            r5[r1] = r7
            java.lang.String r1 = r0.getString(r4, r5)
        L96:
            r9.setTitle(r1)
            boolean r1 = r12.aboveSystem
            if (r1 == 0) goto La6
            android.view.Window r1 = r9.getWindow()
            r5 = 2010(0x7da, float:2.817E-42)
            r1.setType(r5)
        La6:
            android.view.Window r1 = r9.getWindow()
            android.view.WindowManager$LayoutParams r1 = r1.getAttributes()
            java.lang.StringBuilder r5 = new java.lang.StringBuilder
            r5.<init>()
            java.lang.String r7 = "Application Not Responding: "
            r5.append(r7)
            com.android.server.am.ProcessRecord r7 = r9.mProc
            android.content.pm.ApplicationInfo r7 = r7.info
            java.lang.String r7 = r7.processName
            r5.append(r7)
            java.lang.String r5 = r5.toString()
            r1.setTitle(r5)
            r5 = 272(0x110, float:3.81E-43)
            r1.privateFlags = r5
            android.view.Window r5 = r9.getWindow()
            r5.setAttributes(r1)
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.am.AppNotRespondingDialog.<init>(com.android.server.am.ActivityManagerService, android.content.Context, com.android.server.am.AppNotRespondingDialog$Data):void");
    }

    @Override // android.app.AlertDialog, android.app.Dialog
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout frame = (FrameLayout) findViewById(16908331);
        Context context = getContext();
        LayoutInflater.from(context).inflate(17367092, (ViewGroup) frame, true);
        TextView report = (TextView) findViewById(16908805);
        report.setOnClickListener(this);
        boolean hasReceiver = this.mProc.errorReportReceiver != null;
        report.setVisibility(hasReceiver ? 0 : 8);
        TextView close = (TextView) findViewById(16908803);
        close.setOnClickListener(this);
        TextView wait = (TextView) findViewById(16908807);
        wait.setOnClickListener(this);
        findViewById(16908953).setVisibility(0);
    }

    @Override // android.view.View.OnClickListener
    public void onClick(View v) {
        int id = v.getId();
        if (id == 16908803) {
            this.mHandler.obtainMessage(1).sendToTarget();
        } else if (id == 16908805) {
            this.mHandler.obtainMessage(3).sendToTarget();
        } else if (id == 16908807) {
            this.mHandler.obtainMessage(2).sendToTarget();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class Data {
        final ApplicationInfo aInfo;
        final boolean aboveSystem;
        final ProcessRecord proc;

        /* JADX INFO: Access modifiers changed from: package-private */
        public Data(ProcessRecord proc, ApplicationInfo aInfo, boolean aboveSystem) {
            this.proc = proc;
            this.aInfo = aInfo;
            this.aboveSystem = aboveSystem;
        }
    }
}
