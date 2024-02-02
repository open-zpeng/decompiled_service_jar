package com.android.server.input;

import android.app.IInputForwarder;
import android.hardware.input.InputManagerInternal;
import android.view.InputEvent;
import com.android.server.LocalServices;
/* loaded from: classes.dex */
class InputForwarder extends IInputForwarder.Stub {
    private final int mDisplayId;
    private final InputManagerInternal mInputManagerInternal = (InputManagerInternal) LocalServices.getService(InputManagerInternal.class);

    /* JADX INFO: Access modifiers changed from: package-private */
    public InputForwarder(int displayId) {
        this.mDisplayId = displayId;
    }

    public boolean forwardEvent(InputEvent event) {
        return this.mInputManagerInternal.injectInputEvent(event, this.mDisplayId, 0);
    }
}
