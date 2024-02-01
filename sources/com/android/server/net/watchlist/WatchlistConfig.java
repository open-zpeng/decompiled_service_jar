package com.android.server.net.watchlist;

import android.os.FileUtils;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.zip.CRC32;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
/* loaded from: classes.dex */
class WatchlistConfig {
    private static final String NETWORK_WATCHLIST_DB_FOR_TEST_PATH = "/data/misc/network_watchlist/network_watchlist_for_test.xml";
    private static final String NETWORK_WATCHLIST_DB_PATH = "/data/misc/network_watchlist/network_watchlist.xml";
    private static final String TAG = "WatchlistConfig";
    private static final WatchlistConfig sInstance = new WatchlistConfig();
    private volatile CrcShaDigests mDomainDigests;
    private volatile CrcShaDigests mIpDigests;
    private boolean mIsSecureConfig;
    private File mXmlFile;

    /* loaded from: classes.dex */
    private static class XmlTags {
        private static final String CRC32_DOMAIN = "crc32-domain";
        private static final String CRC32_IP = "crc32-ip";
        private static final String HASH = "hash";
        private static final String SHA256_DOMAIN = "sha256-domain";
        private static final String SHA256_IP = "sha256-ip";
        private static final String WATCHLIST_CONFIG = "watchlist-config";

