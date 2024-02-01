package com.android.server.wm;

import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Pools;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.MagnificationSpec;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.wm.SurfaceAnimator;
import com.android.server.wm.WindowContainer;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes2.dex */
public class WindowContainer<E extends WindowContainer> extends ConfigurationContainer<E> implements Comparable<WindowContainer>, SurfaceAnimator.Animatable {
    static final int ANIMATION_LAYER_BOOSTED = 1;
    static final int ANIMATION_LAYER_HOME = 2;
    static final int ANIMATION_LAYER_STANDARD = 0;
    static final int POSITION_BOTTOM = Integer.MIN_VALUE;
    static final int POSITION_TOP = Integer.MAX_VALUE;
    private static final String TAG = "WindowManager";
    private boolean mCommittedReparentToAnimationLeash;
    WindowContainerController mController;
    protected DisplayContent mDisplayContent;
    private final SurfaceControl.Transaction mPendingTransaction;
    protected final SurfaceAnimator mSurfaceAnimator;
    protected SurfaceControl mSurfaceControl;
    protected final WindowManagerService mWmService;
    private WindowContainer<WindowContainer> mParent = null;
    protected final WindowList<E> mChildren = new WindowList<>();
    protected int mOrientation = -1;
    private final Pools.SynchronizedPool<WindowContainer<E>.ForAllWindowsConsumerWrapper> mConsumerWrapperPool = new Pools.SynchronizedPool<>(3);
    private int mLastLayer = 0;
    private SurfaceControl mLastRelativeToLayer = null;
    private final Point mTmpPos = new Point();
    protected final Point mLastSurfacePosition = new Point();
    private int mTreeWeight = 1;
    private final LinkedList<WindowContainer> mTmpChain1 = new LinkedList<>();
    private final LinkedList<WindowContainer> mTmpChain2 = new LinkedList<>();

