package com.android.server.webkit;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.webkit.UserPackage;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;
import java.io.PrintWriter;
import java.util.List;
/* loaded from: classes.dex */
public class WebViewUpdateServiceImpl {
    private static final int MULTIPROCESS_SETTING_OFF_VALUE = Integer.MIN_VALUE;
    private static final int MULTIPROCESS_SETTING_ON_VALUE = Integer.MAX_VALUE;
    private static final String TAG = WebViewUpdateServiceImpl.class.getSimpleName();
    private final Context mContext;
    private SystemInterface mSystemInterface;
    private WebViewUpdater mWebViewUpdater;

    public WebViewUpdateServiceImpl(Context context, SystemInterface systemInterface) {
        this.mContext = context;
        this.mSystemInterface = systemInterface;
        this.mWebViewUpdater = new WebViewUpdater(this.mContext, this.mSystemInterface);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void packageStateChanged(String packageName, int changedState, int userId) {
        updateFallbackStateOnPackageChange(packageName, changedState);
        this.mWebViewUpdater.packageStateChanged(packageName, changedState);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void prepareWebViewInSystemServer() {
        updateFallbackStateOnBoot();
        this.mWebViewUpdater.prepareWebViewInSystemServer();
        this.mSystemInterface.notifyZygote(isMultiProcessEnabled());
    }

    private boolean existsValidNonFallbackProvider(WebViewProviderInfo[] providers) {
        for (WebViewProviderInfo provider : providers) {
            if (provider.availableByDefault && !provider.isFallback) {
                List<UserPackage> userPackages = this.mSystemInterface.getPackageInfoForProviderAllUsers(this.mContext, provider);
                if (WebViewUpdater.isInstalledAndEnabledForAllUsers(userPackages) && this.mWebViewUpdater.isValidProvider(provider, userPackages.get(0).getPackageInfo())) {
                    return true;
                }
            }
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleNewUser(int userId) {
        if (userId == 0) {
            return;
        }
        handleUserChange();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void handleUserRemoved(int userId) {
        handleUserChange();
    }

    private void handleUserChange() {
        if (this.mSystemInterface.isFallbackLogicEnabled()) {
            updateFallbackState(this.mSystemInterface.getWebViewPackages());
        }
        this.mWebViewUpdater.updateCurrentWebViewPackage(null);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void notifyRelroCreationCompleted() {
        this.mWebViewUpdater.notifyRelroCreationCompleted();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WebViewProviderResponse waitForAndGetProvider() {
        return this.mWebViewUpdater.waitForAndGetProvider();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public String changeProviderAndSetting(String newProvider) {
        return this.mWebViewUpdater.changeProviderAndSetting(newProvider);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WebViewProviderInfo[] getValidWebViewPackages() {
        return this.mWebViewUpdater.getValidWebViewPackages();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public WebViewProviderInfo[] getWebViewPackages() {
        return this.mSystemInterface.getWebViewPackages();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public PackageInfo getCurrentWebViewPackage() {
        return this.mWebViewUpdater.getCurrentWebViewPackage();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void enableFallbackLogic(boolean enable) {
        this.mSystemInterface.enableFallbackLogic(enable);
    }

    private void updateFallbackStateOnBoot() {
        if (this.mSystemInterface.isFallbackLogicEnabled()) {
            WebViewProviderInfo[] webviewProviders = this.mSystemInterface.getWebViewPackages();
            updateFallbackState(webviewProviders);
        }
    }

    private void updateFallbackStateOnPackageChange(String changedPackage, int changedState) {
        if (this.mSystemInterface.isFallbackLogicEnabled()) {
            WebViewProviderInfo[] webviewProviders = this.mSystemInterface.getWebViewPackages();
            boolean changedPackageAvailableByDefault = false;
            int length = webviewProviders.length;
            int i = 0;
            while (true) {
                if (i >= length) {
                    break;
                }
                WebViewProviderInfo provider = webviewProviders[i];
                if (!provider.packageName.equals(changedPackage)) {
                    i++;
                } else if (provider.availableByDefault) {
                    changedPackageAvailableByDefault = true;
                }
            }
            if (changedPackageAvailableByDefault) {
                updateFallbackState(webviewProviders);
            }
        }
    }

    private void updateFallbackState(WebViewProviderInfo[] webviewProviders) {
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewProviders);
        if (fallbackProvider == null) {
            return;
        }
        boolean existsValidNonFallbackProvider = existsValidNonFallbackProvider(webviewProviders);
        List<UserPackage> userPackages = this.mSystemInterface.getPackageInfoForProviderAllUsers(this.mContext, fallbackProvider);
        if (existsValidNonFallbackProvider && !isDisabledForAllUsers(userPackages)) {
            this.mSystemInterface.uninstallAndDisablePackageForAllUsers(this.mContext, fallbackProvider.packageName);
        } else if (!existsValidNonFallbackProvider && !WebViewUpdater.isInstalledAndEnabledForAllUsers(userPackages)) {
            this.mSystemInterface.enablePackageForAllUsers(this.mContext, fallbackProvider.packageName, true);
        }
    }

    private static WebViewProviderInfo getFallbackProvider(WebViewProviderInfo[] webviewPackages) {
        for (WebViewProviderInfo provider : webviewPackages) {
            if (provider.isFallback) {
                return provider;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isFallbackPackage(String packageName) {
        if (packageName == null || !this.mSystemInterface.isFallbackLogicEnabled()) {
            return false;
        }
        WebViewProviderInfo[] webviewPackages = this.mSystemInterface.getWebViewPackages();
        WebViewProviderInfo fallbackProvider = getFallbackProvider(webviewPackages);
        return fallbackProvider != null && packageName.equals(fallbackProvider.packageName);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public boolean isMultiProcessEnabled() {
        int settingValue = this.mSystemInterface.getMultiProcessSetting(this.mContext);
        return this.mSystemInterface.isMultiProcessDefaultEnabled() ? settingValue > Integer.MIN_VALUE : settingValue >= MULTIPROCESS_SETTING_ON_VALUE;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void enableMultiProcess(boolean enable) {
        PackageInfo current = getCurrentWebViewPackage();
        this.mSystemInterface.setMultiProcessSetting(this.mContext, enable ? MULTIPROCESS_SETTING_ON_VALUE : Integer.MIN_VALUE);
        this.mSystemInterface.notifyZygote(enable);
        if (current != null) {
            this.mSystemInterface.killPackageDependents(current.packageName);
        }
    }

    private static boolean isDisabledForAllUsers(List<UserPackage> userPackages) {
        for (UserPackage userPackage : userPackages) {
            if (userPackage.getPackageInfo() != null && userPackage.isEnabledPackage()) {
                return false;
            }
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public void dumpState(PrintWriter pw) {
        pw.println("Current WebView Update Service state");
        pw.println(String.format("  Fallback logic enabled: %b", Boolean.valueOf(this.mSystemInterface.isFallbackLogicEnabled())));
        pw.println(String.format("  Multiprocess enabled: %b", Boolean.valueOf(isMultiProcessEnabled())));
        this.mWebViewUpdater.dumpState(pw);
    }
}
