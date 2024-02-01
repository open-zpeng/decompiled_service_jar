package com.xiaopeng.xui.xuiaudio.utils;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;
import com.google.gson.Gson;
import com.xiaopeng.util.FeatureOption;
import com.xiaopeng.xui.xuiaudio.xuiAudioChannel.channelData;
import com.xiaopeng.xui.xuiaudio.xuiAudioChannel.massageSeatEffect;
import com.xiaopeng.xui.xuiaudio.xuiAudioEffect.AudioEffectData;
import com.xiaopeng.xui.xuiaudio.xuiAudioVolume.volumeJsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes2.dex */
public class xuiAudioParser {
    private static final String TAG = "xuiAudioParser";
    private static final String audioConfig_Folder_data_normal = "/data/xuiservice/xuiaudio/";
    private static final String audioConfig_Folder_sys_normal = "/system/etc/xuiservice/xuiaudio/";
    private static final String audioConfig_channelDataName = "xuiChannelPolicy.json";
    private static final String audioConfig_volumeDataName = "xuiVolumeData.json";
    private static final String audioConfig_volumePolicyName = "xuiConflictVolumePolicy.json";
    private static final String audioConfig_volumeTypeName = "xuiVolumeType.json";
    private static final String effectFile_Name = "xuiaudioeffectconfig";
    private static final String effect_Folder_data_normal = "/data/xuiservice/xuiaudioeffect/";
    private static final String effect_Folder_data_vave = "/data/xuiservice/xuiaudioeffect-vave/";
    private static final String effect_Folder_sys_normal = "/system/etc/xuiservice/xuiaudioeffect/";
    private static final String effect_Folder_sys_vave = "/system/etc/xuiservice/xuiaudioeffect-vave/";
    private static Context mContext = null;
    private static Gson mGson = null;
    private static final String massage_seat_file_Name = "massageseatconfig";
    private static final String massage_seat_folder = "/system/etc/xuiservice/massageseat/";
    private static xuiAudioParser instance = null;
    private static int AudioFeature = 0;
    private static volumeJsonObject.streamVolumePare mStreamVolumeType = null;
    private static volumeJsonObject.conflictVolumeObject mConflictVolumeObject = null;
    private static volumeJsonObject.volumeTypeData mVolumeTypeData = null;
    private static channelData mChannelData = null;
    private static List<AudioEffectData> mAudioEffectDataList = null;
    private static List<massageSeatEffect> mMassageSeatEffectList = null;
    private static final boolean hasMassSeat = FeatureOption.hasFeature("FSEAT_RHYTHM");

    public xuiAudioParser(Context context) {
        mContext = context;
        mGson = new Gson();
        mAudioEffectDataList = new ArrayList();
    }

    public static xuiAudioParser getInstance(Context context) {
        if (instance == null) {
            instance = new xuiAudioParser(context);
        }
        return instance;
    }

    private static boolean isPathExist(String dirPath) {
        File file = new File(dirPath);
        return file.exists();
    }

    public static synchronized volumeJsonObject.streamVolumePare parseStreamVolumePare() {
        synchronized (xuiAudioParser.class) {
            if (mStreamVolumeType != null) {
                return mStreamVolumeType;
            }
            volumeJsonObject.streamVolumePare mVolumeType1 = null;
            volumeJsonObject.streamVolumePare mVolumeType2 = null;
            if (mGson == null) {
                mGson = new Gson();
            }
            try {
                Log.d(TAG, "parseVolumeType  PATH:/system/etc/xuiservice/xuiaudio/xuiVolumeType.json");
                if (isPathExist("/system/etc/xuiservice/xuiaudio/xuiVolumeType.json")) {
                    mVolumeType1 = (volumeJsonObject.streamVolumePare) mGson.fromJson(getConfigFileContentByPath("/system/etc/xuiservice/xuiaudio/xuiVolumeType.json"), (Class<Object>) volumeJsonObject.streamVolumePare.class);
                }
                Log.d(TAG, "parseVolumeType  PATH:/data/xuiservice/xuiaudio/xuiVolumeType.json");
                if (isPathExist("/data/xuiservice/xuiaudio/xuiVolumeType.json")) {
                    mVolumeType2 = (volumeJsonObject.streamVolumePare) mGson.fromJson(getConfigFileContentByPath("/data/xuiservice/xuiaudio/xuiVolumeType.json"), (Class<Object>) volumeJsonObject.streamVolumePare.class);
                }
                Log.d(TAG, "parseVolumeType end!!");
            } catch (Exception e) {
                handleException(e);
            }
            if (mVolumeType2 == null) {
                mStreamVolumeType = mVolumeType1;
            } else if (mVolumeType1 != null) {
                mStreamVolumeType = mVolumeType1.version >= mVolumeType2.version ? mVolumeType1 : mVolumeType2;
            }
            return mStreamVolumeType;
        }
    }

