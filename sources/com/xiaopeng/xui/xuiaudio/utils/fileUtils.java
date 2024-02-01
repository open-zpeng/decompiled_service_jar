package com.xiaopeng.xui.xuiaudio.utils;

import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes2.dex */
public class fileUtils {
    private static String TAG = "fileUtils";

    public static List<File> searchFiles(final String folderName, final String keyword) {
        File folder = new File(folderName);
        List<File> result = new ArrayList<>();
        File[] subFolders = folder.listFiles(new FileFilter() { // from class: com.xiaopeng.xui.xuiaudio.utils.fileUtils.1
            @Override // java.io.FileFilter
            public boolean accept(File file) {
                if (file.getName().toLowerCase().contains(keyword)) {
                    String str = fileUtils.TAG;
                    Log.d(str, "find file:" + folderName + "" + file.getName());
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

    public static String getConfigFileContent(File readname) {
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
            String str = TAG;
            Log.e(str, "getConfigFileContent " + e);
        }
        return stringBuilder.toString();
    }
}
