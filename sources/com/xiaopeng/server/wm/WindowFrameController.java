package com.xiaopeng.server.wm;

import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import com.android.server.wm.DisplayFrames;
import com.android.server.wm.SharedDisplayContainer;
import com.xiaopeng.view.SharedDisplayManager;
import com.xiaopeng.view.WindowFrameModel;
import com.xiaopeng.view.xpWindowManager;
import java.util.HashMap;

/* loaded from: classes2.dex */
public class WindowFrameController {
    public static final int FLAG_RECT_BOTTOM = 8;
    public static final int FLAG_RECT_LEFT = 1;
    public static final int FLAG_RECT_RIGHT = 4;
    public static final int FLAG_RECT_TOP = 2;
    private static final String TAG = "WindowFrameController";
    private static final HashMap<Integer, Rect> sWindowBounds = new HashMap<>();
    private static final HashMap<Integer, Rect> sDisplayBounds = new HashMap<>();
    private static volatile String sResizePackages = null;
    private static WindowFrameController sController = null;

    private WindowFrameController() {
    }

    public static WindowFrameController get() {
        if (sController == null) {
            synchronized (xpWindowManagerService.class) {
                if (sController == null) {
                    sController = new WindowFrameController();
                }
            }
        }
        return sController;
    }

    public static void setResizePackage(String packageName) {
        sResizePackages = packageName;
    }

    public static boolean isResizingPackage(WindowManager.LayoutParams lp) {
        return (lp == null || TextUtils.isEmpty(lp.packageName) || !lp.packageName.equals(sResizePackages)) ? false : true;
    }

    public void setWindowBounds(HashMap<Integer, Rect> bounds) {
        sWindowBounds.putAll(bounds);
    }

    public static Rect getWindowFrame(int type) {
        return sWindowBounds.getOrDefault(Integer.valueOf(type), null);
    }

    public static WindowFrameModel getWindowFrame(WindowManager.LayoutParams lp) {
        return createWindowFrame(lp);
    }

