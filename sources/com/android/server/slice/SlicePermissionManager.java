package com.android.server.slice;

import android.content.ContentProvider;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;
import com.android.server.slice.DirtyTracker;
import com.android.server.slice.SliceProviderPermissions;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Objects;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

/* loaded from: classes.dex */
public class SlicePermissionManager implements DirtyTracker {
    static final int DB_VERSION = 2;
    private static final long PERMISSION_CACHE_PERIOD = 300000;
    private static final String SLICE_DIR = "slice";
    private static final String TAG = "SlicePermissionManager";
    private static final String TAG_LIST = "slice-access-list";
    private static final long WRITE_GRACE_PERIOD = 500;
    private final String ATT_VERSION;
    private final ArrayMap<PkgUser, SliceClientPermissions> mCachedClients;
    private final ArrayMap<PkgUser, SliceProviderPermissions> mCachedProviders;
    private final Context mContext;
    private final ArraySet<DirtyTracker.Persistable> mDirty;
    private final Handler mHandler;
    private final File mSliceDir;

    @VisibleForTesting
    SlicePermissionManager(Context context, Looper looper, File sliceDir) {
        this.ATT_VERSION = "version";
        this.mCachedProviders = new ArrayMap<>();
        this.mCachedClients = new ArrayMap<>();
        this.mDirty = new ArraySet<>();
        this.mContext = context;
        this.mHandler = new H(looper);
        this.mSliceDir = sliceDir;
    }

    public SlicePermissionManager(Context context, Looper looper) {
        this(context, looper, new File(Environment.getDataDirectory(), "system/slice"));
    }

