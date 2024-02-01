package com.xiaopeng.xpaudioeffectpolicy;

import android.util.Log;
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

/* loaded from: classes2.dex */
public class XpAudioEffectParser {
    private static final String ConfigFile_Folder_data_middle = "/data/xuiservice/xuiaudioeffect-middle/";
    private static final String ConfigFile_Folder_data_normal = "/data/xuiservice/xuiaudioeffect/";
    private static final String ConfigFile_Folder_data_vave = "/data/xuiservice/xuiaudioeffect-vave/";
    private static final String ConfigFile_Folder_sys_middle = "/system/etc/xuiservice/xuiaudioeffect-middle/";
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

    /* loaded from: classes2.dex */
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

    /* loaded from: classes2.dex */
    public class XpAudioEffectParam {
        public int ModeIndex;
        public String ModeName;
        public XpEffectModeParam[] ModeParam;
        public double ModeVersion;

        public XpAudioEffectParam() {
        }
    }

    /* loaded from: classes2.dex */
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
                    Log.d(XpAudioEffectParser.TAG, "find file:" + folderName + "" + file.getName());
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
            XpAudioEffectParam sXpAudioEffectParam2 = data.get(i2);
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
        if (jsonContent != null && "".equals(jsonContent)) {
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

    /* JADX WARN: Removed duplicated region for block: B:31:0x00a1 A[Catch: Exception -> 0x010d, TRY_LEAVE, TryCatch #1 {Exception -> 0x010d, blocks: (B:4:0x001f, B:8:0x002e, B:11:0x0049, B:13:0x004f, B:14:0x0053, B:16:0x0059, B:21:0x0070, B:26:0x0091, B:28:0x0097, B:29:0x009b, B:31:0x00a1, B:36:0x00b6, B:38:0x00cc, B:39:0x00d8, B:41:0x00de, B:18:0x0065, B:33:0x00ad), top: B:49:0x001f, inners: #0, #2 }] */
    /* JADX WARN: Removed duplicated region for block: B:41:0x00de A[Catch: Exception -> 0x010d, LOOP:2: B:39:0x00d8->B:41:0x00de, LOOP_END, TRY_LEAVE, TryCatch #1 {Exception -> 0x010d, blocks: (B:4:0x001f, B:8:0x002e, B:11:0x0049, B:13:0x004f, B:14:0x0053, B:16:0x0059, B:21:0x0070, B:26:0x0091, B:28:0x0097, B:29:0x009b, B:31:0x00a1, B:36:0x00b6, B:38:0x00cc, B:39:0x00d8, B:41:0x00de, B:18:0x0065, B:33:0x00ad), top: B:49:0x001f, inners: #0, #2 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public com.xiaopeng.xpaudioeffectpolicy.XpAudioEffectParser.XpAudioEffectListArray parseXpAudioEffect() {
        /*
            Method dump skipped, instructions count: 274
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.xiaopeng.xpaudioeffectpolicy.XpAudioEffectParser.parseXpAudioEffect():com.xiaopeng.xpaudioeffectpolicy.XpAudioEffectParser$XpAudioEffectListArray");
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
