package com.android.server.autofill.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.text.TextUtils;
import android.util.Slog;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutofillWindowPresenter;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.TextView;
import com.android.server.UiThread;
import com.android.server.autofill.Helper;
import com.android.server.autofill.ui.FillUi;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.pm.PackageManagerService;
import com.android.server.wm.WindowManagerService;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public final class FillUi {
    private static final String TAG = "FillUi";
    private static final int THEME_ID = 16974786;
    private static final TypedValue sTempTypedValue = new TypedValue();
    private final ItemsAdapter mAdapter;
    private AnnounceFilterResult mAnnounceFilterResult;
    private final Callback mCallback;
    private int mContentHeight;
    private int mContentWidth;
    private final Context mContext;
    private boolean mDestroyed;
    private String mFilterText;
    private final View mFooter;
    private final boolean mFullScreen;
    private final View mHeader;
    private final ListView mListView;
    private final int mVisibleDatasetsMaxCount;
    private final AnchoredWindow mWindow;
    private final Point mTempPoint = new Point();
    private final AutofillWindowPresenter mWindowPresenter = new AutofillWindowPresenter();

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface Callback {
        void dispatchUnhandledKey(KeyEvent keyEvent);

        void onCanceled();

        void onDatasetPicked(Dataset dataset);

        void onDestroy();

        void onResponsePicked(FillResponse fillResponse);

        void requestHideFillUi();

        void requestShowFillUi(int i, int i2, IAutofillWindowPresenter iAutofillWindowPresenter);

        void startIntentSender(IntentSender intentSender);
    }

    public static boolean isFullScreen(Context context) {
        if (Helper.sFullScreenMode != null) {
            if (Helper.sVerbose) {
                Slog.v(TAG, "forcing full-screen mode to " + Helper.sFullScreenMode);
            }
            return Helper.sFullScreenMode.booleanValue();
        }
        return context.getPackageManager().hasSystemFeature("android.software.leanback");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public FillUi(Context context, final FillResponse response, AutofillId focusedViewId, String filterText, OverlayControl overlayControl, CharSequence serviceLabel, Drawable serviceIcon, Callback callback) {
        RemoteViews.OnClickHandler interceptionHandler;
        int datasetCount;
        RemoteViews.OnClickHandler clickBlocker;
        Pattern filterPattern;
        this.mCallback = callback;
        this.mFullScreen = isFullScreen(context);
        this.mContext = new ContextThemeWrapper(context, (int) THEME_ID);
        LayoutInflater inflater = LayoutInflater.from(this.mContext);
        RemoteViews headerPresentation = response.getHeader();
        RemoteViews footerPresentation = response.getFooter();
        ViewGroup decor = this.mFullScreen ? (ViewGroup) inflater.inflate(17367101, (ViewGroup) null) : (headerPresentation == null && footerPresentation == null) ? (ViewGroup) inflater.inflate(17367100, (ViewGroup) null) : (ViewGroup) inflater.inflate(17367102, (ViewGroup) null);
        TextView titleView = (TextView) decor.findViewById(16908828);
        if (titleView != null) {
            titleView.setText(this.mContext.getString(17039571, serviceLabel));
        }
        ImageView iconView = (ImageView) decor.findViewById(16908825);
        if (iconView != null) {
            iconView.setImageDrawable(serviceIcon);
        }
        if (this.mFullScreen) {
            Point outPoint = this.mTempPoint;
            this.mContext.getDisplay().getSize(outPoint);
            this.mContentWidth = -1;
            this.mContentHeight = outPoint.y / 2;
            if (Helper.sVerbose) {
                Slog.v(TAG, "initialized fillscreen LayoutParams " + this.mContentWidth + "," + this.mContentHeight);
            }
        }
        decor.addOnUnhandledKeyEventListener(new View.OnUnhandledKeyEventListener() { // from class: com.android.server.autofill.ui.-$$Lambda$FillUi$FY016gv4LQ5AA6yOkKTH3EM5zaM
            @Override // android.view.View.OnUnhandledKeyEventListener
            public final boolean onUnhandledKeyEvent(View view, KeyEvent keyEvent) {
                return FillUi.lambda$new$0(FillUi.this, view, keyEvent);
            }
        });
        if (Helper.sVisibleDatasetsMaxCount > 0) {
            this.mVisibleDatasetsMaxCount = Helper.sVisibleDatasetsMaxCount;
            if (Helper.sVerbose) {
                Slog.v(TAG, "overriding maximum visible datasets to " + this.mVisibleDatasetsMaxCount);
            }
        } else {
            this.mVisibleDatasetsMaxCount = this.mContext.getResources().getInteger(17694724);
        }
        RemoteViews.OnClickHandler interceptionHandler2 = new RemoteViews.OnClickHandler() { // from class: com.android.server.autofill.ui.FillUi.1
            public boolean onClickHandler(View view, PendingIntent pendingIntent, Intent fillInIntent) {
                if (pendingIntent != null) {
                    FillUi.this.mCallback.startIntentSender(pendingIntent.getIntentSender());
                    return true;
                }
                return true;
            }
        };
        if (response.getAuthentication() != null) {
            this.mHeader = null;
            this.mListView = null;
            this.mFooter = null;
            this.mAdapter = null;
            ViewGroup container = (ViewGroup) decor.findViewById(16908827);
            try {
                response.getPresentation().setApplyTheme(THEME_ID);
                View content = response.getPresentation().apply(this.mContext, decor, interceptionHandler2);
                container.addView(content);
                container.setFocusable(true);
                container.setOnClickListener(new View.OnClickListener() { // from class: com.android.server.autofill.ui.-$$Lambda$FillUi$Vch3vycmBdfZEFnJyIbU-GchQzA
                    @Override // android.view.View.OnClickListener
                    public final void onClick(View view) {
                        FillUi.this.mCallback.onResponsePicked(response);
                    }
                });
                if (!this.mFullScreen) {
                    Point maxSize = this.mTempPoint;
                    resolveMaxWindowSize(this.mContext, maxSize);
                    content.getLayoutParams().width = this.mFullScreen ? maxSize.x : -2;
                    content.getLayoutParams().height = -2;
                    int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(maxSize.x, Integer.MIN_VALUE);
                    int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(maxSize.y, Integer.MIN_VALUE);
                    decor.measure(widthMeasureSpec, heightMeasureSpec);
                    this.mContentWidth = content.getMeasuredWidth();
                    this.mContentHeight = content.getMeasuredHeight();
                }
                this.mWindow = new AnchoredWindow(decor, overlayControl);
                requestShowFillUi();
                return;
            } catch (RuntimeException e) {
                callback.onCanceled();
                Slog.e(TAG, "Error inflating remote views", e);
                this.mWindow = null;
                return;
            }
        }
        int datasetCount2 = response.getDatasets().size();
        if (Helper.sVerbose) {
            Slog.v(TAG, "Number datasets: " + datasetCount2 + " max visible: " + this.mVisibleDatasetsMaxCount);
        }
        RemoteViews.OnClickHandler clickBlocker2 = null;
        if (headerPresentation != null) {
            clickBlocker2 = newClickBlocker();
            headerPresentation.setApplyTheme(THEME_ID);
            this.mHeader = headerPresentation.apply(this.mContext, null, clickBlocker2);
            LinearLayout headerContainer = (LinearLayout) decor.findViewById(16908824);
            if (Helper.sVerbose) {
                Slog.v(TAG, "adding header");
            }
            headerContainer.addView(this.mHeader);
            headerContainer.setVisibility(0);
        } else {
            this.mHeader = null;
        }
        if (footerPresentation != null) {
            LinearLayout footerContainer = (LinearLayout) decor.findViewById(16908823);
            if (footerContainer != null) {
                clickBlocker2 = clickBlocker2 == null ? newClickBlocker() : clickBlocker2;
                footerPresentation.setApplyTheme(THEME_ID);
                this.mFooter = footerPresentation.apply(this.mContext, null, clickBlocker2);
                if (Helper.sVerbose) {
                    Slog.v(TAG, "adding footer");
                }
                footerContainer.addView(this.mFooter);
                footerContainer.setVisibility(0);
            } else {
                this.mFooter = null;
            }
        } else {
            this.mFooter = null;
        }
        RemoteViews.OnClickHandler clickBlocker3 = clickBlocker2;
        ArrayList<ViewItem> items = new ArrayList<>(datasetCount2);
        int i = 0;
        while (true) {
            int i2 = i;
            if (i2 >= datasetCount2) {
                break;
            }
            Dataset dataset = (Dataset) response.getDatasets().get(i2);
            int index = dataset.getFieldIds().indexOf(focusedViewId);
            if (index >= 0) {
                RemoteViews presentation = dataset.getFieldPresentation(index);
                if (presentation == null) {
                    datasetCount = datasetCount2;
                    StringBuilder sb = new StringBuilder();
                    clickBlocker = clickBlocker3;
                    sb.append("not displaying UI on field ");
                    sb.append(focusedViewId);
                    sb.append(" because service didn't provide a presentation for it on ");
                    sb.append(dataset);
                    Slog.w(TAG, sb.toString());
                    interceptionHandler = interceptionHandler2;
                } else {
                    datasetCount = datasetCount2;
                    clickBlocker = clickBlocker3;
                    try {
                        if (Helper.sVerbose) {
                            try {
                                Slog.v(TAG, "setting remote view for " + focusedViewId);
                            } catch (RuntimeException e2) {
                                e = e2;
                                interceptionHandler = interceptionHandler2;
                                Slog.e(TAG, "Error inflating remote views", e);
                                i = i2 + 1;
                                datasetCount2 = datasetCount;
                                clickBlocker3 = clickBlocker;
                                interceptionHandler2 = interceptionHandler;
                            }
                        }
                        presentation.setApplyTheme(THEME_ID);
                        View view = presentation.apply(this.mContext, null, interceptionHandler2);
                        Dataset.DatasetFieldFilter filter = dataset.getFilter(index);
                        String valueText = null;
                        boolean filterable = true;
                        if (filter == null) {
                            AutofillValue value = (AutofillValue) dataset.getFieldValues().get(index);
                            if (value == null || !value.isText()) {
                                interceptionHandler = interceptionHandler2;
                            } else {
                                interceptionHandler = interceptionHandler2;
                                valueText = value.getTextValue().toString().toLowerCase();
                            }
                            filterPattern = null;
                        } else {
                            interceptionHandler = interceptionHandler2;
                            filterPattern = filter.pattern;
                            if (filterPattern == null) {
                                if (Helper.sVerbose) {
                                    Slog.v(TAG, "Explicitly disabling filter at id " + focusedViewId + " for dataset #" + index);
                                }
                                filterable = false;
                            }
                        }
                        items.add(new ViewItem(dataset, filterPattern, filterable, valueText, view));
                    } catch (RuntimeException e3) {
                        e = e3;
                        interceptionHandler = interceptionHandler2;
                    }
                }
            } else {
                interceptionHandler = interceptionHandler2;
                datasetCount = datasetCount2;
                clickBlocker = clickBlocker3;
            }
            i = i2 + 1;
            datasetCount2 = datasetCount;
            clickBlocker3 = clickBlocker;
            interceptionHandler2 = interceptionHandler;
        }
        this.mAdapter = new ItemsAdapter(items);
        this.mListView = (ListView) decor.findViewById(16908826);
        this.mListView.setAdapter((ListAdapter) this.mAdapter);
        this.mListView.setVisibility(0);
        this.mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() { // from class: com.android.server.autofill.ui.-$$Lambda$FillUi$jpLZ4TKMFTozyqA8_WsBHG3lWBg
            @Override // android.widget.AdapterView.OnItemClickListener
            public final void onItemClick(AdapterView adapterView, View view2, int i3, long j) {
                FillUi.lambda$new$2(FillUi.this, adapterView, view2, i3, j);
            }
        });
        if (filterText == null) {
            this.mFilterText = null;
        } else {
            this.mFilterText = filterText.toLowerCase();
        }
        applyNewFilterText();
        this.mWindow = new AnchoredWindow(decor, overlayControl);
    }

    public static /* synthetic */ boolean lambda$new$0(FillUi fillUi, View view, KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == 4 || keyCode == 66 || keyCode == 111) {
            return false;
        }
        switch (keyCode) {
            case WindowManagerService.H.REPORT_WINDOWS_CHANGE /* 19 */:
            case 20:
            case BackupHandler.MSG_OP_COMPLETE /* 21 */:
            case WindowManagerService.H.REPORT_HARD_KEYBOARD_STATUS_CHANGE /* 22 */:
            case WindowManagerService.H.BOOT_TIMEOUT /* 23 */:
                return false;
            default:
                fillUi.mCallback.dispatchUnhandledKey(event);
                return true;
        }
    }

    public static /* synthetic */ void lambda$new$2(FillUi fillUi, AdapterView adapter, View view, int position, long id) {
        ViewItem vi = fillUi.mAdapter.getItem(position);
        fillUi.mCallback.onDatasetPicked(vi.dataset);
    }

    void requestShowFillUi() {
        this.mCallback.requestShowFillUi(this.mContentWidth, this.mContentHeight, this.mWindowPresenter);
    }

    private RemoteViews.OnClickHandler newClickBlocker() {
        return new RemoteViews.OnClickHandler() { // from class: com.android.server.autofill.ui.FillUi.2
            public boolean onClickHandler(View view, PendingIntent pendingIntent, Intent fillInIntent) {
                if (Helper.sVerbose) {
                    Slog.v(FillUi.TAG, "Ignoring click on " + view);
                    return true;
                }
                return true;
            }
        };
    }

    private void applyNewFilterText() {
        final int oldCount = this.mAdapter.getCount();
        this.mAdapter.getFilter().filter(this.mFilterText, new Filter.FilterListener() { // from class: com.android.server.autofill.ui.-$$Lambda$FillUi$IWXsdxX6-XHI4qKxpJLqtc98gZA
            @Override // android.widget.Filter.FilterListener
            public final void onFilterComplete(int i) {
                FillUi.lambda$applyNewFilterText$3(FillUi.this, oldCount, i);
            }
        });
    }

    public static /* synthetic */ void lambda$applyNewFilterText$3(FillUi fillUi, int oldCount, int count) {
        if (fillUi.mDestroyed) {
            return;
        }
        if (count <= 0) {
            if (Helper.sDebug) {
                int size = fillUi.mFilterText != null ? fillUi.mFilterText.length() : 0;
                Slog.d(TAG, "No dataset matches filter with " + size + " chars");
            }
            fillUi.mCallback.requestHideFillUi();
            return;
        }
        if (fillUi.updateContentSize()) {
            fillUi.requestShowFillUi();
        }
        if (fillUi.mAdapter.getCount() <= fillUi.mVisibleDatasetsMaxCount) {
            fillUi.mListView.setVerticalScrollBarEnabled(false);
        } else {
            fillUi.mListView.setVerticalScrollBarEnabled(true);
            fillUi.mListView.onVisibilityAggregated(true);
        }
        if (fillUi.mAdapter.getCount() != oldCount) {
            fillUi.mListView.requestLayout();
        }
    }

    public void setFilterText(String filterText) {
        String filterText2;
        throwIfDestroyed();
        if (this.mAdapter == null) {
            if (TextUtils.isEmpty(filterText)) {
                requestShowFillUi();
                return;
            } else {
                this.mCallback.requestHideFillUi();
                return;
            }
        }
        if (filterText == null) {
            filterText2 = null;
        } else {
            filterText2 = filterText.toLowerCase();
        }
        if (Objects.equals(this.mFilterText, filterText2)) {
            return;
        }
        this.mFilterText = filterText2;
        applyNewFilterText();
    }

    public void destroy(boolean notifyClient) {
        throwIfDestroyed();
        if (this.mWindow != null) {
            this.mWindow.hide(false);
        }
        this.mCallback.onDestroy();
        if (notifyClient) {
            this.mCallback.requestHideFillUi();
        }
        this.mDestroyed = true;
    }

    private boolean updateContentSize() {
        if (this.mAdapter == null) {
            return false;
        }
        if (this.mFullScreen) {
            return true;
        }
        boolean changed = false;
        if (this.mAdapter.getCount() <= 0) {
            if (this.mContentWidth != 0) {
                this.mContentWidth = 0;
                changed = true;
            }
            if (this.mContentHeight != 0) {
                this.mContentHeight = 0;
                return true;
            }
            return changed;
        }
        Point maxSize = this.mTempPoint;
        resolveMaxWindowSize(this.mContext, maxSize);
        this.mContentWidth = 0;
        this.mContentHeight = 0;
        int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(maxSize.x, Integer.MIN_VALUE);
        int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(maxSize.y, Integer.MIN_VALUE);
        int itemCount = this.mAdapter.getCount();
        if (this.mHeader != null) {
            this.mHeader.measure(widthMeasureSpec, heightMeasureSpec);
            changed = false | updateWidth(this.mHeader, maxSize) | updateHeight(this.mHeader, maxSize);
        }
        for (int i = 0; i < itemCount; i++) {
            View view = this.mAdapter.getItem(i).view;
            view.measure(widthMeasureSpec, heightMeasureSpec);
            changed |= updateWidth(view, maxSize);
            if (i < this.mVisibleDatasetsMaxCount) {
                changed |= updateHeight(view, maxSize);
            }
        }
        if (this.mFooter != null) {
            this.mFooter.measure(widthMeasureSpec, heightMeasureSpec);
            return changed | updateWidth(this.mFooter, maxSize) | updateHeight(this.mFooter, maxSize);
        }
        return changed;
    }

    private boolean updateWidth(View view, Point maxSize) {
        int clampedMeasuredWidth = Math.min(view.getMeasuredWidth(), maxSize.x);
        int newContentWidth = Math.max(this.mContentWidth, clampedMeasuredWidth);
        if (newContentWidth == this.mContentWidth) {
            return false;
        }
        this.mContentWidth = newContentWidth;
        return true;
    }

    private boolean updateHeight(View view, Point maxSize) {
        int clampedMeasuredHeight = Math.min(view.getMeasuredHeight(), maxSize.y);
        int newContentHeight = this.mContentHeight + clampedMeasuredHeight;
        if (newContentHeight == this.mContentHeight) {
            return false;
        }
        this.mContentHeight = newContentHeight;
        return true;
    }

    private void throwIfDestroyed() {
        if (this.mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }

    private static void resolveMaxWindowSize(Context context, Point outPoint) {
        context.getDisplay().getSize(outPoint);
        TypedValue typedValue = sTempTypedValue;
        context.getTheme().resolveAttribute(17891344, typedValue, true);
        outPoint.x = (int) typedValue.getFraction(outPoint.x, outPoint.x);
        context.getTheme().resolveAttribute(17891343, typedValue, true);
        outPoint.y = (int) typedValue.getFraction(outPoint.y, outPoint.y);
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class ViewItem {
        public final Dataset dataset;
        public final Pattern filter;
        public final boolean filterable;
        public final String value;
        public final View view;

        ViewItem(Dataset dataset, Pattern filter, boolean filterable, String value, View view) {
            this.dataset = dataset;
            this.value = value;
            this.view = view;
            this.filter = filter;
            this.filterable = filterable;
        }

        public boolean matches(CharSequence filterText) {
            if (TextUtils.isEmpty(filterText)) {
                return true;
            }
            if (this.filterable) {
                String constraintLowerCase = filterText.toString().toLowerCase();
                if (this.filter != null) {
                    return this.filter.matcher(constraintLowerCase).matches();
                }
                if (this.value == null) {
                    return this.dataset.getAuthentication() == null;
                }
                return this.value.toLowerCase().startsWith(constraintLowerCase);
            }
            return false;
        }

        public String toString() {
            StringBuilder builder = new StringBuilder("ViewItem:[view=").append(this.view.getAutofillId());
            String datasetId = this.dataset == null ? null : this.dataset.getId();
            if (datasetId != null) {
                builder.append(", dataset=");
                builder.append(datasetId);
            }
            if (this.value != null) {
                builder.append(", value=");
                builder.append(this.value.length());
                builder.append("_chars");
            }
            if (this.filterable) {
                builder.append(", filterable");
            }
            if (this.filter != null) {
                builder.append(", filter=");
                builder.append(this.filter.pattern().length());
                builder.append("_chars");
            }
            builder.append(']');
            return builder.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class AutofillWindowPresenter extends IAutofillWindowPresenter.Stub {
        private AutofillWindowPresenter() {
        }

        public void show(final WindowManager.LayoutParams p, Rect transitionEpicenter, boolean fitsSystemWindows, int layoutDirection) {
            if (Helper.sVerbose) {
                Slog.v(FillUi.TAG, "AutofillWindowPresenter.show(): fit=" + fitsSystemWindows + ", params=" + Helper.paramsToString(p));
            }
            UiThread.getHandler().post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$FillUi$AutofillWindowPresenter$N4xQe2B0oe5MBiqZlsy3Lb7vZTg
                @Override // java.lang.Runnable
                public final void run() {
                    FillUi.this.mWindow.show(p);
                }
            });
        }

        public void hide(Rect transitionEpicenter) {
            Handler handler = UiThread.getHandler();
            final AnchoredWindow anchoredWindow = FillUi.this.mWindow;
            Objects.requireNonNull(anchoredWindow);
            handler.post(new Runnable() { // from class: com.android.server.autofill.ui.-$$Lambda$E4J-3bUcyqJNd4ZlExSBhwy8Tx4
                @Override // java.lang.Runnable
                public final void run() {
                    FillUi.AnchoredWindow.this.hide();
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public final class AnchoredWindow {
        private final View mContentView;
        private final OverlayControl mOverlayControl;
        private WindowManager.LayoutParams mShowParams;
        private boolean mShowing;
        private final WindowManager mWm;

        AnchoredWindow(View contentView, OverlayControl overlayControl) {
            this.mWm = (WindowManager) contentView.getContext().getSystemService(WindowManager.class);
            this.mContentView = contentView;
            this.mOverlayControl = overlayControl;
        }

        public void show(WindowManager.LayoutParams params) {
            this.mShowParams = params;
            if (Helper.sVerbose) {
                Slog.v(FillUi.TAG, "show(): showing=" + this.mShowing + ", params=" + Helper.paramsToString(params));
            }
            try {
                params.packageName = PackageManagerService.PLATFORM_PACKAGE_NAME;
                params.setTitle("Autofill UI");
                if (!this.mShowing) {
                    params.accessibilityTitle = this.mContentView.getContext().getString(17039548);
                    this.mWm.addView(this.mContentView, params);
                    this.mOverlayControl.hideOverlays();
                    this.mShowing = true;
                    return;
                }
                this.mWm.updateViewLayout(this.mContentView, params);
            } catch (WindowManager.BadTokenException e) {
                if (Helper.sDebug) {
                    Slog.d(FillUi.TAG, "Filed with with token " + params.token + " gone.");
                }
                FillUi.this.mCallback.onDestroy();
            } catch (IllegalStateException e2) {
                Slog.e(FillUi.TAG, "Exception showing window " + params, e2);
                FillUi.this.mCallback.onDestroy();
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public void hide() {
            hide(true);
        }

        void hide(boolean destroyCallbackOnError) {
            try {
                try {
                    if (this.mShowing) {
                        this.mWm.removeView(this.mContentView);
                        this.mShowing = false;
                    }
                } catch (IllegalStateException e) {
                    Slog.e(FillUi.TAG, "Exception hiding window ", e);
                    if (destroyCallbackOnError) {
                        FillUi.this.mCallback.onDestroy();
                    }
                }
            } finally {
                this.mOverlayControl.showOverlays();
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("mCallback: ");
        pw.println(this.mCallback != null);
        pw.print(prefix);
        pw.print("mFullScreen: ");
        pw.println(this.mFullScreen);
        pw.print(prefix);
        pw.print("mVisibleDatasetsMaxCount: ");
        pw.println(this.mVisibleDatasetsMaxCount);
        if (this.mHeader != null) {
            pw.print(prefix);
            pw.print("mHeader: ");
            pw.println(this.mHeader);
        }
        if (this.mListView != null) {
            pw.print(prefix);
            pw.print("mListView: ");
            pw.println(this.mListView);
        }
        if (this.mFooter != null) {
            pw.print(prefix);
            pw.print("mFooter: ");
            pw.println(this.mFooter);
        }
        if (this.mAdapter != null) {
            pw.print(prefix);
            pw.print("mAdapter: ");
            pw.println(this.mAdapter);
        }
        if (this.mFilterText != null) {
            pw.print(prefix);
            pw.print("mFilterText: ");
            Helper.printlnRedactedText(pw, this.mFilterText);
        }
        pw.print(prefix);
        pw.print("mContentWidth: ");
        pw.println(this.mContentWidth);
        pw.print(prefix);
        pw.print("mContentHeight: ");
        pw.println(this.mContentHeight);
        pw.print(prefix);
        pw.print("mDestroyed: ");
        pw.println(this.mDestroyed);
        if (this.mWindow != null) {
            pw.print(prefix);
            pw.print("mWindow: ");
            String prefix2 = prefix + "  ";
            pw.println();
            pw.print(prefix2);
            pw.print("showing: ");
            pw.println(this.mWindow.mShowing);
            pw.print(prefix2);
            pw.print("view: ");
            pw.println(this.mWindow.mContentView);
            if (this.mWindow.mShowParams != null) {
                pw.print(prefix2);
                pw.print("params: ");
                pw.println(this.mWindow.mShowParams);
            }
            pw.print(prefix2);
            pw.print("screen coordinates: ");
            if (this.mWindow.mContentView != null) {
                int[] coordinates = this.mWindow.mContentView.getLocationOnScreen();
                pw.print(coordinates[0]);
                pw.print("x");
                pw.println(coordinates[1]);
                return;
            }
            pw.println("N/A");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void announceSearchResultIfNeeded() {
        if (AccessibilityManager.getInstance(this.mContext).isEnabled()) {
            if (this.mAnnounceFilterResult == null) {
                this.mAnnounceFilterResult = new AnnounceFilterResult();
            }
            this.mAnnounceFilterResult.post();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class ItemsAdapter extends BaseAdapter implements Filterable {
        private final List<ViewItem> mAllItems;
        private final List<ViewItem> mFilteredItems = new ArrayList();

        ItemsAdapter(List<ViewItem> items) {
            this.mAllItems = Collections.unmodifiableList(new ArrayList(items));
            this.mFilteredItems.addAll(items);
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        /* renamed from: com.android.server.autofill.ui.FillUi$ItemsAdapter$1  reason: invalid class name */
        /* loaded from: classes.dex */
        public class AnonymousClass1 extends Filter {
            AnonymousClass1() {
            }

            @Override // android.widget.Filter
            protected Filter.FilterResults performFiltering(final CharSequence filterText) {
                List<ViewItem> filtered = (List) ItemsAdapter.this.mAllItems.stream().filter(new Predicate() { // from class: com.android.server.autofill.ui.-$$Lambda$FillUi$ItemsAdapter$1$8s9zobTvKJVJjInaObtlx2flLMc
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        boolean matches;
                        matches = ((FillUi.ViewItem) obj).matches(filterText);
                        return matches;
                    }
                }).collect(Collectors.toList());
                Filter.FilterResults results = new Filter.FilterResults();
                results.values = filtered;
                results.count = filtered.size();
                return results;
            }

            @Override // android.widget.Filter
            protected void publishResults(CharSequence constraint, Filter.FilterResults results) {
                int oldItemCount = ItemsAdapter.this.mFilteredItems.size();
                ItemsAdapter.this.mFilteredItems.clear();
                if (results.count > 0) {
                    List<ViewItem> items = (List) results.values;
                    ItemsAdapter.this.mFilteredItems.addAll(items);
                }
                boolean resultCountChanged = oldItemCount != ItemsAdapter.this.mFilteredItems.size();
                if (resultCountChanged) {
                    FillUi.this.announceSearchResultIfNeeded();
                }
                ItemsAdapter.this.notifyDataSetChanged();
            }
        }

        @Override // android.widget.Filterable
        public Filter getFilter() {
            return new AnonymousClass1();
        }

        @Override // android.widget.Adapter
        public int getCount() {
            return this.mFilteredItems.size();
        }

        @Override // android.widget.Adapter
        public ViewItem getItem(int position) {
            return this.mFilteredItems.get(position);
        }

        @Override // android.widget.Adapter
        public long getItemId(int position) {
            return position;
        }

        @Override // android.widget.Adapter
        public View getView(int position, View convertView, ViewGroup parent) {
            return getItem(position).view;
        }

        public String toString() {
            return "ItemsAdapter: [all=" + this.mAllItems + ", filtered=" + this.mFilteredItems + "]";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class AnnounceFilterResult implements Runnable {
        private static final int SEARCH_RESULT_ANNOUNCEMENT_DELAY = 1000;

        private AnnounceFilterResult() {
        }

        public void post() {
            remove();
            FillUi.this.mListView.postDelayed(this, 1000L);
        }

        public void remove() {
            FillUi.this.mListView.removeCallbacks(this);
        }

        @Override // java.lang.Runnable
        public void run() {
            String text;
            int count = FillUi.this.mListView.getAdapter().getCount();
            if (count <= 0) {
                text = FillUi.this.mContext.getString(17039549);
            } else {
                text = FillUi.this.mContext.getResources().getQuantityString(18153472, count, Integer.valueOf(count));
            }
            FillUi.this.mListView.announceForAccessibility(text);
        }
    }
}