        private XmlTags() {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static class CrcShaDigests {
        final HarmfulDigests crc32Digests;
        final HarmfulDigests sha256Digests;

        public CrcShaDigests(HarmfulDigests crc32Digests, HarmfulDigests sha256Digests) {
            this.crc32Digests = crc32Digests;
            this.sha256Digests = sha256Digests;
        }
    }

    public static WatchlistConfig getInstance() {
        return sInstance;
    }

    private WatchlistConfig() {
        this(new File(NETWORK_WATCHLIST_DB_PATH));
    }

    @VisibleForTesting
    protected WatchlistConfig(File xmlFile) {
        this.mIsSecureConfig = true;
        this.mXmlFile = xmlFile;
        reloadConfig();
    }

    /* JADX WARN: Removed duplicated region for block: B:33:0x008e  */
    /* JADX WARN: Removed duplicated region for block: B:34:0x0091 A[Catch: Throwable -> 0x00f2, TryCatch #3 {IOException | IllegalStateException | IndexOutOfBoundsException | NullPointerException | NumberFormatException | XmlPullParserException -> 0x0103, blocks: (B:5:0x0009, B:41:0x00ec, B:7:0x0011, B:8:0x003c, B:10:0x0043, B:32:0x008b, B:38:0x00a1, B:34:0x0091, B:35:0x0095, B:36:0x0099, B:37:0x009d, B:19:0x0061, B:22:0x006b, B:25:0x0075, B:28:0x0080, B:40:0x00bd), top: B:57:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:35:0x0095 A[Catch: Throwable -> 0x00f2, TryCatch #3 {IOException | IllegalStateException | IndexOutOfBoundsException | NullPointerException | NumberFormatException | XmlPullParserException -> 0x0103, blocks: (B:5:0x0009, B:41:0x00ec, B:7:0x0011, B:8:0x003c, B:10:0x0043, B:32:0x008b, B:38:0x00a1, B:34:0x0091, B:35:0x0095, B:36:0x0099, B:37:0x009d, B:19:0x0061, B:22:0x006b, B:25:0x0075, B:28:0x0080, B:40:0x00bd), top: B:57:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:36:0x0099 A[Catch: Throwable -> 0x00f2, TryCatch #3 {IOException | IllegalStateException | IndexOutOfBoundsException | NullPointerException | NumberFormatException | XmlPullParserException -> 0x0103, blocks: (B:5:0x0009, B:41:0x00ec, B:7:0x0011, B:8:0x003c, B:10:0x0043, B:32:0x008b, B:38:0x00a1, B:34:0x0091, B:35:0x0095, B:36:0x0099, B:37:0x009d, B:19:0x0061, B:22:0x006b, B:25:0x0075, B:28:0x0080, B:40:0x00bd), top: B:57:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:37:0x009d A[Catch: Throwable -> 0x00f2, TryCatch #3 {IOException | IllegalStateException | IndexOutOfBoundsException | NullPointerException | NumberFormatException | XmlPullParserException -> 0x0103, blocks: (B:5:0x0009, B:41:0x00ec, B:7:0x0011, B:8:0x003c, B:10:0x0043, B:32:0x008b, B:38:0x00a1, B:34:0x0091, B:35:0x0095, B:36:0x0099, B:37:0x009d, B:19:0x0061, B:22:0x006b, B:25:0x0075, B:28:0x0080, B:40:0x00bd), top: B:57:0x0009 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void reloadConfig() {
        /*
            Method dump skipped, instructions count: 280
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.net.watchlist.WatchlistConfig.reloadConfig():void");
    }

    private void parseHashes(XmlPullParser parser, String tagName, List<byte[]> hashList) throws IOException, XmlPullParserException {
        parser.require(2, null, tagName);
        while (parser.nextTag() == 2) {
            parser.require(2, null, "hash");
            byte[] hash = HexDump.hexStringToByteArray(parser.nextText());
            parser.require(3, null, "hash");
            hashList.add(hash);
        }
        parser.require(3, null, tagName);
    }

    public boolean containsDomain(String domain) {
        CrcShaDigests domainDigests = this.mDomainDigests;
        if (domainDigests == null) {
            return false;
        }
        byte[] crc32 = getCrc32(domain);
        if (!domainDigests.crc32Digests.contains(crc32)) {
            return false;
        }
        byte[] sha256 = getSha256(domain);
        return domainDigests.sha256Digests.contains(sha256);
    }

    public boolean containsIp(String ip) {
        CrcShaDigests ipDigests = this.mIpDigests;
        if (ipDigests == null) {
            return false;
        }
        byte[] crc32 = getCrc32(ip);
        if (!ipDigests.crc32Digests.contains(crc32)) {
            return false;
        }
        byte[] sha256 = getSha256(ip);
        return ipDigests.sha256Digests.contains(sha256);
    }

    private byte[] getCrc32(String str) {
        CRC32 crc = new CRC32();
        crc.update(str.getBytes());
        long tmp = crc.getValue();
        return new byte[]{(byte) ((tmp >> 24) & 255), (byte) ((tmp >> 16) & 255), (byte) ((tmp >> 8) & 255), (byte) (tmp & 255)};
    }

    private byte[] getSha256(String str) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA256");
            messageDigest.update(str.getBytes());
            return messageDigest.digest();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    public boolean isConfigSecure() {
        return this.mIsSecureConfig;
    }

    public byte[] getWatchlistConfigHash() {
        if (this.mXmlFile.exists()) {
            try {
                return DigestUtils.getSha256Hash(this.mXmlFile);
            } catch (IOException | NoSuchAlgorithmException e) {
                Log.e(TAG, "Unable to get watchlist config hash", e);
                return null;
            }
        }
        return null;
    }

    public void setTestMode(InputStream testConfigInputStream) throws IOException {
        Log.i(TAG, "Setting watchlist testing config");
        FileUtils.copyToFileOrThrow(testConfigInputStream, new File(NETWORK_WATCHLIST_DB_FOR_TEST_PATH));
        this.mIsSecureConfig = false;
        this.mXmlFile = new File(NETWORK_WATCHLIST_DB_FOR_TEST_PATH);
        reloadConfig();
    }

    public void removeTestModeConfig() {
        try {
            File f = new File(NETWORK_WATCHLIST_DB_FOR_TEST_PATH);
            if (f.exists()) {
                f.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to delete test config");
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        byte[] hash = getWatchlistConfigHash();
        StringBuilder sb = new StringBuilder();
        sb.append("Watchlist config hash: ");
        sb.append(hash != null ? HexDump.toHexString(hash) : null);
        pw.println(sb.toString());
        pw.println("Domain CRC32 digest list:");
        if (this.mDomainDigests != null) {
            this.mDomainDigests.crc32Digests.dump(fd, pw, args);
        }
        pw.println("Domain SHA256 digest list:");
        if (this.mDomainDigests != null) {
            this.mDomainDigests.sha256Digests.dump(fd, pw, args);
        }
        pw.println("Ip CRC32 digest list:");
        if (this.mIpDigests != null) {
            this.mIpDigests.crc32Digests.dump(fd, pw, args);
        }
        pw.println("Ip SHA256 digest list:");
        if (this.mIpDigests != null) {
            this.mIpDigests.sha256Digests.dump(fd, pw, args);
        }
    }
}
