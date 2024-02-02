package com.android.server.autofill.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.IntentSender;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.net.util.NetworkConstants;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveInfo;
import android.service.autofill.ValueFinder;
import android.text.TextUtils;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.autofill.AutofillId;
import android.view.autofill.IAutofillWindowPresenter;
import android.widget.Toast;
import com.android.internal.logging.MetricsLogger;
import com.android.server.UiThread;
import com.android.server.autofill.Helper;
import com.android.server.autofill.ui.FillUi;
import com.android.server.autofill.ui.SaveUi;
import java.io.PrintWriter;
/* loaded from: classes.dex */
public final class AutoFillUI {
    private static final String TAG = "AutofillUI";
    private AutoFillUiCallback mCallback;
    private final Context mContext;
    private FillUi mFillUi;
    private final Handler mHandler = UiThread.getHandler();
    private final MetricsLogger mMetricsLogger = new MetricsLogger();
    private final OverlayControl mOverlayControl;
    private SaveUi mSaveUi;

    /* loaded from: classes.dex */
    public interface AutoFillUiCallback {
        void authenticate(int i, int i2, IntentSender intentSender, Bundle bundle);

        void cancelSave();

        void dispatchUnhandledKey(AutofillId autofillId, KeyEvent keyEvent);

        void fill(int i, int i2, Dataset dataset);

        void requestHideFillUi(AutofillId autofillId);

        void requestShowFillUi(AutofillId autofillId, int i, int i2, IAutofillWindowPresenter iAutofillWindowPresenter);

        void save();

        void startIntentSender(IntentSender intentSender);
    }

    public AutoFillUI(Context context) {
        this.mContext = context;
        this.mOverlayControl = new OverlayControl(context);
    }

