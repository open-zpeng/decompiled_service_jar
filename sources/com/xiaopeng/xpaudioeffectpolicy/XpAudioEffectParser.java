package com.xiaopeng.xpaudioeffectpolicy;

import android.os.SystemProperties;
import android.util.Log;
import com.android.server.backup.BackupManagerConstants;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
/* loaded from: classes.dex */
public class XpAudioEffectParser {
    private static final String ConfigFile_Folder_data_normal = "/data/xuiservice/xuiaudioeffect/";
    private static final String ConfigFile_Folder_data_vave = "/data/xuiservice/xuiaudioeffect-vave/";
    private static final String ConfigFile_Folder_sys_normal = "/system/etc/xuiservice/xuiaudioeffect/";
    private static final String ConfigFile_Folder_sys_vave = "/system/etc/xuiservice/xuiaudioeffect-vave/";
    private static final String ConfigFile_Name = "xuiaudioeffectconfig";
    public static final String KEY_EQLevel = "EQLevel";
    public static final String KEY_EffectName = "EffectName";
    public static final String KEY_Freq1 = "Freq1";
    public static final String KEY_Freq2 = "Freq2";
    public static final String KEY_Freq3 = "Freq3";
    public static final String KEY_Freq4 = "Freq4";
    public static final String KEY_Freq5 = "Freq5";
    public static final String KEY_Freq6 = "Freq6";
    public static final String KEY_Freq7 = "Freq7";
    public static final String KEY_Freq8 = "Freq8";
    public static final String KEY_Freq9 = "Freq9";
    public static final String KEY_Gain1 = "Gain1";
    public static final String KEY_Gain2 = "Gain2";
    public static final String KEY_Gain3 = "Gain3";
    public static final String KEY_Gain4 = "Gain4";
    public static final String KEY_Gain5 = "Gain5";
    public static final String KEY_Gain6 = "Gain6";
    public static final String KEY_Gain7 = "Gain7";
    public static final String KEY_Gain8 = "Gain8";
    public static final String KEY_Gain9 = "Gain9";
    public static final String KEY_ModeIndex = "ModeIndex";
    public static final String KEY_ModeName = "ModeName";
    public static final String KEY_ModeParam = "ModeParam";
    public static final String KEY_ModeVersion = "ModeVersion";
    public static final String KEY_Q1 = "Q1";
    public static final String KEY_Q2 = "Q2";
    public static final String KEY_Q3 = "Q3";
    public static final String KEY_Q4 = "Q4";
    public static final String KEY_Q5 = "Q5";
    public static final String KEY_Q6 = "Q6";
    public static final String KEY_Q7 = "Q7";
    public static final String KEY_Q8 = "Q8";
    public static final String KEY_Q9 = "Q9";
    public static final String KEY_XpAudioEffectList = "XpAudioEffectList";
    private static final String TAG = "XpAudioEffectParser";

    /* loaded from: classes.dex */
    public class XpEffectModeParam {
        public int EQLevel;
        public int Freq1;
        public int Freq2;
        public int Freq3;
        public int Freq4;
        public int Freq5;
        public int Freq6;
        public int Freq7;
        public int Freq8;
        public int Freq9;
        public double Gain1;
        public double Gain2;
        public double Gain3;
        public double Gain4;
        public double Gain5;
        public double Gain6;
        public double Gain7;
        public double Gain8;
        public double Gain9;
        public double Q1;
        public double Q2;
        public double Q3;
        public double Q4;
        public double Q5;
        public double Q6;
        public double Q7;
        public double Q8;
        public double Q9;

        public XpEffectModeParam() {
        }
    }

    /* loaded from: classes.dex */
    public class XpAudioEffectParam {
        public int ModeIndex;
        public String ModeName;
        public XpEffectModeParam[] ModeParam;
        public double ModeVersion;

        public XpAudioEffectParam() {
        }
    }

    /* loaded from: classes.dex */
    public class XpAudioEffectListArray {
        public String EffectName;
        public List<XpAudioEffectParam> mXpAudioEffectList;

        public XpAudioEffectListArray() {
        }
    }

