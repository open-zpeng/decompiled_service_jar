package com.android.server.wm;

import android.app.Dialog;
import android.content.Context;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.TextView;

/* loaded from: classes2.dex */
public final class LaunchWarningWindow extends Dialog {
    public LaunchWarningWindow(Context context, ActivityRecord cur, ActivityRecord next) {
        super(context, 16974908);
        requestWindowFeature(3);
        getWindow().setType(2003);
        getWindow().addFlags(24);
        setContentView(17367179);
        setTitle(context.getText(17040242));
        TypedValue out = new TypedValue();
        getContext().getTheme().resolveAttribute(16843605, out, true);
        getWindow().setFeatureDrawableResource(3, out.resourceId);
        ImageView icon = (ImageView) findViewById(16909383);
        icon.setImageDrawable(next.info.applicationInfo.loadIcon(context.getPackageManager()));
        TextView text = (TextView) findViewById(16909384);
        text.setText(context.getResources().getString(17040241, next.info.applicationInfo.loadLabel(context.getPackageManager()).toString()));
        ImageView icon2 = (ImageView) findViewById(16909301);
        icon2.setImageDrawable(cur.info.applicationInfo.loadIcon(context.getPackageManager()));
        TextView text2 = (TextView) findViewById(16909302);
        text2.setText(context.getResources().getString(17040240, cur.info.applicationInfo.loadLabel(context.getPackageManager()).toString()));
    }
}
