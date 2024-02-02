package com.android.server.location;

import android.net.TrafficStats;
import android.text.TextUtils;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
/* loaded from: classes.dex */
public class GpsXtraDownloader {
    private static final String DEFAULT_USER_AGENT = "Android";
    private static final long MAXIMUM_CONTENT_LENGTH_BYTES = 1000000;
    private int mNextServerIndex;
    private final String mUserAgent;
    private final String[] mXtraServers;
    private static final String TAG = "GpsXtraDownloader";
    private static final boolean DEBUG = Log.isLoggable(TAG, 3);
    private static final int CONNECTION_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(30);
    private static final int READ_TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(60);

    /* JADX INFO: Access modifiers changed from: package-private */
    public GpsXtraDownloader(Properties properties) {
        String server1 = properties.getProperty("XTRA_SERVER_1");
        String server2 = properties.getProperty("XTRA_SERVER_2");
        String server3 = properties.getProperty("XTRA_SERVER_3");
        int count = server1 != null ? 0 + 1 : 0;
        count = server2 != null ? count + 1 : count;
        count = server3 != null ? count + 1 : count;
        String agent = properties.getProperty("XTRA_USER_AGENT");
        if (TextUtils.isEmpty(agent)) {
            this.mUserAgent = DEFAULT_USER_AGENT;
        } else {
            this.mUserAgent = agent;
        }
        if (count == 0) {
            Log.e(TAG, "No XTRA servers were specified in the GPS configuration");
            this.mXtraServers = null;
            return;
        }
        this.mXtraServers = new String[count];
        int count2 = 0;
        if (server1 != null) {
            this.mXtraServers[0] = server1;
            count2 = 0 + 1;
        }
        if (server2 != null) {
            this.mXtraServers[count2] = server2;
            count2++;
        }
        if (server3 != null) {
            this.mXtraServers[count2] = server3;
            count2++;
        }
        Random random = new Random();
        this.mNextServerIndex = random.nextInt(count2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* JADX WARN: Finally extract failed */
    public byte[] downloadXtraData() {
        byte[] result = null;
        int startIndex = this.mNextServerIndex;
        if (this.mXtraServers == null) {
            return null;
        }
        while (result == null) {
            int oldTag = TrafficStats.getAndSetThreadStatsTag(-188);
            try {
                result = doDownload(this.mXtraServers[this.mNextServerIndex]);
                TrafficStats.setThreadStatsTag(oldTag);
                this.mNextServerIndex++;
                if (this.mNextServerIndex == this.mXtraServers.length) {
                    this.mNextServerIndex = 0;
                }
                if (this.mNextServerIndex == startIndex) {
                    break;
                }
            } catch (Throwable th) {
                TrafficStats.setThreadStatsTag(oldTag);
                throw th;
            }
        }
        return result;
    }

    protected byte[] doDownload(String url) {
        if (DEBUG) {
            Log.d(TAG, "Downloading XTRA data from " + url);
        }
        HttpURLConnection connection = null;
        try {
            try {
                HttpURLConnection connection2 = (HttpURLConnection) new URL(url).openConnection();
                connection2.setRequestProperty("Accept", "*/*, application/vnd.wap.mms-message, application/vnd.wap.sic");
                connection2.setRequestProperty("x-wap-profile", "http://www.openmobilealliance.org/tech/profiles/UAPROF/ccppschema-20021212#");
                connection2.setConnectTimeout(CONNECTION_TIMEOUT_MS);
                connection2.setReadTimeout(READ_TIMEOUT_MS);
                connection2.connect();
                int statusCode = connection2.getResponseCode();
                if (statusCode != 200) {
                    if (DEBUG) {
                        Log.d(TAG, "HTTP error downloading gps XTRA: " + statusCode);
                    }
                    if (connection2 != null) {
                        connection2.disconnect();
                    }
                    return null;
                }
                InputStream in = connection2.getInputStream();
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    do {
                        int count = in.read(buffer);
                        if (count == -1) {
                            byte[] byteArray = bytes.toByteArray();
                            if (in != null) {
                                in.close();
                            }
                            if (connection2 != null) {
                                connection2.disconnect();
                            }
                            return byteArray;
                        }
                        bytes.write(buffer, 0, count);
                    } while (bytes.size() <= MAXIMUM_CONTENT_LENGTH_BYTES);
                    if (DEBUG) {
                        Log.d(TAG, "XTRA file too large");
                    }
                    if (in != null) {
                        in.close();
                    }
                    if (connection2 != null) {
                        connection2.disconnect();
                    }
                    return null;
                } catch (Throwable th) {
                    try {
                        throw th;
                    } catch (Throwable th2) {
                        if (in != null) {
                            if (th != null) {
                                try {
                                    in.close();
                                } catch (Throwable th3) {
                                    th.addSuppressed(th3);
                                }
                            } else {
                                in.close();
                            }
                        }
                        throw th2;
                    }
                }
            } catch (IOException ioe) {
                if (DEBUG) {
                    Log.d(TAG, "Error downloading gps XTRA: ", ioe);
                }
                if (0 != 0) {
                    connection.disconnect();
                }
                return null;
            }
        } catch (Throwable th4) {
            if (0 != 0) {
                connection.disconnect();
            }
            throw th4;
        }
    }
}
