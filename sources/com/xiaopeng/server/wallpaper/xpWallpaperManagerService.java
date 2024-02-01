package com.xiaopeng.server.wallpaper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.view.SharedDisplayManager;

/* loaded from: classes2.dex */
public class xpWallpaperManagerService {
    private static final String TAG = "xpWallpaperManagerService";
    private static final int WINDOW_TYPE_WALLPAPER = 2050;
    private Context mContext;
    private WindowManager.LayoutParams mLayoutParams;
    private ViewGroup mWallpaperView;
    private WindowManager mWindowManager;
    private static final boolean ENABLED = !SharedDisplayManager.isUnityUI();
    private static int sDefaultFgWallpaperId = 17302252;
    private static int sDefaultBgWallpaperId = 17301723;
    private static xpWallpaperManagerService sService = null;

    private xpWallpaperManagerService(Context context) {
        this.mContext = context;
        this.mWindowManager = (WindowManager) context.getSystemService("window");
    }

    public static xpWallpaperManagerService get(Context context) {
        if (sService == null) {
            synchronized (xpWallpaperManagerService.class) {
                if (sService == null) {
                    sService = new xpWallpaperManagerService(context);
                }
            }
        }
        return sService;
    }

    public void init() {
        attachWallpaper();
        setWallpaper(sDefaultFgWallpaperId, sDefaultBgWallpaperId);
    }

    public void attachWallpaper() {
        Rect rect;
        if (ENABLED && (rect = getDisplayRect(this.mWindowManager)) != null) {
            try {
                int width = rect.width();
                int height = rect.height();
                this.mWallpaperView = createWallpaperView(this.mContext, width, height);
                this.mLayoutParams = new WindowManager.LayoutParams(-1, -1, WINDOW_TYPE_WALLPAPER, 1048632, -3);
                this.mLayoutParams.gravity = 48;
                this.mLayoutParams.token = new Binder();
                this.mLayoutParams.privateFlags |= 16;
                this.mWindowManager.addView(this.mWallpaperView, this.mLayoutParams);
                xpLogger.i(TAG, "attachWallpaper isAttachedToWindow=" + this.mWallpaperView.isAttachedToWindow());
            } catch (Exception e) {
                xpLogger.i(TAG, "attachWallpaper e=" + e);
            }
        }
    }

    public void detachWallpaper() {
        if (ENABLED) {
            try {
                this.mWindowManager.removeView(this.mWallpaperView);
                xpLogger.i(TAG, "detachWallpaper");
            } catch (Exception e) {
            }
        }
    }

    public void setWallpaper(final int fgId, final int bgId) {
        ViewGroup viewGroup;
        if (ENABLED && (viewGroup = this.mWallpaperView) != null && (viewGroup instanceof WallpaperView)) {
            viewGroup.post(new Runnable() { // from class: com.xiaopeng.server.wallpaper.xpWallpaperManagerService.1
                @Override // java.lang.Runnable
                public void run() {
                    ((WallpaperView) xpWallpaperManagerService.this.mWallpaperView).setForegroundResource(fgId);
                    ((WallpaperView) xpWallpaperManagerService.this.mWallpaperView).setBackgroundResource(bgId);
                    ((WallpaperView) xpWallpaperManagerService.this.mWallpaperView).updateResourceDrawable();
                }
            });
        }
    }

    public void setHomeWallpaper(boolean shadow) {
        xpLogger.i(TAG, "setHomeWallpaper shadow=" + shadow);
        setWallpaper(shadow ? 0 : sDefaultFgWallpaperId, sDefaultBgWallpaperId);
    }

    private static ViewGroup createWallpaperView(Context context, int width, int height) {
        if (context != null && width > 0 && height > 0) {
            FrameLayout layout = new WallpaperView(context);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
            layout.setLayoutParams(lp);
            return layout;
        }
        return null;
    }

    private static Rect getDisplayRect(WindowManager wm) {
        if (wm != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isThemeChanged(Configuration newConfig) {
        return (newConfig == null || (newConfig.uiMode & 192) == 0) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    @SuppressLint({"AppCompatCustomView"})
    /* loaded from: classes2.dex */
    public static class WallpaperView extends FrameLayout {
        private boolean mBgChanged;
        private int mBgId;
        private boolean mFgChanged;
        private int mFgId;

        public WallpaperView(Context context) {
            super(context);
            this.mBgId = 0;
            this.mFgId = 0;
            this.mBgChanged = true;
            this.mFgChanged = true;
            init(context, null, 0, 0);
        }

        public WallpaperView(Context context, AttributeSet attrs) {
            super(context, attrs);
            this.mBgId = 0;
            this.mFgId = 0;
            this.mBgChanged = true;
            this.mFgChanged = true;
            init(context, attrs, 0, 0);
        }

        public WallpaperView(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
            this.mBgId = 0;
            this.mFgId = 0;
            this.mBgChanged = true;
            this.mFgChanged = true;
            init(context, attrs, defStyleAttr, 0);
        }

        public WallpaperView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
            this.mBgId = 0;
            this.mFgId = 0;
            this.mBgChanged = true;
            this.mFgChanged = true;
            init(context, attrs, defStyleAttr, defStyleRes);
        }

        public void init(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        }

        @Override // android.view.View
        protected void onVisibilityChanged(View changedView, int vis) {
            super.onVisibilityChanged(changedView, vis);
            this.mFgChanged = true;
            this.mBgChanged = true;
            updateResourceDrawable();
        }

        @Override // android.view.View
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            if (xpWallpaperManagerService.isThemeChanged(newConfig)) {
                this.mFgChanged = true;
                this.mBgChanged = true;
                updateResourceDrawable();
            }
        }

        @Override // android.view.ViewGroup, android.view.View
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            this.mFgChanged = true;
            this.mBgChanged = true;
            updateResourceDrawable();
        }

        @Override // android.view.ViewGroup, android.view.View
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
        }

        @Override // android.view.View
        public void setBackgroundResource(int id) {
            super.setBackgroundResource(id);
            this.mBgChanged = this.mBgId != id;
            this.mBgId = id;
        }

        public void setForegroundResource(int id) {
            this.mFgChanged = this.mFgId != id;
            this.mFgId = id;
        }

        public void updateResourceDrawable() {
            try {
                if (this.mBgChanged) {
                    setBackground(this.mBgId != 0 ? getContext().getDrawable(this.mBgId) : null);
                }
                if (this.mFgChanged) {
                    setForeground(this.mFgId != 0 ? getContext().getDrawable(this.mFgId) : null);
                }
                if (this.mBgChanged || this.mFgChanged) {
                    refreshDrawableState();
                    requestLayout();
                    invalidate();
                }
            } catch (Exception e) {
            }
        }
    }
}
