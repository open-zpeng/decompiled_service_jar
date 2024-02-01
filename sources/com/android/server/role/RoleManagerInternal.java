package com.android.server.role;

import android.util.ArrayMap;
import android.util.ArraySet;

/* loaded from: classes.dex */
public abstract class RoleManagerInternal {
    public abstract ArrayMap<String, ArraySet<String>> getRolesAndHolders(int i);
}
