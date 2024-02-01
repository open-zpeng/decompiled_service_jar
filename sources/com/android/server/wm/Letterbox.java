package com.android.server.wm;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Process;
import android.view.IWindow;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputEventReceiver;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import com.android.server.UiThread;
import java.util.function.Supplier;

/* loaded from: classes2.dex */
public class Letterbox {
    private static final Rect EMPTY_RECT = new Rect();
    private static final Point ZERO_POINT = new Point(0, 0);
    private final Supplier<SurfaceControl.Builder> mFactory;
    private final Rect mOuter = new Rect();
    private final Rect mInner = new Rect();
    private final LetterboxSurface mTop = new LetterboxSurface("top");
    private final LetterboxSurface mLeft = new LetterboxSurface("left");
    private final LetterboxSurface mBottom = new LetterboxSurface("bottom");
    private final LetterboxSurface mRight = new LetterboxSurface("right");
    private final LetterboxSurface[] mSurfaces = {this.mLeft, this.mTop, this.mRight, this.mBottom};

    public Letterbox(Supplier<SurfaceControl.Builder> surfaceControlFactory) {
        this.mFactory = surfaceControlFactory;
    }

    public void layout(Rect outer, Rect inner, Point surfaceOrigin) {
        this.mOuter.set(outer);
        this.mInner.set(inner);
        this.mTop.layout(outer.left, outer.top, inner.right, inner.top, surfaceOrigin);
        this.mLeft.layout(outer.left, inner.top, inner.left, outer.bottom, surfaceOrigin);
        this.mBottom.layout(inner.left, inner.bottom, outer.right, outer.bottom, surfaceOrigin);
        this.mRight.layout(inner.right, outer.top, outer.right, inner.bottom, surfaceOrigin);
    }

