package com.android.server.pm.permission;

import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import com.android.internal.util.ArrayUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
/* loaded from: classes.dex */
public final class PermissionsState {
    private static final int[] NO_GIDS = new int[0];
    public static final int PERMISSION_OPERATION_FAILURE = -1;
    public static final int PERMISSION_OPERATION_SUCCESS = 0;
    public static final int PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED = 1;
    private int[] mGlobalGids = NO_GIDS;
    private SparseBooleanArray mPermissionReviewRequired;
    private ArrayMap<String, PermissionData> mPermissions;

    public PermissionsState() {
    }

    public PermissionsState(PermissionsState prototype) {
        copyFrom(prototype);
    }

    public void setGlobalGids(int[] globalGids) {
        if (!ArrayUtils.isEmpty(globalGids)) {
            this.mGlobalGids = Arrays.copyOf(globalGids, globalGids.length);
        }
    }

    public void copyFrom(PermissionsState other) {
        if (other == this) {
            return;
        }
        if (this.mPermissions != null) {
            if (other.mPermissions == null) {
                this.mPermissions = null;
            } else {
                this.mPermissions.clear();
            }
        }
        int i = 0;
        if (other.mPermissions != null) {
            if (this.mPermissions == null) {
                this.mPermissions = new ArrayMap<>();
            }
            int permissionCount = other.mPermissions.size();
            for (int i2 = 0; i2 < permissionCount; i2++) {
                String name = other.mPermissions.keyAt(i2);
                PermissionData permissionData = other.mPermissions.valueAt(i2);
                this.mPermissions.put(name, new PermissionData(permissionData));
            }
        }
        this.mGlobalGids = NO_GIDS;
        if (other.mGlobalGids != NO_GIDS) {
            this.mGlobalGids = Arrays.copyOf(other.mGlobalGids, other.mGlobalGids.length);
        }
        if (this.mPermissionReviewRequired != null) {
            if (other.mPermissionReviewRequired == null) {
                this.mPermissionReviewRequired = null;
            } else {
                this.mPermissionReviewRequired.clear();
            }
        }
        if (other.mPermissionReviewRequired != null) {
            if (this.mPermissionReviewRequired == null) {
                this.mPermissionReviewRequired = new SparseBooleanArray();
            }
            int userCount = other.mPermissionReviewRequired.size();
            while (true) {
                int i3 = i;
                if (i3 < userCount) {
                    boolean reviewRequired = other.mPermissionReviewRequired.valueAt(i3);
                    this.mPermissionReviewRequired.put(i3, reviewRequired);
                    i = i3 + 1;
                } else {
                    return;
                }
            }
        }
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PermissionsState other = (PermissionsState) obj;
        if (this.mPermissions == null) {
            if (other.mPermissions != null) {
                return false;
            }
        } else if (!this.mPermissions.equals(other.mPermissions)) {
            return false;
        }
        if (this.mPermissionReviewRequired == null) {
            if (other.mPermissionReviewRequired != null) {
                return false;
            }
        } else if (!this.mPermissionReviewRequired.equals(other.mPermissionReviewRequired)) {
            return false;
        }
        return Arrays.equals(this.mGlobalGids, other.mGlobalGids);
    }

    public boolean isPermissionReviewRequired(int userId) {
        return this.mPermissionReviewRequired != null && this.mPermissionReviewRequired.get(userId);
    }

    public int grantInstallPermission(BasePermission permission) {
        return grantPermission(permission, -1);
    }

    public int revokeInstallPermission(BasePermission permission) {
        return revokePermission(permission, -1);
    }

    public int grantRuntimePermission(BasePermission permission, int userId) {
        enforceValidUserId(userId);
        if (userId == -1) {
            return -1;
        }
        return grantPermission(permission, userId);
    }

    public int revokeRuntimePermission(BasePermission permission, int userId) {
        enforceValidUserId(userId);
        if (userId == -1) {
            return -1;
        }
        return revokePermission(permission, userId);
    }

    public boolean hasRuntimePermission(String name, int userId) {
        enforceValidUserId(userId);
        return !hasInstallPermission(name) && hasPermission(name, userId);
    }

