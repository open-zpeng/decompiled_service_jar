package com.xiaopeng.xui.xuiaudio.xuiAudioChannel;

import android.content.Context;
import android.media.AudioSystem;
import android.util.Log;
import com.android.server.backup.BackupManagerConstants;
import com.google.gson.Gson;
import com.xiaopeng.util.FeatureOption;
import java.util.ArrayList;
/* loaded from: classes.dex */
public class xuiExternalAudioPath {
    private static final String TAG = "xuiExternalAudioPath";
    private static final boolean hasPassageBtUsbFeature = FeatureOption.FO_PASSENGER_BT_AUDIO_EXIST;
    private static Context mContext = null;
    private static Gson mGson = null;
    private static xuiExternalAudioPath mInstance = null;
    private static final String pipePath = "/data/mytest";
    private passengerPkgList mPkgList;

    private xuiExternalAudioPath(Context context) {
        mContext = context;
        mGson = new Gson();
    }

    public static xuiExternalAudioPath getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new xuiExternalAudioPath(context);
        }
        return mInstance;
    }

    /* loaded from: classes.dex */
    private class pkgBean {
        public int pid;
        public String pkgName;
        public int uid;

        private pkgBean() {
        }

        public int getPid() {
            return this.pid;
        }

        public void setPid(int pid) {
            this.pid = pid;
        }

        public int getUid() {
            return this.uid;
        }

        public void setUid(int uid) {
            this.uid = uid;
        }

        public String getPkgName() {
            return this.pkgName;
        }

        public void setPkgName(String pkgName) {
            this.pkgName = pkgName;
        }
    }

    /* loaded from: classes.dex */
    private class passengerPkgList {
        public pkgBean[] pkglist;

        private passengerPkgList() {
        }

        public pkgBean[] getPkgList() {
            return this.pkglist;
        }

        public void setPkgList(pkgBean[] pkglist) {
            this.pkglist = pkglist;
        }
    }

    private void saveUidList(String str) {
        Log.d(TAG, "saveUidList  PATH:/data/mytest  :" + str);
        if (hasPassageBtUsbFeature) {
            AudioSystem.setParameters("ApsMethod=updateUidList;value=" + str);
        }
    }

    public synchronized void setPassengerPkgList(ArrayList<Integer> uidList) {
        StringBuilder sb = new StringBuilder();
        sb.append("setPassengerPkgList ");
        sb.append(uidList == null ? BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS : uidList.toString());
        Log.d(TAG, sb.toString());
        if (uidList != null) {
            StringBuilder mStr = new StringBuilder();
            for (int i = 0; i < uidList.size(); i++) {
                mStr.append(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + uidList.get(i) + ",");
            }
            saveUidList(mStr.toString());
        } else {
            saveUidList(BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS);
        }
    }

    public void setAvasStreamEnable(int busType, boolean enable) {
        Log.d(TAG, "setAvasStreamEnable BUS:" + busType + " " + enable);
    }

    private void handleException(String func, Exception e) {
        Log.e(TAG, BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + func + " " + e);
    }
}
