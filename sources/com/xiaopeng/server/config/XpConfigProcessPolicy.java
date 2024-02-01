package com.xiaopeng.server.config;

import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.internal.os.BackgroundThread;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/* loaded from: classes2.dex */
public class XpConfigProcessPolicy {
    private static final String TAG = "XpConfigProcessPolicy";
    private static final String XP_CONFIG_PROCESS_POLICY = "/system/etc/xp_config_process_policy.json";
    private final Object mSync;
    private XpProcessPolicy mXpProcessPolicy;

    private XpConfigProcessPolicy() {
        this.mSync = new Object();
    }

    public static XpConfigProcessPolicy getInstance() {
        return SingleInstance.instance;
    }

    public void init() {
        BackgroundThread.getExecutor().execute(new Runnable() { // from class: com.xiaopeng.server.config.-$$Lambda$XpConfigProcessPolicy$gj2CKR5LWCmZgb_znjjA9qm-CKY
            @Override // java.lang.Runnable
            public final void run() {
                XpConfigProcessPolicy.this.lambda$init$0$XpConfigProcessPolicy();
            }
        });
    }

    public /* synthetic */ void lambda$init$0$XpConfigProcessPolicy() {
        Slog.d(TAG, "init procss policy config");
        File file = new File(XP_CONFIG_PROCESS_POLICY);
        if (file.exists() && file.isFile()) {
            StringBuilder builder = new StringBuilder();
            try {
                InputStreamReader reader = new InputStreamReader(new FileInputStream(file));
                BufferedReader br = new BufferedReader(reader);
                while (true) {
                    try {
                        String line = br.readLine();
                        if (line == null) {
                            break;
                        }
                        builder.append(line);
                    } catch (Throwable th) {
                        try {
                            throw th;
                        } catch (Throwable th2) {
                            $closeResource(th, br);
                            throw th2;
                        }
                    }
                }
                $closeResource(null, br);
                $closeResource(null, reader);
            } catch (Exception e) {
                Slog.w(TAG, "read exception: " + e.getMessage());
            }
            String content = builder.toString();
            if (!TextUtils.isEmpty(content)) {
                try {
                    JSONObject jsonObject = new JSONObject(content);
                    synchronized (this.mSync) {
                        this.mXpProcessPolicy = new XpProcessPolicy();
                        if (jsonObject.has("commonMaxMem")) {
                            this.mXpProcessPolicy.setCommonMaxMem(jsonObject.getInt("commonMaxMem"));
                        }
                        if (jsonObject.has("processLimits")) {
                            JSONArray jsonArray = jsonObject.getJSONArray("processLimits");
                            if (jsonArray == null) {
                                return;
                            }
                            Slog.i(TAG, "jsonArray: " + jsonArray.toString());
                            Map map = this.mXpProcessPolicy.getMap();
                            for (int i = 0; i < jsonArray.length(); i++) {
                                JSONObject jsonObject1 = jsonArray.getJSONObject(i);
                                String processName = jsonObject1.getString("processName");
                                int processMaxMem = jsonObject1.getInt("processMaxMem");
                                map.put(processName, Integer.valueOf(processMaxMem));
                            }
                        }
                    }
                } catch (Exception e2) {
                    Slog.e(TAG, "error parse json msg: " + e2.getMessage());
                }
            }
        }
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    public XpProcessPolicy getXpProcessPolicy() {
        XpProcessPolicy xpProcessPolicy;
        synchronized (this.mSync) {
            xpProcessPolicy = this.mXpProcessPolicy;
        }
        return xpProcessPolicy;
    }

    public void dumpConfig(PrintWriter pw) {
        XpProcessPolicy xpProcessPolicy;
        pw.println("  Xp Config Process Policy:");
        synchronized (this.mSync) {
            xpProcessPolicy = this.mXpProcessPolicy;
        }
        if (xpProcessPolicy != null) {
            pw.println("    " + xpProcessPolicy.toString());
        }
    }

    /* loaded from: classes2.dex */
    private static class SingleInstance {
        private static final XpConfigProcessPolicy instance = new XpConfigProcessPolicy();

        private SingleInstance() {
        }
    }

    /* loaded from: classes2.dex */
    public static class XpProcessPolicy {
        private ArrayMap<String, Integer> arrayMap = new ArrayMap<>();
        private int commonMaxMem;

        public int getCommonMaxMem() {
            return this.commonMaxMem;
        }

        public void setCommonMaxMem(int commonMaxMem) {
            this.commonMaxMem = commonMaxMem;
        }

        public Map<String, Integer> getMap() {
            return this.arrayMap;
        }

        public String toString() {
            return "XpProcessPolicy{commonMaxMem=" + this.commonMaxMem + ", arrayMap=" + this.arrayMap + '}';
        }
    }
}