    /* loaded from: classes2.dex */
    @interface AnimationLayer {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowContainer(WindowManagerService wms) {
        this.mWmService = wms;
        this.mPendingTransaction = wms.mTransactionFactory.make();
        this.mSurfaceAnimator = new SurfaceAnimator(this, new Runnable() { // from class: com.android.server.wm.-$$Lambda$yVRF8YoeNdTa8GR1wDStVsHu8xM
            @Override // java.lang.Runnable
            public final void run() {
                WindowContainer.this.onAnimationFinished();
            }
        }, wms);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public final WindowContainer getParent() {
        return this.mParent;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public int getChildCount() {
        return this.mChildren.size();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.wm.ConfigurationContainer
    public E getChildAt(int index) {
        return this.mChildren.get(index);
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);
        updateSurfacePosition();
        scheduleAnimation();
    }

    protected final void setParent(WindowContainer<WindowContainer> parent) {
        this.mParent = parent;
        onParentChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @Override // com.android.server.wm.ConfigurationContainer
    public void onParentChanged() {
        super.onParentChanged();
        if (this.mParent == null) {
            return;
        }
        if (this.mSurfaceControl == null) {
            this.mSurfaceControl = makeSurface().build();
            getPendingTransaction().show(this.mSurfaceControl);
            updateSurfacePosition();
        } else {
            reparentSurfaceControl(getPendingTransaction(), this.mParent.mSurfaceControl);
        }
        this.mParent.assignChildLayers();
        scheduleAnimation();
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void addChild(E child, Comparator<E> comparator) {
        if (child.getParent() != null) {
            throw new IllegalArgumentException("addChild: container=" + child.getName() + " is already a child of container=" + child.getParent().getName() + " can't add to container=" + getName());
        }
        int positionToAdd = -1;
        if (comparator != null) {
            int count = this.mChildren.size();
            int i = 0;
            while (true) {
                if (i >= count) {
                    break;
                } else if (comparator.compare(child, this.mChildren.get(i)) >= 0) {
                    i++;
                } else {
                    positionToAdd = i;
                    break;
                }
            }
        }
        if (positionToAdd == -1) {
            this.mChildren.add(child);
        } else {
            this.mChildren.add(positionToAdd, child);
        }
        onChildAdded(child);
        child.setParent(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void addChild(E child, int index) {
        if (child.getParent() != null) {
            throw new IllegalArgumentException("addChild: container=" + child.getName() + " is already a child of container=" + child.getParent().getName() + " can't add to container=" + getName());
        } else if ((index < 0 && index != Integer.MIN_VALUE) || (index > this.mChildren.size() && index != POSITION_TOP)) {
            throw new IllegalArgumentException("addChild: invalid position=" + index + ", children number=" + this.mChildren.size());
        } else {
            if (index == POSITION_TOP) {
                index = this.mChildren.size();
            } else if (index == Integer.MIN_VALUE) {
                index = 0;
            }
            this.mChildren.add(index, child);
            onChildAdded(child);
            child.setParent(this);
        }
    }

    private void onChildAdded(WindowContainer child) {
        this.mTreeWeight += child.mTreeWeight;
        for (WindowContainer parent = getParent(); parent != null; parent = parent.getParent()) {
            parent.mTreeWeight += child.mTreeWeight;
        }
        onChildPositionChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeChild(E child) {
        if (this.mChildren.remove(child)) {
            onChildRemoved(child);
            child.setParent(null);
            return;
        }
        throw new IllegalArgumentException("removeChild: container=" + child.getName() + " is not a child of container=" + getName());
    }

    private void onChildRemoved(WindowContainer child) {
        this.mTreeWeight -= child.mTreeWeight;
        for (WindowContainer parent = getParent(); parent != null; parent = parent.getParent()) {
            parent.mTreeWeight -= child.mTreeWeight;
        }
        onChildPositionChanged();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeImmediately() {
        while (!this.mChildren.isEmpty()) {
            E child = this.mChildren.peekLast();
            child.removeImmediately();
            if (this.mChildren.remove(child)) {
                onChildRemoved(child);
            }
        }
        if (this.mSurfaceControl != null) {
            getPendingTransaction().remove(this.mSurfaceControl);
            WindowContainer<WindowContainer> windowContainer = this.mParent;
            if (windowContainer != null) {
                windowContainer.getPendingTransaction().merge(getPendingTransaction());
            }
            this.mSurfaceControl = null;
            scheduleAnimation();
        }
        WindowContainer<WindowContainer> windowContainer2 = this.mParent;
        if (windowContainer2 != null) {
            windowContainer2.removeChild(this);
        }
        if (this.mController != null) {
            setController(null);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getPrefixOrderIndex() {
        WindowContainer<WindowContainer> windowContainer = this.mParent;
        if (windowContainer == null) {
            return 0;
        }
        return windowContainer.getPrefixOrderIndex(this);
    }

    private int getPrefixOrderIndex(WindowContainer child) {
        WindowContainer childI;
        int order = 0;
        for (int i = 0; i < this.mChildren.size() && child != (childI = this.mChildren.get(i)); i++) {
            order += childI.mTreeWeight;
        }
        WindowContainer<WindowContainer> windowContainer = this.mParent;
        if (windowContainer != null) {
            order += windowContainer.getPrefixOrderIndex(this);
        }
        return order + 1;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void removeIfPossible() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            wc.removeIfPossible();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasChild(E child) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            E current = this.mChildren.get(i);
            if (current == child || current.hasChild(child)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void positionChildAt(int position, E child, boolean includingParents) {
        if (child.getParent() != this) {
            throw new IllegalArgumentException("removeChild: container=" + child.getName() + " is not a child of container=" + getName() + " current parent=" + child.getParent());
        } else if ((position < 0 && position != Integer.MIN_VALUE) || (position > this.mChildren.size() && position != POSITION_TOP)) {
            throw new IllegalArgumentException("positionAt: invalid position=" + position + ", children number=" + this.mChildren.size());
        } else {
            if (position >= this.mChildren.size() - 1) {
                position = POSITION_TOP;
            } else if (position == 0) {
                position = Integer.MIN_VALUE;
            }
            if (position == Integer.MIN_VALUE) {
                if (this.mChildren.peekFirst() != child) {
                    this.mChildren.remove(child);
                    this.mChildren.addFirst(child);
                    onChildPositionChanged();
                }
                if (includingParents && getParent() != null) {
                    getParent().positionChildAt(Integer.MIN_VALUE, this, true);
                }
            } else if (position == POSITION_TOP) {
                if (this.mChildren.peekLast() != child) {
                    this.mChildren.remove(child);
                    this.mChildren.add(child);
                    onChildPositionChanged();
                }
                if (includingParents && getParent() != null) {
                    getParent().positionChildAt(POSITION_TOP, this, true);
                }
            } else {
                this.mChildren.remove(child);
                this.mChildren.add(position, child);
                onChildPositionChanged();
            }
        }
    }

    void onChildPositionChanged() {
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        int diff = diffRequestedOverrideBounds(overrideConfiguration.windowConfiguration.getBounds());
        super.onRequestedOverrideConfigurationChanged(overrideConfiguration);
        WindowContainer<WindowContainer> windowContainer = this.mParent;
        if (windowContainer != null) {
            windowContainer.onDescendantOverrideConfigurationChanged();
        }
        if (diff == 0) {
            return;
        }
        if ((diff & 2) == 2) {
            onResize();
        } else {
            onMovedByResize();
        }
    }

    void onDescendantOverrideConfigurationChanged() {
        WindowContainer<WindowContainer> windowContainer = this.mParent;
        if (windowContainer != null) {
            windowContainer.onDescendantOverrideConfigurationChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onDisplayChanged(DisplayContent dc) {
        this.mDisplayContent = dc;
        if (dc != null && dc != this) {
            dc.getPendingTransaction().merge(this.mPendingTransaction);
        }
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer child = this.mChildren.get(i);
            child.onDisplayChanged(dc);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public DisplayContent getDisplayContent() {
        return this.mDisplayContent;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setWaitingForDrawnIfResizingChanged() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            wc.setWaitingForDrawnIfResizingChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onResize() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            wc.onParentResize();
        }
    }

    void onParentResize() {
        if (hasOverrideBounds()) {
            return;
        }
        onResize();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onMovedByResize() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            wc.onMovedByResize();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetDragResizingChangeReported() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            wc.resetDragResizingChangeReported();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void forceWindowsScaleableInTransaction(boolean force) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            wc.forceWindowsScaleableInTransaction(force);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSelfOrChildAnimating() {
        if (isSelfAnimating()) {
            return true;
        }
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            WindowContainer wc = this.mChildren.get(j);
            if (wc.isSelfOrChildAnimating()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAnimating() {
        WindowContainer<WindowContainer> windowContainer;
        return isSelfAnimating() || ((windowContainer = this.mParent) != null && windowContainer.isAnimating());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isAppAnimating() {
        for (int j = this.mChildren.size() - 1; j >= 0; j--) {
            WindowContainer wc = this.mChildren.get(j);
            if (wc.isAppAnimating()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isSelfAnimating() {
        return this.mSurfaceAnimator.isAnimating();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void sendAppVisibilityToClients() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            wc.sendAppVisibilityToClients();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasContentToDisplay() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            if (wc.hasContentToDisplay()) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isVisible() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            if (wc.isVisible()) {
                return true;
            }
        }
        return false;
    }

    /* JADX WARN: Multi-variable type inference failed */
    boolean isOnTop() {
        return getParent().getTopChild() == this && getParent().isOnTop();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public E getTopChild() {
        return this.mChildren.peekLast();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean checkCompleteDeferredRemoval() {
        boolean stillDeferringRemoval = false;
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            stillDeferringRemoval |= wc.checkCompleteDeferredRemoval();
        }
        return stillDeferringRemoval;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void checkAppWindowsReadyToShow() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            wc.checkAppWindowsReadyToShow();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onAppTransitionDone() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowContainer wc = this.mChildren.get(i);
            wc.onAppTransitionDone();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean onDescendantOrientationChanged(IBinder freezeDisplayToken, ConfigurationContainer requestingContainer) {
        WindowContainer parent = getParent();
        if (parent == null) {
            return false;
        }
        return parent.onDescendantOrientationChanged(freezeDisplayToken, requestingContainer);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean handlesOrientationChangeFromDescendant() {
        WindowContainer parent = getParent();
        return parent != null && parent.handlesOrientationChangeFromDescendant();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOrientation(int orientation) {
        setOrientation(orientation, null, null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setOrientation(int orientation, IBinder freezeDisplayToken, ConfigurationContainer requestingContainer) {
        boolean changed = this.mOrientation != orientation;
        this.mOrientation = orientation;
        if (!changed) {
            return;
        }
        WindowContainer parent = getParent();
        if (parent != null) {
            onDescendantOrientationChanged(freezeDisplayToken, requestingContainer);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getOrientation() {
        return getOrientation(this.mOrientation);
    }

    int getOrientation(int candidate) {
        if (fillsParent()) {
            int i = this.mOrientation;
            if (i != -2 && i != -1) {
                return i;
            }
            for (int i2 = this.mChildren.size() - 1; i2 >= 0; i2--) {
                WindowContainer wc = this.mChildren.get(i2);
                int orientation = wc.getOrientation(candidate == 3 ? 3 : -2);
                if (orientation == 3) {
                    candidate = orientation;
                } else if (orientation != -2 && (wc.fillsParent() || orientation != -1)) {
                    return orientation;
                }
            }
            return candidate;
        }
        return -2;
    }

    boolean fillsParent() {
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void switchUser() {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            this.mChildren.get(i).switchUser();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            for (int i = this.mChildren.size() - 1; i >= 0; i--) {
                if (this.mChildren.get(i).forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
            return false;
        }
        int count = this.mChildren.size();
        for (int i2 = 0; i2 < count; i2++) {
            if (this.mChildren.get(i2).forAllWindows(callback, traverseTopToBottom)) {
                return true;
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void forAllWindows(Consumer<WindowState> callback, boolean traverseTopToBottom) {
        WindowContainer<E>.ForAllWindowsConsumerWrapper wrapper = obtainConsumerWrapper(callback);
        forAllWindows(wrapper, traverseTopToBottom);
        wrapper.release();
    }

    void forAllAppWindows(Consumer<AppWindowToken> callback) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            this.mChildren.get(i).forAllAppWindows(callback);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void forAllTasks(Consumer<Task> callback) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            this.mChildren.get(i).forAllTasks(callback);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WindowState getWindow(Predicate<WindowState> callback) {
        for (int i = this.mChildren.size() - 1; i >= 0; i--) {
            WindowState w = this.mChildren.get(i).getWindow(callback);
            if (w != null) {
                return w;
            }
        }
        return null;
    }

    /* JADX WARN: Can't rename method to resolve collision */
    /* JADX WARN: Code restructure failed: missing block: B:22:0x0055, code lost:
        if (r4 != r10) goto L26;
     */
    /* JADX WARN: Code restructure failed: missing block: B:24:0x0062, code lost:
        return -1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:25:0x0063, code lost:
        if (r4 != r11) goto L29;
     */
    /* JADX WARN: Code restructure failed: missing block: B:27:0x0070, code lost:
        return 1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:28:0x0071, code lost:
        r7 = r4.mChildren;
     */
    /* JADX WARN: Code restructure failed: missing block: B:29:0x0083, code lost:
        if (r7.indexOf(r0.peekLast()) <= r7.indexOf(r3.peekLast())) goto L31;
     */
    /* JADX WARN: Code restructure failed: missing block: B:31:0x0086, code lost:
        r1 = -1;
     */
    /* JADX WARN: Code restructure failed: missing block: B:33:0x0091, code lost:
        return r1;
     */
    @Override // java.lang.Comparable
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public int compareTo(com.android.server.wm.WindowContainer r11) {
        /*
            r10 = this;
            if (r10 != r11) goto L4
            r0 = 0
            return r0
        L4:
            com.android.server.wm.WindowContainer<com.android.server.wm.WindowContainer> r0 = r10.mParent
            r1 = 1
            r2 = -1
            if (r0 == 0) goto L1d
            com.android.server.wm.WindowContainer<com.android.server.wm.WindowContainer> r3 = r11.mParent
            if (r0 != r3) goto L1d
            com.android.server.wm.WindowList<E extends com.android.server.wm.WindowContainer> r0 = r0.mChildren
            int r3 = r0.indexOf(r10)
            int r4 = r0.indexOf(r11)
            if (r3 <= r4) goto L1b
            goto L1c
        L1b:
            r1 = r2
        L1c:
            return r1
        L1d:
            java.util.LinkedList<com.android.server.wm.WindowContainer> r0 = r10.mTmpChain1
            java.util.LinkedList<com.android.server.wm.WindowContainer> r3 = r10.mTmpChain2
            r10.getParents(r0)     // Catch: java.lang.Throwable -> Lb1
            r11.getParents(r3)     // Catch: java.lang.Throwable -> Lb1
            r4 = 0
            java.lang.Object r5 = r0.peekLast()     // Catch: java.lang.Throwable -> Lb1
            com.android.server.wm.WindowContainer r5 = (com.android.server.wm.WindowContainer) r5     // Catch: java.lang.Throwable -> Lb1
            java.lang.Object r6 = r3.peekLast()     // Catch: java.lang.Throwable -> Lb1
            com.android.server.wm.WindowContainer r6 = (com.android.server.wm.WindowContainer) r6     // Catch: java.lang.Throwable -> Lb1
        L34:
            if (r5 == 0) goto L53
            if (r6 == 0) goto L53
            if (r5 != r6) goto L53
            java.lang.Object r7 = r0.removeLast()     // Catch: java.lang.Throwable -> Lb1
            com.android.server.wm.WindowContainer r7 = (com.android.server.wm.WindowContainer) r7     // Catch: java.lang.Throwable -> Lb1
            r4 = r7
            r3.removeLast()     // Catch: java.lang.Throwable -> Lb1
            java.lang.Object r7 = r0.peekLast()     // Catch: java.lang.Throwable -> Lb1
            com.android.server.wm.WindowContainer r7 = (com.android.server.wm.WindowContainer) r7     // Catch: java.lang.Throwable -> Lb1
            r5 = r7
            java.lang.Object r7 = r3.peekLast()     // Catch: java.lang.Throwable -> Lb1
            com.android.server.wm.WindowContainer r7 = (com.android.server.wm.WindowContainer) r7     // Catch: java.lang.Throwable -> Lb1
            r6 = r7
            goto L34
        L53:
            if (r4 == 0) goto L92
            if (r4 != r10) goto L63
        L58:
            java.util.LinkedList<com.android.server.wm.WindowContainer> r1 = r10.mTmpChain1
            r1.clear()
            java.util.LinkedList<com.android.server.wm.WindowContainer> r1 = r10.mTmpChain2
            r1.clear()
            return r2
        L63:
            if (r4 != r11) goto L71
        L66:
            java.util.LinkedList<com.android.server.wm.WindowContainer> r2 = r10.mTmpChain1
            r2.clear()
            java.util.LinkedList<com.android.server.wm.WindowContainer> r2 = r10.mTmpChain2
            r2.clear()
            return r1
        L71:
            com.android.server.wm.WindowList<E extends com.android.server.wm.WindowContainer> r7 = r4.mChildren     // Catch: java.lang.Throwable -> Lb1
            java.lang.Object r8 = r0.peekLast()     // Catch: java.lang.Throwable -> Lb1
            int r8 = r7.indexOf(r8)     // Catch: java.lang.Throwable -> Lb1
            java.lang.Object r9 = r3.peekLast()     // Catch: java.lang.Throwable -> Lb1
            int r9 = r7.indexOf(r9)     // Catch: java.lang.Throwable -> Lb1
            if (r8 <= r9) goto L86
            goto L87
        L86:
            r1 = r2
        L87:
            java.util.LinkedList<com.android.server.wm.WindowContainer> r2 = r10.mTmpChain1
            r2.clear()
            java.util.LinkedList<com.android.server.wm.WindowContainer> r2 = r10.mTmpChain2
            r2.clear()
            return r1
        L92:
            java.lang.IllegalArgumentException r1 = new java.lang.IllegalArgumentException     // Catch: java.lang.Throwable -> Lb1
            java.lang.StringBuilder r2 = new java.lang.StringBuilder     // Catch: java.lang.Throwable -> Lb1
            r2.<init>()     // Catch: java.lang.Throwable -> Lb1
            java.lang.String r7 = "No in the same hierarchy this="
            r2.append(r7)     // Catch: java.lang.Throwable -> Lb1
            r2.append(r0)     // Catch: java.lang.Throwable -> Lb1
            java.lang.String r7 = " other="
            r2.append(r7)     // Catch: java.lang.Throwable -> Lb1
            r2.append(r3)     // Catch: java.lang.Throwable -> Lb1
            java.lang.String r2 = r2.toString()     // Catch: java.lang.Throwable -> Lb1
            r1.<init>(r2)     // Catch: java.lang.Throwable -> Lb1
            throw r1     // Catch: java.lang.Throwable -> Lb1
        Lb1:
            r1 = move-exception
            java.util.LinkedList<com.android.server.wm.WindowContainer> r2 = r10.mTmpChain1
            r2.clear()
            java.util.LinkedList<com.android.server.wm.WindowContainer> r2 = r10.mTmpChain2
            r2.clear()
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.WindowContainer.compareTo(com.android.server.wm.WindowContainer):int");
    }

    private void getParents(LinkedList<WindowContainer> parents) {
        parents.clear();
        WindowContainer current = this;
        do {
            parents.addLast(current);
            current = current.mParent;
        } while (current != null);
    }

    WindowContainerController getController() {
        return this.mController;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setController(WindowContainerController controller) {
        if (this.mController != null && controller != null) {
            throw new IllegalArgumentException("Can't set controller=" + this.mController + " for container=" + this + " Already set to=" + this.mController);
        }
        if (controller != null) {
            controller.setContainer(this);
        } else {
            WindowContainerController windowContainerController = this.mController;
            if (windowContainerController != null) {
                windowContainerController.setContainer(null);
            }
        }
        this.mController = controller;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceControl.Builder makeSurface() {
        WindowContainer p = getParent();
        return p.makeChildSurface(this);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceControl.Builder makeChildSurface(WindowContainer child) {
        WindowContainer p = getParent();
        return p.makeChildSurface(child).setParent(this.mSurfaceControl);
    }

    public SurfaceControl getParentSurfaceControl() {
        WindowContainer parent = getParent();
        if (parent == null) {
            return null;
        }
        return parent.getSurfaceControl();
    }

    boolean shouldMagnify() {
        if (this.mSurfaceControl == null) {
            return false;
        }
        for (int i = 0; i < this.mChildren.size(); i++) {
            if (!this.mChildren.get(i).shouldMagnify()) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceSession getSession() {
        if (getParent() != null) {
            return getParent().getSession();
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void assignLayer(SurfaceControl.Transaction t, int layer) {
        boolean changed = (layer == this.mLastLayer && this.mLastRelativeToLayer == null) ? false : true;
        if (this.mSurfaceControl != null && changed) {
            setLayer(t, layer);
            this.mLastLayer = layer;
            this.mLastRelativeToLayer = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void assignRelativeLayer(SurfaceControl.Transaction t, SurfaceControl relativeTo, int layer) {
        boolean changed = (layer == this.mLastLayer && this.mLastRelativeToLayer == relativeTo) ? false : true;
        if (this.mSurfaceControl != null && changed) {
            setRelativeLayer(t, relativeTo, layer);
            this.mLastLayer = layer;
            this.mLastRelativeToLayer = relativeTo;
        }
    }

    protected void setLayer(SurfaceControl.Transaction t, int layer) {
        this.mSurfaceAnimator.setLayer(t, layer);
    }

    protected void setRelativeLayer(SurfaceControl.Transaction t, SurfaceControl relativeTo, int layer) {
        this.mSurfaceAnimator.setRelativeLayer(t, relativeTo, layer);
    }

    protected void reparentSurfaceControl(SurfaceControl.Transaction t, SurfaceControl newParent) {
        this.mSurfaceAnimator.reparent(t, newParent);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void assignChildLayers(SurfaceControl.Transaction t) {
        int layer = 0;
        for (int j = 0; j < this.mChildren.size(); j++) {
            WindowContainer wc = this.mChildren.get(j);
            wc.assignChildLayers(t);
            if (!wc.needsZBoost()) {
                wc.assignLayer(t, layer);
                layer++;
            }
        }
        for (int j2 = 0; j2 < this.mChildren.size(); j2++) {
            WindowContainer wc2 = this.mChildren.get(j2);
            if (wc2.needsZBoost()) {
                wc2.assignLayer(t, layer);
                layer++;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void assignChildLayers() {
        assignChildLayers(getPendingTransaction());
        scheduleAnimation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean needsZBoost() {
        for (int i = 0; i < this.mChildren.size(); i++) {
            if (this.mChildren.get(i).needsZBoost()) {
                return true;
            }
        }
        return false;
    }

    @Override // com.android.server.wm.ConfigurationContainer
    public void writeToProto(ProtoOutputStream proto, long fieldId, int logLevel) {
        boolean isVisible = isVisible();
        if (logLevel == 2 && !isVisible) {
            return;
        }
        long token = proto.start(fieldId);
        super.writeToProto(proto, 1146756268033L, logLevel);
        proto.write(1120986464258L, this.mOrientation);
        proto.write(1133871366147L, isVisible);
        if (this.mSurfaceAnimator.isAnimating()) {
            this.mSurfaceAnimator.writeToProto(proto, 1146756268036L);
        }
        proto.end(token);
    }

    private WindowContainer<E>.ForAllWindowsConsumerWrapper obtainConsumerWrapper(Consumer<WindowState> consumer) {
        WindowContainer<E>.ForAllWindowsConsumerWrapper wrapper = (ForAllWindowsConsumerWrapper) this.mConsumerWrapperPool.acquire();
        if (wrapper == null) {
            wrapper = new ForAllWindowsConsumerWrapper();
        }
        wrapper.setConsumer(consumer);
        return wrapper;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes2.dex */
    public final class ForAllWindowsConsumerWrapper implements ToBooleanFunction<WindowState> {
        private Consumer<WindowState> mConsumer;

        private ForAllWindowsConsumerWrapper() {
        }

        void setConsumer(Consumer<WindowState> consumer) {
            this.mConsumer = consumer;
        }

        public boolean apply(WindowState w) {
            this.mConsumer.accept(w);
            return false;
        }

        void release() {
            this.mConsumer = null;
            WindowContainer.this.mConsumerWrapperPool.release(this);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void applyMagnificationSpec(SurfaceControl.Transaction t, MagnificationSpec spec) {
        if (shouldMagnify()) {
            t.setMatrix(this.mSurfaceControl, spec.scale, 0.0f, 0.0f, spec.scale).setPosition(this.mSurfaceControl, spec.offsetX, spec.offsetY);
            return;
        }
        for (int i = 0; i < this.mChildren.size(); i++) {
            this.mChildren.get(i).applyMagnificationSpec(t, spec);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareSurfaces() {
        this.mCommittedReparentToAnimationLeash = this.mSurfaceAnimator.hasLeash();
        for (int i = 0; i < this.mChildren.size(); i++) {
            this.mChildren.get(i).prepareSurfaces();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean hasCommittedReparentToAnimationLeash() {
        return this.mCommittedReparentToAnimationLeash;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void scheduleAnimation() {
        WindowContainer<WindowContainer> windowContainer = this.mParent;
        if (windowContainer != null) {
            windowContainer.scheduleAnimation();
        }
    }

    public SurfaceControl getSurfaceControl() {
        return this.mSurfaceControl;
    }

    public SurfaceControl.Transaction getPendingTransaction() {
        DisplayContent displayContent = getDisplayContent();
        if (displayContent != null && displayContent != this) {
            return displayContent.getPendingTransaction();
        }
        return this.mPendingTransaction;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startAnimation(SurfaceControl.Transaction t, AnimationAdapter anim, boolean hidden) {
        if (WindowManagerDebugConfig.DEBUG_ANIM) {
            Slog.v(TAG, "Starting animation on " + this + ": " + anim);
        }
        this.mSurfaceAnimator.startAnimation(t, anim, hidden);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void transferAnimation(WindowContainer from) {
        this.mSurfaceAnimator.transferAnimation(from.mSurfaceAnimator);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void cancelAnimation() {
        this.mSurfaceAnimator.cancelAnimation();
    }

    public SurfaceControl.Builder makeAnimationLeash() {
        return makeSurface();
    }

    public SurfaceControl getAnimationLeashParent() {
        return getParentSurfaceControl();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SurfaceControl getAppAnimationLayer(@AnimationLayer int animationLayer) {
        WindowContainer parent = getParent();
        if (parent != null) {
            return parent.getAppAnimationLayer(animationLayer);
        }
        return null;
    }

    public void commitPendingTransaction() {
        scheduleAnimation();
    }

    void reassignLayer(SurfaceControl.Transaction t) {
        WindowContainer parent = getParent();
        if (parent != null) {
            parent.assignChildLayers(t);
        }
    }

    public void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash) {
        this.mLastLayer = -1;
        reassignLayer(t);
    }

    public void onAnimationLeashLost(SurfaceControl.Transaction t) {
        this.mLastLayer = -1;
        reassignLayer(t);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void onAnimationFinished() {
        this.mWmService.onAnimationFinished();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AnimationAdapter getAnimation() {
        return this.mSurfaceAnimator.getAnimation();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void startDelayingAnimationStart() {
        this.mSurfaceAnimator.startDelayingAnimationStart();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void endDelayingAnimationStart() {
        this.mSurfaceAnimator.endDelayingAnimationStart();
    }

    public int getSurfaceWidth() {
        return this.mSurfaceControl.getWidth();
    }

    public int getSurfaceHeight() {
        return this.mSurfaceControl.getHeight();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (this.mSurfaceAnimator.isAnimating()) {
            pw.print(prefix);
            pw.println("ContainerAnimator:");
            SurfaceAnimator surfaceAnimator = this.mSurfaceAnimator;
            surfaceAnimator.dump(pw, prefix + "  ");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void updateSurfacePosition() {
        if (this.mSurfaceControl == null) {
            return;
        }
        getRelativeDisplayedPosition(this.mTmpPos);
        if (this.mTmpPos.equals(this.mLastSurfacePosition)) {
            return;
        }
        getPendingTransaction().setPosition(this.mSurfaceControl, this.mTmpPos.x, this.mTmpPos.y);
        this.mLastSurfacePosition.set(this.mTmpPos.x, this.mTmpPos.y);
    }

    @VisibleForTesting
    Point getLastSurfacePosition() {
        return this.mLastSurfacePosition;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getDisplayedBounds() {
        return getBounds();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void getRelativeDisplayedPosition(Point outPos) {
        Rect dispBounds = getDisplayedBounds();
        outPos.set(dispBounds.left, dispBounds.top);
        WindowContainer parent = getParent();
        if (parent != null) {
            Rect parentBounds = parent.getDisplayedBounds();
            outPos.offset(-parentBounds.left, -parentBounds.top);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Dimmer getDimmer() {
        WindowContainer<WindowContainer> windowContainer = this.mParent;
        if (windowContainer == null) {
            return null;
        }
        return windowContainer.getDimmer();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Dimmer getDimmer(int sharedId) {
        WindowContainer<WindowContainer> windowContainer = this.mParent;
        if (windowContainer == null) {
            return null;
        }
        return windowContainer.getDimmer(sharedId);
    }
}