    private static WindowFrameModel createWindowFrame(WindowManager.LayoutParams lp) {
        if (lp == null) {
            return null;
        }
        int type = lp.type;
        int sharedId = lp.sharedId;
        int displayId = lp.displayId == -1 ? 0 : lp.displayId;
        int activityFlags = lp.intentFlags;
        int systemUiVisibility = lp.systemUiVisibility | lp.subtreeSystemUiVisibility;
        boolean panel = (activityFlags & 256) == 256;
        boolean fullscreenWindow = xpWindowManager.isFullscreen(systemUiVisibility, lp.flags, lp.xpFlags);
        boolean fullscreenActivity = (activityFlags & 64) == 64;
        boolean fullscreen = fullscreenWindow | fullscreenActivity;
        boolean physicalFullscreen = (activityFlags & 32) == 32;
        WindowFrameModel model = new WindowFrameModel();
        if (SharedDisplayManager.enable()) {
            boolean resizing = isResizingPackage(lp);
            boolean sharedValid = SharedDisplayManager.sharedValid(sharedId);
            Rect windowBounds = getComputedWindowBoundsLocked(type);
            Rect sharedBounds = getSharedDeviceBounds(sharedId);
            getDisplayBoundsLocked(displayId);
            Rect primaryBounds = getSharedDeviceBounds(0);
            Rect physicalBounds = getDisplayBoundsLocked(displayId);
            int offsetX = (!sharedValid || sharedBounds == null) ? 0 : sharedBounds.left;
            setRect(model.windowBounds, windowBounds);
            setRect(model.physicalBounds, physicalBounds);
            setRect(model.parentBounds, sharedValid ? sharedBounds : primaryBounds);
            setRect(model.displayBounds, sharedValid ? sharedBounds : primaryBounds);
            setRect(model.contentBounds, windowBounds);
            setRect(model.unrestrictedBounds, sharedValid ? sharedBounds : primaryBounds);
            if (resizing) {
                setRect(model.parentBounds, physicalBounds);
            }
            Rect bounds = new Rect();
            bounds.setEmpty();
            if (type == 5) {
                bounds.setEmpty();
                bounds.set(windowBounds);
                bounds.offset(offsetX, 0);
                Rect contentBounds = new Rect(bounds);
                setRect(model.stableBounds, fullscreen ? model.unrestrictedBounds : bounds);
                setRect(model.windowBounds, fullscreen ? model.unrestrictedBounds : bounds);
                setRect(model.parentBounds, fullscreen ? model.unrestrictedBounds : bounds);
                setRect(model.contentBounds, fullscreen ? model.unrestrictedBounds : contentBounds);
                setRect(model.applicationBounds, fullscreen ? model.unrestrictedBounds : bounds);
                if (physicalFullscreen) {
                    setRect(model.windowBounds, physicalBounds);
                    setRect(model.physicalBounds, physicalBounds);
                    setRect(model.parentBounds, physicalBounds);
                    setRect(model.displayBounds, physicalBounds);
                    setRect(model.contentBounds, physicalBounds);
                    setRect(model.unrestrictedBounds, physicalBounds);
                    setRect(model.stableBounds, physicalBounds);
                    setRect(model.applicationBounds, physicalBounds);
                }
            } else if (type == 6) {
                int statusBarHeight = getRectHeight(getWindowFrame(2000));
                bounds.setEmpty();
                bounds.set(windowBounds);
                bounds.offset(offsetX, 0);
                Rect contentBounds2 = new Rect(bounds);
                contentBounds2.offset(0, statusBarHeight);
                setRect(model.stableBounds, fullscreen ? model.unrestrictedBounds : bounds);
                setRect(model.windowBounds, fullscreen ? model.unrestrictedBounds : bounds);
                setRect(model.parentBounds, fullscreen ? model.unrestrictedBounds : bounds);
                setRect(model.contentBounds, fullscreen ? model.unrestrictedBounds : contentBounds2);
                setRect(model.applicationBounds, fullscreen ? model.unrestrictedBounds : bounds);
                if (physicalFullscreen) {
                    setRect(model.windowBounds, physicalBounds);
                    setRect(model.physicalBounds, physicalBounds);
                    setRect(model.parentBounds, physicalBounds);
                    setRect(model.displayBounds, physicalBounds);
                    setRect(model.contentBounds, physicalBounds);
                    setRect(model.unrestrictedBounds, physicalBounds);
                    setRect(model.stableBounds, physicalBounds);
                    setRect(model.applicationBounds, physicalBounds);
                }
            } else if (type == 10) {
                Rect contentBounds3 = null;
                bounds.setEmpty();
                if (fullscreen) {
                    bounds.set(sharedBounds);
                } else if (panel) {
                    int statusBarHeight2 = getRectHeight(getWindowFrame(2000));
                    bounds.set(new Rect(getWindowFrame(6)));
                    bounds.offset(offsetX, 0);
                    Rect contentBounds4 = new Rect(bounds);
                    contentBounds4.offset(0, statusBarHeight2);
                    contentBounds3 = contentBounds4;
                } else {
                    if (sharedId == 1) {
                        offsetX -= getRectWidth(getWindowFrame(2019));
                    }
                    bounds.set(new Rect(getWindowFrame(1)));
                    bounds.offset(offsetX, 0);
                }
                setRect(model.stableBounds, bounds);
                setRect(model.windowBounds, bounds);
                setRect(model.contentBounds, contentBounds3 != null ? contentBounds3 : bounds);
                setRect(model.applicationBounds, bounds);
            } else if (type != 12) {
                if (type == 2011) {
                    int position = SharedDisplayContainer.InputMethodPolicy.getInputMethodPosition();
                    bounds.setEmpty();
                    bounds.set(getSharedDeviceBounds(position));
                    setRect(model.windowBounds, bounds);
                    setRect(model.physicalBounds, physicalBounds);
                    setRect(model.parentBounds, bounds);
                    setRect(model.displayBounds, bounds);
                    setRect(model.contentBounds, bounds);
                    setRect(model.unrestrictedBounds, bounds);
                    setRect(model.decorBounds, bounds);
                    setRect(model.stableBounds, bounds);
                    setRect(model.visibleBounds, bounds);
                    setRect(model.applicationBounds, fullscreen ? model.unrestrictedBounds : bounds);
                } else {
                    if (windowBounds != null) {
                        Rect stableBounds = new Rect(new Rect(getWindowFrame(1)));
                        stableBounds.offset(offsetX, 0);
                        bounds.setEmpty();
                        bounds.set(windowBounds);
                        bounds.offset(offsetX, 0);
                        setRect(model.stableBounds, stableBounds);
                        setRect(model.windowBounds, bounds);
                        setRect(model.contentBounds, bounds);
                        setRect(model.visibleBounds, bounds);
                        setRect(model.applicationBounds, fullscreen ? model.unrestrictedBounds : bounds);
                    }
                    boolean isApplicationWindow = xpWindowManager.isApplicationWindowType(type);
                    if (physicalFullscreen && !isApplicationWindow) {
                        setRect(model.windowBounds, physicalBounds);
                        setRect(model.physicalBounds, physicalBounds);
                        setRect(model.parentBounds, physicalBounds);
                        setRect(model.displayBounds, physicalBounds);
                        setRect(model.contentBounds, physicalBounds);
                        setRect(model.unrestrictedBounds, physicalBounds);
                        setRect(model.stableBounds, physicalBounds);
                        setRect(model.applicationBounds, physicalBounds);
                    }
                }
            } else if (windowBounds != null) {
                Rect stableBounds2 = new Rect(new Rect(getWindowFrame(1)));
                stableBounds2.offset(offsetX, 0);
                bounds.setEmpty();
                bounds.set(windowBounds);
                bounds.offset(offsetX, 0);
                setRect(model.stableBounds, stableBounds2);
                setRect(model.parentBounds, bounds);
                setRect(model.windowBounds, bounds);
                setRect(model.contentBounds, bounds);
                setRect(model.visibleBounds, bounds);
                setRect(model.applicationBounds, bounds);
            }
        } else {
            Rect windowBounds2 = getComputedWindowBoundsLocked(type);
            Rect displayBounds = getDisplayBoundsLocked(displayId);
            Rect physicalBounds2 = getDisplayBoundsLocked(displayId);
            Rect application = new Rect(getWindowFrame(1));
            Rect bounds2 = new Rect();
            bounds2.setEmpty();
            if (type == 6) {
                int statusBarHeight3 = getRectHeight(getWindowFrame(2000));
                bounds2.setEmpty();
                bounds2.set(windowBounds2);
                Rect contentBounds5 = new Rect(bounds2);
                contentBounds5.offset(0, statusBarHeight3);
                setRect(model.unrestrictedBounds, displayBounds);
                setRect(model.stableBounds, fullscreen ? model.unrestrictedBounds : bounds2);
                setRect(model.windowBounds, fullscreen ? model.unrestrictedBounds : bounds2);
                setRect(model.parentBounds, fullscreen ? model.unrestrictedBounds : bounds2);
                setRect(model.contentBounds, fullscreen ? model.unrestrictedBounds : contentBounds5);
                setRect(model.applicationBounds, fullscreen ? model.unrestrictedBounds : bounds2);
                if (physicalFullscreen) {
                    setRect(model.windowBounds, physicalBounds2);
                    setRect(model.physicalBounds, physicalBounds2);
                    setRect(model.parentBounds, physicalBounds2);
                    setRect(model.displayBounds, physicalBounds2);
                    setRect(model.contentBounds, physicalBounds2);
                    setRect(model.unrestrictedBounds, physicalBounds2);
                    setRect(model.stableBounds, physicalBounds2);
                    setRect(model.applicationBounds, physicalBounds2);
                }
            } else if (type == 9) {
                setRect(model.unrestrictedBounds, displayBounds);
                setRect(model.windowBounds, fullscreen ? model.unrestrictedBounds : windowBounds2);
                setRect(model.physicalBounds, displayBounds);
                setRect(model.parentBounds, null);
                setRect(model.displayBounds, displayBounds);
                setRect(model.contentBounds, fullscreen ? model.unrestrictedBounds : windowBounds2);
                setRect(model.visibleBounds, null);
                setRect(model.decorBounds, null);
                setRect(model.stableBounds, new Rect(getWindowFrame(1)));
                setRect(model.overscanBounds, model.unrestrictedBounds);
                setRect(model.applicationBounds, fullscreen ? model.unrestrictedBounds : application);
            } else if (type != 12) {
                if (type == 2011) {
                    bounds2.setEmpty();
                    bounds2.set(displayBounds);
                    setRect(model.windowBounds, bounds2);
                    setRect(model.physicalBounds, physicalBounds2);
                    setRect(model.parentBounds, bounds2);
                    setRect(model.displayBounds, bounds2);
                    setRect(model.contentBounds, bounds2);
                    setRect(model.unrestrictedBounds, bounds2);
                    setRect(model.decorBounds, bounds2);
                    setRect(model.stableBounds, bounds2);
                    setRect(model.visibleBounds, bounds2);
                    setRect(model.applicationBounds, fullscreen ? model.unrestrictedBounds : bounds2);
                } else {
                    setRect(model.unrestrictedBounds, displayBounds);
                    setRect(model.windowBounds, windowBounds2);
                    setRect(model.physicalBounds, displayBounds);
                    setRect(model.parentBounds, null);
                    setRect(model.displayBounds, displayBounds);
                    setRect(model.contentBounds, windowBounds2);
                    setRect(model.visibleBounds, null);
                    setRect(model.decorBounds, null);
                    setRect(model.stableBounds, new Rect(getWindowFrame(1)));
                    setRect(model.overscanBounds, model.unrestrictedBounds);
                    setRect(model.applicationBounds, fullscreen ? model.unrestrictedBounds : application);
                }
            } else if (windowBounds2 != null) {
                Rect stableBounds3 = new Rect(application);
                stableBounds3.offset(0, 0);
                bounds2.setEmpty();
                bounds2.set(windowBounds2);
                bounds2.offset(0, 0);
                setRect(model.stableBounds, stableBounds3);
                setRect(model.parentBounds, bounds2);
                setRect(model.windowBounds, bounds2);
                setRect(model.contentBounds, bounds2);
                setRect(model.visibleBounds, bounds2);
                setRect(model.applicationBounds, bounds2);
            }
        }
        return model;
    }

