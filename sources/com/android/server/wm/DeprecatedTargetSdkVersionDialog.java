package com.android.server.wm;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.Window;
import com.android.server.utils.AppInstallerUtil;

/* loaded from: classes2.dex */
public class DeprecatedTargetSdkVersionDialog {
    private static final String TAG = "ActivityTaskManager";
    private final AlertDialog mDialog;
    private final String mPackageName;

    public DeprecatedTargetSdkVersionDialog(final AppWarnings manager, final Context context, ApplicationInfo appInfo) {
        this.mPackageName = appInfo.packageName;
        PackageManager pm = context.getPackageManager();
        CharSequence label = appInfo.loadSafeLabel(pm, 500.0f, 5);
        CharSequence message = context.getString(17039869);
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setPositiveButton(17039370, new DialogInterface.OnClickListener() { // from class: com.android.server.wm.-$$Lambda$DeprecatedTargetSdkVersionDialog$TaeLH3pyy18K9h_WuSYLeQFy9Io
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                DeprecatedTargetSdkVersionDialog.this.lambda$new$0$DeprecatedTargetSdkVersionDialog(manager, dialogInterface, i);
            }
        }).setMessage(message).setTitle(label);
        final Intent installerIntent = AppInstallerUtil.createIntent(context, appInfo.packageName);
        if (installerIntent != null) {
            builder.setNeutralButton(17039868, new DialogInterface.OnClickListener() { // from class: com.android.server.wm.-$$Lambda$DeprecatedTargetSdkVersionDialog$ZkWArfvd086vsF78_zwSd67uSUs
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int i) {
                    context.startActivity(installerIntent);
                }
            });
        }
        this.mDialog = builder.create();
        this.mDialog.create();
        Window window = this.mDialog.getWindow();
        window.setType(2002);
        window.getAttributes().setTitle("DeprecatedTargetSdkVersionDialog");
    }

    public /* synthetic */ void lambda$new$0$DeprecatedTargetSdkVersionDialog(AppWarnings manager, DialogInterface dialog, int which) {
        manager.setPackageFlag(this.mPackageName, 4, true);
    }

    public String getPackageName() {
        return this.mPackageName;
    }

    public void show() {
        Log.w(TAG, "Showing SDK deprecation warning for package " + this.mPackageName);
        this.mDialog.show();
    }

    public void dismiss() {
        this.mDialog.dismiss();
    }
}