    public Rect getInsets() {
        return new Rect(this.mLeft.getWidth(), this.mTop.getHeight(), this.mRight.getWidth(), this.mBottom.getHeight());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getInnerFrame() {
        return this.mInner;
    }

    public boolean isOverlappingWith(Rect rect) {
        LetterboxSurface[] letterboxSurfaceArr;
        for (LetterboxSurface surface : this.mSurfaces) {
            if (surface.isOverlappingWith(rect)) {
                return true;
            }
        }
        return false;
    }

    public void hide() {
        Rect rect = EMPTY_RECT;
        layout(rect, rect, ZERO_POINT);
    }

    public void destroy() {
        LetterboxSurface[] letterboxSurfaceArr;
        this.mOuter.setEmpty();
        this.mInner.setEmpty();
        for (LetterboxSurface surface : this.mSurfaces) {
            surface.remove();
        }
    }

    public boolean needsApplySurfaceChanges() {
        LetterboxSurface[] letterboxSurfaceArr;
        for (LetterboxSurface surface : this.mSurfaces) {
            if (surface.needsApplySurfaceChanges()) {
                return true;
            }
        }
        return false;
    }

    public void applySurfaceChanges(SurfaceControl.Transaction t) {
        LetterboxSurface[] letterboxSurfaceArr;
        for (LetterboxSurface surface : this.mSurfaces) {
            surface.applySurfaceChanges(t);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void attachInput(WindowState win) {
        LetterboxSurface[] letterboxSurfaceArr;
        for (LetterboxSurface surface : this.mSurfaces) {
            surface.attachInput(win);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onMovedToDisplay(int displayId) {
        LetterboxSurface[] letterboxSurfaceArr;
        for (LetterboxSurface surface : this.mSurfaces) {
            if (surface.mInputInterceptor != null) {
                surface.mInputInterceptor.mWindowHandle.displayId = displayId;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public static class InputInterceptor {
        final InputChannel mClientChannel;
        final InputEventReceiver mInputEventReceiver;
        final InputChannel mServerChannel;
        final Binder mToken = new Binder();
        final InputWindowHandle mWindowHandle;
        final WindowManagerService mWmService;

        InputInterceptor(String namePrefix, WindowState win) {
            this.mWmService = win.mWmService;
            StringBuilder sb = new StringBuilder();
            sb.append(namePrefix);
            sb.append(win.mAppToken != null ? win.mAppToken : win);
            String name = sb.toString();
            InputChannel[] channels = InputChannel.openInputChannelPair(name);
            this.mServerChannel = channels[0];
            this.mClientChannel = channels[1];
            this.mInputEventReceiver = new SimpleInputReceiver(this.mClientChannel);
            this.mWmService.mInputManager.registerInputChannel(this.mServerChannel, this.mToken);
            this.mWindowHandle = new InputWindowHandle((InputApplicationHandle) null, (IWindow) null, win.getDisplayId());
            InputWindowHandle inputWindowHandle = this.mWindowHandle;
            inputWindowHandle.name = name;
            inputWindowHandle.token = this.mToken;
            inputWindowHandle.layoutParamsFlags = 545259560;
            inputWindowHandle.layoutParamsType = 2022;
            inputWindowHandle.dispatchingTimeoutNanos = 5000000000L;
            inputWindowHandle.visible = true;
            inputWindowHandle.ownerPid = Process.myPid();
            this.mWindowHandle.ownerUid = Process.myUid();
            this.mWindowHandle.scaleFactor = 1.0f;
        }

        void updateTouchableRegion(Rect frame) {
            if (frame.isEmpty()) {
                this.mWindowHandle.token = null;
                return;
            }
            InputWindowHandle inputWindowHandle = this.mWindowHandle;
            inputWindowHandle.token = this.mToken;
            inputWindowHandle.touchableRegion.set(frame);
            this.mWindowHandle.touchableRegion.translate(-frame.left, -frame.top);
        }

        void dispose() {
            this.mWmService.mInputManager.unregisterInputChannel(this.mServerChannel);
            this.mInputEventReceiver.dispose();
            this.mServerChannel.dispose();
            this.mClientChannel.dispose();
        }

        /* loaded from: classes2.dex */
        private static class SimpleInputReceiver extends InputEventReceiver {
            SimpleInputReceiver(InputChannel inputChannel) {
                super(inputChannel, UiThread.getHandler().getLooper());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public class LetterboxSurface {
        private InputInterceptor mInputInterceptor;
        private SurfaceControl mSurface;
        private final String mType;
        private final Rect mSurfaceFrameRelative = new Rect();
        private final Rect mLayoutFrameGlobal = new Rect();
        private final Rect mLayoutFrameRelative = new Rect();

        public LetterboxSurface(String type) {
            this.mType = type;
        }

        public void layout(int left, int top, int right, int bottom, Point surfaceOrigin) {
            this.mLayoutFrameGlobal.set(left, top, right, bottom);
            this.mLayoutFrameRelative.set(this.mLayoutFrameGlobal);
            this.mLayoutFrameRelative.offset(-surfaceOrigin.x, -surfaceOrigin.y);
        }

        private void createSurface() {
            this.mSurface = ((SurfaceControl.Builder) Letterbox.this.mFactory.get()).setName("Letterbox - " + this.mType).setFlags(4).setColorLayer().build();
            this.mSurface.setLayer(-1);
            this.mSurface.setColor(new float[]{0.0f, 0.0f, 0.0f});
            this.mSurface.setColorSpaceAgnostic(true);
        }

        void attachInput(WindowState win) {
            InputInterceptor inputInterceptor = this.mInputInterceptor;
            if (inputInterceptor != null) {
                inputInterceptor.dispose();
            }
            this.mInputInterceptor = new InputInterceptor("Letterbox_" + this.mType + "_", win);
        }

        public void remove() {
            if (this.mSurface != null) {
                new SurfaceControl.Transaction().remove(this.mSurface).apply();
                this.mSurface = null;
            }
            InputInterceptor inputInterceptor = this.mInputInterceptor;
            if (inputInterceptor != null) {
                inputInterceptor.dispose();
                this.mInputInterceptor = null;
            }
        }

        public int getWidth() {
            return Math.max(0, this.mLayoutFrameGlobal.width());
        }

        public int getHeight() {
            return Math.max(0, this.mLayoutFrameGlobal.height());
        }

        public boolean isOverlappingWith(Rect rect) {
            if (this.mLayoutFrameGlobal.isEmpty()) {
                return false;
            }
            return Rect.intersects(rect, this.mLayoutFrameGlobal);
        }

        public void applySurfaceChanges(SurfaceControl.Transaction t) {
            InputInterceptor inputInterceptor;
            if (this.mSurfaceFrameRelative.equals(this.mLayoutFrameRelative)) {
                return;
            }
            this.mSurfaceFrameRelative.set(this.mLayoutFrameRelative);
            if (!this.mSurfaceFrameRelative.isEmpty()) {
                if (this.mSurface == null) {
                    createSurface();
                }
                t.setPosition(this.mSurface, this.mSurfaceFrameRelative.left, this.mSurfaceFrameRelative.top);
                t.setWindowCrop(this.mSurface, this.mSurfaceFrameRelative.width(), this.mSurfaceFrameRelative.height());
                t.show(this.mSurface);
            } else {
                SurfaceControl surfaceControl = this.mSurface;
                if (surfaceControl != null) {
                    t.hide(surfaceControl);
                }
            }
            if (this.mSurface != null && (inputInterceptor = this.mInputInterceptor) != null) {
                inputInterceptor.updateTouchableRegion(this.mSurfaceFrameRelative);
                t.setInputWindowInfo(this.mSurface, this.mInputInterceptor.mWindowHandle);
            }
        }

        public boolean needsApplySurfaceChanges() {
            return !this.mSurfaceFrameRelative.equals(this.mLayoutFrameRelative);
        }
    }
}