    public static DisplayFrames getOverrideDisplayFrames(WindowManager.LayoutParams lp, DisplayFrames displayFrames) {
        if (lp != null) {
            DisplayFrames df = DisplayFrames.clone(displayFrames);
            WindowFrameModel model = getWindowFrame(lp);
            if (model != null && df != null) {
                setRect(df.mParent, model.parentBounds);
                setRect(df.mContent, model.contentBounds);
                setRect(df.mCurrent, model.contentBounds, 5);
                setRect(df.mDock, model.decorBounds);
                setRect(df.mStable, model.stableBounds);
                setRect(df.mStableFullscreen, model.unrestrictedBounds);
                setRect(df.mDisplay, model.displayBounds);
                setRect(df.mOverscan, model.unrestrictedBounds);
                setRect(df.mRestrictedOverscan, model.unrestrictedBounds);
                setRect(df.mRestricted, model.unrestrictedBounds);
                setRect(df.mUnrestricted, model.unrestrictedBounds);
            }
            return df;
        }
        return displayFrames;
    }

    public static Rect getSharedDeviceBounds(int sharedId) {
        Rect bounds = new Rect();
        bounds.setEmpty();
        Rect physicalBounds = getDisplayBoundsLocked(0);
        if (physicalBounds != null) {
            int offsetX = 0;
            if (sharedId == 0) {
                offsetX = 0;
            } else if (sharedId == 1) {
                offsetX = physicalBounds.width() / 2;
            }
            bounds.set(physicalBounds.left, physicalBounds.top, physicalBounds.left + (physicalBounds.width() / 2), physicalBounds.bottom);
            bounds.offset(offsetX, 0);
        }
        return bounds;
    }