    public boolean hasInstallPermission(String name) {
        return hasPermission(name, -1);
    }

    public boolean hasPermission(String name, int userId) {
        PermissionData permissionData;
        enforceValidUserId(userId);
        return (this.mPermissions == null || (permissionData = this.mPermissions.get(name)) == null || !permissionData.isGranted(userId)) ? false : true;
    }

    public boolean hasRequestedPermission(ArraySet<String> names) {
        if (this.mPermissions == null) {
            return false;
        }
        for (int i = names.size() - 1; i >= 0; i--) {
            if (this.mPermissions.get(names.valueAt(i)) != null) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getPermissions(int userId) {
        enforceValidUserId(userId);
        if (this.mPermissions == null) {
            return Collections.emptySet();
        }
        Set<String> permissions = new ArraySet<>(this.mPermissions.size());
        int permissionCount = this.mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            String permission = this.mPermissions.keyAt(i);
            if (hasInstallPermission(permission)) {
                permissions.add(permission);
            } else if (userId != -1 && hasRuntimePermission(permission, userId)) {
                permissions.add(permission);
            }
        }
        return permissions;
    }

    public PermissionState getInstallPermissionState(String name) {
        return getPermissionState(name, -1);
    }

    public PermissionState getRuntimePermissionState(String name, int userId) {
        enforceValidUserId(userId);
        return getPermissionState(name, userId);
    }

    public List<PermissionState> getInstallPermissionStates() {
        return getPermissionStatesInternal(-1);
    }

    public List<PermissionState> getRuntimePermissionStates(int userId) {
        enforceValidUserId(userId);
        return getPermissionStatesInternal(userId);
    }

    public int getPermissionFlags(String name, int userId) {
        PermissionState installPermState = getInstallPermissionState(name);
        if (installPermState != null) {
            return installPermState.getFlags();
        }
        PermissionState runtimePermState = getRuntimePermissionState(name, userId);
        if (runtimePermState != null) {
            return runtimePermState.getFlags();
        }
        return 0;
    }

    public boolean updatePermissionFlags(BasePermission permission, int userId, int flagMask, int flagValues) {
        enforceValidUserId(userId);
        boolean mayChangeFlags = (flagValues == 0 && flagMask == 0) ? false : true;
        if (this.mPermissions == null) {
            if (!mayChangeFlags) {
                return false;
            }
            ensurePermissionData(permission);
        }
        PermissionData permissionData = this.mPermissions.get(permission.getName());
        if (permissionData == null) {
            if (!mayChangeFlags) {
                return false;
            }
            permissionData = ensurePermissionData(permission);
        }
        int oldFlags = permissionData.getFlags(userId);
        boolean updated = permissionData.updateFlags(userId, flagMask, flagValues);
        if (updated) {
            int newFlags = permissionData.getFlags(userId);
            if ((oldFlags & 64) == 0 && (newFlags & 64) != 0) {
                if (this.mPermissionReviewRequired == null) {
                    this.mPermissionReviewRequired = new SparseBooleanArray();
                }
                this.mPermissionReviewRequired.put(userId, true);
            } else if ((oldFlags & 64) != 0 && (newFlags & 64) == 0 && this.mPermissionReviewRequired != null && !hasPermissionRequiringReview(userId)) {
                this.mPermissionReviewRequired.delete(userId);
                if (this.mPermissionReviewRequired.size() <= 0) {
                    this.mPermissionReviewRequired = null;
                }
            }
        }
        return updated;
    }

    private boolean hasPermissionRequiringReview(int userId) {
        int permissionCount = this.mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            PermissionData permission = this.mPermissions.valueAt(i);
            if ((permission.getFlags(userId) & 64) != 0) {
                return true;
            }
        }
        return false;
    }

