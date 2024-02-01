package com.android.server.autofill.ui;

import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.BatchUpdates;
import android.service.autofill.CustomDescription;
import android.service.autofill.InternalTransformation;
import android.service.autofill.InternalValidator;
import android.service.autofill.SaveInfo;
import android.service.autofill.ValueFinder;
import android.text.Html;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import com.android.internal.logging.MetricsLogger;
import com.android.server.UiThread;
import com.android.server.autofill.Helper;
import java.io.PrintWriter;
import java.util.ArrayList;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class SaveUi {
    private static final String TAG = "AutofillSaveUi";
    private static final int THEME_ID = 16974787;
    private final boolean mCompatMode;
    private final ComponentName mComponentName;
    private boolean mDestroyed;
    private final Dialog mDialog;
    private final OneTimeListener mListener;
    private final OverlayControl mOverlayControl;
    private final PendingUi mPendingUi;
    private final String mServicePackageName;
    private final CharSequence mSubTitle;
    private final CharSequence mTitle;
    private final Handler mHandler = UiThread.getHandler();
    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    /* loaded from: classes.dex */
    public interface OnSaveListener {
        void onCancel(IntentSender intentSender);

        void onDestroy();

        void onSave();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class OneTimeListener implements OnSaveListener {
        private boolean mDone;
        private final OnSaveListener mRealListener;

        OneTimeListener(OnSaveListener realListener) {
            this.mRealListener = realListener;
        }

        @Override // com.android.server.autofill.ui.SaveUi.OnSaveListener
        public void onSave() {
            if (Helper.sDebug) {
                Slog.d(SaveUi.TAG, "OneTimeListener.onSave(): " + this.mDone);
            }
            if (this.mDone) {
                return;
            }
            this.mRealListener.onSave();
        }

        @Override // com.android.server.autofill.ui.SaveUi.OnSaveListener
        public void onCancel(IntentSender listener) {
            if (Helper.sDebug) {
                Slog.d(SaveUi.TAG, "OneTimeListener.onCancel(): " + this.mDone);
            }
            if (this.mDone) {
                return;
            }
            this.mRealListener.onCancel(listener);
        }

        @Override // com.android.server.autofill.ui.SaveUi.OnSaveListener
        public void onDestroy() {
            if (Helper.sDebug) {
                Slog.d(SaveUi.TAG, "OneTimeListener.onDestroy(): " + this.mDone);
            }
            if (this.mDone) {
                return;
            }
            this.mDone = true;
            this.mRealListener.onDestroy();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public SaveUi(Context context, PendingUi pendingUi, CharSequence serviceLabel, Drawable serviceIcon, String servicePackageName, ComponentName componentName, final SaveInfo info, ValueFinder valueFinder, OverlayControl overlayControl, OnSaveListener listener, boolean compatMode) {
        this.mPendingUi = pendingUi;
        this.mListener = new OneTimeListener(listener);
        this.mOverlayControl = overlayControl;
        this.mServicePackageName = servicePackageName;
        this.mComponentName = componentName;
        this.mCompatMode = compatMode;
        Context context2 = new ContextThemeWrapper(context, (int) THEME_ID);
        LayoutInflater inflater = LayoutInflater.from(context2);
        View view = inflater.inflate(17367103, (ViewGroup) null);
        TextView titleView = (TextView) view.findViewById(16908833);
        ArraySet<String> types = new ArraySet<>(3);
        int type = info.getType();
        if ((type & 1) != 0) {
            types.add(context2.getString(17039563));
        }
        if ((type & 2) != 0) {
            types.add(context2.getString(17039560));
        }
        if ((type & 4) != 0) {
            types.add(context2.getString(17039561));
        }
        if ((type & 8) != 0) {
            types.add(context2.getString(17039564));
        }
        if ((type & 16) != 0) {
            types.add(context2.getString(17039562));
        }
        switch (types.size()) {
            case 1:
                this.mTitle = Html.fromHtml(context2.getString(17039559, types.valueAt(0), serviceLabel), 0);
                break;
            case 2:
                this.mTitle = Html.fromHtml(context2.getString(17039557, types.valueAt(0), types.valueAt(1), serviceLabel), 0);
                break;
            case 3:
                this.mTitle = Html.fromHtml(context2.getString(17039558, types.valueAt(0), types.valueAt(1), types.valueAt(2), serviceLabel), 0);
                break;
            default:
                this.mTitle = Html.fromHtml(context2.getString(17039556, serviceLabel), 0);
                break;
        }
        titleView.setText(this.mTitle);
        setServiceIcon(context2, view, serviceIcon);
        boolean hasCustomDescription = applyCustomDescription(context2, view, valueFinder, info);
        if (hasCustomDescription) {
            this.mSubTitle = null;
            if (Helper.sDebug) {
                Slog.d(TAG, "on constructor: applied custom description");
            }
        } else {
            this.mSubTitle = info.getDescription();
            if (this.mSubTitle != null) {
                writeLog(1131, type);
                ViewGroup subtitleContainer = (ViewGroup) view.findViewById(16908830);
                TextView subtitleView = new TextView(context2);
                subtitleView.setText(this.mSubTitle);
                subtitleContainer.addView(subtitleView, new ViewGroup.LayoutParams(-1, -2));
                subtitleContainer.setVisibility(0);
            }
            if (Helper.sDebug) {
                Slog.d(TAG, "on constructor: title=" + ((Object) this.mTitle) + ", subTitle=" + ((Object) this.mSubTitle));
            }
        }
        TextView noButton = (TextView) view.findViewById(16908832);
        if (info.getNegativeActionStyle() == 1) {
            noButton.setText(17040821);
        } else {
            noButton.setText(17039555);
        }
        noButton.setOnClickListener(new View.OnClickListener() { // from class: com.android.server.autofill.ui.-$$Lambda$SaveUi$E9O26NP1L_DDYBfaO7fQ0hhPAx8
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SaveUi.this.mListener.onCancel(info.getNegativeActionListener());
            }
        });
        View yesButton = view.findViewById(16908834);
        yesButton.setOnClickListener(new View.OnClickListener() { // from class: com.android.server.autofill.ui.-$$Lambda$SaveUi$b3z89RdKv6skukyM-l67uIcvlf0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SaveUi.this.mListener.onSave();
            }
        });
        this.mDialog = new Dialog(context2, THEME_ID);
        this.mDialog.setContentView(view);
        this.mDialog.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: com.android.server.autofill.ui.-$$Lambda$SaveUi$ckPlzqJfB_ohleAkb5RXKU7mFY8
            @Override // android.content.DialogInterface.OnDismissListener
            public final void onDismiss(DialogInterface dialogInterface) {
                SaveUi.this.mListener.onCancel(null);
            }
        });
        Window window = this.mDialog.getWindow();
        window.setType(2038);
        window.addFlags(393248);
        window.addPrivateFlags(16);
        window.setSoftInputMode(32);
        window.setGravity(81);
        window.setCloseOnTouchOutside(true);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = -1;
        params.accessibilityTitle = context2.getString(17039554);
        params.windowAnimations = 16974604;
        show();
    }

    private boolean applyCustomDescription(Context context, View saveUiView, ValueFinder valueFinder, SaveInfo info) {
        View customSubtitleView;
        CustomDescription customDescription;
        int type;
        CustomDescription customDescription2 = info.getCustomDescription();
        if (customDescription2 == null) {
            return false;
        }
        final int type2 = info.getType();
        writeLog(1129, type2);
        RemoteViews template = customDescription2.getPresentation();
        if (template == null) {
            Slog.w(TAG, "No remote view on custom description");
            return false;
        }
        ArrayList<Pair<Integer, InternalTransformation>> transformations = customDescription2.getTransformations();
        if (transformations != null && !InternalTransformation.batchApply(valueFinder, template, transformations)) {
            Slog.w(TAG, "could not apply main transformations on custom description");
            return false;
        }
        RemoteViews.OnClickHandler handler = new RemoteViews.OnClickHandler() { // from class: com.android.server.autofill.ui.SaveUi.1
            public boolean onClickHandler(View view, PendingIntent pendingIntent, Intent intent) {
                LogMaker log = SaveUi.this.newLogMaker(1132, type2);
                boolean isValid = SaveUi.isValidLink(pendingIntent, intent);
                if (!isValid) {
                    log.setType(0);
                    SaveUi.this.mMetricsLogger.write(log);
                    return false;
                }
                if (Helper.sVerbose) {
                    Slog.v(SaveUi.TAG, "Intercepting custom description intent");
                }
                IBinder token = SaveUi.this.mPendingUi.getToken();
                intent.putExtra("android.view.autofill.extra.RESTORE_SESSION_TOKEN", token);
                try {
                    SaveUi.this.mPendingUi.client.startIntentSender(pendingIntent.getIntentSender(), intent);
                    SaveUi.this.mPendingUi.setState(2);
                    if (Helper.sDebug) {
                        Slog.d(SaveUi.TAG, "hiding UI until restored with token " + token);
                    }
                    SaveUi.this.hide();
                    log.setType(1);
                    SaveUi.this.mMetricsLogger.write(log);
                    return true;
                } catch (RemoteException e) {
                    Slog.w(SaveUi.TAG, "error triggering pending intent: " + intent);
                    log.setType(11);
                    SaveUi.this.mMetricsLogger.write(log);
                    return false;
                }
            }
        };
        try {
            template.setApplyTheme(THEME_ID);
            customSubtitleView = template.apply(context, null, handler);
            ArrayList<Pair<InternalValidator, BatchUpdates>> updates = customDescription2.getUpdates();
            if (updates != null) {
                int size = updates.size();
                if (Helper.sDebug) {
                    try {
                        Slog.d(TAG, "custom description has " + size + " batch updates");
                    } catch (Exception e) {
                        e = e;
                        Slog.e(TAG, "Error applying custom description. ", e);
                        return false;
                    }
                }
                int i = 0;
                while (i < size) {
                    Pair<InternalValidator, BatchUpdates> pair = updates.get(i);
                    InternalValidator condition = (InternalValidator) pair.first;
                    try {
                        if (condition == null) {
                            customDescription = customDescription2;
                            type = type2;
                        } else if (condition.isValid(valueFinder)) {
                            BatchUpdates batchUpdates = (BatchUpdates) pair.second;
                            RemoteViews templateUpdates = batchUpdates.getUpdates();
                            if (templateUpdates != null) {
                                if (Helper.sDebug) {
                                    customDescription = customDescription2;
                                    try {
                                        StringBuilder sb = new StringBuilder();
                                        type = type2;
                                        sb.append("Applying template updates for batch update #");
                                        sb.append(i);
                                        Slog.d(TAG, sb.toString());
                                    } catch (Exception e2) {
                                        e = e2;
                                        Slog.e(TAG, "Error applying custom description. ", e);
                                        return false;
                                    }
                                } else {
                                    customDescription = customDescription2;
                                    type = type2;
                                }
                                templateUpdates.reapply(context, customSubtitleView);
                            } else {
                                customDescription = customDescription2;
                                type = type2;
                            }
                            ArrayList<Pair<Integer, InternalTransformation>> batchTransformations = batchUpdates.getTransformations();
                            if (batchTransformations == null) {
                                continue;
                            } else {
                                if (Helper.sDebug) {
                                    Slog.d(TAG, "Applying child transformation for batch update #" + i + ": " + batchTransformations);
                                }
                                if (!InternalTransformation.batchApply(valueFinder, template, batchTransformations)) {
                                    Slog.w(TAG, "Could not apply child transformation for batch update #" + i + ": " + batchTransformations);
                                    return false;
                                }
                                template.reapply(context, customSubtitleView);
                            }
                            i++;
                            customDescription2 = customDescription;
                            type2 = type;
                        } else {
                            customDescription = customDescription2;
                            type = type2;
                        }
                        if (Helper.sDebug) {
                            Slog.d(TAG, "Skipping batch update #" + i);
                        }
                        i++;
                        customDescription2 = customDescription;
                        type2 = type;
                    } catch (Exception e3) {
                        e = e3;
                        Slog.e(TAG, "Error applying custom description. ", e);
                        return false;
                    }
                }
            }
        } catch (Exception e4) {
            e = e4;
        }
        try {
            ViewGroup subtitleContainer = (ViewGroup) saveUiView.findViewById(16908830);
            subtitleContainer.addView(customSubtitleView);
            subtitleContainer.setVisibility(0);
            return true;
        } catch (Exception e5) {
            e = e5;
            Slog.e(TAG, "Error applying custom description. ", e);
            return false;
        }
    }

    private void setServiceIcon(Context context, View view, Drawable serviceIcon) {
        ImageView iconView = (ImageView) view.findViewById(16908831);
        Resources res = context.getResources();
        int maxWidth = res.getDimensionPixelSize(17104940);
        int actualWidth = serviceIcon.getMinimumWidth();
        int actualHeight = serviceIcon.getMinimumHeight();
        if (actualWidth > maxWidth || actualHeight > maxWidth) {
            Slog.w(TAG, "Not adding service icon of size (" + actualWidth + "x" + actualHeight + ") because maximum is (" + maxWidth + "x" + maxWidth + ").");
            ((ViewGroup) iconView.getParent()).removeView(iconView);
            return;
        }
        if (Helper.sDebug) {
            Slog.d(TAG, "Adding service icon (" + actualWidth + "x" + actualHeight + ") as it's less than maximum (" + maxWidth + "x" + maxWidth + ").");
        }
        iconView.setImageDrawable(serviceIcon);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isValidLink(PendingIntent pendingIntent, Intent intent) {
        if (pendingIntent == null) {
            Slog.w(TAG, "isValidLink(): custom description without pending intent");
            return false;
        } else if (!pendingIntent.isActivity()) {
            Slog.w(TAG, "isValidLink(): pending intent not for activity");
            return false;
        } else if (intent == null) {
            Slog.w(TAG, "isValidLink(): no intent");
            return false;
        } else {
            return true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public LogMaker newLogMaker(int category, int saveType) {
        return newLogMaker(category).addTaggedData(1130, Integer.valueOf(saveType));
    }

    private LogMaker newLogMaker(int category) {
        return Helper.newLogMaker(category, this.mComponentName, this.mServicePackageName, this.mPendingUi.sessionId, this.mCompatMode);
    }

    private void writeLog(int category, int saveType) {
        this.mMetricsLogger.write(newLogMaker(category, saveType));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void onPendingUi(int operation, IBinder token) {
        if (!this.mPendingUi.matches(token)) {
            Slog.w(TAG, "restore(" + operation + "): got token " + token + " instead of " + this.mPendingUi.getToken());
            return;
        }
        LogMaker log = newLogMaker(1134);
        try {
            switch (operation) {
                case 1:
                    log.setType(5);
                    if (Helper.sDebug) {
                        Slog.d(TAG, "Cancelling pending save dialog for " + token);
                    }
                    hide();
                    break;
                case 2:
                    if (Helper.sDebug) {
                        Slog.d(TAG, "Restoring save dialog for " + token);
                    }
                    log.setType(1);
                    show();
                    break;
                default:
                    log.setType(11);
                    Slog.w(TAG, "restore(): invalid operation " + operation);
                    break;
            }
            this.mMetricsLogger.write(log);
            this.mPendingUi.setState(4);
        } catch (Throwable th) {
            this.mMetricsLogger.write(log);
            throw th;
        }
    }

    private void show() {
        Slog.i(TAG, "Showing save dialog: " + ((Object) this.mTitle));
        this.mDialog.show();
        this.mOverlayControl.hideOverlays();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PendingUi hide() {
        if (Helper.sVerbose) {
            Slog.v(TAG, "Hiding save dialog.");
        }
        try {
            this.mDialog.hide();
            this.mOverlayControl.showOverlays();
            return this.mPendingUi;
        } catch (Throwable th) {
            this.mOverlayControl.showOverlays();
            throw th;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void destroy() {
        try {
            if (Helper.sDebug) {
                Slog.d(TAG, "destroy()");
            }
            throwIfDestroyed();
            this.mListener.onDestroy();
            this.mHandler.removeCallbacksAndMessages(this.mListener);
            this.mDialog.dismiss();
            this.mDestroyed = true;
        } finally {
            this.mOverlayControl.showOverlays();
        }
    }

    private void throwIfDestroyed() {
        if (this.mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }

    public String toString() {
        return this.mTitle == null ? "NO TITLE" : this.mTitle.toString();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("title: ");
        pw.println(this.mTitle);
        pw.print(prefix);
        pw.print("subtitle: ");
        pw.println(this.mSubTitle);
        pw.print(prefix);
        pw.print("pendingUi: ");
        pw.println(this.mPendingUi);
        pw.print(prefix);
        pw.print("service: ");
        pw.println(this.mServicePackageName);
        pw.print(prefix);
        pw.print("app: ");
        pw.println(this.mComponentName.toShortString());
        pw.print(prefix);
        pw.print("compat mode: ");
        pw.println(this.mCompatMode);
        View view = this.mDialog.getWindow().getDecorView();
        int[] loc = view.getLocationOnScreen();
        pw.print(prefix);
        pw.print("coordinates: ");
        pw.print('(');
        pw.print(loc[0]);
        pw.print(',');
        pw.print(loc[1]);
        pw.print(')');
        pw.print('(');
        pw.print(loc[0] + view.getWidth());
        pw.print(',');
        pw.print(loc[1] + view.getHeight());
        pw.println(')');
        pw.print(prefix);
        pw.print("destroyed: ");
        pw.println(this.mDestroyed);
    }
}