    public void grantFullAccess(String pkg, int userId) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        SliceClientPermissions client = getClient(pkgUser);
        client.setHasFullAccess(true);
    }

    public void grantSliceAccess(String pkg, int userId, String providerPkg, int providerUser, Uri uri) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        PkgUser providerPkgUser = new PkgUser(providerPkg, providerUser);
        SliceClientPermissions client = getClient(pkgUser);
        client.grantUri(uri, providerPkgUser);
        SliceProviderPermissions provider = getProvider(providerPkgUser);
        provider.getOrCreateAuthority(ContentProvider.getUriWithoutUserId(uri).getAuthority()).addPkg(pkgUser);
    }

    public void revokeSliceAccess(String pkg, int userId, String providerPkg, int providerUser, Uri uri) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        PkgUser providerPkgUser = new PkgUser(providerPkg, providerUser);
        SliceClientPermissions client = getClient(pkgUser);
        client.revokeUri(uri, providerPkgUser);
    }

    public void removePkg(String pkg, int userId) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        SliceProviderPermissions provider = getProvider(pkgUser);
        for (SliceProviderPermissions.SliceAuthority authority : provider.getAuthorities()) {
            for (PkgUser p : authority.getPkgs()) {
                getClient(p).removeAuthority(authority.getAuthority(), userId);
            }
        }
        SliceClientPermissions client = getClient(pkgUser);
        client.clear();
        this.mHandler.obtainMessage(3, pkgUser);
    }

    public String[] getAllPackagesGranted(String pkg) {
        ArraySet<String> ret = new ArraySet<>();
        for (SliceProviderPermissions.SliceAuthority authority : getProvider(new PkgUser(pkg, 0)).getAuthorities()) {
            for (PkgUser pkgUser : authority.getPkgs()) {
                ret.add(pkgUser.mPkg);
            }
        }
        return (String[]) ret.toArray(new String[ret.size()]);
    }

    public boolean hasFullAccess(String pkg, int userId) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        return getClient(pkgUser).hasFullAccess();
    }

    public boolean hasPermission(String pkg, int userId, Uri uri) {
        PkgUser pkgUser = new PkgUser(pkg, userId);
        SliceClientPermissions client = getClient(pkgUser);
        int providerUserId = ContentProvider.getUserIdFromUri(uri, userId);
        return client.hasFullAccess() || client.hasPermission(ContentProvider.getUriWithoutUserId(uri), providerUserId);
    }

    @Override // com.android.server.slice.DirtyTracker
    public void onPersistableDirty(DirtyTracker.Persistable obj) {
        this.mHandler.removeMessages(2);
        this.mHandler.obtainMessage(1, obj).sendToTarget();
        this.mHandler.sendEmptyMessageDelayed(2, 500L);
    }

    public void writeBackup(XmlSerializer out) throws IOException, XmlPullParserException {
        String[] list;
        synchronized (this) {
            out.startTag(null, TAG_LIST);
            out.attribute(null, "version", String.valueOf(2));
            DirtyTracker tracker = new DirtyTracker() { // from class: com.android.server.slice.-$$Lambda$SlicePermissionManager$y3Tun5dTftw8s8sky62syeWR34U
                @Override // com.android.server.slice.DirtyTracker
                public final void onPersistableDirty(DirtyTracker.Persistable persistable) {
                    SlicePermissionManager.lambda$writeBackup$0(persistable);
                }
            };
            if (this.mHandler.hasMessages(2)) {
                this.mHandler.removeMessages(2);
                handlePersist();
            }
            for (String file : new File(this.mSliceDir.getAbsolutePath()).list()) {
                ParserHolder parser = getParser(file);
                DirtyTracker.Persistable p = null;
                while (true) {
                    if (parser.parser.getEventType() == 1) {
                        break;
                    } else if (parser.parser.getEventType() == 2) {
                        p = "client".equals(parser.parser.getName()) ? SliceClientPermissions.createFrom(parser.parser, tracker) : SliceProviderPermissions.createFrom(parser.parser, tracker);
                    } else {
                        parser.parser.next();
                    }
                }
                if (p != null) {
                    p.writeTo(out);
                } else {
                    Slog.w(TAG, "Invalid or empty slice permissions file: " + file);
                }
                if (parser != null) {
                    $closeResource(null, parser);
                }
            }
            out.endTag(null, TAG_LIST);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$writeBackup$0(DirtyTracker.Persistable obj) {
    }

    private static /* synthetic */ void $closeResource(Throwable x0, AutoCloseable x1) {
        if (x0 == null) {
            x1.close();
            return;
        }
        try {
            x1.close();
        } catch (Throwable th) {
            x0.addSuppressed(th);
        }
    }

    public void readRestore(XmlPullParser parser) throws IOException, XmlPullParserException {
        synchronized (this) {
            while (true) {
                if ((parser.getEventType() != 2 || !TAG_LIST.equals(parser.getName())) && parser.getEventType() != 1) {
                    parser.next();
                }
            }
            int xmlVersion = XmlUtils.readIntAttribute(parser, "version", 0);
            if (xmlVersion < 2) {
                return;
            }
            while (parser.getEventType() != 1) {
                if (parser.getEventType() == 2) {
                    if ("client".equals(parser.getName())) {
                        SliceClientPermissions client = SliceClientPermissions.createFrom(parser, this);
                        synchronized (this.mCachedClients) {
                            this.mCachedClients.put(client.getPkg(), client);
                        }
                        onPersistableDirty(client);
                        this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(4, client.getPkg()), 300000L);
                    } else if ("provider".equals(parser.getName())) {
                        SliceProviderPermissions provider = SliceProviderPermissions.createFrom(parser, this);
                        synchronized (this.mCachedProviders) {
                            this.mCachedProviders.put(provider.getPkg(), provider);
                        }
                        onPersistableDirty(provider);
                        this.mHandler.sendMessageDelayed(this.mHandler.obtainMessage(5, provider.getPkg()), 300000L);
                    } else {
                        parser.next();
                    }
                } else {
                    parser.next();
                }
            }
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:49:0x0067 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private com.android.server.slice.SliceClientPermissions getClient(com.android.server.slice.SlicePermissionManager.PkgUser r8) {
        /*
            r7 = this;
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceClientPermissions> r0 = r7.mCachedClients
            monitor-enter(r0)
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceClientPermissions> r1 = r7.mCachedClients     // Catch: java.lang.Throwable -> L73
            java.lang.Object r1 = r1.get(r8)     // Catch: java.lang.Throwable -> L73
            com.android.server.slice.SliceClientPermissions r1 = (com.android.server.slice.SliceClientPermissions) r1     // Catch: java.lang.Throwable -> L73
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L73
            if (r1 != 0) goto L71
            java.lang.String r0 = com.android.server.slice.SliceClientPermissions.getFileName(r8)     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
            com.android.server.slice.SlicePermissionManager$ParserHolder r0 = r7.getParser(r0)     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
            r2 = 0
            org.xmlpull.v1.XmlPullParser r3 = com.android.server.slice.SlicePermissionManager.ParserHolder.access$100(r0)     // Catch: java.lang.Throwable -> L42
            com.android.server.slice.SliceClientPermissions r3 = com.android.server.slice.SliceClientPermissions.createFrom(r3, r7)     // Catch: java.lang.Throwable -> L42
            r1 = r3
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceClientPermissions> r3 = r7.mCachedClients     // Catch: java.lang.Throwable -> L42
            monitor-enter(r3)     // Catch: java.lang.Throwable -> L42
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceClientPermissions> r4 = r7.mCachedClients     // Catch: java.lang.Throwable -> L3f
            r4.put(r8, r1)     // Catch: java.lang.Throwable -> L3f
            monitor-exit(r3)     // Catch: java.lang.Throwable -> L3f
            android.os.Handler r3 = r7.mHandler     // Catch: java.lang.Throwable -> L42
            android.os.Handler r4 = r7.mHandler     // Catch: java.lang.Throwable -> L42
            r5 = 4
            android.os.Message r4 = r4.obtainMessage(r5, r8)     // Catch: java.lang.Throwable -> L42
            r5 = 300000(0x493e0, double:1.482197E-318)
            r3.sendMessageDelayed(r4, r5)     // Catch: java.lang.Throwable -> L42
            if (r0 == 0) goto L3e
            $closeResource(r2, r0)     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
        L3e:
            return r1
        L3f:
            r2 = move-exception
            monitor-exit(r3)     // Catch: java.lang.Throwable -> L3f
            throw r2     // Catch: java.lang.Throwable -> L42
        L42:
            r2 = move-exception
            throw r2     // Catch: java.lang.Throwable -> L44
        L44:
            r3 = move-exception
            if (r0 == 0) goto L4a
            $closeResource(r2, r0)     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
        L4a:
            throw r3     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
        L4b:
            r0 = move-exception
            java.lang.String r2 = "SlicePermissionManager"
            java.lang.String r3 = "Can't read client"
            android.util.Log.e(r2, r3, r0)
            goto L5f
        L54:
            r0 = move-exception
            java.lang.String r2 = "SlicePermissionManager"
            java.lang.String r3 = "Can't read client"
            android.util.Log.e(r2, r3, r0)
            goto L5e
        L5d:
            r0 = move-exception
        L5e:
        L5f:
            com.android.server.slice.SliceClientPermissions r0 = new com.android.server.slice.SliceClientPermissions
            r0.<init>(r8, r7)
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceClientPermissions> r2 = r7.mCachedClients
            monitor-enter(r2)
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceClientPermissions> r1 = r7.mCachedClients     // Catch: java.lang.Throwable -> L6e
            r1.put(r8, r0)     // Catch: java.lang.Throwable -> L6e
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L6e
            goto L72
        L6e:
            r1 = move-exception
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L6e
            throw r1
        L71:
            r0 = r1
        L72:
            return r0
        L73:
            r1 = move-exception
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L73
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.slice.SlicePermissionManager.getClient(com.android.server.slice.SlicePermissionManager$PkgUser):com.android.server.slice.SliceClientPermissions");
    }

    /* JADX WARN: Removed duplicated region for block: B:49:0x0067 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    private com.android.server.slice.SliceProviderPermissions getProvider(com.android.server.slice.SlicePermissionManager.PkgUser r8) {
        /*
            r7 = this;
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceProviderPermissions> r0 = r7.mCachedProviders
            monitor-enter(r0)
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceProviderPermissions> r1 = r7.mCachedProviders     // Catch: java.lang.Throwable -> L73
            java.lang.Object r1 = r1.get(r8)     // Catch: java.lang.Throwable -> L73
            com.android.server.slice.SliceProviderPermissions r1 = (com.android.server.slice.SliceProviderPermissions) r1     // Catch: java.lang.Throwable -> L73
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L73
            if (r1 != 0) goto L71
            java.lang.String r0 = com.android.server.slice.SliceProviderPermissions.getFileName(r8)     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
            com.android.server.slice.SlicePermissionManager$ParserHolder r0 = r7.getParser(r0)     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
            r2 = 0
            org.xmlpull.v1.XmlPullParser r3 = com.android.server.slice.SlicePermissionManager.ParserHolder.access$100(r0)     // Catch: java.lang.Throwable -> L42
            com.android.server.slice.SliceProviderPermissions r3 = com.android.server.slice.SliceProviderPermissions.createFrom(r3, r7)     // Catch: java.lang.Throwable -> L42
            r1 = r3
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceProviderPermissions> r3 = r7.mCachedProviders     // Catch: java.lang.Throwable -> L42
            monitor-enter(r3)     // Catch: java.lang.Throwable -> L42
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceProviderPermissions> r4 = r7.mCachedProviders     // Catch: java.lang.Throwable -> L3f
            r4.put(r8, r1)     // Catch: java.lang.Throwable -> L3f
            monitor-exit(r3)     // Catch: java.lang.Throwable -> L3f
            android.os.Handler r3 = r7.mHandler     // Catch: java.lang.Throwable -> L42
            android.os.Handler r4 = r7.mHandler     // Catch: java.lang.Throwable -> L42
            r5 = 5
            android.os.Message r4 = r4.obtainMessage(r5, r8)     // Catch: java.lang.Throwable -> L42
            r5 = 300000(0x493e0, double:1.482197E-318)
            r3.sendMessageDelayed(r4, r5)     // Catch: java.lang.Throwable -> L42
            if (r0 == 0) goto L3e
            $closeResource(r2, r0)     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
        L3e:
            return r1
        L3f:
            r2 = move-exception
            monitor-exit(r3)     // Catch: java.lang.Throwable -> L3f
            throw r2     // Catch: java.lang.Throwable -> L42
        L42:
            r2 = move-exception
            throw r2     // Catch: java.lang.Throwable -> L44
        L44:
            r3 = move-exception
            if (r0 == 0) goto L4a
            $closeResource(r2, r0)     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
        L4a:
            throw r3     // Catch: org.xmlpull.v1.XmlPullParserException -> L4b java.io.IOException -> L54 java.io.FileNotFoundException -> L5d
        L4b:
            r0 = move-exception
            java.lang.String r2 = "SlicePermissionManager"
            java.lang.String r3 = "Can't read provider"
            android.util.Log.e(r2, r3, r0)
            goto L5f
        L54:
            r0 = move-exception
            java.lang.String r2 = "SlicePermissionManager"
            java.lang.String r3 = "Can't read provider"
            android.util.Log.e(r2, r3, r0)
            goto L5e
        L5d:
            r0 = move-exception
        L5e:
        L5f:
            com.android.server.slice.SliceProviderPermissions r0 = new com.android.server.slice.SliceProviderPermissions
            r0.<init>(r8, r7)
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceProviderPermissions> r2 = r7.mCachedProviders
            monitor-enter(r2)
            android.util.ArrayMap<com.android.server.slice.SlicePermissionManager$PkgUser, com.android.server.slice.SliceProviderPermissions> r1 = r7.mCachedProviders     // Catch: java.lang.Throwable -> L6e
            r1.put(r8, r0)     // Catch: java.lang.Throwable -> L6e
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L6e
            goto L72
        L6e:
            r1 = move-exception
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L6e
            throw r1
        L71:
            r0 = r1
        L72:
            return r0
        L73:
            r1 = move-exception
            monitor-exit(r0)     // Catch: java.lang.Throwable -> L73
            throw r1
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.slice.SlicePermissionManager.getProvider(com.android.server.slice.SlicePermissionManager$PkgUser):com.android.server.slice.SliceProviderPermissions");
    }

    private ParserHolder getParser(String fileName) throws FileNotFoundException, XmlPullParserException {
        AtomicFile file = getFile(fileName);
        ParserHolder holder = new ParserHolder();
        holder.input = file.openRead();
        holder.parser = XmlPullParserFactory.newInstance().newPullParser();
        holder.parser.setInput(holder.input, Xml.Encoding.UTF_8.name());
        return holder;
    }

    private AtomicFile getFile(String fileName) {
        if (!this.mSliceDir.exists()) {
            this.mSliceDir.mkdir();
        }
        return new AtomicFile(new File(this.mSliceDir, fileName));
    }

    @VisibleForTesting
    void handlePersist() {
        synchronized (this) {
            Iterator<DirtyTracker.Persistable> it = this.mDirty.iterator();
            while (it.hasNext()) {
                DirtyTracker.Persistable persistable = it.next();
                AtomicFile file = getFile(persistable.getFileName());
                try {
                    FileOutputStream stream = file.startWrite();
                    try {
                        XmlSerializer out = XmlPullParserFactory.newInstance().newSerializer();
                        out.setOutput(stream, Xml.Encoding.UTF_8.name());
                        persistable.writeTo(out);
                        out.flush();
                        file.finishWrite(stream);
                    } catch (IOException | RuntimeException | XmlPullParserException e) {
                        Slog.w(TAG, "Failed to save access file, restoring backup", e);
                        file.failWrite(stream);
                    }
                } catch (IOException e2) {
                    Slog.w(TAG, "Failed to save access file", e2);
                    return;
                }
            }
            this.mDirty.clear();
        }
    }

    @VisibleForTesting
    void addDirtyImmediate(DirtyTracker.Persistable obj) {
        this.mDirty.add(obj);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void handleRemove(PkgUser pkgUser) {
        getFile(SliceClientPermissions.getFileName(pkgUser)).delete();
        getFile(SliceProviderPermissions.getFileName(pkgUser)).delete();
        this.mDirty.remove(this.mCachedClients.remove(pkgUser));
        this.mDirty.remove(this.mCachedProviders.remove(pkgUser));
    }

    /* loaded from: classes.dex */
    private final class H extends Handler {
        private static final int MSG_ADD_DIRTY = 1;
        private static final int MSG_CLEAR_CLIENT = 4;
        private static final int MSG_CLEAR_PROVIDER = 5;
        private static final int MSG_PERSIST = 2;
        private static final int MSG_REMOVE = 3;

        public H(Looper looper) {
            super(looper);
        }

        @Override // android.os.Handler
        public void handleMessage(Message msg) {
            int i = msg.what;
            if (i == 1) {
                SlicePermissionManager.this.mDirty.add((DirtyTracker.Persistable) msg.obj);
            } else if (i == 2) {
                SlicePermissionManager.this.handlePersist();
            } else if (i == 3) {
                SlicePermissionManager.this.handleRemove((PkgUser) msg.obj);
            } else if (i == 4) {
                synchronized (SlicePermissionManager.this.mCachedClients) {
                    SlicePermissionManager.this.mCachedClients.remove(msg.obj);
                }
            } else if (i == 5) {
                synchronized (SlicePermissionManager.this.mCachedProviders) {
                    SlicePermissionManager.this.mCachedProviders.remove(msg.obj);
                }
            }
        }
    }

    /* loaded from: classes.dex */
    public static class PkgUser {
        private static final String FORMAT = "%s@%d";
        private static final String SEPARATOR = "@";
        private final String mPkg;
        private final int mUserId;

        public PkgUser(String pkg, int userId) {
            this.mPkg = pkg;
            this.mUserId = userId;
        }

        public PkgUser(String pkgUserStr) throws IllegalArgumentException {
            try {
                String[] vals = pkgUserStr.split(SEPARATOR, 2);
                this.mPkg = vals[0];
                this.mUserId = Integer.parseInt(vals[1]);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        public String getPkg() {
            return this.mPkg;
        }

        public int getUserId() {
            return this.mUserId;
        }

        public int hashCode() {
            return this.mPkg.hashCode() + this.mUserId;
        }

        public boolean equals(Object obj) {
            if (getClass().equals(obj != null ? obj.getClass() : null)) {
                PkgUser other = (PkgUser) obj;
                return Objects.equals(other.mPkg, this.mPkg) && other.mUserId == this.mUserId;
            }
            return false;
        }

        public String toString() {
            return String.format(FORMAT, this.mPkg, Integer.valueOf(this.mUserId));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public class ParserHolder implements AutoCloseable {
        private InputStream input;
        private XmlPullParser parser;

        private ParserHolder() {
        }

        @Override // java.lang.AutoCloseable
        public void close() throws IOException {
            this.input.close();
        }
    }
}
