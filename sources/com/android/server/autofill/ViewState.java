package com.android.server.autofill;

import android.graphics.Rect;
import android.service.autofill.FillResponse;
import android.util.DebugUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import java.io.PrintWriter;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class ViewState {
    public static final int STATE_AUTOFILLED = 4;
    public static final int STATE_AUTOFILL_FAILED = 1024;
    public static final int STATE_CHANGED = 8;
    public static final int STATE_FILLABLE = 2;
    public static final int STATE_IGNORED = 128;
    public static final int STATE_INITIAL = 1;
    public static final int STATE_RESTARTED_SESSION = 256;
    public static final int STATE_STARTED_PARTITION = 32;
    public static final int STATE_STARTED_SESSION = 16;
    public static final int STATE_UNKNOWN = 0;
    public static final int STATE_URL_BAR = 512;
    public static final int STATE_WAITING_DATASET_AUTH = 64;
    private static final String TAG = "ViewState";
    public final AutofillId id;
    private AutofillValue mAutofilledValue;
    private AutofillValue mCurrentValue;
    private String mDatasetId;
    private final Listener mListener;
    private FillResponse mResponse;
    private AutofillValue mSanitizedValue;
    private final Session mSession;
    private int mState;
    private Rect mVirtualBounds;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface Listener {
        void onFillReady(FillResponse fillResponse, AutofillId autofillId, AutofillValue autofillValue);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public ViewState(Session session, AutofillId id, Listener listener, int state) {
        this.mSession = session;
        this.id = id;
        this.mListener = listener;
        this.mState = state;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public Rect getVirtualBounds() {
        return this.mVirtualBounds;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AutofillValue getCurrentValue() {
        return this.mCurrentValue;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setCurrentValue(AutofillValue value) {
        this.mCurrentValue = value;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AutofillValue getAutofilledValue() {
        return this.mAutofilledValue;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setAutofilledValue(AutofillValue value) {
        this.mAutofilledValue = value;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public AutofillValue getSanitizedValue() {
        return this.mSanitizedValue;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setSanitizedValue(AutofillValue value) {
        this.mSanitizedValue = value;
    }

    FillResponse getResponse() {
        return this.mResponse;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setResponse(FillResponse response) {
        this.mResponse = response;
    }

    CharSequence getServiceName() {
        return this.mSession.getServiceName();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public int getState() {
        return this.mState;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getStateAsString() {
        return getStateAsString(this.mState);
    }

    static String getStateAsString(int state) {
        return DebugUtils.flagsToString(ViewState.class, "STATE_", state);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setState(int state) {
        if (this.mState == 1) {
            this.mState = state;
        } else {
            this.mState |= state;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void resetState(int state) {
        this.mState &= ~state;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String getDatasetId() {
        return this.mDatasetId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void setDatasetId(String datasetId) {
        this.mDatasetId = datasetId;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void update(AutofillValue autofillValue, Rect virtualBounds, int flags) {
        if (autofillValue != null) {
            this.mCurrentValue = autofillValue;
        }
        if (virtualBounds != null) {
            this.mVirtualBounds = virtualBounds;
        }
        maybeCallOnFillReady(flags);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void maybeCallOnFillReady(int flags) {
        if ((this.mState & 4) != 0 && (flags & 1) == 0) {
            if (Helper.sDebug) {
                Slog.d(TAG, "Ignoring UI for " + this.id + " on " + getStateAsString());
            }
        } else if (this.mResponse != null) {
            if (this.mResponse.getDatasets() != null || this.mResponse.getAuthentication() != null) {
                this.mListener.onFillReady(this.mResponse, this.id, this.mCurrentValue);
            }
        }
    }

    public String toString() {
        StringBuilder builder = new StringBuilder("ViewState: [id=").append(this.id);
        if (this.mDatasetId != null) {
            builder.append("datasetId:");
            builder.append(this.mDatasetId);
        }
        builder.append("state:");
        builder.append(getStateAsString());
        if (this.mCurrentValue != null) {
            builder.append("currentValue:");
            builder.append(this.mCurrentValue);
        }
        if (this.mAutofilledValue != null) {
            builder.append("autofilledValue:");
            builder.append(this.mAutofilledValue);
        }
        if (this.mSanitizedValue != null) {
            builder.append("sanitizedValue:");
            builder.append(this.mSanitizedValue);
        }
        if (this.mVirtualBounds != null) {
            builder.append("virtualBounds:");
            builder.append(this.mVirtualBounds);
        }
        return builder.toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("id:");
        pw.println(this.id);
        if (this.mDatasetId != null) {
            pw.print(prefix);
            pw.print("datasetId:");
            pw.println(this.mDatasetId);
        }
        pw.print(prefix);
        pw.print("state:");
        pw.println(getStateAsString());
        if (this.mResponse != null) {
            pw.print(prefix);
            pw.print("response id:");
            pw.println(this.mResponse.getRequestId());
        }
        if (this.mCurrentValue != null) {
            pw.print(prefix);
            pw.print("currentValue:");
            pw.println(this.mCurrentValue);
        }
        if (this.mAutofilledValue != null) {
            pw.print(prefix);
            pw.print("autofilledValue:");
            pw.println(this.mAutofilledValue);
        }
        if (this.mSanitizedValue != null) {
            pw.print(prefix);
            pw.print("sanitizedValue:");
            pw.println(this.mSanitizedValue);
        }
        if (this.mVirtualBounds != null) {
            pw.print(prefix);
            pw.print("virtualBounds:");
            pw.println(this.mVirtualBounds);
        }
    }
}