    private static String getConfigFileContentByPath(String path) {
        File readname = new File(path);
        return getConfigFileContent(readname);
    }

    private static String getConfigFileContent(File readname) {
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

    public static synchronized volumeJsonObject.conflictVolumeObject parseConflictVolumePolicy() {
        synchronized (xuiAudioParser.class) {
            if (mConflictVolumeObject != null) {
                return mConflictVolumeObject;
            }
            volumeJsonObject.conflictVolumeObject mconflictVolumeObject1 = null;
            volumeJsonObject.conflictVolumeObject mconflictVolumeObject2 = null;
            if (mGson == null) {
                mGson = new Gson();
            }
            try {
                Log.d(TAG, "parseConflictVolumePolicy  PATH:/system/etc/xuiservice/xuiaudio/xuiConflictVolumePolicy.json");
                if (isPathExist("/system/etc/xuiservice/xuiaudio/xuiConflictVolumePolicy.json")) {
                    mconflictVolumeObject1 = (volumeJsonObject.conflictVolumeObject) mGson.fromJson(getConfigFileContentByPath("/system/etc/xuiservice/xuiaudio/xuiConflictVolumePolicy.json"), (Class<Object>) volumeJsonObject.conflictVolumeObject.class);
                }
                Log.d(TAG, "parseConflictVolumePolicy  PATH:/data/xuiservice/xuiaudio/xuiConflictVolumePolicy.json");
                if (isPathExist("/data/xuiservice/xuiaudio/xuiConflictVolumePolicy.json")) {
                    mconflictVolumeObject2 = (volumeJsonObject.conflictVolumeObject) mGson.fromJson(getConfigFileContentByPath("/data/xuiservice/xuiaudio/xuiConflictVolumePolicy.json"), (Class<Object>) volumeJsonObject.conflictVolumeObject.class);
                }
                Log.d(TAG, "parseConflictVolumePolicy end!!");
            } catch (Exception e) {
                handleException(e);
            }
            if (mconflictVolumeObject2 == null) {
                mConflictVolumeObject = mconflictVolumeObject1;
            } else if (mconflictVolumeObject1 != null) {
                mConflictVolumeObject = mconflictVolumeObject1.version >= mconflictVolumeObject2.version ? mconflictVolumeObject1 : mconflictVolumeObject2;
            }
            return mConflictVolumeObject;
        }
    }

    public static synchronized volumeJsonObject.volumeTypeData parseVolumeData() {
        synchronized (xuiAudioParser.class) {
            if (mVolumeTypeData != null) {
                return mVolumeTypeData;
            }
            volumeJsonObject.volumeTypeData mVolumeTypeData1 = null;
            volumeJsonObject.volumeTypeData mVolumeTypeData2 = null;
            if (mGson == null) {
                mGson = new Gson();
            }
            try {
                Log.d(TAG, "parseVolumeData  PATH:/system/etc/xuiservice/xuiaudio/xuiVolumeData.json");
                if (isPathExist("/system/etc/xuiservice/xuiaudio/xuiVolumeData.json")) {
                    mVolumeTypeData1 = (volumeJsonObject.volumeTypeData) mGson.fromJson(getConfigFileContentByPath("/system/etc/xuiservice/xuiaudio/xuiVolumeData.json"), (Class<Object>) volumeJsonObject.volumeTypeData.class);
                }
                Log.d(TAG, "parseVolumeData  PATH:/data/xuiservice/xuiaudio/xuiVolumeData.json");
                if (isPathExist("/data/xuiservice/xuiaudio/xuiVolumeData.json")) {
                    mVolumeTypeData2 = (volumeJsonObject.volumeTypeData) mGson.fromJson(getConfigFileContentByPath("/data/xuiservice/xuiaudio/xuiVolumeData.json"), (Class<Object>) volumeJsonObject.volumeTypeData.class);
                }
                Log.d(TAG, "parseVolumeData end!!");
            } catch (Exception e) {
                handleException(e);
            }
            if (mVolumeTypeData2 == null) {
                mVolumeTypeData = mVolumeTypeData1;
            } else if (mVolumeTypeData1 != null) {
                mVolumeTypeData = mVolumeTypeData1.version >= mVolumeTypeData2.version ? mVolumeTypeData1 : mVolumeTypeData2;
            }
            return mVolumeTypeData;
        }
    }

    public static synchronized channelData parseChannelData() {
        synchronized (xuiAudioParser.class) {
            if (mChannelData != null) {
                return mChannelData;
            }
            channelData mChannelData1 = null;
            channelData mChannelData2 = null;
            if (mGson == null) {
                mGson = new Gson();
            }
            try {
                Log.d(TAG, "parseChannelData  PATH:/system/etc/xuiservice/xuiaudio/xuiChannelPolicy.json");
                if (isPathExist("/system/etc/xuiservice/xuiaudio/xuiChannelPolicy.json")) {
                    mChannelData1 = (channelData) mGson.fromJson(getConfigFileContentByPath("/system/etc/xuiservice/xuiaudio/xuiChannelPolicy.json"), (Class<Object>) channelData.class);
                }
                Log.d(TAG, "parseChannelData  PATH:/data/xuiservice/xuiaudio/xuiChannelPolicy.json");
                if (isPathExist("/data/xuiservice/xuiaudio/xuiChannelPolicy.json")) {
                    mChannelData2 = (channelData) mGson.fromJson(getConfigFileContentByPath("/data/xuiservice/xuiaudio/xuiChannelPolicy.json"), (Class<Object>) channelData.class);
                }
                Log.d(TAG, "parseChannelData end!!");
            } catch (Exception e) {
                handleException(e);
            }
            if (mChannelData2 == null) {
                mChannelData = mChannelData1;
            } else if (mChannelData1 != null) {
                mChannelData = mChannelData1.version >= mChannelData2.version ? mChannelData1 : mChannelData2;
            }
            return mChannelData;
        }
    }

    public static synchronized List<AudioEffectData> getAudioEffectDataList() {
        String ConfigFile_Folder_data;
        String ConfigFile_Folder_sys;
        List<AudioEffectData> list;
        synchronized (xuiAudioParser.class) {
            int vave_level = SystemProperties.getInt("persist.sys.xiaopeng.vave_level", 0);
            if ((isPathExist(effect_Folder_data_vave) || isPathExist(effect_Folder_sys_vave)) && vave_level == 1) {
                ConfigFile_Folder_data = effect_Folder_data_vave;
                ConfigFile_Folder_sys = effect_Folder_sys_vave;
            } else {
                ConfigFile_Folder_data = effect_Folder_data_normal;
                ConfigFile_Folder_sys = effect_Folder_sys_normal;
            }
            List<File> fileListdata = searchFiles(ConfigFile_Folder_data, effectFile_Name);
            List<File> fileListsys = searchFiles(ConfigFile_Folder_sys, effectFile_Name);
            List<AudioEffectData> mAudioEffectData_data = new ArrayList<>();
            List<AudioEffectData> mAudioEffectData_sys = new ArrayList<>();
            if (fileListdata != null && !fileListdata.isEmpty()) {
                for (File mfile : fileListdata) {
                    try {
                        AudioEffectData tAudioEffectData = (AudioEffectData) mGson.fromJson(getConfigFileContent(mfile), (Class<Object>) AudioEffectData.class);
                        checkAndSetEffectList(mAudioEffectData_data, tAudioEffectData);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                }
            }
            if (fileListsys != null && !fileListsys.isEmpty()) {
                for (File mfile2 : fileListsys) {
                    try {
                        AudioEffectData tAudioEffectData2 = (AudioEffectData) mGson.fromJson(getConfigFileContent(mfile2), (Class<Object>) AudioEffectData.class);
                        checkAndSetEffectList(mAudioEffectData_sys, tAudioEffectData2);
                    } catch (Exception e2) {
                        e2.printStackTrace();
                        return null;
                    }
                }
            }
            mAudioEffectDataList = mergeAudioEffectList(mAudioEffectData_data, mAudioEffectData_sys);
            if (mAudioEffectDataList != null && mAudioEffectDataList.size() != 0) {
                for (AudioEffectData mParam : mAudioEffectDataList) {
                    Log.i(TAG, "getAudioEffectDataList: " + mParam.ModeIndex + " " + mParam.ModeName + " " + mParam.ModeVersion);
                }
            }
            list = mAudioEffectDataList;
        }
        return list;
    }

    public static List<File> searchFiles(final String folderName, final String keyword) {
        File folder = new File(folderName);
        List<File> result = new ArrayList<>();
        File[] subFolders = folder.listFiles(new FileFilter() { // from class: com.xiaopeng.xui.xuiaudio.utils.xuiAudioParser.1
            @Override // java.io.FileFilter
            public boolean accept(File file) {
                if (file.getName().toLowerCase().contains(keyword)) {
                    Log.d(xuiAudioParser.TAG, "find file:" + folderName + "" + file.getName());
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

    private static void checkAndSetEffectList(List<AudioEffectData> listArray, AudioEffectData effect) {
        if (effect == null || listArray == null) {
            return;
        }
        if (listArray.isEmpty()) {
            listArray.add(effect);
            return;
        }
        for (int i = listArray.size() - 1; i >= 0; i--) {
            AudioEffectData tAudioEffectData = listArray.get(i);
            if (tAudioEffectData != null && tAudioEffectData.ModeIndex == effect.ModeIndex) {
                if (tAudioEffectData.ModeVersion < effect.ModeVersion) {
                    listArray.remove(i);
                    listArray.add(effect);
                    return;
                } else {
                    return;
                }
            }
        }
        listArray.add(effect);
    }

    private static List<AudioEffectData> mergeAudioEffectList(List<AudioEffectData> data, List<AudioEffectData> sys) {
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
        List<AudioEffectData> mAudioEffectList = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            AudioEffectData dAudioEffectData = data.get(i);
            if (dAudioEffectData != null) {
                boolean added = false;
                int j = 0;
                while (true) {
                    if (j >= sys.size()) {
                        break;
                    }
                    AudioEffectData sAudioEffectData = sys.get(j);
                    if (sAudioEffectData == null || dAudioEffectData.ModeIndex != sAudioEffectData.ModeIndex) {
                        j++;
                    } else if (dAudioEffectData.ModeVersion <= sAudioEffectData.ModeVersion) {
                        Log.i(TAG, " add s index:" + dAudioEffectData.ModeIndex);
                        mAudioEffectList.add(sAudioEffectData);
                        added = true;
                    } else {
                        Log.i(TAG, " add d index:" + dAudioEffectData.ModeIndex);
                        mAudioEffectList.add(dAudioEffectData);
                        added = true;
                    }
                }
                if (!added) {
                    Log.i(TAG, " add  index:" + dAudioEffectData.ModeIndex);
                    mAudioEffectList.add(dAudioEffectData);
                }
            }
        }
        Log.i(TAG, "mergeAudioEffectList mAudioEffectList.size():" + mAudioEffectList.size());
        for (int i2 = 0; i2 < sys.size(); i2++) {
            AudioEffectData sAudioEffectData2 = sys.get(i2);
            if (sAudioEffectData2 != null) {
                boolean added2 = false;
                int j2 = 0;
                while (true) {
                    if (j2 >= mAudioEffectList.size()) {
                        break;
                    }
                    AudioEffectData mList = mAudioEffectList.get(j2);
                    if (sAudioEffectData2.ModeIndex != mList.ModeIndex) {
                        j2++;
                    } else {
                        added2 = true;
                        break;
                    }
                }
                if (!added2) {
                    mAudioEffectList.add(sAudioEffectData2);
                }
            }
        }
        Log.d(TAG, "mergeAudioEffectList 2 mAudioEffectList.size():" + mAudioEffectList.size());
        return mAudioEffectList;
    }

    public static synchronized List<massageSeatEffect> parseMassageSeatEffect() {
        synchronized (xuiAudioParser.class) {
            if (hasMassSeat) {
                if (mMassageSeatEffectList != null) {
                    return mMassageSeatEffectList;
                }
                if (mGson == null) {
                    mGson = new Gson();
                }
                List<File> fileListsys = searchFiles(massage_seat_folder, massage_seat_file_Name);
                mMassageSeatEffectList = new ArrayList();
                Log.d(TAG, "parseMassageSeatEffect  fileListsys.size :" + fileListsys.size());
                if (!fileListsys.isEmpty()) {
                    for (File mfile : fileListsys) {
                        try {
                            massageSeatEffect tMassageSeatEffect = (massageSeatEffect) mGson.fromJson(getConfigFileContent(mfile), (Class<Object>) massageSeatEffect.class);
                            mMassageSeatEffectList.add(tMassageSeatEffect);
                        } catch (Exception e) {
                            e.printStackTrace();
                            return null;
                        }
                    }
                }
                return mMassageSeatEffectList;
            }
            return null;
        }
    }

    private static void handleException(Exception e) {
        Log.d(TAG, "handleException " + e);
    }
}
