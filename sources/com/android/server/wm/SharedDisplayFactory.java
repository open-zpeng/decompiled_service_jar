package com.android.server.wm;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.WindowManager;
import com.android.server.hdmi.HdmiCecKeycode;
import com.android.server.wm.SharedDisplayContainer;
import com.xiaopeng.app.xpActivityInfo;
import com.xiaopeng.app.xpDialogInfo;
import com.xiaopeng.server.input.xpInputActionHandler;
import com.xiaopeng.server.input.xpInputManagerService;
import com.xiaopeng.util.xpLogger;
import com.xiaopeng.util.xpTextUtils;
import com.xiaopeng.view.SharedDisplayManager;
import java.util.HashMap;

/* loaded from: classes2.dex */
public class SharedDisplayFactory {
    private static final String KEY_QUICK_PANEL_EVENT = "key_quick_panel_event";
    private static final String KEY_SLIDE_TOUCH_EVENT = "key_slide_touch_event";
    private static final String TAG = "SharedDisplayFactory";

    public static <T> T getBundle(Bundle bundle, String key, T defaultValue) {
        if (TextUtils.isEmpty(key) || bundle == null) {
            return defaultValue;
        }
        return !bundle.containsKey(key) ? defaultValue : (T) bundle.get(key);
    }

    public static <T> void addBundle(Bundle bundle, String key, T value) {
        if (TextUtils.isEmpty(key) || bundle == null || value == null) {
            return;
        }
        if (value instanceof Integer) {
            bundle.putInt(key, ((Integer) value).intValue());
        } else if (value instanceof String) {
            bundle.putString(key, (String) value);
        } else if (value instanceof Float) {
            bundle.putFloat(key, ((Float) value).floatValue());
        } else if (value instanceof Boolean) {
            bundle.putBoolean(key, ((Boolean) value).booleanValue());
        } else if (value instanceof Parcelable) {
            bundle.putParcelable(key, (Parcelable) value);
        }
    }

    public static void setQuickPanelEvent(Context context, int sharedId) {
        if (context == null) {
            return;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            HashMap<String, Object> map = new HashMap<>();
            map.put("sharedId", Integer.valueOf(sharedId));
            map.put("event", 0);
            map.put("time", Long.valueOf(System.currentTimeMillis()));
            String content = xpTextUtils.toJsonString(map);
            xpLogger.i(TAG, "setQuickPanelEvent content=" + content);
            Settings.Secure.putString(resolver, "key_quick_panel_event_" + sharedId, content);
        } catch (Exception e) {
        }
    }

    public static boolean isSlideTouchEnabled(Context context, int screenId) {
        String content;
        if (context == null) {
            return true;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            content = Settings.Secure.getString(resolver, KEY_SLIDE_TOUCH_EVENT);
        } catch (Exception e) {
        }
        if (TextUtils.isEmpty(content)) {
            return true;
        }
        Integer enable = (Integer) xpTextUtils.getValue(xpInputManagerService.InputPolicyKey.KEY_ENABLE, content, 1);
        Integer sharedId = (Integer) xpTextUtils.getValue("sharedId", content, 0);
        xpLogger.i(TAG, "isSlideTouchEnabled enable=" + enable + " sharedId=" + sharedId + " screenId=" + screenId + " content=" + content);
        if (enable.intValue() == 0) {
            if (sharedId.intValue() == -1) {
                return false;
            }
            if (SharedDisplayManager.findScreenId(sharedId.intValue()) == screenId) {
                return false;
            }
        }
        return true;
    }

