package com.android.server.wm;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.animation.Animation;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class WallpaperWindowToken extends WindowToken {
    private static final String TAG = "WindowManager";

    /* JADX INFO: Access modifiers changed from: package-private */
    public WallpaperWindowToken(WindowManagerService service, IBinder token, boolean explicit, DisplayContent dc, boolean ownerCanManageAppTokens) {
        super(service, token, 2013, explicit, dc, ownerCanManageAppTokens);
        dc.mWallpaperController.addWallpaperToken(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.WindowToken
    public void setExiting() {
        super.setExiting();
        this.mDisplayContent.mWallpaperController.removeWallpaperToken(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void hideWallpaperToken(boolean wasDeferred, String reason) {
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            WindowState wallpaper = (WindowState) this.mChildren.get(j);
            wallpaper.hideWallpaperWindow(wasDeferred, reason);
        }
        setHidden(true);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendWindowWallpaperCommand(String action, int x, int y, int z, Bundle extras, boolean sync) {
        for (int wallpaperNdx = this.mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            WindowState wallpaper = (WindowState) this.mChildren.get(wallpaperNdx);
            try {
                wallpaper.mClient.dispatchWallpaperCommand(action, x, y, z, extras, sync);
                sync = false;
            } catch (RemoteException e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateWallpaperOffset(int dw, int dh, boolean sync) {
        WallpaperController wallpaperController = this.mDisplayContent.mWallpaperController;
        for (int wallpaperNdx = this.mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            WindowState wallpaper = (WindowState) this.mChildren.get(wallpaperNdx);
            if (wallpaperController.updateWallpaperOffset(wallpaper, dw, dh, sync)) {
                sync = false;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateWallpaperVisibility(boolean visible) {
        DisplayInfo displayInfo = this.mDisplayContent.getDisplayInfo();
        int dw = displayInfo.logicalWidth;
        int dh = displayInfo.logicalHeight;
        if (isHidden() == visible) {
            setHidden(!visible);
            this.mDisplayContent.setLayoutNeeded();
        }
        WallpaperController wallpaperController = this.mDisplayContent.mWallpaperController;
        for (int wallpaperNdx = this.mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            WindowState wallpaper = (WindowState) this.mChildren.get(wallpaperNdx);
            if (visible) {
                wallpaperController.updateWallpaperOffset(wallpaper, dw, dh, false);
            }
            wallpaper.dispatchWallpaperVisibility(visible);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startAnimation(Animation anim) {
        for (int ndx = this.mChildren.size() - 1; ndx >= 0; ndx--) {
            WindowState windowState = (WindowState) this.mChildren.get(ndx);
            windowState.startAnimation(anim);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateWallpaperWindows(boolean visible) {
        if (isHidden() == visible) {
            if (WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
                StringBuilder sb = new StringBuilder();
                sb.append("Wallpaper token ");
                sb.append(this.token);
                sb.append(" hidden=");
                sb.append(!visible);
                Slog.d(TAG, sb.toString());
            }
            setHidden(!visible);
            this.mDisplayContent.setLayoutNeeded();
        }
        DisplayInfo displayInfo = this.mDisplayContent.getDisplayInfo();
        int dw = displayInfo.logicalWidth;
        int dh = displayInfo.logicalHeight;
        WallpaperController wallpaperController = this.mDisplayContent.mWallpaperController;
        for (int wallpaperNdx = this.mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            WindowState wallpaper = (WindowState) this.mChildren.get(wallpaperNdx);
            if (visible) {
                wallpaperController.updateWallpaperOffset(wallpaper, dw, dh, false);
            }
            wallpaper.dispatchWallpaperVisibility(visible);
            if (WindowManagerDebugConfig.DEBUG_LAYERS || WindowManagerDebugConfig.DEBUG_WALLPAPER_LIGHT) {
                Slog.v(TAG, "adjustWallpaper win " + wallpaper + " anim layer: " + wallpaper.mWinAnimator.mAnimLayer);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasVisibleNotDrawnWallpaper() {
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            WindowState wallpaper = (WindowState) this.mChildren.get(j);
            if (wallpaper.hasVisibleNotDrawnWallpaper()) {
                return true;
            }
        }
        return false;
    }

    @Override // com.android.server.wm.WindowToken
    public String toString() {
        if (this.stringName == null) {
            this.stringName = "WallpaperWindowToken{" + Integer.toHexString(System.identityHashCode(this)) + " token=" + this.token + '}';
        }
        return this.stringName;
    }
}