    public static int getRectWidth(Rect rect) {
        if (rect != null) {
            return rect.width();
        }
        return 0;
    }

    public static int getRectHeight(Rect rect) {
        if (rect != null) {
            return rect.height();
        }
        return 0;
    }

    public static void setRect(Rect srcRect, Rect destRect) {
        if (srcRect != null && destRect != null && !destRect.isEmpty()) {
            srcRect.set(destRect);
        }
    }

    public static void setRect(Rect srcRect, Rect destRect, int flags) {
        if (srcRect != null && destRect != null && !destRect.isEmpty()) {
            if ((flags & 1) == 1) {
                srcRect.left = destRect.left;
            }
            if ((flags & 2) == 2) {
                srcRect.top = destRect.top;
            }
            if ((flags & 4) == 4) {
                srcRect.right = destRect.right;
            }
            if ((flags & 8) == 8) {
                srcRect.bottom = destRect.bottom;
            }
        }
    }

    public static Rect getWindowBoundsLocked(int type) {
        return sWindowBounds.getOrDefault(Integer.valueOf(type), null);
    }

    public static Rect getComputedWindowBoundsLocked(int type) {
        Rect bounds = getWindowBoundsLocked(type);
        if (xpWindowManager.rectValid(bounds)) {
            return bounds;
        }
        int mappingType = xpWindowManager.isSystemDialogWindowType(type) ? 2008 : -1;
        return mappingType > 0 ? getWindowBoundsLocked(2008) : bounds;
    }

    public static Rect getDisplayBoundsLocked(int displayId) {
        synchronized (sDisplayBounds) {
            if (sDisplayBounds.containsKey(Integer.valueOf(displayId))) {
                return sDisplayBounds.get(Integer.valueOf(displayId));
            }
            Rect bounds = getDisplayBounds(displayId);
            if (bounds != null && !bounds.isEmpty()) {
                sDisplayBounds.put(Integer.valueOf(displayId), bounds);
                return bounds;
            }
            return null;
        }
    }

    public static Rect getDisplayBounds(int displayId) {
        try {
            Rect bounds = new Rect();
            DisplayMetrics metrics = new DisplayMetrics();
            Display display = DisplayManagerGlobal.getInstance().getRealDisplay(Math.max(displayId, 0));
            if (display != null) {
                display.getRealMetrics(metrics);
                bounds.set(0, 0, metrics.widthPixels, metrics.heightPixels);
                return bounds;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
