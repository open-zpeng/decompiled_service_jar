package com.xiaopeng.server.aftersales;

import android.util.Slog;
import java.util.Arrays;
/* loaded from: classes.dex */
class AfterSalesDaemonEvent {
    private static final String TAG = "AfterSalesDaemonEvent";
    public static final String XP_AFTERSALES_CMD_SEPARATOR = "~";
    public static final String XP_AFTERSALES_PARAM_SEPARATOR = "#";
    public static final String XP_AFTERSALES_REQ = "XP_AFTERSALES_REQ";
    public static final String XP_AFTERSALES_RESPONSE = "XP_AFTERSALES_RES";
    private final int mAction;
    private final int mCommand;
    private final String mFlag;
    private final String[] mParam;

    public AfterSalesDaemonEvent(String flag, int command, int action, String[] param) {
        this.mFlag = flag;
        this.mCommand = command;
        this.mAction = action;
        this.mParam = param;
    }

    public AfterSalesDaemonEvent(String flag, int command, int action, Object... args) {
        this.mFlag = flag;
        this.mCommand = command;
        this.mAction = action;
        this.mParam = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            this.mParam[i] = String.valueOf(args[i]);
        }
    }

    public String getFlag() {
        return this.mFlag;
    }

    public int getCommand() {
        return this.mCommand;
    }

    public int getAction() {
        return this.mAction;
    }

    public String[] getParam() {
        return this.mParam;
    }

    public String toString() {
        return "AfterSalesDaemonEvent{mFlag='" + this.mFlag + "', mCommand=" + this.mCommand + ", mAction=" + this.mAction + ", mParam=" + Arrays.toString(this.mParam) + '}';
    }

    public static AfterSalesDaemonEvent parseRawEvent(String rawEvent) {
        String[] parsed = rawEvent.split(XP_AFTERSALES_CMD_SEPARATOR);
        if (parsed.length < 3) {
            Slog.e(TAG, "parsing RawEvent '" + rawEvent + "' Insufficient arguments");
            return null;
        }
        String flag = parsed[0];
        int cmd = -1;
        try {
            cmd = Integer.parseInt(parsed[1]);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "parsing RawEvent cmd :" + e);
        }
        int action = -1;
        try {
            action = Integer.parseInt(parsed[2]);
        } catch (NumberFormatException e2) {
            Slog.e(TAG, "parsing RawEvent action :" + e2);
        }
        String[] param = null;
        if (parsed.length > 3) {
            param = parsed[3].split(XP_AFTERSALES_PARAM_SEPARATOR, -1);
        }
        return new AfterSalesDaemonEvent(flag, cmd, action, param);
    }

    public String toRawEvent() {
        String[] strArr;
        StringBuilder rawBuilder = new StringBuilder();
        rawBuilder.append(this.mFlag);
        rawBuilder.append(XP_AFTERSALES_CMD_SEPARATOR);
        rawBuilder.append(this.mCommand);
        rawBuilder.append(XP_AFTERSALES_CMD_SEPARATOR);
        rawBuilder.append(this.mAction);
        rawBuilder.append(XP_AFTERSALES_CMD_SEPARATOR);
        for (String param : this.mParam) {
            rawBuilder.append(param);
            rawBuilder.append(XP_AFTERSALES_PARAM_SEPARATOR);
        }
        rawBuilder.append("\r\n");
        return rawBuilder.toString();
    }
}
