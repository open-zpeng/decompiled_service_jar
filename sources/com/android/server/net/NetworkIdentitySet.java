package com.android.server.net;

import android.net.NetworkIdentity;
import android.util.proto.ProtoOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
/* loaded from: classes.dex */
public class NetworkIdentitySet extends HashSet<NetworkIdentity> implements Comparable<NetworkIdentitySet> {
    private static final int VERSION_ADD_DEFAULT_NETWORK = 5;
    private static final int VERSION_ADD_METERED = 4;
    private static final int VERSION_ADD_NETWORK_ID = 3;
    private static final int VERSION_ADD_ROAMING = 2;
    private static final int VERSION_INIT = 1;

    public NetworkIdentitySet() {
    }

    public NetworkIdentitySet(DataInputStream in) throws IOException {
        boolean roaming;
        boolean z;
        int version = in.readInt();
        int size = in.readInt();
        for (int i = 0; i < size; i++) {
            if (version <= 1) {
                in.readInt();
            }
            int type = in.readInt();
            int subType = in.readInt();
            String subscriberId = readOptionalString(in);
            String networkId = version >= 3 ? readOptionalString(in) : null;
            if (version < 2) {
                roaming = false;
            } else {
                boolean roaming2 = in.readBoolean();
                roaming = roaming2;
            }
            if (version >= 4) {
                z = in.readBoolean();
            } else if (type != 0) {
                z = false;
            } else {
                z = true;
            }
            boolean metered = z;
            boolean defaultNetwork = version >= 5 ? in.readBoolean() : true;
            add(new NetworkIdentity(type, subType, subscriberId, networkId, roaming, metered, defaultNetwork));
        }
    }

    public void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(5);
        out.writeInt(size());
        Iterator<NetworkIdentity> it = iterator();
        while (it.hasNext()) {
            NetworkIdentity ident = it.next();
            out.writeInt(ident.getType());
            out.writeInt(ident.getSubType());
            writeOptionalString(out, ident.getSubscriberId());
            writeOptionalString(out, ident.getNetworkId());
            out.writeBoolean(ident.getRoaming());
            out.writeBoolean(ident.getMetered());
            out.writeBoolean(ident.getDefaultNetwork());
        }
    }

    public boolean isAnyMemberMetered() {
        if (isEmpty()) {
            return false;
        }
        Iterator<NetworkIdentity> it = iterator();
        while (it.hasNext()) {
            NetworkIdentity ident = it.next();
            if (ident.getMetered()) {
                return true;
            }
        }
        return false;
    }

    public boolean isAnyMemberRoaming() {
        if (isEmpty()) {
            return false;
        }
        Iterator<NetworkIdentity> it = iterator();
        while (it.hasNext()) {
            NetworkIdentity ident = it.next();
            if (ident.getRoaming()) {
                return true;
            }
        }
        return false;
    }

    public boolean areAllMembersOnDefaultNetwork() {
        if (isEmpty()) {
            return true;
        }
        Iterator<NetworkIdentity> it = iterator();
        while (it.hasNext()) {
            NetworkIdentity ident = it.next();
            if (!ident.getDefaultNetwork()) {
                return false;
            }
        }
        return true;
    }

    private static void writeOptionalString(DataOutputStream out, String value) throws IOException {
        if (value != null) {
            out.writeByte(1);
            out.writeUTF(value);
            return;
        }
        out.writeByte(0);
    }

    private static String readOptionalString(DataInputStream in) throws IOException {
        if (in.readByte() != 0) {
            return in.readUTF();
        }
        return null;
    }

    @Override // java.lang.Comparable
    public int compareTo(NetworkIdentitySet another) {
        if (isEmpty()) {
            return -1;
        }
        if (another.isEmpty()) {
            return 1;
        }
        NetworkIdentity ident = iterator().next();
        NetworkIdentity anotherIdent = another.iterator().next();
        return ident.compareTo(anotherIdent);
    }

    public void writeToProto(ProtoOutputStream proto, long tag) {
        long start = proto.start(tag);
        Iterator<NetworkIdentity> it = iterator();
        while (it.hasNext()) {
            NetworkIdentity ident = it.next();
            ident.writeToProto(proto, 2246267895809L);
        }
        proto.end(start);
    }
}