    public List<File> searchFiles(final String folderName, final String keyword) {
        File folder = new File(folderName);
        List<File> result = new ArrayList<>();
        File[] subFolders = folder.listFiles(new FileFilter() { // from class: com.xiaopeng.xpaudioeffectpolicy.XpAudioEffectParser.1
            @Override // java.io.FileFilter
            public boolean accept(File file) {
                if (file.getName().toLowerCase().contains(keyword)) {
                    Log.d(XpAudioEffectParser.TAG, "find file:" + folderName + BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS + file.getName());
                    return true;
                }
                return false;
            }
        });
        if (subFolders != null) {
            for (File file : subFolders) {
                if (file.isFile()) {
                    result.add(file);
                }
            }
        }
        return result;
    }

    private String getConfigFileContent(File readname) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            InputStreamReader reader = new InputStreamReader(new FileInputStream(readname));
            BufferedReader br = new BufferedReader(reader);
            while (true) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                stringBuilder.append(line);
            }
            br.close();
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "getConfigFileContent " + e);
        }
        return stringBuilder.toString();
    }

    private void checkAndSetEffectList(List<XpAudioEffectParam> listArray, XpAudioEffectParam effectList) {
        if (effectList == null || listArray == null) {
            return;
        }
        if (listArray.isEmpty()) {
            listArray.add(effectList);
            return;
        }
        for (int i = listArray.size() - 1; i >= 0; i--) {
            XpAudioEffectParam tXpAudioEffectParam = listArray.get(i);
            if (tXpAudioEffectParam != null && tXpAudioEffectParam.ModeIndex == effectList.ModeIndex) {
                if (tXpAudioEffectParam.ModeVersion < effectList.ModeVersion) {
                    listArray.remove(i);
                    listArray.add(effectList);
                    return;
                } else {
                    return;
                }
            }
        }
        listArray.add(effectList);
    }

    private List<XpAudioEffectParam> mergeAudioEffectList(List<XpAudioEffectParam> data, List<XpAudioEffectParam> sys) {
        if (data.isEmpty() && sys.isEmpty()) {
            return null;
        }
        if (data.isEmpty() && !sys.isEmpty()) {
            return sys;
        }
        if (!data.isEmpty() && sys.isEmpty()) {
            return data;
        }
        Log.i(TAG, "mergeAudioEffectList data.size():" + data.size() + " sys.size():" + sys.size());
        List<XpAudioEffectParam> mAudioEffectList = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            XpAudioEffectParam dXpAudioEffectParam = data.get(i);
            if (dXpAudioEffectParam != null) {
                boolean added = false;
                int j = 0;
                while (true) {
                    if (j >= sys.size()) {
                        break;
                    }
                    XpAudioEffectParam sXpAudioEffectParam = sys.get(j);
                    if (sXpAudioEffectParam == null || dXpAudioEffectParam.ModeIndex != sXpAudioEffectParam.ModeIndex) {
                        j++;
                    } else if (dXpAudioEffectParam.ModeVersion <= sXpAudioEffectParam.ModeVersion) {
                        Log.i(TAG, " add s index:" + dXpAudioEffectParam.ModeIndex);
                        mAudioEffectList.add(sXpAudioEffectParam);
                        added = true;
                    } else {
                        Log.i(TAG, " add d index:" + dXpAudioEffectParam.ModeIndex);
                        mAudioEffectList.add(dXpAudioEffectParam);
                        added = true;
                    }
                }
                if (!added) {
                    Log.i(TAG, " add  index:" + dXpAudioEffectParam.ModeIndex);
                    mAudioEffectList.add(dXpAudioEffectParam);
                }
            }
        }
        Log.i(TAG, "mergeAudioEffectList mAudioEffectList.size():" + mAudioEffectList.size());
        for (int i2 = 0; i2 < sys.size(); i2++) {
            XpAudioEffectParam sXpAudioEffectParam2 = sys.get(i2);
            if (sXpAudioEffectParam2 != null) {
                boolean added2 = false;
                int j2 = 0;
                while (true) {
                    if (j2 >= mAudioEffectList.size()) {
                        break;
                    }
                    XpAudioEffectParam mList = mAudioEffectList.get(j2);
                    if (sXpAudioEffectParam2.ModeIndex != mList.ModeIndex) {
                        j2++;
                    } else {
                        added2 = true;
                        break;
                    }
                }
                if (!added2) {
                    mAudioEffectList.add(sXpAudioEffectParam2);
                }
            }
        }
        Log.d(TAG, "mergeAudioEffectList 2 mAudioEffectList.size():" + mAudioEffectList.size());
        return mAudioEffectList;
    }

    private XpAudioEffectParam parseAudioConfigList(String jsonContent) {
        if (jsonContent != null && BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS.equals(jsonContent)) {
            Log.e(TAG, "parseAudioConfigList jsonContent==null");
            return null;
        }
        XpAudioEffectParam mTempEffestList = new XpAudioEffectParam();
        try {
            JSONObject jObject = new JSONObject(jsonContent);
            mTempEffestList.ModeName = jObject.getString(KEY_ModeName);
            mTempEffestList.ModeIndex = jObject.getInt(KEY_ModeIndex);
            mTempEffestList.ModeVersion = 0.0d;
            try {
                mTempEffestList.ModeVersion = Double.valueOf(jObject.get(KEY_ModeVersion).toString()).doubleValue();
            } catch (Exception e) {
            }
            Log.i(TAG, "parseAudioConfigList  ModeName:" + mTempEffestList.ModeName + " ModeIndex:" + mTempEffestList.ModeIndex);
            JSONArray jParamArray = jObject.getJSONArray(KEY_ModeParam);
            if (jParamArray != null) {
                int Paramlength = jParamArray.length();
                mTempEffestList.ModeParam = new XpEffectModeParam[Paramlength];
                for (int j = 0; j < Paramlength; j++) {
                    JSONObject paramObject = jParamArray.getJSONObject(j);
                    mTempEffestList.ModeParam[j] = parseXpEffectModeParam(paramObject);
                    if (mTempEffestList.ModeParam[j] == null) {
                        Log.e(TAG, "parseAudioConfigList parseXpEffectModeParam==null j:" + j);
                        return null;
                    }
                }
                return mTempEffestList;
            }
            Log.e(TAG, "parseAudioConfigList jParamArray==null");
            return null;
        } catch (JSONException e2) {
            e2.printStackTrace();
            Log.e(TAG, "parseAudioConfigList() " + e2);
            return null;
        }
    }

    public XpAudioEffectParam getXpAudioEffectParam(List<XpAudioEffectParam> mXpAudioEffectList, int index) {
        for (XpAudioEffectParam mAudioEffectParam : mXpAudioEffectList) {
            if (mAudioEffectParam.ModeIndex == index) {
                return mAudioEffectParam;
            }
        }
        return null;
    }

    private static boolean isPathExist(String dirPath) {
        File file = new File(dirPath);
        if (!file.exists()) {
            return false;
        }
        return true;
    }

    public XpAudioEffectListArray parseXpAudioEffect() {
        String ConfigFile_Folder_data;
        String ConfigFile_Folder_sys;
        XpAudioEffectListArray mEffectListArray = new XpAudioEffectListArray();
        int vave_level = SystemProperties.getInt("persist.sys.xiaopeng.vave_level", 0);
        mEffectListArray.EffectName = "Normal";
        try {
            if (isPathExist(ConfigFile_Folder_data_vave) && isPathExist(ConfigFile_Folder_sys_vave) && vave_level == 1) {
                ConfigFile_Folder_data = ConfigFile_Folder_data_vave;
                ConfigFile_Folder_sys = ConfigFile_Folder_sys_vave;
            } else {
                ConfigFile_Folder_data = ConfigFile_Folder_data_normal;
                ConfigFile_Folder_sys = ConfigFile_Folder_sys_normal;
            }
            List<File> fileListdata = searchFiles(ConfigFile_Folder_data, ConfigFile_Name);
            List<File> fileListsys = searchFiles(ConfigFile_Folder_sys, ConfigFile_Name);
            List<XpAudioEffectParam> mXpAudioEffectParam_data = new ArrayList<>();
            List<XpAudioEffectParam> mXpAudioEffectParam_sys = new ArrayList<>();
            if (fileListdata != null && !fileListdata.isEmpty()) {
                for (File mfile : fileListdata) {
                    String fileContent = getConfigFileContent(mfile);
                    try {
                        XpAudioEffectParam tXpAudioEffectParam = parseAudioConfigList(fileContent);
                        checkAndSetEffectList(mXpAudioEffectParam_data, tXpAudioEffectParam);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log.e(TAG, "parseXpAudioEffect() " + e);
                    }
                }
            }
            if (fileListsys != null && !fileListsys.isEmpty()) {
                for (File mfile2 : fileListsys) {
                    String fileContent2 = getConfigFileContent(mfile2);
                    try {
                        XpAudioEffectParam tXpAudioEffectParam2 = parseAudioConfigList(fileContent2);
                        checkAndSetEffectList(mXpAudioEffectParam_sys, tXpAudioEffectParam2);
                    } catch (Exception e2) {
                        e2.printStackTrace();
                        Log.e(TAG, "parseXpAudioEffect() " + e2);
                    }
                }
            }
            mEffectListArray.mXpAudioEffectList = mergeAudioEffectList(mXpAudioEffectParam_data, mXpAudioEffectParam_sys);
            for (XpAudioEffectParam mParam : mEffectListArray.mXpAudioEffectList) {
                Log.i(TAG, "parseXpAudioEffect: " + mParam.ModeIndex + " " + mParam.ModeName + " " + mParam.ModeVersion);
            }
        } catch (Exception e3) {
            e3.printStackTrace();
        }
        return mEffectListArray;
    }

    private XpEffectModeParam parseXpEffectModeParam(JSONObject object) {
        if (object == null) {
            return null;
        }
        XpEffectModeParam mModeParam = new XpEffectModeParam();
        try {
            mModeParam.EQLevel = object.getInt(KEY_EQLevel);
            mModeParam.Freq1 = object.getInt(KEY_Freq1);
            mModeParam.Gain1 = object.getDouble(KEY_Gain1);
            mModeParam.Q1 = object.getDouble(KEY_Q1);
            mModeParam.Freq2 = object.getInt(KEY_Freq2);
            mModeParam.Gain2 = object.getDouble(KEY_Gain2);
            mModeParam.Q2 = object.getDouble(KEY_Q2);
            mModeParam.Freq3 = object.getInt(KEY_Freq3);
            mModeParam.Gain3 = object.getDouble(KEY_Gain3);
            mModeParam.Q3 = object.getDouble(KEY_Q3);
            mModeParam.Freq4 = object.getInt(KEY_Freq4);
            mModeParam.Gain4 = object.getDouble(KEY_Gain4);
            mModeParam.Q4 = object.getDouble(KEY_Q4);
            mModeParam.Freq5 = object.getInt(KEY_Freq5);
            mModeParam.Gain5 = object.getDouble(KEY_Gain5);
            mModeParam.Q5 = object.getDouble(KEY_Q5);
            mModeParam.Freq6 = object.getInt(KEY_Freq6);
            mModeParam.Gain6 = object.getDouble(KEY_Gain6);
            mModeParam.Q6 = object.getDouble(KEY_Q6);
            mModeParam.Freq7 = object.getInt(KEY_Freq7);
            mModeParam.Gain7 = object.getDouble(KEY_Gain7);
            mModeParam.Q7 = object.getDouble(KEY_Q7);
            mModeParam.Freq8 = object.getInt(KEY_Freq8);
            mModeParam.Gain8 = object.getDouble(KEY_Gain8);
            mModeParam.Q8 = object.getDouble(KEY_Q8);
            mModeParam.Freq9 = object.getInt(KEY_Freq9);
            mModeParam.Gain9 = object.getDouble(KEY_Gain9);
            mModeParam.Q9 = object.getDouble(KEY_Q9);
            Log.i(TAG, "parseXpEffectModeParam  EQLevel:" + mModeParam.EQLevel);
            return mModeParam;
        } catch (JSONException e) {
            e.printStackTrace();
            Log.e(TAG, "parseXpEffectModeParam  e:" + e);
            return null;
        }
    }
}
