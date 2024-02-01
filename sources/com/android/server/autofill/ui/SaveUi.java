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
import android.service.autofill.InternalOnClickAction;
import android.service.autofill.InternalTransformation;
import android.service.autofill.InternalValidator;
import android.service.autofill.SaveInfo;
import android.service.autofill.ValueFinder;
import android.text.Html;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
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
    private static final String TAG = "SaveUi";
    private static final int THEME_ID_DARK = 16974814;
    private static final int THEME_ID_LIGHT = 16974822;
    private final boolean mCompatMode;
    private final ComponentName mComponentName;
    private boolean mDestroyed;
    private final Dialog mDialog;
    private final OneActionThenDestroyListener mListener;
    private final OverlayControl mOverlayControl;
    private final PendingUi mPendingUi;
    private final String mServicePackageName;
    private final CharSequence mSubTitle;
    private final int mThemeId;
    private final CharSequence mTitle;
    private final Handler mHandler = UiThread.getHandler();
    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    /* loaded from: classes.dex */
    public interface OnSaveListener {
        void onCancel(IntentSender intentSender);

        void onDestroy();

        void onSave();
    }

    /* loaded from: classes.dex */
    private class OneActionThenDestroyListener implements OnSaveListener {
        private boolean mDone;
        private final OnSaveListener mRealListener;

        OneActionThenDestroyListener(OnSaveListener realListener) {
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
    public SaveUi(Context context, PendingUi pendingUi, CharSequence serviceLabel, Drawable serviceIcon, String servicePackageName, ComponentName componentName, final SaveInfo info, ValueFinder valueFinder, OverlayControl overlayControl, OnSaveListener listener, boolean nightMode, boolean isUpdate, boolean compatMode) {
        if (Helper.sVerbose) {
            Slog.v(TAG, "nightMode: " + nightMode);
        }
        this.mThemeId = nightMode ? THEME_ID_DARK : THEME_ID_LIGHT;
        this.mPendingUi = pendingUi;
        this.mListener = new OneActionThenDestroyListener(listener);
        this.mOverlayControl = overlayControl;
        this.mServicePackageName = servicePackageName;
        this.mComponentName = componentName;
        this.mCompatMode = compatMode;
        Context context2 = new ContextThemeWrapper(context, this.mThemeId);
        LayoutInflater inflater = LayoutInflater.from(context2);
        View view = inflater.inflate(17367103, (ViewGroup) null);
        TextView titleView = (TextView) view.findViewById(16908851);
        ArraySet<String> types = new ArraySet<>(3);
        int type = info.getType();
        if ((type & 1) != 0) {
            types.add(context2.getString(17039580));
        }
        if ((type & 2) != 0) {
            types.add(context2.getString(17039577));
        }
        if ((type & 4) != 0) {
            types.add(context2.getString(17039578));
        }
        if ((type & 8) != 0) {
            types.add(context2.getString(17039581));
        }
        if ((type & 16) != 0) {
            types.add(context2.getString(17039579));
        }
        int size = types.size();
        if (size == 1) {
            this.mTitle = Html.fromHtml(context2.getString(isUpdate ? 17039590 : 17039576, types.valueAt(0), serviceLabel), 0);
        } else if (size == 2) {
            this.mTitle = Html.fromHtml(context2.getString(isUpdate ? 17039588 : 17039574, types.valueAt(0), types.valueAt(1), serviceLabel), 0);
        } else if (size != 3) {
            this.mTitle = Html.fromHtml(context2.getString(isUpdate ? 17039587 : 17039573, serviceLabel), 0);
        } else {
            this.mTitle = Html.fromHtml(context2.getString(isUpdate ? 17039589 : 17039575, types.valueAt(0), types.valueAt(1), types.valueAt(2), serviceLabel), 0);
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
                ViewGroup subtitleContainer = (ViewGroup) view.findViewById(16908848);
                TextView subtitleView = new TextView(context2);
                subtitleView.setText(this.mSubTitle);
                subtitleContainer.addView(subtitleView, new ViewGroup.LayoutParams(-1, -2));
                subtitleContainer.setVisibility(0);
            }
            if (Helper.sDebug) {
                Slog.d(TAG, "on constructor: title=" + ((Object) this.mTitle) + ", subTitle=" + ((Object) this.mSubTitle));
            }
        }
        TextView noButton = (TextView) view.findViewById(16908850);
        if (info.getNegativeActionStyle() == 1) {
            noButton.setText(17040978);
        } else {
            noButton.setText(17039572);
        }
        noButton.setOnClickListener(new View.OnClickListener() { // from class: com.android.server.autofill.ui.-$$Lambda$SaveUi$E9O26NP1L_DDYBfaO7fQ0hhPAx8
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SaveUi.this.lambda$new$0$SaveUi(info, view2);
            }
        });
        TextView yesButton = (TextView) view.findViewById(16908852);
        if (isUpdate) {
            yesButton.setText(17039591);
        }
        yesButton.setOnClickListener(new View.OnClickListener() { // from class: com.android.server.autofill.ui.-$$Lambda$SaveUi$b3z89RdKv6skukyM-l67uIcvlf0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view2) {
                SaveUi.this.lambda$new$1$SaveUi(view2);
            }
        });
        this.mDialog = new Dialog(context2, this.mThemeId);
        this.mDialog.setContentView(view);
        this.mDialog.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: com.android.server.autofill.ui.-$$Lambda$SaveUi$ckPlzqJfB_ohleAkb5RXKU7mFY8
            @Override // android.content.DialogInterface.OnDismissListener
            public final void onDismiss(DialogInterface dialogInterface) {
                SaveUi.this.lambda$new$2$SaveUi(dialogInterface);
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
        params.accessibilityTitle = context2.getString(17039571);
        params.windowAnimations = 16974610;
        show();
    }

    public /* synthetic */ void lambda$new$0$SaveUi(SaveInfo info, View v) {
        this.mListener.onCancel(info.getNegativeActionListener());
    }

    public /* synthetic */ void lambda$new$1$SaveUi(View v) {
        this.mListener.onSave();
    }

    public /* synthetic */ void lambda$new$2$SaveUi(DialogInterface d) {
        this.mListener.onCancel(null);
    }

    private boolean applyCustomDescription(Context context, View saveUiView, ValueFinder valueFinder, SaveInfo info) {
        int type;
        ArrayList<Pair<Integer, InternalTransformation>> transformations;
        SparseArray<InternalOnClickAction> actions;
        CustomDescription customDescription = info.getCustomDescription();
        if (customDescription == null) {
            return false;
        }
        final int type2 = info.getType();
        writeLog(1129, type2);
        RemoteViews template = customDescription.getPresentation();
        if (template == null) {
            Slog.w(TAG, "No remote view on custom description");
            return false;
        }
        ArrayList<Pair<Integer, InternalTransformation>> transformations2 = customDescription.getTransformations();
        if (Helper.sVerbose) {
            Slog.v(TAG, "applyCustomDescription(): transformations = " + transformations2);
        }
        if (transformations2 != null && !InternalTransformation.batchApply(valueFinder, template, transformations2)) {
            Slog.w(TAG, "could not apply main transformations on custom description");
            return false;
        }
        RemoteViews.OnClickHandler handler = new RemoteViews.OnClickHandler() { // from class: com.android.server.autofill.ui.-$$Lambda$SaveUi$AjvUEgdrIrhxK5p_4hhcwEoBDgU
            public final boolean onClickHandler(View view, PendingIntent pendingIntent, RemoteViews.RemoteResponse remoteResponse) {
                return SaveUi.this.lambda$applyCustomDescription$3$SaveUi(type2, view, pendingIntent, remoteResponse);
            }
        };
        try {
            View customSubtitleView = template.applyWithTheme(context, null, handler, this.mThemeId);
            ArrayList<Pair<InternalValidator, BatchUpdates>> updates = customDescription.getUpdates();
            if (Helper.sVerbose) {
                try {
                    Slog.v(TAG, "applyCustomDescription(): view = " + customSubtitleView + " updates=" + updates);
                } catch (Exception e) {
                    e = e;
                    Slog.e(TAG, "Error applying custom description. ", e);
                    return false;
                }
            }
            if (updates != null) {
                try {
                    int size = updates.size();
                    if (Helper.sDebug) {
                        Slog.d(TAG, "custom description has " + size + " batch updates");
                    }
                    int i = 0;
                    while (i < size) {
                        Pair<InternalValidator, BatchUpdates> pair = updates.get(i);
                        InternalValidator condition = (InternalValidator) pair.first;
                        try {
                            if (condition == null) {
                                type = type2;
                                transformations = transformations2;
                            } else if (condition.isValid(valueFinder)) {
                                BatchUpdates batchUpdates = (BatchUpdates) pair.second;
                                RemoteViews templateUpdates = batchUpdates.getUpdates();
                                if (templateUpdates != null) {
                                    if (Helper.sDebug) {
                                        type = type2;
                                        try {
                                            StringBuilder sb = new StringBuilder();
                                            transformations = transformations2;
                                            sb.append("Applying template updates for batch update #");
                                            sb.append(i);
                                            Slog.d(TAG, sb.toString());
                                        } catch (Exception e2) {
                                            e = e2;
                                            Slog.e(TAG, "Error applying custom description. ", e);
                                            return false;
                                        }
                                    } else {
                                        type = type2;
                                        transformations = transformations2;
                                    }
                                    templateUpdates.reapply(context, customSubtitleView);
                                } else {
                                    type = type2;
                                    transformations = transformations2;
                                }
                                ArrayList<Pair<Integer, InternalTransformation>> batchTransformations = batchUpdates.getTransformations();
                                if (batchTransformations != null) {
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
                                type2 = type;
                                transformations2 = transformations;
                            } else {
                                type = type2;
                                transformations = transformations2;
                            }
                            if (Helper.sDebug) {
                                Slog.d(TAG, "Skipping batch update #" + i);
                            }
                            i++;
                            type2 = type;
                            transformations2 = transformations;
                        } catch (Exception e3) {
                            e = e3;
                            Slog.e(TAG, "Error applying custom description. ", e);
                            return false;
                        }
                    }
                } catch (Exception e4) {
                    e = e4;
                }
            }
            SparseArray<InternalOnClickAction> actions2 = customDescription.getActions();
            if (actions2 != null) {
                int size2 = actions2.size();
                if (Helper.sDebug) {
                    Slog.d(TAG, "custom description has " + size2 + " actions");
                }
                if (customSubtitleView instanceof ViewGroup) {
                    final ViewGroup rootView = (ViewGroup) customSubtitleView;
                    int i2 = 0;
                    while (i2 < size2) {
                        int id = actions2.keyAt(i2);
                        final InternalOnClickAction action = actions2.valueAt(i2);
                        View child = rootView.findViewById(id);
                        if (child == null) {
                            StringBuilder sb2 = new StringBuilder();
                            actions = actions2;
                            sb2.append("Ignoring action ");
                            sb2.append(action);
                            sb2.append(" for view ");
                            sb2.append(id);
                            sb2.append(" because it's not on ");
                            sb2.append(rootView);
                            Slog.w(TAG, sb2.toString());
                        } else {
                            actions = actions2;
                            child.setOnClickListener(new View.OnClickListener() { // from class: com.android.server.autofill.ui.-$$Lambda$SaveUi$PXbcwumYwHXYcovQfoabkLzf8CM
                                @Override // android.view.View.OnClickListener
                                public final void onClick(View view) {
                                    SaveUi.lambda$applyCustomDescription$4(action, rootView, view);
                                }
                            });
                        }
                        i2++;
                        actions2 = actions;
                    }
                } else {
                    Slog.w(TAG, "cannot apply actions because custom description root is not a ViewGroup: " + customSubtitleView);
                }
            }
            try {
                ViewGroup subtitleContainer = (ViewGroup) saveUiView.findViewById(16908848);
                subtitleContainer.addView(customSubtitleView);
                subtitleContainer.setVisibility(0);
                return true;
            } catch (Exception e5) {
                e = e5;
                Slog.e(TAG, "Error applying custom description. ", e);
                return false;
            }
        } catch (Exception e6) {
            e = e6;
        }
    }

    public /* synthetic */ boolean lambda$applyCustomDescription$3$SaveUi(int type, View view, PendingIntent pendingIntent, RemoteViews.RemoteResponse response) {
        Intent intent = (Intent) response.getLaunchOptions(view).first;
        LogMaker log = newLogMaker(1132, type);
        boolean isValid = isValidLink(pendingIntent, intent);
        if (!isValid) {
            log.setType(0);
            this.mMetricsLogger.write(log);
            return false;
        }
        if (Helper.sVerbose) {
            Slog.v(TAG, "Intercepting custom description intent");
        }
        IBinder token = this.mPendingUi.getToken();
        intent.putExtra("android.view.autofill.extra.RESTORE_SESSION_TOKEN", token);
        try {
            this.mPendingUi.client.startIntentSender(pendingIntent.getIntentSender(), intent);
            this.mPendingUi.setState(2);
            if (Helper.sDebug) {
                Slog.d(TAG, "hiding UI until restored with token " + token);
            }
            hide();
            log.setType(1);
            this.mMetricsLogger.write(log);
            return true;
        } catch (RemoteException e) {
            Slog.w(TAG, "error triggering pending intent: " + intent);
            log.setType(11);
            this.mMetricsLogger.write(log);
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$applyCustomDescription$4(InternalOnClickAction action, ViewGroup rootView, View v) {
        if (Helper.sVerbose) {
            Slog.v(TAG, "Applying " + action + " after " + v + " was clicked");
        }
        action.onClick(rootView);
    }

    private void setServiceIcon(Context context, View view, Drawable serviceIcon) {
        ImageView iconView = (ImageView) view.findViewById(16908849);
        Resources res = context.getResources();
        int maxWidth = res.getDimensionPixelSize(17104948);
        int actualWidth = serviceIcon.getMinimumWidth();
        int actualHeight = serviceIcon.getMinimumHeight();
        if (actualWidth <= maxWidth && actualHeight <= maxWidth) {
            if (Helper.sDebug) {
                Slog.d(TAG, "Adding service icon (" + actualWidth + "x" + actualHeight + ") as it's less than maximum (" + maxWidth + "x" + maxWidth + ").");
            }
            iconView.setImageDrawable(serviceIcon);
            return;
        }
        Slog.w(TAG, "Not adding service icon of size (" + actualWidth + "x" + actualHeight + ") because maximum is (" + maxWidth + "x" + maxWidth + ").");
        ((ViewGroup) iconView.getParent()).removeView(iconView);
    }

    private static boolean isValidLink(PendingIntent pendingIntent, Intent intent) {
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

    private LogMaker newLogMaker(int category, int saveType) {
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
            if (operation == 1) {
                log.setType(5);
                if (Helper.sDebug) {
                    Slog.d(TAG, "Cancelling pending save dialog for " + token);
                }
                hide();
            } else if (operation == 2) {
                if (Helper.sDebug) {
                    Slog.d(TAG, "Restoring save dialog for " + token);
                }
                log.setType(1);
                show();
            } else {
                log.setType(11);
                Slog.w(TAG, "restore(): invalid operation " + operation);
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
        CharSequence charSequence = this.mTitle;
        return charSequence == null ? "NO TITLE" : charSequence.toString();
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
        pw.print(prefix);
        pw.print("theme id: ");
        pw.print(this.mThemeId);
        int i = this.mThemeId;
        if (i == THEME_ID_DARK) {
            pw.println(" (dark)");
        } else if (i == THEME_ID_LIGHT) {
            pw.println(" (light)");
        } else {
            pw.println("(UNKNOWN_MODE)");
        }
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
