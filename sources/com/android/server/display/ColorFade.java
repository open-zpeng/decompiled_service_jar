package com.android.server.display;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManagerInternal;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import com.android.server.LocalServices;
import com.android.server.usb.descriptors.UsbTerminalTypes;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import libcore.io.Streams;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class ColorFade {
    private static final int COLOR_FADE_LAYER = 1073741825;
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_POWER = false;
    private static final int DEJANK_FRAMES = 3;
    public static final int MODE_COOL_DOWN = 1;
    public static final int MODE_FADE = 2;
    public static final int MODE_WARM_UP = 0;
    private static final String TAG = "ColorFade";
    private boolean mCreatedResources;
    private int mDisplayHeight;
    private final int mDisplayId;
    private int mDisplayLayerStack;
    private int mDisplayWidth;
    private EGLConfig mEglConfig;
    private EGLContext mEglContext;
    private EGLDisplay mEglDisplay;
    private EGLSurface mEglSurface;
    private int mGammaLoc;
    private int mMode;
    private int mOpacityLoc;
    private boolean mPrepared;
    private int mProgram;
    private int mProjMatrixLoc;
    private Surface mSurface;
    private float mSurfaceAlpha;
    private SurfaceControl mSurfaceControl;
    private NaturalSurfaceLayout mSurfaceLayout;
    private SurfaceSession mSurfaceSession;
    private boolean mSurfaceVisible;
    private int mTexCoordLoc;
    private int mTexMatrixLoc;
    private boolean mTexNamesGenerated;
    private int mTexUnitLoc;
    private int mVertexLoc;
    private final int[] mTexNames = new int[1];
    private final float[] mTexMatrix = new float[16];
    private final float[] mProjMatrix = new float[16];
    private final int[] mGLBuffers = new int[2];
    private final FloatBuffer mVertexBuffer = createNativeFloatBuffer(8);
    private final FloatBuffer mTexCoordBuffer = createNativeFloatBuffer(8);
    private final DisplayManagerInternal mDisplayManagerInternal = (DisplayManagerInternal) LocalServices.getService(DisplayManagerInternal.class);

    public ColorFade(int displayId) {
        this.mDisplayId = displayId;
    }

    public boolean prepare(Context context, int mode) {
        this.mMode = mode;
        DisplayInfo displayInfo = this.mDisplayManagerInternal.getDisplayInfo(this.mDisplayId);
        this.mDisplayLayerStack = displayInfo.layerStack;
        this.mDisplayWidth = displayInfo.getNaturalWidth();
        this.mDisplayHeight = displayInfo.getNaturalHeight();
        if (!createSurface() || !createEglContext() || !createEglSurface() || !captureScreenshotTextureAndSetViewport()) {
            dismiss();
            return false;
        } else if (attachEglContext()) {
            try {
                if (!initGLShaders(context) || !initGLBuffers() || checkGlErrors("prepare")) {
                    detachEglContext();
                    dismiss();
                    return false;
                }
                detachEglContext();
                this.mCreatedResources = true;
                this.mPrepared = true;
                if (mode == 1) {
                    for (int i = 0; i < 3; i++) {
                        draw(1.0f);
                    }
                }
                return true;
            } finally {
                detachEglContext();
            }
        } else {
            return false;
        }
    }

    private String readFile(Context context, int resourceId) {
        try {
            InputStream stream = context.getResources().openRawResource(resourceId);
            return new String(Streams.readFully(new InputStreamReader(stream)));
        } catch (IOException e) {
            Slog.e(TAG, "Unrecognized shader " + Integer.toString(resourceId));
            throw new RuntimeException(e);
        }
    }

    private int loadShader(Context context, int resourceId, int type) {
        String source = readFile(context, resourceId);
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, 35713, compiled, 0);
        if (compiled[0] == 0) {
            Slog.e(TAG, "Could not compile shader " + shader + ", " + type + ":");
            Slog.e(TAG, GLES20.glGetShaderSource(shader));
            Slog.e(TAG, GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private boolean initGLShaders(Context context) {
        int vshader = loadShader(context, 17825795, 35633);
        int fshader = loadShader(context, 17825794, 35632);
        GLES20.glReleaseShaderCompiler();
        if (vshader == 0 || fshader == 0) {
            return false;
        }
        this.mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(this.mProgram, vshader);
        GLES20.glAttachShader(this.mProgram, fshader);
        GLES20.glDeleteShader(vshader);
        GLES20.glDeleteShader(fshader);
        GLES20.glLinkProgram(this.mProgram);
        this.mVertexLoc = GLES20.glGetAttribLocation(this.mProgram, "position");
        this.mTexCoordLoc = GLES20.glGetAttribLocation(this.mProgram, "uv");
        this.mProjMatrixLoc = GLES20.glGetUniformLocation(this.mProgram, "proj_matrix");
        this.mTexMatrixLoc = GLES20.glGetUniformLocation(this.mProgram, "tex_matrix");
        this.mOpacityLoc = GLES20.glGetUniformLocation(this.mProgram, "opacity");
        this.mGammaLoc = GLES20.glGetUniformLocation(this.mProgram, "gamma");
        this.mTexUnitLoc = GLES20.glGetUniformLocation(this.mProgram, "texUnit");
        GLES20.glUseProgram(this.mProgram);
        GLES20.glUniform1i(this.mTexUnitLoc, 0);
        GLES20.glUseProgram(0);
        return true;
    }

    private void destroyGLShaders() {
        GLES20.glDeleteProgram(this.mProgram);
        checkGlErrors("glDeleteProgram");
    }

    private boolean initGLBuffers() {
        setQuad(this.mVertexBuffer, 0.0f, 0.0f, this.mDisplayWidth, this.mDisplayHeight);
        GLES20.glBindTexture(36197, this.mTexNames[0]);
        GLES20.glTexParameteri(36197, 10240, 9728);
        GLES20.glTexParameteri(36197, 10241, 9728);
        GLES20.glTexParameteri(36197, 10242, 33071);
        GLES20.glTexParameteri(36197, 10243, 33071);
        GLES20.glBindTexture(36197, 0);
        GLES20.glGenBuffers(2, this.mGLBuffers, 0);
        GLES20.glBindBuffer(34962, this.mGLBuffers[0]);
        GLES20.glBufferData(34962, this.mVertexBuffer.capacity() * 4, this.mVertexBuffer, 35044);
        GLES20.glBindBuffer(34962, this.mGLBuffers[1]);
        GLES20.glBufferData(34962, this.mTexCoordBuffer.capacity() * 4, this.mTexCoordBuffer, 35044);
        GLES20.glBindBuffer(34962, 0);
        return true;
    }

    private void destroyGLBuffers() {
        GLES20.glDeleteBuffers(2, this.mGLBuffers, 0);
        checkGlErrors("glDeleteBuffers");
    }

    private static void setQuad(FloatBuffer vtx, float x, float y, float w, float h) {
        vtx.put(0, x);
        vtx.put(1, y);
        vtx.put(2, x);
        vtx.put(3, y + h);
        vtx.put(4, x + w);
        vtx.put(5, y + h);
        vtx.put(6, x + w);
        vtx.put(7, y);
    }

    public void dismissResources() {
        if (this.mCreatedResources) {
            attachEglContext();
            try {
                destroyScreenshotTexture();
                destroyGLShaders();
                destroyGLBuffers();
                destroyEglSurface();
                detachEglContext();
                GLES20.glFlush();
                this.mCreatedResources = false;
            } catch (Throwable th) {
                detachEglContext();
                throw th;
            }
        }
    }

    public void dismiss() {
        if (this.mPrepared) {
            dismissResources();
            destroySurface();
            this.mPrepared = false;
        }
    }

    public boolean draw(float level) {
        if (this.mPrepared) {
            if (this.mMode == 2) {
                return showSurface(1.0f - level);
            }
            if (attachEglContext()) {
                try {
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(16384);
                    double one_minus_level = 1.0f - level;
                    double cos = Math.cos(3.141592653589793d * one_minus_level);
                    double sign = cos < 0.0d ? -1.0d : 1.0d;
                    float opacity = ((float) (-Math.pow(one_minus_level, 2.0d))) + 1.0f;
                    float gamma = (float) (0.1d + (((0.5d * sign * Math.pow(cos, 2.0d)) + 0.5d) * 0.9d));
                    drawFaded(opacity, 1.0f / gamma);
                    if (checkGlErrors("drawFrame")) {
                        return false;
                    }
                    EGL14.eglSwapBuffers(this.mEglDisplay, this.mEglSurface);
                    detachEglContext();
                    return showSurface(1.0f);
                } finally {
                    detachEglContext();
                }
            }
            return false;
        }
        return false;
    }

    private void drawFaded(float opacity, float gamma) {
        GLES20.glUseProgram(this.mProgram);
        GLES20.glUniformMatrix4fv(this.mProjMatrixLoc, 1, false, this.mProjMatrix, 0);
        GLES20.glUniformMatrix4fv(this.mTexMatrixLoc, 1, false, this.mTexMatrix, 0);
        GLES20.glUniform1f(this.mOpacityLoc, opacity);
        GLES20.glUniform1f(this.mGammaLoc, gamma);
        GLES20.glActiveTexture(33984);
        GLES20.glBindTexture(36197, this.mTexNames[0]);
        GLES20.glBindBuffer(34962, this.mGLBuffers[0]);
        GLES20.glEnableVertexAttribArray(this.mVertexLoc);
        GLES20.glVertexAttribPointer(this.mVertexLoc, 2, 5126, false, 0, 0);
        GLES20.glBindBuffer(34962, this.mGLBuffers[1]);
        GLES20.glEnableVertexAttribArray(this.mTexCoordLoc);
        GLES20.glVertexAttribPointer(this.mTexCoordLoc, 2, 5126, false, 0, 0);
        GLES20.glDrawArrays(6, 0, 4);
        GLES20.glBindTexture(36197, 0);
        GLES20.glBindBuffer(34962, 0);
    }

    private void ortho(float left, float right, float bottom, float top, float znear, float zfar) {
        this.mProjMatrix[0] = 2.0f / (right - left);
        this.mProjMatrix[1] = 0.0f;
        this.mProjMatrix[2] = 0.0f;
        this.mProjMatrix[3] = 0.0f;
        this.mProjMatrix[4] = 0.0f;
        this.mProjMatrix[5] = 2.0f / (top - bottom);
        this.mProjMatrix[6] = 0.0f;
        this.mProjMatrix[7] = 0.0f;
        this.mProjMatrix[8] = 0.0f;
        this.mProjMatrix[9] = 0.0f;
        this.mProjMatrix[10] = (-2.0f) / (zfar - znear);
        this.mProjMatrix[11] = 0.0f;
        this.mProjMatrix[12] = (-(right + left)) / (right - left);
        this.mProjMatrix[13] = (-(top + bottom)) / (top - bottom);
        this.mProjMatrix[14] = (-(zfar + znear)) / (zfar - znear);
        this.mProjMatrix[15] = 1.0f;
    }

    private boolean captureScreenshotTextureAndSetViewport() {
        if (attachEglContext()) {
            try {
                if (!this.mTexNamesGenerated) {
                    GLES20.glGenTextures(1, this.mTexNames, 0);
                    if (checkGlErrors("glGenTextures")) {
                        return false;
                    }
                    this.mTexNamesGenerated = true;
                }
                SurfaceTexture st = new SurfaceTexture(this.mTexNames[0]);
                Surface s = new Surface(st);
                SurfaceControl.screenshot(SurfaceControl.getBuiltInDisplay(0), s);
                st.updateTexImage();
                st.getTransformMatrix(this.mTexMatrix);
                s.release();
                st.release();
                this.mTexCoordBuffer.put(0, 0.0f);
                this.mTexCoordBuffer.put(1, 0.0f);
                this.mTexCoordBuffer.put(2, 0.0f);
                this.mTexCoordBuffer.put(3, 1.0f);
                this.mTexCoordBuffer.put(4, 1.0f);
                this.mTexCoordBuffer.put(5, 1.0f);
                this.mTexCoordBuffer.put(6, 1.0f);
                this.mTexCoordBuffer.put(7, 0.0f);
                GLES20.glViewport(0, 0, this.mDisplayWidth, this.mDisplayHeight);
                ortho(0.0f, this.mDisplayWidth, 0.0f, this.mDisplayHeight, -1.0f, 1.0f);
                return true;
            } finally {
                detachEglContext();
            }
        }
        return false;
    }

    private void destroyScreenshotTexture() {
        if (this.mTexNamesGenerated) {
            this.mTexNamesGenerated = false;
            GLES20.glDeleteTextures(1, this.mTexNames, 0);
            checkGlErrors("glDeleteTextures");
        }
    }

    private boolean createEglContext() {
        if (this.mEglDisplay == null) {
            this.mEglDisplay = EGL14.eglGetDisplay(0);
            if (this.mEglDisplay == EGL14.EGL_NO_DISPLAY) {
                logEglError("eglGetDisplay");
                return false;
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(this.mEglDisplay, version, 0, version, 1)) {
                this.mEglDisplay = null;
                logEglError("eglInitialize");
                return false;
            }
        }
        if (this.mEglConfig == null) {
            int[] eglConfigAttribList = {12352, 4, 12324, 8, 12323, 8, 12322, 8, 12321, 8, 12344};
            int[] numEglConfigs = new int[1];
            EGLConfig[] eglConfigs = new EGLConfig[1];
            if (!EGL14.eglChooseConfig(this.mEglDisplay, eglConfigAttribList, 0, eglConfigs, 0, eglConfigs.length, numEglConfigs, 0)) {
                logEglError("eglChooseConfig");
                return false;
            } else if (numEglConfigs[0] <= 0) {
                Slog.e(TAG, "no valid config found");
                return false;
            } else {
                this.mEglConfig = eglConfigs[0];
            }
        }
        if (this.mEglContext == null) {
            int[] eglContextAttribList = {12440, 2, 12344};
            this.mEglContext = EGL14.eglCreateContext(this.mEglDisplay, this.mEglConfig, EGL14.EGL_NO_CONTEXT, eglContextAttribList, 0);
            if (this.mEglContext == null) {
                logEglError("eglCreateContext");
                return false;
            }
        }
        return true;
    }

    private boolean createSurface() {
        int flags;
        if (this.mSurfaceSession == null) {
            this.mSurfaceSession = new SurfaceSession();
        }
        SurfaceControl.openTransaction();
        try {
            if (this.mSurfaceControl == null) {
                try {
                    if (this.mMode == 2) {
                        flags = 131076;
                    } else {
                        flags = UsbTerminalTypes.TERMINAL_BIDIR_SKRPHONE_SUPRESS;
                    }
                    this.mSurfaceControl = new SurfaceControl.Builder(this.mSurfaceSession).setName(TAG).setSize(this.mDisplayWidth, this.mDisplayHeight).setFlags(flags).build();
                    this.mSurfaceControl.setLayerStack(this.mDisplayLayerStack);
                    this.mSurfaceControl.setSize(this.mDisplayWidth, this.mDisplayHeight);
                    this.mSurface = new Surface();
                    this.mSurface.copyFrom(this.mSurfaceControl);
                    this.mSurfaceLayout = new NaturalSurfaceLayout(this.mDisplayManagerInternal, this.mDisplayId, this.mSurfaceControl);
                    this.mSurfaceLayout.onDisplayTransaction();
                } catch (Surface.OutOfResourcesException ex) {
                    Slog.e(TAG, "Unable to create surface.", ex);
                    SurfaceControl.closeTransaction();
                    return false;
                }
            }
            SurfaceControl.closeTransaction();
            return true;
        } catch (Throwable th) {
            SurfaceControl.closeTransaction();
            throw th;
        }
    }

    private boolean createEglSurface() {
        if (this.mEglSurface == null) {
            int[] eglSurfaceAttribList = {12344};
            this.mEglSurface = EGL14.eglCreateWindowSurface(this.mEglDisplay, this.mEglConfig, this.mSurface, eglSurfaceAttribList, 0);
            if (this.mEglSurface == null) {
                logEglError("eglCreateWindowSurface");
                return false;
            }
        }
        return true;
    }

    private void destroyEglSurface() {
        if (this.mEglSurface != null) {
            if (!EGL14.eglDestroySurface(this.mEglDisplay, this.mEglSurface)) {
                logEglError("eglDestroySurface");
            }
            this.mEglSurface = null;
        }
    }

    private void destroySurface() {
        if (this.mSurfaceControl != null) {
            this.mSurfaceLayout.dispose();
            this.mSurfaceLayout = null;
            SurfaceControl.openTransaction();
            try {
                this.mSurfaceControl.destroy();
                this.mSurface.release();
                SurfaceControl.closeTransaction();
                this.mSurfaceControl = null;
                this.mSurfaceVisible = false;
                this.mSurfaceAlpha = 0.0f;
            } catch (Throwable th) {
                SurfaceControl.closeTransaction();
                throw th;
            }
        }
    }

    private boolean showSurface(float alpha) {
        if (!this.mSurfaceVisible || this.mSurfaceAlpha != alpha) {
            SurfaceControl.openTransaction();
            try {
                this.mSurfaceControl.setLayer(1073741825);
                this.mSurfaceControl.setAlpha(alpha);
                this.mSurfaceControl.show();
                SurfaceControl.closeTransaction();
                this.mSurfaceVisible = true;
                this.mSurfaceAlpha = alpha;
            } catch (Throwable th) {
                SurfaceControl.closeTransaction();
                throw th;
            }
        }
        return true;
    }

    private boolean attachEglContext() {
        if (this.mEglSurface == null) {
            return false;
        }
        if (!EGL14.eglMakeCurrent(this.mEglDisplay, this.mEglSurface, this.mEglSurface, this.mEglContext)) {
            logEglError("eglMakeCurrent");
            return false;
        }
        return true;
    }

    private void detachEglContext() {
        if (this.mEglDisplay != null) {
            EGL14.eglMakeCurrent(this.mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }
    }

    private static FloatBuffer createNativeFloatBuffer(int size) {
        ByteBuffer bb = ByteBuffer.allocateDirect(size * 4);
        bb.order(ByteOrder.nativeOrder());
        return bb.asFloatBuffer();
    }

    private static void logEglError(String func) {
        Slog.e(TAG, func + " failed: error " + EGL14.eglGetError(), new Throwable());
    }

    private static boolean checkGlErrors(String func) {
        return checkGlErrors(func, true);
    }

    private static boolean checkGlErrors(String func, boolean log) {
        boolean hadError = false;
        while (true) {
            int error = GLES20.glGetError();
            if (error != 0) {
                if (log) {
                    Slog.e(TAG, func + " failed: error " + error, new Throwable());
                }
                hadError = true;
            } else {
                return hadError;
            }
        }
    }

    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("Color Fade State:");
        pw.println("  mPrepared=" + this.mPrepared);
        pw.println("  mMode=" + this.mMode);
        pw.println("  mDisplayLayerStack=" + this.mDisplayLayerStack);
        pw.println("  mDisplayWidth=" + this.mDisplayWidth);
        pw.println("  mDisplayHeight=" + this.mDisplayHeight);
        pw.println("  mSurfaceVisible=" + this.mSurfaceVisible);
        pw.println("  mSurfaceAlpha=" + this.mSurfaceAlpha);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class NaturalSurfaceLayout implements DisplayManagerInternal.DisplayTransactionListener {
        private final int mDisplayId;
        private final DisplayManagerInternal mDisplayManagerInternal;
        private SurfaceControl mSurfaceControl;

        public NaturalSurfaceLayout(DisplayManagerInternal displayManagerInternal, int displayId, SurfaceControl surfaceControl) {
            this.mDisplayManagerInternal = displayManagerInternal;
            this.mDisplayId = displayId;
            this.mSurfaceControl = surfaceControl;
            this.mDisplayManagerInternal.registerDisplayTransactionListener(this);
        }

        public void dispose() {
            synchronized (this) {
                this.mSurfaceControl = null;
            }
            this.mDisplayManagerInternal.unregisterDisplayTransactionListener(this);
        }

        public void onDisplayTransaction() {
            synchronized (this) {
                if (this.mSurfaceControl == null) {
                    return;
                }
                DisplayInfo displayInfo = this.mDisplayManagerInternal.getDisplayInfo(this.mDisplayId);
                switch (displayInfo.rotation) {
                    case 0:
                        this.mSurfaceControl.setPosition(0.0f, 0.0f);
                        this.mSurfaceControl.setMatrix(1.0f, 0.0f, 0.0f, 1.0f);
                        break;
                    case 1:
                        this.mSurfaceControl.setPosition(0.0f, displayInfo.logicalHeight);
                        this.mSurfaceControl.setMatrix(0.0f, -1.0f, 1.0f, 0.0f);
                        break;
                    case 2:
                        this.mSurfaceControl.setPosition(displayInfo.logicalWidth, displayInfo.logicalHeight);
                        this.mSurfaceControl.setMatrix(-1.0f, 0.0f, 0.0f, -1.0f);
                        break;
                    case 3:
                        this.mSurfaceControl.setPosition(displayInfo.logicalWidth, 0.0f);
                        this.mSurfaceControl.setMatrix(0.0f, 1.0f, -1.0f, 0.0f);
                        break;
                }
            }
        }
    }
}
