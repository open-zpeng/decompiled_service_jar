package com.android.server;

import android.app.IXpConfigService;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import com.xiaopeng.util.xpTextUtils;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes.dex */
public class XpConfigService extends IXpConfigService.Stub {
    private static final String TAG = "XpConfigService";
    private static volatile String appList;
    private static boolean isScreenon = true;
    private XpConfigServiceHandler mHandler;

    public XpConfigService(Context context) {
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        this.mHandler = new XpConfigServiceHandler(handlerThread.getLooper());
    }

    public void init() {
        Log.i(TAG, "init");
        this.mHandler.init();
    }

    public String getAppList() {
        return appList;
    }

    /* loaded from: classes.dex */
    private static final class XpConfigServiceHandler extends Handler {
        private static final int MESSAGE_INIT = 1;

        public XpConfigServiceHandler(Looper looper) {
            super(looper);
        }

        public void init() {
            sendEmptyMessage(1);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (msg.what == 1) {
                CatchCatonAppPolicy.loadCatonPolicy();
            }
        }
    }

    /* loaded from: classes.dex */
    protected static class CatchCatonAppPolicy {
        public static final String KEY_APP_LIST = "catchapplist";
        public static final String KEY_PACKAGE_NAME = "packageName";
        private static final String POLICY_FILE_SYSTEM = "/system/etc/catonconfig/catonconfig.json";
        private static final String SEPARATOR = "#";

        protected CatchCatonAppPolicy() {
        }

        public static void loadCatonPolicy() {
            JSONArray arrays;
            try {
                StringBuilder stringBuilder = new StringBuilder();
                File systemFile = new File(POLICY_FILE_SYSTEM);
                String content = xpTextUtils.getValue(systemFile);
                if (TextUtils.isEmpty(content)) {
                    return;
                }
                JSONObject jsonObj = new JSONObject(content);
                if (jsonObj.has(KEY_APP_LIST) && (arrays = jsonObj.getJSONArray(KEY_APP_LIST)) != null) {
                    int length = arrays.length();
                    for (int i = 0; i < length; i++) {
                        JSONObject object = arrays.getJSONObject(i);
                        String packageName = xpTextUtils.toString(xpTextUtils.getValue("packageName", object));
                        stringBuilder.append(packageName);
                        stringBuilder.append(SEPARATOR);
                    }
                }
                String unused = XpConfigService.appList = stringBuilder.toString();
                Log.i(XpConfigService.TAG, "loadProcessPolicy completed content:" + XpConfigService.appList);
            } catch (Exception e) {
                Log.i(XpConfigService.TAG, "loadProcessPolicy e=" + e);
            }
        }
    }
}
