package com.android.server.am;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.WindowManager;
/* loaded from: classes.dex */
final class FactoryErrorDialog extends BaseErrorDialog {
    private final Handler mHandler;

    public FactoryErrorDialog(Context context, CharSequence msg) {
        super(context);
        this.mHandler = new Handler() { // from class: com.android.server.am.FactoryErrorDialog.1
            @Override // android.os.Handler
            public void handleMessage(Message msg2) {
                throw new RuntimeException("Rebooting from failed factory test");
            }
        };
        setCancelable(false);
        setTitle(context.getText(17039898));
        setMessage(msg);
        setButton(-1, context.getText(17039901), this.mHandler.obtainMessage(0));
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle("Factory Error");
        getWindow().setAttributes(attrs);
    }

    @Override // android.app.Dialog
    public void onStop() {
    }
}
