package com.android.server.wm;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.ConfigurationContainer;
import java.io.PrintWriter;
import java.util.ArrayList;

/* loaded from: classes2.dex */
public abstract class ConfigurationContainer<E extends ConfigurationContainer> {
    static final int BOUNDS_CHANGE_NONE = 0;
    static final int BOUNDS_CHANGE_POSITION = 1;
    static final int BOUNDS_CHANGE_SIZE = 2;
    private boolean mHasOverrideConfiguration;
    private int mLastOverrideConfigurationChanges;
    private Rect mReturnBounds = new Rect();
    private Configuration mRequestedOverrideConfiguration = new Configuration();
    private Configuration mResolvedOverrideConfiguration = new Configuration();
    private Configuration mFullConfiguration = new Configuration();
    private Configuration mMergedOverrideConfiguration = new Configuration();
    private ArrayList<ConfigurationContainerListener> mChangeListeners = new ArrayList<>();
    private final Configuration mTmpConfig = new Configuration();
    private final Rect mTmpRect = new Rect();

    protected abstract E getChildAt(int i);

    protected abstract int getChildCount();

    protected abstract ConfigurationContainer getParent();

    public Configuration getConfiguration() {
        return this.mFullConfiguration;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getLastOverrideConfigurationChanges() {
        return this.mLastOverrideConfigurationChanges;
    }

    public void onConfigurationChanged(Configuration newParentConfig) {
        this.mTmpConfig.setTo(this.mResolvedOverrideConfiguration);
        resolveOverrideConfiguration(newParentConfig);
        this.mFullConfiguration.setTo(newParentConfig);
        this.mLastOverrideConfigurationChanges = this.mFullConfiguration.updateFrom(this.mResolvedOverrideConfiguration);
        if (!this.mTmpConfig.equals(this.mResolvedOverrideConfiguration)) {
            onMergedOverrideConfigurationChanged();
            for (int i = this.mChangeListeners.size() - 1; i >= 0; i--) {
                this.mChangeListeners.get(i).onRequestedOverrideConfigurationChanged(this.mResolvedOverrideConfiguration);
            }
        }
        int i2 = getChildCount();
        for (int i3 = i2 - 1; i3 >= 0; i3--) {
            ConfigurationContainer child = getChildAt(i3);
            child.onConfigurationChanged(this.mFullConfiguration);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resolveOverrideConfiguration(Configuration newParentConfig) {
        this.mResolvedOverrideConfiguration.setTo(this.mRequestedOverrideConfiguration);
    }

    public Configuration getRequestedOverrideConfiguration() {
        return this.mRequestedOverrideConfiguration;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Configuration getResolvedOverrideConfiguration() {
        return this.mResolvedOverrideConfiguration;
    }

    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        this.mHasOverrideConfiguration = !Configuration.EMPTY.equals(overrideConfiguration);
        this.mRequestedOverrideConfiguration.setTo(overrideConfiguration);
        ConfigurationContainer parent = getParent();
        onConfigurationChanged(parent != null ? parent.getConfiguration() : Configuration.EMPTY);
    }

    public Configuration getMergedOverrideConfiguration() {
        return this.mMergedOverrideConfiguration;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onMergedOverrideConfigurationChanged() {
        ConfigurationContainer parent = getParent();
        if (parent != null) {
            this.mMergedOverrideConfiguration.setTo(parent.getMergedOverrideConfiguration());
            this.mMergedOverrideConfiguration.updateFrom(this.mResolvedOverrideConfiguration);
        } else {
            this.mMergedOverrideConfiguration.setTo(this.mResolvedOverrideConfiguration);
        }
        for (int i = getChildCount() - 1; i >= 0; i--) {
            ConfigurationContainer child = getChildAt(i);
            child.onMergedOverrideConfigurationChanged();
        }
    }

    public boolean matchParentBounds() {
        return getRequestedOverrideBounds().isEmpty();
    }

    public boolean equivalentRequestedOverrideBounds(Rect bounds) {
        return equivalentBounds(getRequestedOverrideBounds(), bounds);
    }

    public static boolean equivalentBounds(Rect bounds, Rect other) {
        return bounds == other || (bounds != null && (bounds.equals(other) || (bounds.isEmpty() && other == null))) || (other != null && other.isEmpty() && bounds == null);
    }

    public Rect getBounds() {
        this.mReturnBounds.set(getConfiguration().windowConfiguration.getBounds());
        return this.mReturnBounds;
    }

    public void getBounds(Rect outBounds) {
        outBounds.set(getBounds());
    }

    public void getPosition(Point out) {
        Rect bounds = getBounds();
        out.set(bounds.left, bounds.top);
    }

    public Rect getRequestedOverrideBounds() {
        this.mReturnBounds.set(getRequestedOverrideConfiguration().windowConfiguration.getBounds());
        return this.mReturnBounds;
    }

    public boolean hasOverrideBounds() {
        return !getRequestedOverrideBounds().isEmpty();
    }

    public void getRequestedOverrideBounds(Rect outBounds) {
        outBounds.set(getRequestedOverrideBounds());
    }

    public int setBounds(Rect bounds) {
        int boundsChange = diffRequestedOverrideBounds(bounds);
        if (boundsChange == 0) {
            return boundsChange;
        }
        this.mTmpConfig.setTo(getRequestedOverrideConfiguration());
        this.mTmpConfig.windowConfiguration.setBounds(bounds);
        onRequestedOverrideConfigurationChanged(this.mTmpConfig);
        return boundsChange;
    }

    public int setBounds(int left, int top, int right, int bottom) {
        this.mTmpRect.set(left, top, right, bottom);
        return setBounds(this.mTmpRect);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int diffRequestedOverrideBounds(Rect bounds) {
        if (equivalentRequestedOverrideBounds(bounds)) {
            return 0;
        }
        int boundsChange = 0;
        Rect existingBounds = getRequestedOverrideBounds();
        if (bounds == null || existingBounds.left != bounds.left || existingBounds.top != bounds.top) {
            boundsChange = 0 | 1;
        }
        if (bounds == null || existingBounds.width() != bounds.width() || existingBounds.height() != bounds.height()) {
            return boundsChange | 2;
        }
        return boundsChange;
    }

    boolean hasOverrideConfiguration() {
        return this.mHasOverrideConfiguration;
    }

    public WindowConfiguration getWindowConfiguration() {
        return this.mFullConfiguration.windowConfiguration;
    }

    public int getWindowingMode() {
        return this.mFullConfiguration.windowConfiguration.getWindowingMode();
    }

    public int getRequestedOverrideWindowingMode() {
        return this.mRequestedOverrideConfiguration.windowConfiguration.getWindowingMode();
    }

    public void setWindowingMode(int windowingMode) {
        this.mTmpConfig.setTo(getRequestedOverrideConfiguration());
        this.mTmpConfig.windowConfiguration.setWindowingMode(windowingMode);
        onRequestedOverrideConfigurationChanged(this.mTmpConfig);
    }

    public void setAlwaysOnTop(boolean alwaysOnTop) {
        this.mTmpConfig.setTo(getRequestedOverrideConfiguration());
        this.mTmpConfig.windowConfiguration.setAlwaysOnTop(alwaysOnTop);
        onRequestedOverrideConfigurationChanged(this.mTmpConfig);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDisplayWindowingMode(int windowingMode) {
        this.mTmpConfig.setTo(getRequestedOverrideConfiguration());
        this.mTmpConfig.windowConfiguration.setDisplayWindowingMode(windowingMode);
        onRequestedOverrideConfigurationChanged(this.mTmpConfig);
    }

    public boolean inMultiWindowMode() {
        int windowingMode = this.mFullConfiguration.windowConfiguration.getWindowingMode();
        return (windowingMode == 1 || windowingMode == 0) ? false : true;
    }

    public boolean inSplitScreenWindowingMode() {
        int windowingMode = this.mFullConfiguration.windowConfiguration.getWindowingMode();
        return windowingMode == 3 || windowingMode == 4;
    }

    public boolean inSplitScreenSecondaryWindowingMode() {
        int windowingMode = this.mFullConfiguration.windowConfiguration.getWindowingMode();
        return windowingMode == 4;
    }

    public boolean inSplitScreenPrimaryWindowingMode() {
        return this.mFullConfiguration.windowConfiguration.getWindowingMode() == 3;
    }

    public boolean supportsSplitScreenWindowingMode() {
        return this.mFullConfiguration.windowConfiguration.supportSplitScreenWindowingMode();
    }

    public boolean inPinnedWindowingMode() {
        return this.mFullConfiguration.windowConfiguration.getWindowingMode() == 2;
    }

    public boolean inFreeformWindowingMode() {
        return this.mFullConfiguration.windowConfiguration.getWindowingMode() == 5;
    }

    public int getActivityType() {
        return this.mFullConfiguration.windowConfiguration.getActivityType();
    }

    public void setActivityType(int activityType) {
        int currentActivityType = getActivityType();
        if (currentActivityType == activityType) {
            return;
        }
        if (currentActivityType != 0) {
            throw new IllegalStateException("Can't change activity type once set: " + this + " activityType=" + WindowConfiguration.activityTypeToString(activityType));
        }
        this.mTmpConfig.setTo(getRequestedOverrideConfiguration());
        this.mTmpConfig.windowConfiguration.setActivityType(activityType);
        onRequestedOverrideConfigurationChanged(this.mTmpConfig);
    }

    public boolean isActivityTypeHome() {
        return getActivityType() == 2;
    }

    public boolean isActivityTypeRecents() {
        return getActivityType() == 3;
    }

    public boolean isActivityTypeAssistant() {
        return getActivityType() == 4;
    }

    public boolean isActivityTypeStandard() {
        return getActivityType() == 1;
    }

    public boolean isActivityTypeStandardOrUndefined() {
        int activityType = getActivityType();
        return activityType == 1 || activityType == 0;
    }

    public boolean hasCompatibleActivityType(ConfigurationContainer other) {
        int thisType = getActivityType();
        int otherType = other.getActivityType();
        if (thisType == otherType) {
            return true;
        }
        if (thisType == 4) {
            return false;
        }
        return thisType == 0 || otherType == 0;
    }

    public boolean isCompatible(int windowingMode, int activityType) {
        int thisActivityType = getActivityType();
        int thisWindowingMode = getWindowingMode();
        boolean sameActivityType = thisActivityType == activityType;
        boolean sameWindowingMode = thisWindowingMode == windowingMode;
        if (sameActivityType && sameWindowingMode) {
            return true;
        }
        if ((activityType != 0 && activityType != 1) || !isActivityTypeStandardOrUndefined()) {
            return sameActivityType;
        }
        return sameWindowingMode;
    }

    public void registerConfigurationChangeListener(ConfigurationContainerListener listener) {
        if (this.mChangeListeners.contains(listener)) {
            return;
        }
        this.mChangeListeners.add(listener);
        listener.onRequestedOverrideConfigurationChanged(this.mResolvedOverrideConfiguration);
    }

    public void unregisterConfigurationChangeListener(ConfigurationContainerListener listener) {
        this.mChangeListeners.remove(listener);
    }

    @VisibleForTesting
    boolean containsListener(ConfigurationContainerListener listener) {
        return this.mChangeListeners.contains(listener);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onParentChanged() {
        ConfigurationContainer parent = getParent();
        if (parent != null) {
            onConfigurationChanged(parent.mFullConfiguration);
            onMergedOverrideConfigurationChanged();
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void writeToProto(ProtoOutputStream proto, long fieldId, int logLevel) {
        if (logLevel != 0 && !this.mHasOverrideConfiguration) {
            return;
        }
        long token = proto.start(fieldId);
        this.mRequestedOverrideConfiguration.writeToProto(proto, 1146756268033L, logLevel == 2);
        if (logLevel == 0) {
            this.mFullConfiguration.writeToProto(proto, 1146756268034L, false);
            this.mMergedOverrideConfiguration.writeToProto(proto, 1146756268035L, false);
        }
        proto.end(token);
    }

    public void dumpChildrenNames(PrintWriter pw, String prefix) {
        String childPrefix = prefix + " ";
        pw.println(getName() + " type=" + WindowConfiguration.activityTypeToString(getActivityType()) + " mode=" + WindowConfiguration.windowingModeToString(getWindowingMode()) + " override-mode=" + WindowConfiguration.windowingModeToString(getRequestedOverrideWindowingMode()));
        for (int i = getChildCount() + (-1); i >= 0; i += -1) {
            E cc = getChildAt(i);
            pw.print(childPrefix + "#" + i + " ");
            cc.dumpChildrenNames(pw, childPrefix);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getName() {
        return toString();
    }

    public boolean isAlwaysOnTop() {
        return this.mFullConfiguration.windowConfiguration.isAlwaysOnTop();
    }
}
