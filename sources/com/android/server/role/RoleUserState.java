package com.android.server.role;

import android.os.Environment;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/* loaded from: classes.dex */
public class RoleUserState {
    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_PACKAGES_HASH = "packagesHash";
    private static final String ATTRIBUTE_VERSION = "version";
    private static final String LOG_TAG = RoleUserState.class.getSimpleName();
    private static final String ROLES_FILE_NAME = "roles.xml";
    private static final String TAG_HOLDER = "holder";
    private static final String TAG_ROLE = "role";
    private static final String TAG_ROLES = "roles";
    public static final int VERSION_UNDEFINED = -1;
    private static final long WRITE_DELAY_MILLIS = 200;
    private final Callback mCallback;
    @GuardedBy({"mLock"})
    private boolean mDestroyed;
    @GuardedBy({"mLock"})
    private String mPackagesHash;
    private final int mUserId;
    @GuardedBy({"mLock"})
    private boolean mWriteScheduled;
    private final Object mLock = new Object();
    @GuardedBy({"mLock"})
    private int mVersion = -1;
    @GuardedBy({"mLock"})
    private ArrayMap<String, ArraySet<String>> mRoles = new ArrayMap<>();
    private final Handler mWriteHandler = new Handler(BackgroundThread.getHandler().getLooper());

    /* loaded from: classes.dex */
    public interface Callback {
        void onRoleHoldersChanged(String str, int i, String str2, String str3);
    }

    public RoleUserState(int userId, Callback callback) {
        this.mUserId = userId;
        this.mCallback = callback;
        readFile();
    }