    public static void playSoundEffect(Context context, Handler handler) {
        if (context == null || handler == null) {
            return;
        }
        final AudioManager audio = (AudioManager) context.getSystemService("audio");
        handler.post(new Runnable() { // from class: com.android.server.wm.-$$Lambda$SharedDisplayFactory$vDhwEMk6JuNS1mkJl5udfunJ1gA
            @Override // java.lang.Runnable
            public final void run() {
                SharedDisplayFactory.lambda$playSoundEffect$0(audio);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$playSoundEffect$0(AudioManager audio) {
        audio.loadSoundEffects();
        audio.playSoundEffect(18);
        xpLogger.i(TAG, xpInputActionHandler.FUNCTION_PLAY_SOUND_EFFECT);
    }

    public static xpActivityInfo createActivityInfo(Intent intent, ActivityInfo info) {
        String packageName = "";
        String className = "";
        if (info != null) {
            ComponentName component = info.getComponentName();
            packageName = info.packageName;
            className = component != null ? component.getClassName() : "";
        }
        return xpActivityInfo.create(intent, packageName, className);
    }

    public static xpActivityInfo createActivityInfo(Intent intent, ResolveInfo info) {
        String packageName = "";
        String className = "";
        if (info != null && info.activityInfo != null) {
            ComponentName component = info.activityInfo.getComponentName();
            packageName = info.activityInfo.packageName;
            className = component != null ? component.getClassName() : "";
        }
        return xpActivityInfo.create(intent, packageName, className);
    }

    public static xpDialogInfo getTopDialog(Context context, Bundle extras) {
        HashMap<Integer, xpDialogInfo> map;
        if (context == null || (map = getTopDialog(context)) == null || map.isEmpty()) {
            return null;
        }
        boolean matchSharedId = extras != null && extras.containsKey("sharedId");
        boolean matchScreenId = extras != null && extras.containsKey("screenId");
        int sharedId = ((Integer) getBundle(extras, "sharedId", -1)).intValue();
        int screenId = ((Integer) getBundle(extras, "screenId", Integer.valueOf(SharedDisplayManager.findScreenId(sharedId)))).intValue();
        for (Integer num : map.keySet()) {
            int id = num.intValue();
            xpDialogInfo info = map.get(Integer.valueOf(id));
            if (info != null) {
                boolean match = sharedId == id || screenId == SharedDisplayManager.findScreenId(id);
                if (match && matchSharedId) {
                    int _sharedId = extras.getInt("sharedId", sharedId);
                    match = match && _sharedId == id;
                }
                if (match && matchScreenId) {
                    int _screenId = extras.getInt("screenId", screenId);
                    match = match && _screenId == SharedDisplayManager.findScreenId(id);
                }
                if (match) {
                    return info;
                }
            }
        }
        return null;
    }

    /* JADX WARN: Removed duplicated region for block: B:29:0x00a4  */
    /* JADX WARN: Removed duplicated region for block: B:48:0x00e1 A[Catch: Exception -> 0x0160, TryCatch #0 {Exception -> 0x0160, blocks: (B:6:0x0011, B:9:0x0028, B:11:0x0033, B:14:0x003a, B:16:0x003e, B:32:0x00ac, B:34:0x00b2, B:36:0x00b8, B:37:0x00bc, B:39:0x00c2, B:42:0x00cb, B:44:0x00d3, B:48:0x00e1, B:50:0x00e7, B:21:0x008d, B:24:0x0097), top: B:59:0x0011 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public static java.util.HashMap<java.lang.Integer, com.xiaopeng.app.xpDialogInfo> getTopDialog(android.content.Context r21) {
        /*
            Method dump skipped, instructions count: 354
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.wm.SharedDisplayFactory.getTopDialog(android.content.Context):java.util.HashMap");
    }

    public static boolean dismissDialog(Context context, Bundle extras) {
        int screenId;
        boolean z = false;
        if (context == null) {
            return false;
        }
        boolean z2 = true;
        if (extras != null) {
            try {
                screenId = extras.getInt("screenId", 0);
            } catch (Exception e) {
                return true;
            }
        } else {
            screenId = 0;
        }
        boolean topOnly = extras != null ? extras.getBoolean("topOnly", true) : true;
        if (SharedDisplayContainer.SharedDisplayImpl.hasDialog(screenId)) {
            WindowManager wm = (WindowManager) context.getSystemService("window");
            if (topOnly) {
                HashMap<Integer, xpDialogInfo> map = getTopDialog(context);
                if (map != null && !map.isEmpty()) {
                    for (Integer num : map.keySet()) {
                        int id = num.intValue();
                        xpDialogInfo info = map.get(Integer.valueOf(id));
                        if (info != null && screenId == SharedDisplayManager.findScreenId(id)) {
                            boolean isUnityDialog = (info.windowType != 5 || info.subType <= 0) ? z : z2;
                            if (isUnityDialog) {
                                wm.setSharedEvent(HdmiCecKeycode.CEC_KEYCODE_RESTORE_VOLUME_FUNCTION, id, "");
                            } else {
                                HashMap<String, String> json = new HashMap<>();
                                json.put(String.valueOf(screenId), info.token);
                                Bundle bundle = new Bundle();
                                bundle.putBoolean("topOnly", topOnly);
                                bundle.putInt("screenId", screenId);
                                bundle.putString("token", xpTextUtils.toJsonString(json));
                                broadcastDismissDialog(context, bundle);
                            }
                            z = false;
                            z2 = true;
                        }
                    }
                    return true;
                }
                return false;
            }
            return broadcastDismissDialog(context, extras);
        }
        return false;
    }

    public static boolean broadcastDismissDialog(Context context, Bundle extras) {
        if (context == null) {
            return false;
        }
        try {
            Intent intent = new Intent("com.xiaopeng.intent.action.DISMISS_DIALOG");
            intent.addFlags(1344274432);
            if (extras != null) {
                intent.putExtras(extras);
            }
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