    public boolean updatePermissionFlagsForAllPermissions(int userId, int flagMask, int flagValues) {
        enforceValidUserId(userId);
        if (this.mPermissions == null) {
            return false;
        }
        boolean changed = false;
        int permissionCount = this.mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            PermissionData permissionData = this.mPermissions.valueAt(i);
            changed |= permissionData.updateFlags(userId, flagMask, flagValues);
        }
        return changed;
    }

    public int[] computeGids(int userId) {
        enforceValidUserId(userId);
        int[] gids = this.mGlobalGids;
        if (this.mPermissions != null) {
            int permissionCount = this.mPermissions.size();
            for (int i = 0; i < permissionCount; i++) {
                String permission = this.mPermissions.keyAt(i);
                if (hasPermission(permission, userId)) {
                    PermissionData permissionData = this.mPermissions.valueAt(i);
                    int[] permGids = permissionData.computeGids(userId);
                    if (permGids != NO_GIDS) {
                        gids = appendInts(gids, permGids);
                    }
                }
            }
        }
        return gids;
    }

    public int[] computeGids(int[] userIds) {
        int[] gids = this.mGlobalGids;
        for (int userId : userIds) {
            int[] userGids = computeGids(userId);
            gids = appendInts(gids, userGids);
        }
        return gids;
    }

    public void reset() {
        this.mGlobalGids = NO_GIDS;
        this.mPermissions = null;
        this.mPermissionReviewRequired = null;
    }

    private PermissionState getPermissionState(String name, int userId) {
        PermissionData permissionData;
        if (this.mPermissions == null || (permissionData = this.mPermissions.get(name)) == null) {
            return null;
        }
        return permissionData.getPermissionState(userId);
    }

    private List<PermissionState> getPermissionStatesInternal(int userId) {
        enforceValidUserId(userId);
        if (this.mPermissions == null) {
            return Collections.emptyList();
        }
        List<PermissionState> permissionStates = new ArrayList<>();
        int permissionCount = this.mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            PermissionData permissionData = this.mPermissions.valueAt(i);
            PermissionState permissionState = permissionData.getPermissionState(userId);
            if (permissionState != null) {
                permissionStates.add(permissionState);
            }
        }
        return permissionStates;
    }

    private int grantPermission(BasePermission permission, int userId) {
        if (hasPermission(permission.getName(), userId)) {
            return -1;
        }
        boolean hasGids = !ArrayUtils.isEmpty(permission.computeGids(userId));
        int[] oldGids = hasGids ? computeGids(userId) : NO_GIDS;
        PermissionData permissionData = ensurePermissionData(permission);
        if (permissionData.grant(userId)) {
            if (hasGids) {
                int[] newGids = computeGids(userId);
                return oldGids.length != newGids.length ? 1 : 0;
            }
            return 0;
        }
        return -1;
    }

    private int revokePermission(BasePermission permission, int userId) {
        String permName = permission.getName();
        if (hasPermission(permName, userId)) {
            boolean hasGids = !ArrayUtils.isEmpty(permission.computeGids(userId));
            int[] oldGids = hasGids ? computeGids(userId) : NO_GIDS;
            PermissionData permissionData = this.mPermissions.get(permName);
            if (permissionData.revoke(userId)) {
                if (permissionData.isDefault()) {
                    ensureNoPermissionData(permName);
                }
                if (hasGids) {
                    int[] newGids = computeGids(userId);
                    return oldGids.length != newGids.length ? 1 : 0;
                }
                return 0;
            }
            return -1;
        }
        return -1;
    }

    private static int[] appendInts(int[] current, int[] added) {
        if (current != null && added != null) {
            for (int guid : added) {
                current = ArrayUtils.appendInt(current, guid);
            }
        }
        return current;
    }

    private static void enforceValidUserId(int userId) {
        if (userId != -1 && userId < 0) {
            throw new IllegalArgumentException("Invalid userId:" + userId);
        }
    }

    private PermissionData ensurePermissionData(BasePermission permission) {
        String permName = permission.getName();
        if (this.mPermissions == null) {
            this.mPermissions = new ArrayMap<>();
        }
        PermissionData permissionData = this.mPermissions.get(permName);
        if (permissionData == null) {
            PermissionData permissionData2 = new PermissionData(permission);
            this.mPermissions.put(permName, permissionData2);
            return permissionData2;
        }
        return permissionData;
    }

    private void ensureNoPermissionData(String name) {
        if (this.mPermissions == null) {
            return;
        }
        this.mPermissions.remove(name);
        if (this.mPermissions.isEmpty()) {
            this.mPermissions = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public static final class PermissionData {
        private final BasePermission mPerm;
        private SparseArray<PermissionState> mUserStates;

        public PermissionData(BasePermission perm) {
            this.mUserStates = new SparseArray<>();
            this.mPerm = perm;
        }

        public PermissionData(PermissionData other) {
            this(other.mPerm);
            int otherStateCount = other.mUserStates.size();
            for (int i = 0; i < otherStateCount; i++) {
                int otherUserId = other.mUserStates.keyAt(i);
                PermissionState otherState = other.mUserStates.valueAt(i);
                this.mUserStates.put(otherUserId, new PermissionState(otherState));
            }
        }

        public int[] computeGids(int userId) {
            return this.mPerm.computeGids(userId);
        }

        public boolean isGranted(int userId) {
            if (isInstallPermission()) {
                userId = -1;
            }
            PermissionState userState = this.mUserStates.get(userId);
            if (userState == null) {
                return false;
            }
            return userState.mGranted;
        }

        public boolean grant(int userId) {
            if (isCompatibleUserId(userId) && !isGranted(userId)) {
                PermissionState userState = this.mUserStates.get(userId);
                if (userState == null) {
                    userState = new PermissionState(this.mPerm.getName());
                    this.mUserStates.put(userId, userState);
                }
                userState.mGranted = true;
                return true;
            }
            return false;
        }

        public boolean revoke(int userId) {
            if (isCompatibleUserId(userId) && isGranted(userId)) {
                PermissionState userState = this.mUserStates.get(userId);
                userState.mGranted = false;
                if (userState.isDefault()) {
                    this.mUserStates.remove(userId);
                    return true;
                }
                return true;
            }
            return false;
        }

        public PermissionState getPermissionState(int userId) {
            return this.mUserStates.get(userId);
        }

        public int getFlags(int userId) {
            PermissionState userState = this.mUserStates.get(userId);
            if (userState == null) {
                return 0;
            }
            return userState.mFlags;
        }

        public boolean isDefault() {
            return this.mUserStates.size() <= 0;
        }

        public static boolean isInstallPermissionKey(int userId) {
            return userId == -1;
        }

        public boolean updateFlags(int userId, int flagMask, int flagValues) {
            if (isInstallPermission()) {
                userId = -1;
            }
            if (isCompatibleUserId(userId)) {
                int newFlags = flagValues & flagMask;
                PermissionState userState = this.mUserStates.get(userId);
                if (userState == null) {
                    if (newFlags != 0) {
                        PermissionState userState2 = new PermissionState(this.mPerm.getName());
                        userState2.mFlags = newFlags;
                        this.mUserStates.put(userId, userState2);
                        return true;
                    }
                    return false;
                }
                int oldFlags = userState.mFlags;
                userState.mFlags = (userState.mFlags & (~flagMask)) | newFlags;
                if (userState.isDefault()) {
                    this.mUserStates.remove(userId);
                }
                return userState.mFlags != oldFlags;
            }
            return false;
        }

        private boolean isCompatibleUserId(int userId) {
            return isDefault() || !(isInstallPermission() ^ isInstallPermissionKey(userId));
        }

        private boolean isInstallPermission() {
            return this.mUserStates.size() == 1 && this.mUserStates.get(-1) != null;
        }
    }

    /* loaded from: classes.dex */
    public static final class PermissionState {
        private int mFlags;
        private boolean mGranted;
        private final String mName;

        public PermissionState(String name) {
            this.mName = name;
        }

        public PermissionState(PermissionState other) {
            this.mName = other.mName;
            this.mGranted = other.mGranted;
            this.mFlags = other.mFlags;
        }

        public boolean isDefault() {
            return !this.mGranted && this.mFlags == 0;
        }

        public String getName() {
            return this.mName;
        }

        public boolean isGranted() {
            return this.mGranted;
        }

        public int getFlags() {
            return this.mFlags;
        }
    }
}