    public void setCallback(final AutoFillUiCallback callback) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$Z-Di7CGd-L0nOI4i7_RO1FYbhgU
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.lambda$setCallback$0(AutoFillUI.this, callback);
            }
        });
    }

    public static /* synthetic */ void lambda$setCallback$0(AutoFillUI autoFillUI, AutoFillUiCallback callback) {
        if (autoFillUI.mCallback != callback) {
            if (autoFillUI.mCallback != null) {
                autoFillUI.hideAllUiThread(autoFillUI.mCallback);
            }
            autoFillUI.mCallback = callback;
        }
    }

    public void clearCallback(final AutoFillUiCallback callback) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$i7qTc5vqiej5Psbl-bIkD7js-Ao
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.lambda$clearCallback$1(AutoFillUI.this, callback);
            }
        });
    }

    public static /* synthetic */ void lambda$clearCallback$1(AutoFillUI autoFillUI, AutoFillUiCallback callback) {
        if (autoFillUI.mCallback == callback) {
            autoFillUI.hideAllUiThread(callback);
            autoFillUI.mCallback = null;
        }
    }

    public void showError(int resId, AutoFillUiCallback callback) {
        showError(this.mContext.getString(resId), callback);
    }

    public void showError(final CharSequence message, final AutoFillUiCallback callback) {
        Slog.w(TAG, "showError(): " + ((Object) message));
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$S8lqjy9BKKn2SSfu43iaVPGD6rg
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.lambda$showError$2(AutoFillUI.this, callback, message);
            }
        });
    }

    public static /* synthetic */ void lambda$showError$2(AutoFillUI autoFillUI, AutoFillUiCallback callback, CharSequence message) {
        if (autoFillUI.mCallback != callback) {
            return;
        }
        autoFillUI.hideAllUiThread(callback);
        if (!TextUtils.isEmpty(message)) {
            Toast.makeText(autoFillUI.mContext, message, 1).show();
        }
    }

    public void hideFillUi(final AutoFillUiCallback callback) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$VF2EbGE70QNyGDbklN9Uz5xHqyQ
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.this.hideFillUiUiThread(callback, true);
            }
        });
    }

    public void filterFillUi(final String filterText, final AutoFillUiCallback callback) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$LjywPhTUqjU0ZUlG1crxBg8qhRA
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.lambda$filterFillUi$4(AutoFillUI.this, callback, filterText);
            }
        });
    }

    public static /* synthetic */ void lambda$filterFillUi$4(AutoFillUI autoFillUI, AutoFillUiCallback callback, String filterText) {
        if (callback == autoFillUI.mCallback && autoFillUI.mFillUi != null) {
            autoFillUI.mFillUi.setFilterText(filterText);
        }
    }

    public void showFillUi(AutofillId focusedId, final FillResponse response, final String filterText, String servicePackageName, ComponentName componentName, final CharSequence serviceLabel, final Drawable serviceIcon, final AutoFillUiCallback callback, int sessionId, boolean compatMode) {
        AutofillId autofillId;
        if (Helper.sDebug) {
            int size = filterText == null ? 0 : filterText.length();
            StringBuilder sb = new StringBuilder();
            sb.append("showFillUi(): id=");
            autofillId = focusedId;
            sb.append(autofillId);
            sb.append(", filter=");
            sb.append(size);
            sb.append(" chars");
            Slog.d(TAG, sb.toString());
        } else {
            autofillId = focusedId;
        }
        final LogMaker log = Helper.newLogMaker(910, componentName, servicePackageName, sessionId, compatMode).addTaggedData(911, Integer.valueOf(filterText == null ? 0 : filterText.length())).addTaggedData(909, Integer.valueOf(response.getDatasets() != null ? response.getDatasets().size() : 0));
        final AutofillId autofillId2 = autofillId;
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$H0BWucCEHDp2_3FUpZ9-CLDtxYQ
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.lambda$showFillUi$5(AutoFillUI.this, callback, response, autofillId2, filterText, serviceLabel, serviceIcon, log);
            }
        });
    }

    public static /* synthetic */ void lambda$showFillUi$5(AutoFillUI autoFillUI, final AutoFillUiCallback callback, final FillResponse response, final AutofillId focusedId, String filterText, CharSequence serviceLabel, Drawable serviceIcon, final LogMaker log) {
        if (callback != autoFillUI.mCallback) {
            return;
        }
        autoFillUI.hideAllUiThread(callback);
        autoFillUI.mFillUi = new FillUi(autoFillUI.mContext, response, focusedId, filterText, autoFillUI.mOverlayControl, serviceLabel, serviceIcon, new FillUi.Callback() { // from class: com.android.server.autofill.ui.AutoFillUI.1
            @Override // com.android.server.autofill.ui.FillUi.Callback
            public void onResponsePicked(FillResponse response2) {
                log.setType(3);
                AutoFillUI.this.hideFillUiUiThread(callback, true);
                if (AutoFillUI.this.mCallback != null) {
                    AutoFillUI.this.mCallback.authenticate(response2.getRequestId(), NetworkConstants.ARP_HWTYPE_RESERVED_HI, response2.getAuthentication(), response2.getClientState());
                }
            }

            @Override // com.android.server.autofill.ui.FillUi.Callback
            public void onDatasetPicked(Dataset dataset) {
                log.setType(4);
                AutoFillUI.this.hideFillUiUiThread(callback, true);
                if (AutoFillUI.this.mCallback != null) {
                    int datasetIndex = response.getDatasets().indexOf(dataset);
                    AutoFillUI.this.mCallback.fill(response.getRequestId(), datasetIndex, dataset);
                }
            }

            @Override // com.android.server.autofill.ui.FillUi.Callback
            public void onCanceled() {
                log.setType(5);
                AutoFillUI.this.hideFillUiUiThread(callback, true);
            }

            @Override // com.android.server.autofill.ui.FillUi.Callback
            public void onDestroy() {
                if (log.getType() == 0) {
                    log.setType(2);
                }
                AutoFillUI.this.mMetricsLogger.write(log);
            }

            @Override // com.android.server.autofill.ui.FillUi.Callback
            public void requestShowFillUi(int width, int height, IAutofillWindowPresenter windowPresenter) {
                if (AutoFillUI.this.mCallback != null) {
                    AutoFillUI.this.mCallback.requestShowFillUi(focusedId, width, height, windowPresenter);
                }
            }

            @Override // com.android.server.autofill.ui.FillUi.Callback
            public void requestHideFillUi() {
                if (AutoFillUI.this.mCallback != null) {
                    AutoFillUI.this.mCallback.requestHideFillUi(focusedId);
                }
            }

            @Override // com.android.server.autofill.ui.FillUi.Callback
            public void startIntentSender(IntentSender intentSender) {
                if (AutoFillUI.this.mCallback != null) {
                    AutoFillUI.this.mCallback.startIntentSender(intentSender);
                }
            }

            @Override // com.android.server.autofill.ui.FillUi.Callback
            public void dispatchUnhandledKey(KeyEvent keyEvent) {
                if (AutoFillUI.this.mCallback != null) {
                    AutoFillUI.this.mCallback.dispatchUnhandledKey(focusedId, keyEvent);
                }
            }
        });
    }

    public void showSaveUi(final CharSequence serviceLabel, final Drawable serviceIcon, final String servicePackageName, SaveInfo info, final ValueFinder valueFinder, final ComponentName componentName, final AutoFillUiCallback callback, final PendingUi pendingSaveUi, final boolean compatMode) {
        SaveInfo saveInfo;
        if (Helper.sVerbose) {
            StringBuilder sb = new StringBuilder();
            sb.append("showSaveUi() for ");
            sb.append(componentName.toShortString());
            sb.append(": ");
            saveInfo = info;
            sb.append(saveInfo);
            Slog.v(TAG, sb.toString());
        } else {
            saveInfo = info;
        }
        int numIds = 0 + (info.getRequiredIds() == null ? 0 : info.getRequiredIds().length);
        final LogMaker log = Helper.newLogMaker(916, componentName, servicePackageName, pendingSaveUi.sessionId, compatMode).addTaggedData(917, Integer.valueOf(numIds + (info.getOptionalIds() != null ? info.getOptionalIds().length : 0)));
        final SaveInfo saveInfo2 = saveInfo;
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$xTxq_LM_GKvWtCQ0xT88Q_Y8M7Q
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.lambda$showSaveUi$6(AutoFillUI.this, callback, pendingSaveUi, serviceLabel, serviceIcon, servicePackageName, componentName, saveInfo2, valueFinder, log, compatMode);
            }
        });
    }

    public static /* synthetic */ void lambda$showSaveUi$6(AutoFillUI autoFillUI, AutoFillUiCallback callback, final PendingUi pendingSaveUi, CharSequence serviceLabel, Drawable serviceIcon, String servicePackageName, ComponentName componentName, SaveInfo info, ValueFinder valueFinder, final LogMaker log, boolean compatMode) {
        if (callback != autoFillUI.mCallback) {
            return;
        }
        autoFillUI.hideAllUiThread(callback);
        autoFillUI.mSaveUi = new SaveUi(autoFillUI.mContext, pendingSaveUi, serviceLabel, serviceIcon, servicePackageName, componentName, info, valueFinder, autoFillUI.mOverlayControl, new SaveUi.OnSaveListener() { // from class: com.android.server.autofill.ui.AutoFillUI.2
            @Override // com.android.server.autofill.ui.SaveUi.OnSaveListener
            public void onSave() {
                log.setType(4);
                AutoFillUI.this.hideSaveUiUiThread(AutoFillUI.this.mCallback);
                if (AutoFillUI.this.mCallback != null) {
                    AutoFillUI.this.mCallback.save();
                }
                AutoFillUI.this.destroySaveUiUiThread(pendingSaveUi, true);
            }

            @Override // com.android.server.autofill.ui.SaveUi.OnSaveListener
            public void onCancel(IntentSender listener) {
                log.setType(5);
                AutoFillUI.this.hideSaveUiUiThread(AutoFillUI.this.mCallback);
                if (listener != null) {
                    try {
                        listener.sendIntent(AutoFillUI.this.mContext, 0, null, null, null);
                    } catch (IntentSender.SendIntentException e) {
                        Slog.e(AutoFillUI.TAG, "Error starting negative action listener: " + listener, e);
                    }
                }
                if (AutoFillUI.this.mCallback != null) {
                    AutoFillUI.this.mCallback.cancelSave();
                }
                AutoFillUI.this.destroySaveUiUiThread(pendingSaveUi, true);
            }

            @Override // com.android.server.autofill.ui.SaveUi.OnSaveListener
            public void onDestroy() {
                if (log.getType() == 0) {
                    log.setType(2);
                    if (AutoFillUI.this.mCallback != null) {
                        AutoFillUI.this.mCallback.cancelSave();
                    }
                }
                AutoFillUI.this.mMetricsLogger.write(log);
            }
        }, compatMode);
    }

    public void onPendingSaveUi(final int operation, final IBinder token) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$R46Kz1SlDpiZBOYi-1HNH5FBjnU
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.lambda$onPendingSaveUi$7(AutoFillUI.this, operation, token);
            }
        });
    }

    public static /* synthetic */ void lambda$onPendingSaveUi$7(AutoFillUI autoFillUI, int operation, IBinder token) {
        if (autoFillUI.mSaveUi != null) {
            autoFillUI.mSaveUi.onPendingUi(operation, token);
            return;
        }
        Slog.w(TAG, "onPendingSaveUi(" + operation + "): no save ui");
    }

    public void hideAll(final AutoFillUiCallback callback) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$56AC3ykfo4h_e2LSjdkJ3XQn370
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.this.hideAllUiThread(callback);
            }
        });
    }

    public void destroyAll(final PendingUi pendingSaveUi, final AutoFillUiCallback callback, final boolean notifyClient) {
        this.mHandler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$AutoFillUI$XWhvh2-Jd9NLMoEos-e8RkZdQaI
            @Override // java.lang.Runnable
            public final void run() {
                AutoFillUI.this.destroyAllUiThread(pendingSaveUi, callback, notifyClient);
            }
        });
    }

    public void dump(PrintWriter pw) {
        pw.println("Autofill UI");
        if (this.mFillUi != null) {
            pw.print("  ");
            pw.println("showsFillUi: true");
            this.mFillUi.dump(pw, "    ");
        } else {
            pw.print("  ");
            pw.println("showsFillUi: false");
        }
        if (this.mSaveUi != null) {
            pw.print("  ");
            pw.println("showsSaveUi: true");
            this.mSaveUi.dump(pw, "    ");
            return;
        }
        pw.print("  ");
        pw.println("showsSaveUi: false");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideFillUiUiThread(AutoFillUiCallback callback, boolean notifyClient) {
        if (this.mFillUi != null) {
            if (callback == null || callback == this.mCallback) {
                this.mFillUi.destroy(notifyClient);
                this.mFillUi = null;
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public PendingUi hideSaveUiUiThread(AutoFillUiCallback callback) {
        if (Helper.sVerbose) {
            Slog.v(TAG, "hideSaveUiUiThread(): mSaveUi=" + this.mSaveUi + ", callback=" + callback + ", mCallback=" + this.mCallback);
        }
        if (this.mSaveUi != null) {
            if (callback == null || callback == this.mCallback) {
                return this.mSaveUi.hide();
            }
            return null;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void destroySaveUiUiThread(PendingUi pendingSaveUi, boolean notifyClient) {
        if (this.mSaveUi == null) {
            if (Helper.sDebug) {
                Slog.d(TAG, "destroySaveUiUiThread(): already destroyed");
                return;
            }
            return;
        }
        if (Helper.sDebug) {
            Slog.d(TAG, "destroySaveUiUiThread(): " + pendingSaveUi);
        }
        this.mSaveUi.destroy();
        this.mSaveUi = null;
        if (pendingSaveUi != null && notifyClient) {
            try {
                if (Helper.sDebug) {
                    Slog.d(TAG, "destroySaveUiUiThread(): notifying client");
                }
                pendingSaveUi.client.setSaveUiState(pendingSaveUi.sessionId, false);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client to set save UI state to hidden: " + e);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void destroyAllUiThread(PendingUi pendingSaveUi, AutoFillUiCallback callback, boolean notifyClient) {
        hideFillUiUiThread(callback, notifyClient);
        destroySaveUiUiThread(pendingSaveUi, notifyClient);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideAllUiThread(AutoFillUiCallback callback) {
        hideFillUiUiThread(callback, true);
        PendingUi pendingSaveUi = hideSaveUiUiThread(callback);
        if (pendingSaveUi != null && pendingSaveUi.getState() == 4) {
            if (Helper.sDebug) {
                Slog.d(TAG, "hideAllUiThread(): destroying Save UI because pending restoration is finished");
            }
            destroySaveUiUiThread(pendingSaveUi, true);
        }
    }
}