    public int getVersion() {
        int i;
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            i = this.mVersion;
        }
        return i;
    }

    public void setVersion(int version) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (this.mVersion == version) {
                return;
            }
            this.mVersion = version;
            scheduleWriteFileLocked();
        }
    }

    public String getPackagesHash() {
        String str;
        synchronized (this.mLock) {
            str = this.mPackagesHash;
        }
        return str;
    }

    public void setPackagesHash(String packagesHash) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (Objects.equals(this.mPackagesHash, packagesHash)) {
                return;
            }
            this.mPackagesHash = packagesHash;
            scheduleWriteFileLocked();
        }
    }

    public boolean isRoleAvailable(String roleName) {
        boolean containsKey;
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            containsKey = this.mRoles.containsKey(roleName);
        }
        return containsKey;
    }

    public ArraySet<String> getRoleHolders(String roleName) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            ArraySet<String> packageNames = this.mRoles.get(roleName);
            if (packageNames == null) {
                return null;
            }
            return new ArraySet<>(packageNames);
        }
    }

    public boolean addRoleName(String roleName) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            if (!this.mRoles.containsKey(roleName)) {
                this.mRoles.put(roleName, new ArraySet<>());
                String str = LOG_TAG;
                Slog.i(str, "Added new role: " + roleName);
                scheduleWriteFileLocked();
                return true;
            }
            return false;
        }
    }

    public void setRoleNames(List<String> roleNames) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            boolean changed = false;
            for (int i = this.mRoles.size() - 1; i >= 0; i--) {
                String roleName = this.mRoles.keyAt(i);
                if (!roleNames.contains(roleName)) {
                    ArraySet<String> packageNames = this.mRoles.valueAt(i);
                    if (!packageNames.isEmpty()) {
                        Slog.e(LOG_TAG, "Holders of a removed role should have been cleaned up, role: " + roleName + ", holders: " + packageNames);
                    }
                    this.mRoles.removeAt(i);
                    changed = true;
                }
            }
            int roleNamesSize = roleNames.size();
            for (int i2 = 0; i2 < roleNamesSize; i2++) {
                changed |= addRoleName(roleNames.get(i2));
            }
            if (changed) {
                scheduleWriteFileLocked();
            }
        }
    }

    public boolean addRoleHolder(String roleName, String packageName) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            ArraySet<String> roleHolders = this.mRoles.get(roleName);
            if (roleHolders == null) {
                String str = LOG_TAG;
                Slog.e(str, "Cannot add role holder for unknown role, role: " + roleName + ", package: " + packageName);
                return false;
            }
            boolean changed = roleHolders.add(packageName);
            if (changed) {
                scheduleWriteFileLocked();
            }
            if (changed) {
                this.mCallback.onRoleHoldersChanged(roleName, this.mUserId, null, packageName);
                return true;
            }
            return true;
        }
    }

    public boolean removeRoleHolder(String roleName, String packageName) {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            ArraySet<String> roleHolders = this.mRoles.get(roleName);
            if (roleHolders == null) {
                String str = LOG_TAG;
                Slog.e(str, "Cannot remove role holder for unknown role, role: " + roleName + ", package: " + packageName);
                return false;
            }
            boolean changed = roleHolders.remove(packageName);
            if (changed) {
                scheduleWriteFileLocked();
            }
            if (changed) {
                this.mCallback.onRoleHoldersChanged(roleName, this.mUserId, packageName, null);
                return true;
            }
            return true;
        }
    }

    public List<String> getHeldRoles(String packageName) {
        ArrayList<String> result = new ArrayList<>();
        int size = this.mRoles.size();
        for (int i = 0; i < size; i++) {
            if (this.mRoles.valueAt(i).contains(packageName)) {
                result.add(this.mRoles.keyAt(i));
            }
        }
        return result;
    }

    @GuardedBy({"mLock"})
    private void scheduleWriteFileLocked() {
        throwIfDestroyedLocked();
        if (!this.mWriteScheduled) {
            this.mWriteHandler.sendMessageDelayed(PooledLambda.obtainMessage(new Consumer() { // from class: com.android.server.role.-$$Lambda$RoleUserState$e8W_Zaq_FyocW_DX1qcbN0ld0co
                @Override // java.util.function.Consumer
                public final void accept(Object obj) {
                    ((RoleUserState) obj).writeFile();
                }
            }, this), WRITE_DELAY_MILLIS);
            this.mWriteScheduled = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void writeFile() {
        synchronized (this.mLock) {
            if (this.mDestroyed) {
                return;
            }
            this.mWriteScheduled = false;
            int version = this.mVersion;
            String packagesHash = this.mPackagesHash;
            ArrayMap<String, ArraySet<String>> roles = snapshotRolesLocked();
            File file = getFile(this.mUserId);
            AtomicFile atomicFile = new AtomicFile(file, "roles-" + this.mUserId);
            FileOutputStream out = null;
            try {
                try {
                    out = atomicFile.startWrite();
                    XmlSerializer serializer = Xml.newSerializer();
                    serializer.setOutput(out, StandardCharsets.UTF_8.name());
                    serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
                    serializer.startDocument(null, true);
                    serializeRoles(serializer, version, packagesHash, roles);
                    serializer.endDocument();
                    atomicFile.finishWrite(out);
                    Slog.i(LOG_TAG, "Wrote roles.xml successfully");
                } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                    Slog.wtf(LOG_TAG, "Failed to write roles.xml, restoring backup", e);
                    if (out != null) {
                        atomicFile.failWrite(out);
                    }
                }
            } finally {
                IoUtils.closeQuietly(out);
            }
        }
    }

    private void serializeRoles(XmlSerializer serializer, int version, String packagesHash, ArrayMap<String, ArraySet<String>> roles) throws IOException {
        serializer.startTag(null, TAG_ROLES);
        serializer.attribute(null, "version", Integer.toString(version));
        if (packagesHash != null) {
            serializer.attribute(null, ATTRIBUTE_PACKAGES_HASH, packagesHash);
        }
        int size = roles.size();
        for (int i = 0; i < size; i++) {
            String roleName = roles.keyAt(i);
            ArraySet<String> roleHolders = roles.valueAt(i);
            serializer.startTag(null, TAG_ROLE);
            serializer.attribute(null, "name", roleName);
            serializeRoleHolders(serializer, roleHolders);
            serializer.endTag(null, TAG_ROLE);
        }
        serializer.endTag(null, TAG_ROLES);
    }

    private void serializeRoleHolders(XmlSerializer serializer, ArraySet<String> roleHolders) throws IOException {
        int size = roleHolders.size();
        for (int i = 0; i < size; i++) {
            String roleHolder = roleHolders.valueAt(i);
            serializer.startTag(null, TAG_HOLDER);
            serializer.attribute(null, "name", roleHolder);
            serializer.endTag(null, TAG_HOLDER);
        }
    }

    private void readFile() {
        synchronized (this.mLock) {
            File file = getFile(this.mUserId);
            try {
                try {
                    FileInputStream in = new AtomicFile(file).openRead();
                    try {
                        XmlPullParser parser = Xml.newPullParser();
                        parser.setInput(in, null);
                        parseXmlLocked(parser);
                        Slog.i(LOG_TAG, "Read roles.xml successfully");
                        if (in != null) {
                            in.close();
                        }
                    } finally {
                    }
                } catch (IOException | XmlPullParserException e) {
                    throw new IllegalStateException("Failed to parse roles.xml: " + file, e);
                }
            } catch (FileNotFoundException e2) {
                Slog.i(LOG_TAG, "roles.xml not found");
            }
        }
    }

    private void parseXmlLocked(XmlPullParser parser) throws IOException, XmlPullParserException {
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while (true) {
            int type = parser.next();
            if (type == 1 || ((depth = parser.getDepth()) < innerDepth && type == 3)) {
                break;
            } else if (depth <= innerDepth && type == 2 && parser.getName().equals(TAG_ROLES)) {
                parseRolesLocked(parser);
                return;
            }
        }
        Slog.w(LOG_TAG, "Missing <roles> in roles.xml");
    }

    private void parseRolesLocked(XmlPullParser parser) throws IOException, XmlPullParserException {
        this.mVersion = Integer.parseInt(parser.getAttributeValue(null, "version"));
        this.mPackagesHash = parser.getAttributeValue(null, ATTRIBUTE_PACKAGES_HASH);
        this.mRoles.clear();
        int innerDepth = parser.getDepth() + 1;
        while (true) {
            int type = parser.next();
            if (type != 1) {
                int depth = parser.getDepth();
                if (depth >= innerDepth || type != 3) {
                    if (depth <= innerDepth && type == 2 && parser.getName().equals(TAG_ROLE)) {
                        String roleName = parser.getAttributeValue(null, "name");
                        ArraySet<String> roleHolders = parseRoleHoldersLocked(parser);
                        this.mRoles.put(roleName, roleHolders);
                    }
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    private ArraySet<String> parseRoleHoldersLocked(XmlPullParser parser) throws IOException, XmlPullParserException {
        int depth;
        ArraySet<String> roleHolders = new ArraySet<>();
        int innerDepth = parser.getDepth() + 1;
        while (true) {
            int type = parser.next();
            if (type == 1 || ((depth = parser.getDepth()) < innerDepth && type == 3)) {
                break;
            } else if (depth <= innerDepth && type == 2 && parser.getName().equals(TAG_HOLDER)) {
                String roleHolder = parser.getAttributeValue(null, "name");
                roleHolders.add(roleHolder);
            }
        }
        return roleHolders;
    }

    public void dump(DualDumpOutputStream dumpOutputStream, String fieldName, long fieldId) {
        int version;
        String packagesHash;
        ArrayMap<String, ArraySet<String>> roles;
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            version = this.mVersion;
            packagesHash = this.mPackagesHash;
            roles = snapshotRolesLocked();
        }
        long fieldToken = dumpOutputStream.start(fieldName, fieldId);
        dumpOutputStream.write("user_id", 1120986464257L, this.mUserId);
        dumpOutputStream.write("version", 1120986464258L, version);
        dumpOutputStream.write("packages_hash", 1138166333443L, packagesHash);
        int rolesSize = roles.size();
        for (int rolesIndex = 0; rolesIndex < rolesSize; rolesIndex++) {
            String roleName = roles.keyAt(rolesIndex);
            ArraySet<String> roleHolders = roles.valueAt(rolesIndex);
            long rolesToken = dumpOutputStream.start(TAG_ROLES, 2246267895812L);
            dumpOutputStream.write("name", 1138166333441L, roleName);
            int roleHoldersSize = roleHolders.size();
            int roleHoldersIndex = 0;
            while (roleHoldersIndex < roleHoldersSize) {
                String roleHolder = roleHolders.valueAt(roleHoldersIndex);
                dumpOutputStream.write("holders", 2237677961218L, roleHolder);
                roleHoldersIndex++;
                version = version;
                rolesSize = rolesSize;
            }
            dumpOutputStream.end(rolesToken);
        }
        dumpOutputStream.end(fieldToken);
    }

    public ArrayMap<String, ArraySet<String>> getRolesAndHolders() {
        ArrayMap<String, ArraySet<String>> snapshotRolesLocked;
        synchronized (this.mLock) {
            snapshotRolesLocked = snapshotRolesLocked();
        }
        return snapshotRolesLocked;
    }

    @GuardedBy({"mLock"})
    private ArrayMap<String, ArraySet<String>> snapshotRolesLocked() {
        ArrayMap<String, ArraySet<String>> roles = new ArrayMap<>();
        int size = CollectionUtils.size(this.mRoles);
        for (int i = 0; i < size; i++) {
            String roleName = this.mRoles.keyAt(i);
            ArraySet<String> roleHolders = this.mRoles.valueAt(i);
            roles.put(roleName, new ArraySet<>(roleHolders));
        }
        return roles;
    }

    public void destroy() {
        synchronized (this.mLock) {
            throwIfDestroyedLocked();
            this.mWriteHandler.removeCallbacksAndMessages(null);
            getFile(this.mUserId).delete();
            this.mDestroyed = true;
        }
    }

    @GuardedBy({"mLock"})
    private void throwIfDestroyedLocked() {
        if (this.mDestroyed) {
            throw new IllegalStateException("This RoleUserState has already been destroyed");
        }
    }

    private static File getFile(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), ROLES_FILE_NAME);
    }
}
