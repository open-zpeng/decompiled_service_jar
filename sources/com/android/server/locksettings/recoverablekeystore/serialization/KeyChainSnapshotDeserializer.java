package com.android.server.locksettings.recoverablekeystore.serialization;

import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.KeyDerivationParams;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.util.Base64;
import com.android.server.backup.BackupManagerConstants;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertPath;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
public class KeyChainSnapshotDeserializer {
    public static KeyChainSnapshot deserialize(InputStream inputStream) throws KeyChainSnapshotParserException, IOException {
        try {
            return deserializeInternal(inputStream);
        } catch (XmlPullParserException e) {
            throw new KeyChainSnapshotParserException("Malformed KeyChainSnapshot XML", e);
        }
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code restructure failed: missing block: B:12:0x003d, code lost:
        if (r2.equals("serverParams") != false) goto L11;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static android.security.keystore.recovery.KeyChainSnapshot deserializeInternal(java.io.InputStream r9) throws java.io.IOException, org.xmlpull.v1.XmlPullParserException, com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotParserException {
        /*
            Method dump skipped, instructions count: 346
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.locksettings.recoverablekeystore.serialization.KeyChainSnapshotDeserializer.deserializeInternal(java.io.InputStream):android.security.keystore.recovery.KeyChainSnapshot");
    }

    private static List<WrappedApplicationKey> readWrappedApplicationKeys(XmlPullParser parser) throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(2, KeyChainSnapshotSchema.NAMESPACE, "applicationKeysList");
        ArrayList<WrappedApplicationKey> keys = new ArrayList<>();
        while (parser.next() != 3) {
            if (parser.getEventType() == 2) {
                keys.add(readWrappedApplicationKey(parser));
            }
        }
        parser.require(3, KeyChainSnapshotSchema.NAMESPACE, "applicationKeysList");
        return keys;
    }

    private static WrappedApplicationKey readWrappedApplicationKey(XmlPullParser parser) throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(2, KeyChainSnapshotSchema.NAMESPACE, "applicationKey");
        WrappedApplicationKey.Builder builder = new WrappedApplicationKey.Builder();
        while (parser.next() != 3) {
            if (parser.getEventType() == 2) {
                String name = parser.getName();
                char c = 65535;
                int hashCode = name.hashCode();
                if (hashCode != -963209050) {
                    if (hashCode == 92902992 && name.equals("alias")) {
                        c = 0;
                    }
                } else if (name.equals("keyMaterial")) {
                    c = 1;
                }
                switch (c) {
                    case 0:
                        builder.setAlias(readStringTag(parser, "alias"));
                        continue;
                    case 1:
                        builder.setEncryptedKeyMaterial(readBlobTag(parser, "keyMaterial"));
                        continue;
                    default:
                        throw new KeyChainSnapshotParserException(String.format(Locale.US, "Unexpected tag %s in wrappedApplicationKey", name));
                }
            }
        }
        parser.require(3, KeyChainSnapshotSchema.NAMESPACE, "applicationKey");
        try {
            return builder.build();
        } catch (NullPointerException e) {
            throw new KeyChainSnapshotParserException("Failed to build WrappedApplicationKey", e);
        }
    }

    private static List<KeyChainProtectionParams> readKeyChainProtectionParamsList(XmlPullParser parser) throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(2, KeyChainSnapshotSchema.NAMESPACE, "keyChainProtectionParamsList");
        ArrayList<KeyChainProtectionParams> keyChainProtectionParamsList = new ArrayList<>();
        while (parser.next() != 3) {
            if (parser.getEventType() == 2) {
                keyChainProtectionParamsList.add(readKeyChainProtectionParams(parser));
            }
        }
        parser.require(3, KeyChainSnapshotSchema.NAMESPACE, "keyChainProtectionParamsList");
        return keyChainProtectionParamsList;
    }

    private static KeyChainProtectionParams readKeyChainProtectionParams(XmlPullParser parser) throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(2, KeyChainSnapshotSchema.NAMESPACE, "keyChainProtectionParams");
        KeyChainProtectionParams.Builder builder = new KeyChainProtectionParams.Builder();
        while (parser.next() != 3) {
            if (parser.getEventType() == 2) {
                String name = parser.getName();
                char c = 65535;
                int hashCode = name.hashCode();
                if (hashCode != -776797115) {
                    if (hashCode != -696958923) {
                        if (hashCode == 912448924 && name.equals("keyDerivationParams")) {
                            c = 2;
                        }
                    } else if (name.equals("userSecretType")) {
                        c = 1;
                    }
                } else if (name.equals("lockScreenUiType")) {
                    c = 0;
                }
                switch (c) {
                    case 0:
                        builder.setLockScreenUiFormat(readIntTag(parser, "lockScreenUiType"));
                        continue;
                    case 1:
                        builder.setUserSecretType(readIntTag(parser, "userSecretType"));
                        continue;
                    case 2:
                        builder.setKeyDerivationParams(readKeyDerivationParams(parser));
                        continue;
                    default:
                        throw new KeyChainSnapshotParserException(String.format(Locale.US, "Unexpected tag %s in keyChainProtectionParams", name));
                }
            }
        }
        parser.require(3, KeyChainSnapshotSchema.NAMESPACE, "keyChainProtectionParams");
        try {
            return builder.build();
        } catch (NullPointerException e) {
            throw new KeyChainSnapshotParserException("Failed to build KeyChainProtectionParams", e);
        }
    }

    private static KeyDerivationParams readKeyDerivationParams(XmlPullParser parser) throws XmlPullParserException, IOException, KeyChainSnapshotParserException {
        KeyDerivationParams keyDerivationParams;
        parser.require(2, KeyChainSnapshotSchema.NAMESPACE, "keyDerivationParams");
        int memoryDifficulty = -1;
        int algorithm = -1;
        byte[] salt = null;
        while (parser.next() != 3) {
            if (parser.getEventType() == 2) {
                String name = parser.getName();
                char c = 65535;
                int hashCode = name.hashCode();
                if (hashCode != -973274212) {
                    if (hashCode != 3522646) {
                        if (hashCode == 225490031 && name.equals("algorithm")) {
                            c = 1;
                        }
                    } else if (name.equals("salt")) {
                        c = 2;
                    }
                } else if (name.equals("memoryDifficulty")) {
                    c = 0;
                }
                switch (c) {
                    case 0:
                        memoryDifficulty = readIntTag(parser, "memoryDifficulty");
                        continue;
                    case 1:
                        algorithm = readIntTag(parser, "algorithm");
                        continue;
                    case 2:
                        salt = readBlobTag(parser, "salt");
                        continue;
                    default:
                        throw new KeyChainSnapshotParserException(String.format(Locale.US, "Unexpected tag %s in keyDerivationParams", name));
                }
            }
        }
        if (salt == null) {
            throw new KeyChainSnapshotParserException("salt was not set in keyDerivationParams");
        }
        switch (algorithm) {
            case 1:
                keyDerivationParams = KeyDerivationParams.createSha256Params(salt);
                break;
            case 2:
                keyDerivationParams = KeyDerivationParams.createScryptParams(salt, memoryDifficulty);
                break;
            default:
                throw new KeyChainSnapshotParserException("Unknown algorithm in keyDerivationParams");
        }
        parser.require(3, KeyChainSnapshotSchema.NAMESPACE, "keyDerivationParams");
        return keyDerivationParams;
    }

    private static int readIntTag(XmlPullParser parser, String tagName) throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(2, KeyChainSnapshotSchema.NAMESPACE, tagName);
        String text = readText(parser);
        parser.require(3, KeyChainSnapshotSchema.NAMESPACE, tagName);
        try {
            return Integer.valueOf(text).intValue();
        } catch (NumberFormatException e) {
            throw new KeyChainSnapshotParserException(String.format(Locale.US, "%s expected int but got '%s'", tagName, text), e);
        }
    }

    private static long readLongTag(XmlPullParser parser, String tagName) throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(2, KeyChainSnapshotSchema.NAMESPACE, tagName);
        String text = readText(parser);
        parser.require(3, KeyChainSnapshotSchema.NAMESPACE, tagName);
        try {
            return Long.valueOf(text).longValue();
        } catch (NumberFormatException e) {
            throw new KeyChainSnapshotParserException(String.format(Locale.US, "%s expected long but got '%s'", tagName, text), e);
        }
    }

    private static String readStringTag(XmlPullParser parser, String tagName) throws IOException, XmlPullParserException {
        parser.require(2, KeyChainSnapshotSchema.NAMESPACE, tagName);
        String text = readText(parser);
        parser.require(3, KeyChainSnapshotSchema.NAMESPACE, tagName);
        return text;
    }

    private static byte[] readBlobTag(XmlPullParser parser, String tagName) throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        parser.require(2, KeyChainSnapshotSchema.NAMESPACE, tagName);
        String text = readText(parser);
        parser.require(3, KeyChainSnapshotSchema.NAMESPACE, tagName);
        try {
            return Base64.decode(text, 0);
        } catch (IllegalArgumentException e) {
            throw new KeyChainSnapshotParserException(String.format(Locale.US, "%s expected base64 encoded bytes but got '%s'", tagName, text), e);
        }
    }

    private static CertPath readCertPathTag(XmlPullParser parser, String tagName) throws IOException, XmlPullParserException, KeyChainSnapshotParserException {
        byte[] bytes = readBlobTag(parser, tagName);
        try {
            return CertificateFactory.getInstance("X.509").generateCertPath(new ByteArrayInputStream(bytes));
        } catch (CertificateException e) {
            throw new KeyChainSnapshotParserException("Could not parse CertPath in tag " + tagName, e);
        }
    }

    private static String readText(XmlPullParser parser) throws IOException, XmlPullParserException {
        if (parser.next() != 4) {
            return BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        }
        String result = parser.getText();
        parser.nextTag();
        return result;
    }

    private KeyChainSnapshotDeserializer() {
    }
}
