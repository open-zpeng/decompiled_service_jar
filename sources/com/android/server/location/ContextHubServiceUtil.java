package com.android.server.location;

import android.content.Context;
import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.HubAppInfo;
import android.hardware.contexthub.V1_0.NanoAppBinary;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
/* JADX INFO: Access modifiers changed from: package-private */
/* loaded from: classes.dex */
public class ContextHubServiceUtil {
    private static final String ENFORCE_HW_PERMISSION_MESSAGE = "Permission 'android.permission.LOCATION_HARDWARE' not granted to access ContextHub Hardware";
    private static final String HARDWARE_PERMISSION = "android.permission.LOCATION_HARDWARE";
    private static final String TAG = "ContextHubServiceUtil";

    ContextHubServiceUtil() {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static HashMap<Integer, ContextHubInfo> createContextHubInfoMap(List<ContextHub> hubList) {
        HashMap<Integer, ContextHubInfo> contextHubIdToInfoMap = new HashMap<>();
        for (ContextHub contextHub : hubList) {
            contextHubIdToInfoMap.put(Integer.valueOf(contextHub.hubId), new ContextHubInfo(contextHub));
        }
        return contextHubIdToInfoMap;
    }

    static void copyToByteArrayList(byte[] inputArray, ArrayList<Byte> outputArray) {
        outputArray.clear();
        outputArray.ensureCapacity(inputArray.length);
        for (byte element : inputArray) {
            outputArray.add(Byte.valueOf(element));
        }
    }

    static byte[] createPrimitiveByteArray(ArrayList<Byte> array) {
        byte[] primitiveArray = new byte[array.size()];
        for (int i = 0; i < array.size(); i++) {
            primitiveArray[i] = array.get(i).byteValue();
        }
        return primitiveArray;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int[] createPrimitiveIntArray(Collection<Integer> collection) {
        int[] primitiveArray = new int[collection.size()];
        int i = 0;
        for (Integer num : collection) {
            int contextHubId = num.intValue();
            primitiveArray[i] = contextHubId;
            i++;
        }
        return primitiveArray;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static NanoAppBinary createHidlNanoAppBinary(android.hardware.location.NanoAppBinary nanoAppBinary) {
        NanoAppBinary hidlNanoAppBinary = new NanoAppBinary();
        hidlNanoAppBinary.appId = nanoAppBinary.getNanoAppId();
        hidlNanoAppBinary.appVersion = nanoAppBinary.getNanoAppVersion();
        hidlNanoAppBinary.flags = nanoAppBinary.getFlags();
        hidlNanoAppBinary.targetChreApiMajorVersion = nanoAppBinary.getTargetChreApiMajorVersion();
        hidlNanoAppBinary.targetChreApiMinorVersion = nanoAppBinary.getTargetChreApiMinorVersion();
        try {
            copyToByteArrayList(nanoAppBinary.getBinaryNoHeader(), hidlNanoAppBinary.customBinary);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, e.getMessage());
        } catch (NullPointerException e2) {
            Log.w(TAG, "NanoApp binary was null");
        }
        return hidlNanoAppBinary;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static List<NanoAppState> createNanoAppStateList(List<HubAppInfo> nanoAppInfoList) {
        ArrayList<NanoAppState> nanoAppStateList = new ArrayList<>();
        for (HubAppInfo appInfo : nanoAppInfoList) {
            nanoAppStateList.add(new NanoAppState(appInfo.appId, appInfo.version, appInfo.enabled));
        }
        return nanoAppStateList;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static ContextHubMsg createHidlContextHubMessage(short hostEndPoint, NanoAppMessage message) {
        ContextHubMsg hidlMessage = new ContextHubMsg();
        hidlMessage.appName = message.getNanoAppId();
        hidlMessage.hostEndPoint = hostEndPoint;
        hidlMessage.msgType = message.getMessageType();
        copyToByteArrayList(message.getMessageBody(), hidlMessage.msg);
        return hidlMessage;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static NanoAppMessage createNanoAppMessage(ContextHubMsg message) {
        byte[] messageArray = createPrimitiveByteArray(message.msg);
        return NanoAppMessage.createMessageFromNanoApp(message.appName, message.msgType, messageArray, message.hostEndPoint == -1);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static void checkPermissions(Context context) {
        context.enforceCallingPermission(HARDWARE_PERMISSION, ENFORCE_HW_PERMISSION_MESSAGE);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static int toTransactionResult(int halResult) {
        if (halResult != 0) {
            if (halResult != 5) {
                switch (halResult) {
                    case 2:
                        return 2;
                    case 3:
                        return 3;
                    default:
                        return 1;
                }
            }
            return 4;
        }
        return 0;
    }
}
