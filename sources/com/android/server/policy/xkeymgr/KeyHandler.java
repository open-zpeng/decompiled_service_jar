package com.android.server.policy.xkeymgr;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.view.KeyEvent;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.usb.descriptors.UsbTerminalTypes;
/* loaded from: classes.dex */
public abstract class KeyHandler {
    protected static final boolean DEBUG = SystemProperties.getBoolean("persist.xp.input.logger", false);

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean checkKeyIgnored(int key, boolean down) {
        return false;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean handleKeyBeforeDispatching(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        return false;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean handleUnhandleKeyAfterDispatching(WindowManagerPolicy.WindowState win, KeyEvent event, int policyFlags) {
        return false;
    }

    public void dispatchKey(int keyCode) {
        sendDownAndUpKeyEvents(keyCode);
    }

    private void sendDownAndUpKeyEvents(int keyCode) {
        long downTime = SystemClock.uptimeMillis();
        KeyEvent down = KeyEvent.obtain(downTime, downTime, 0, keyCode, 0, 0, -1, 0, 8, UsbTerminalTypes.TERMINAL_USB_STREAMING, null);
        InputManager.getInstance().injectInputEvent(down, 0);
        down.recycle();
        long upTime = downTime + 100;
        KeyEvent up = KeyEvent.obtain(downTime, upTime, 1, keyCode, 0, 0, -1, 0, 8, UsbTerminalTypes.TERMINAL_USB_STREAMING, null);
        InputManager.getInstance().injectInputEvent(up, 0);
        up.recycle();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public boolean shouldDispatchMediaKeyByCommands() {
        return true;
    }
}
