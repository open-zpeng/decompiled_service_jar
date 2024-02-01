package com.android.server;

import android.app.AppOpsManager;
import android.content.Context;
import android.net.IIpSecService;
import android.net.INetd;
import android.net.IpSecAlgorithm;
import android.net.IpSecConfig;
import android.net.IpSecTransformResponse;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.IpSecUdpEncapResponse;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkUtils;
import android.net.TrafficStats;
import android.net.util.NetdService;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import libcore.io.IoUtils;

/* loaded from: classes.dex */
public class IpSecService extends IIpSecService.Stub {
    static final int FREE_PORT_MIN = 1024;
    private static final InetAddress INADDR_ANY;
    @VisibleForTesting
    static final int MAX_PORT_BIND_ATTEMPTS = 10;
    private static final int NETD_FETCH_TIMEOUT_MS = 5000;
    private static final String NETD_SERVICE_NAME = "netd";
    static final int PORT_MAX = 65535;
    private static final String TUNNEL_OP = "android:manage_ipsec_tunnels";
    @VisibleForTesting
    static final int TUN_INTF_NETID_RANGE = 1024;
    @VisibleForTesting
    static final int TUN_INTF_NETID_START = 64512;
    private final Context mContext;
    @GuardedBy({"IpSecService.this"})
    private int mNextResourceId;
    private int mNextTunnelNetIdIndex;
    private final IpSecServiceConfiguration mSrvConfig;
    private final SparseBooleanArray mTunnelNetIds;
    final UidFdTagger mUidFdTagger;
    @VisibleForTesting
    final UserResourceTracker mUserResourceTracker;
    private static final String TAG = "IpSecService";
    private static final boolean DBG = Log.isLoggable(TAG, 3);
    private static final int[] ADDRESS_FAMILIES = {OsConstants.AF_INET, OsConstants.AF_INET6};

    @VisibleForTesting
    /* loaded from: classes.dex */
    public interface IResource {
        void freeUnderlyingResources() throws RemoteException;

        void invalidate() throws RemoteException;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public interface IpSecServiceConfiguration {
        public static final IpSecServiceConfiguration GETSRVINSTANCE = new IpSecServiceConfiguration() { // from class: com.android.server.IpSecService.IpSecServiceConfiguration.1
            @Override // com.android.server.IpSecService.IpSecServiceConfiguration
            public INetd getNetdInstance() throws RemoteException {
                INetd netd = NetdService.getInstance();
                if (netd == null) {
                    throw new RemoteException("Failed to Get Netd Instance");
                }
                return netd;
            }
        };

        INetd getNetdInstance() throws RemoteException;
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    public interface UidFdTagger {
        void tag(FileDescriptor fileDescriptor, int i) throws IOException;
    }

    static {
        try {
            INADDR_ANY = InetAddress.getByAddress(new byte[]{0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    /* loaded from: classes.dex */
    public class RefcountedResource<T extends IResource> implements IBinder.DeathRecipient {
        IBinder mBinder;
        private final List<RefcountedResource> mChildren;
        int mRefCount = 1;
        private final T mResource;

        RefcountedResource(T resource, IBinder binder, RefcountedResource... children) {
            synchronized (IpSecService.this) {
                this.mResource = resource;
                this.mChildren = new ArrayList(children.length);
                this.mBinder = binder;
                for (RefcountedResource child : children) {
                    this.mChildren.add(child);
                    child.mRefCount++;
                }
                try {
                    this.mBinder.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    binderDied();
                    e.rethrowFromSystemServer();
                }
            }
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            synchronized (IpSecService.this) {
                try {
                    userRelease();
                } catch (Exception e) {
                    Log.e(IpSecService.TAG, "Failed to release resource: " + e);
                }
            }
        }

        public T getResource() {
            return this.mResource;
        }

        @GuardedBy({"IpSecService.this"})
        public void userRelease() throws RemoteException {
            IBinder iBinder = this.mBinder;
            if (iBinder == null) {
                return;
            }
            iBinder.unlinkToDeath(this, 0);
            this.mBinder = null;
            this.mResource.invalidate();
            releaseReference();
        }

        @GuardedBy({"IpSecService.this"})
        @VisibleForTesting
        public void releaseReference() throws RemoteException {
            this.mRefCount--;
            int i = this.mRefCount;
            if (i > 0) {
                return;
            }
            if (i < 0) {
                throw new IllegalStateException("Invalid operation - resource has already been released.");
            }
            this.mResource.freeUnderlyingResources();
            for (RefcountedResource<? extends IResource> child : this.mChildren) {
                child.releaseReference();
            }
            this.mRefCount--;
        }

        public String toString() {
            return "{mResource=" + this.mResource + ", mRefCount=" + this.mRefCount + ", mChildren=" + this.mChildren + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static class ResourceTracker {
        int mCurrent = 0;
        private final int mMax;

        ResourceTracker(int max) {
            this.mMax = max;
        }

        boolean isAvailable() {
            return this.mCurrent < this.mMax;
        }

        void take() {
            if (!isAvailable()) {
                Log.wtf(IpSecService.TAG, "Too many resources allocated!");
            }
            this.mCurrent++;
        }

        void give() {
            if (this.mCurrent <= 0) {
                Log.wtf(IpSecService.TAG, "We've released this resource too many times");
            }
            this.mCurrent--;
        }

        public String toString() {
            return "{mCurrent=" + this.mCurrent + ", mMax=" + this.mMax + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static final class UserRecord {
        public static final int MAX_NUM_ENCAP_SOCKETS = 2;
        public static final int MAX_NUM_SPIS = 8;
        public static final int MAX_NUM_TRANSFORMS = 4;
        public static final int MAX_NUM_TUNNEL_INTERFACES = 2;
        final RefcountedResourceArray<SpiRecord> mSpiRecords = new RefcountedResourceArray<>(SpiRecord.class.getSimpleName());
        final RefcountedResourceArray<TransformRecord> mTransformRecords = new RefcountedResourceArray<>(TransformRecord.class.getSimpleName());
        final RefcountedResourceArray<EncapSocketRecord> mEncapSocketRecords = new RefcountedResourceArray<>(EncapSocketRecord.class.getSimpleName());
        final RefcountedResourceArray<TunnelInterfaceRecord> mTunnelInterfaceRecords = new RefcountedResourceArray<>(TunnelInterfaceRecord.class.getSimpleName());
        final ResourceTracker mSpiQuotaTracker = new ResourceTracker(8);
        final ResourceTracker mTransformQuotaTracker = new ResourceTracker(4);
        final ResourceTracker mSocketQuotaTracker = new ResourceTracker(2);
        final ResourceTracker mTunnelQuotaTracker = new ResourceTracker(2);

        UserRecord() {
        }

        void removeSpiRecord(int resourceId) {
            this.mSpiRecords.remove(resourceId);
        }

        void removeTransformRecord(int resourceId) {
            this.mTransformRecords.remove(resourceId);
        }

        void removeTunnelInterfaceRecord(int resourceId) {
            this.mTunnelInterfaceRecords.remove(resourceId);
        }

        void removeEncapSocketRecord(int resourceId) {
            this.mEncapSocketRecords.remove(resourceId);
        }

        public String toString() {
            return "{mSpiQuotaTracker=" + this.mSpiQuotaTracker + ", mTransformQuotaTracker=" + this.mTransformQuotaTracker + ", mSocketQuotaTracker=" + this.mSocketQuotaTracker + ", mTunnelQuotaTracker=" + this.mTunnelQuotaTracker + ", mSpiRecords=" + this.mSpiRecords + ", mTransformRecords=" + this.mTransformRecords + ", mEncapSocketRecords=" + this.mEncapSocketRecords + ", mTunnelInterfaceRecords=" + this.mTunnelInterfaceRecords + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    @VisibleForTesting
    /* loaded from: classes.dex */
    public static final class UserResourceTracker {
        private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();

        UserResourceTracker() {
        }

        public UserRecord getUserRecord(int uid) {
            checkCallerUid(uid);
            UserRecord r = this.mUserRecords.get(uid);
            if (r == null) {
                UserRecord r2 = new UserRecord();
                this.mUserRecords.put(uid, r2);
                return r2;
            }
            return r;
        }

        private void checkCallerUid(int uid) {
            if (uid != Binder.getCallingUid() && 1000 != Binder.getCallingUid()) {
                throw new SecurityException("Attempted access of unowned resources");
            }
        }

        public String toString() {
            return this.mUserRecords.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public abstract class OwnedResourceRecord implements IResource {
        protected final int mResourceId;
        final int pid;
        final int uid;

        @Override // com.android.server.IpSecService.IResource
        public abstract void freeUnderlyingResources() throws RemoteException;

        protected abstract ResourceTracker getResourceTracker();

        @Override // com.android.server.IpSecService.IResource
        public abstract void invalidate() throws RemoteException;

        OwnedResourceRecord(int resourceId) {
            if (resourceId == -1) {
                throw new IllegalArgumentException("Resource ID must not be INVALID_RESOURCE_ID");
            }
            this.mResourceId = resourceId;
            this.pid = Binder.getCallingPid();
            this.uid = Binder.getCallingUid();
            getResourceTracker().take();
        }

        protected UserRecord getUserRecord() {
            return IpSecService.this.mUserResourceTracker.getUserRecord(this.uid);
        }

        public String toString() {
            return "{mResourceId=" + this.mResourceId + ", pid=" + this.pid + ", uid=" + this.uid + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* loaded from: classes.dex */
    public static class RefcountedResourceArray<T extends IResource> {
        SparseArray<RefcountedResource<T>> mArray = new SparseArray<>();
        private final String mTypeName;

        public RefcountedResourceArray(String typeName) {
            this.mTypeName = typeName;
        }

        T getResourceOrThrow(int key) {
            return getRefcountedResourceOrThrow(key).getResource();
        }

        RefcountedResource<T> getRefcountedResourceOrThrow(int key) {
            RefcountedResource<T> resource = this.mArray.get(key);
            if (resource == null) {
                throw new IllegalArgumentException(String.format("No such %s found for given id: %d", this.mTypeName, Integer.valueOf(key)));
            }
            return resource;
        }

        void put(int key, RefcountedResource<T> obj) {
            Preconditions.checkNotNull(obj, "Null resources cannot be added");
            this.mArray.put(key, obj);
        }

        void remove(int key) {
            this.mArray.remove(key);
        }

        public String toString() {
            return this.mArray.toString();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class TransformRecord extends OwnedResourceRecord {
        private final IpSecConfig mConfig;
        private final EncapSocketRecord mSocket;
        private final SpiRecord mSpi;

        TransformRecord(int resourceId, IpSecConfig config, SpiRecord spi, EncapSocketRecord socket) {
            super(resourceId);
            this.mConfig = config;
            this.mSpi = spi;
            this.mSocket = socket;
            spi.setOwnedByTransform();
        }

        public IpSecConfig getConfig() {
            return this.mConfig;
        }

        public SpiRecord getSpiRecord() {
            return this.mSpi;
        }

        public EncapSocketRecord getSocketRecord() {
            return this.mSocket;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord, com.android.server.IpSecService.IResource
        public void freeUnderlyingResources() {
            int spi = this.mSpi.getSpi();
            try {
                IpSecService.this.mSrvConfig.getNetdInstance().ipSecDeleteSecurityAssociation(this.uid, this.mConfig.getSourceAddress(), this.mConfig.getDestinationAddress(), spi, this.mConfig.getMarkValue(), this.mConfig.getMarkMask(), this.mConfig.getXfrmInterfaceId());
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(IpSecService.TAG, "Failed to delete SA with ID: " + this.mResourceId, e);
            }
            getResourceTracker().give();
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord, com.android.server.IpSecService.IResource
        public void invalidate() throws RemoteException {
            getUserRecord().removeTransformRecord(this.mResourceId);
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mTransformQuotaTracker;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord
        public String toString() {
            return "{super=" + super.toString() + ", mSocket=" + this.mSocket + ", mSpi.mResourceId=" + this.mSpi.mResourceId + ", mConfig=" + this.mConfig + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class SpiRecord extends OwnedResourceRecord {
        private final String mDestinationAddress;
        private boolean mOwnedByTransform;
        private final String mSourceAddress;
        private int mSpi;

        SpiRecord(int resourceId, String sourceAddress, String destinationAddress, int spi) {
            super(resourceId);
            this.mOwnedByTransform = false;
            this.mSourceAddress = sourceAddress;
            this.mDestinationAddress = destinationAddress;
            this.mSpi = spi;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord, com.android.server.IpSecService.IResource
        public void freeUnderlyingResources() {
            try {
                if (!this.mOwnedByTransform) {
                    IpSecService.this.mSrvConfig.getNetdInstance().ipSecDeleteSecurityAssociation(this.uid, this.mSourceAddress, this.mDestinationAddress, this.mSpi, 0, 0, 0);
                }
            } catch (ServiceSpecificException | RemoteException e) {
                Log.e(IpSecService.TAG, "Failed to delete SPI reservation with ID: " + this.mResourceId, e);
            }
            this.mSpi = 0;
            getResourceTracker().give();
        }

        public int getSpi() {
            return this.mSpi;
        }

        public String getDestinationAddress() {
            return this.mDestinationAddress;
        }

        public void setOwnedByTransform() {
            if (this.mOwnedByTransform) {
                throw new IllegalStateException("Cannot own an SPI twice!");
            }
            this.mOwnedByTransform = true;
        }

        public boolean getOwnedByTransform() {
            return this.mOwnedByTransform;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord, com.android.server.IpSecService.IResource
        public void invalidate() throws RemoteException {
            getUserRecord().removeSpiRecord(this.mResourceId);
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mSpiQuotaTracker;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord
        public String toString() {
            return "{super=" + super.toString() + ", mSpi=" + this.mSpi + ", mSourceAddress=" + this.mSourceAddress + ", mDestinationAddress=" + this.mDestinationAddress + ", mOwnedByTransform=" + this.mOwnedByTransform + "}";
        }
    }

    @VisibleForTesting
    int reserveNetId() {
        synchronized (this.mTunnelNetIds) {
            for (int i = 0; i < 1024; i++) {
                int index = this.mNextTunnelNetIdIndex;
                int netId = TUN_INTF_NETID_START + index;
                int i2 = this.mNextTunnelNetIdIndex + 1;
                this.mNextTunnelNetIdIndex = i2;
                if (i2 >= 1024) {
                    this.mNextTunnelNetIdIndex = 0;
                }
                if (!this.mTunnelNetIds.get(netId)) {
                    this.mTunnelNetIds.put(netId, true);
                    return netId;
                }
            }
            throw new IllegalStateException("No free netIds to allocate");
        }
    }

    @VisibleForTesting
    void releaseNetId(int netId) {
        synchronized (this.mTunnelNetIds) {
            this.mTunnelNetIds.delete(netId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class TunnelInterfaceRecord extends OwnedResourceRecord {
        private final int mIfId;
        private final int mIkey;
        private final String mInterfaceName;
        private final String mLocalAddress;
        private final int mOkey;
        private final String mRemoteAddress;
        private final Network mUnderlyingNetwork;

        TunnelInterfaceRecord(int resourceId, String interfaceName, Network underlyingNetwork, String localAddr, String remoteAddr, int ikey, int okey, int intfId) {
            super(resourceId);
            this.mInterfaceName = interfaceName;
            this.mUnderlyingNetwork = underlyingNetwork;
            this.mLocalAddress = localAddr;
            this.mRemoteAddress = remoteAddr;
            this.mIkey = ikey;
            this.mOkey = okey;
            this.mIfId = intfId;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord, com.android.server.IpSecService.IResource
        public void freeUnderlyingResources() {
            int[] iArr;
            try {
                INetd netd = IpSecService.this.mSrvConfig.getNetdInstance();
                netd.ipSecRemoveTunnelInterface(this.mInterfaceName);
                for (int selAddrFamily : IpSecService.ADDRESS_FAMILIES) {
                    netd.ipSecDeleteSecurityPolicy(this.uid, selAddrFamily, 1, this.mOkey, -1, this.mIfId);
                    netd.ipSecDeleteSecurityPolicy(this.uid, selAddrFamily, 0, this.mIkey, -1, this.mIfId);
                }
            } catch (ServiceSpecificException | RemoteException e) {
                Log.e(IpSecService.TAG, "Failed to delete VTI with interface name: " + this.mInterfaceName + " and id: " + this.mResourceId, e);
            }
            getResourceTracker().give();
            IpSecService.this.releaseNetId(this.mIkey);
            IpSecService.this.releaseNetId(this.mOkey);
        }

        public String getInterfaceName() {
            return this.mInterfaceName;
        }

        public Network getUnderlyingNetwork() {
            return this.mUnderlyingNetwork;
        }

        public String getLocalAddress() {
            return this.mLocalAddress;
        }

        public String getRemoteAddress() {
            return this.mRemoteAddress;
        }

        public int getIkey() {
            return this.mIkey;
        }

        public int getOkey() {
            return this.mOkey;
        }

        public int getIfId() {
            return this.mIfId;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mTunnelQuotaTracker;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord, com.android.server.IpSecService.IResource
        public void invalidate() {
            getUserRecord().removeTunnelInterfaceRecord(this.mResourceId);
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord
        public String toString() {
            return "{super=" + super.toString() + ", mInterfaceName=" + this.mInterfaceName + ", mUnderlyingNetwork=" + this.mUnderlyingNetwork + ", mLocalAddress=" + this.mLocalAddress + ", mRemoteAddress=" + this.mRemoteAddress + ", mIkey=" + this.mIkey + ", mOkey=" + this.mOkey + "}";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes.dex */
    public final class EncapSocketRecord extends OwnedResourceRecord {
        private final int mPort;
        private FileDescriptor mSocket;

        EncapSocketRecord(int resourceId, FileDescriptor socket, int port) {
            super(resourceId);
            this.mSocket = socket;
            this.mPort = port;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord, com.android.server.IpSecService.IResource
        public void freeUnderlyingResources() {
            Log.d(IpSecService.TAG, "Closing port " + this.mPort);
            IoUtils.closeQuietly(this.mSocket);
            this.mSocket = null;
            getResourceTracker().give();
        }

        public int getPort() {
            return this.mPort;
        }

        public FileDescriptor getFileDescriptor() {
            return this.mSocket;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord
        protected ResourceTracker getResourceTracker() {
            return getUserRecord().mSocketQuotaTracker;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord, com.android.server.IpSecService.IResource
        public void invalidate() {
            getUserRecord().removeEncapSocketRecord(this.mResourceId);
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord
        public String toString() {
            return "{super=" + super.toString() + ", mSocket=" + this.mSocket + ", mPort=" + this.mPort + "}";
        }
    }

    private IpSecService(Context context) {
        this(context, IpSecServiceConfiguration.GETSRVINSTANCE);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static IpSecService create(Context context) throws InterruptedException {
        IpSecService service = new IpSecService(context);
        service.connectNativeNetdService();
        return service;
    }

    private AppOpsManager getAppOpsManager() {
        AppOpsManager appOps = (AppOpsManager) this.mContext.getSystemService("appops");
        if (appOps == null) {
            throw new RuntimeException("System Server couldn't get AppOps");
        }
        return appOps;
    }

    @VisibleForTesting
    public IpSecService(Context context, IpSecServiceConfiguration config) {
        this(context, config, new UidFdTagger() { // from class: com.android.server.-$$Lambda$IpSecService$AnqunmSwm_yQvDDEPg-gokhVs5M
            @Override // com.android.server.IpSecService.UidFdTagger
            public final void tag(FileDescriptor fileDescriptor, int i) {
                IpSecService.lambda$new$0(fileDescriptor, i);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$new$0(FileDescriptor fd, int uid) throws IOException {
        try {
            TrafficStats.setThreadStatsUid(uid);
            TrafficStats.tagFileDescriptor(fd);
        } finally {
            TrafficStats.clearThreadStatsUid();
        }
    }

    @VisibleForTesting
    public IpSecService(Context context, IpSecServiceConfiguration config, UidFdTagger uidFdTagger) {
        this.mNextResourceId = 1;
        this.mUserResourceTracker = new UserResourceTracker();
        this.mTunnelNetIds = new SparseBooleanArray();
        this.mNextTunnelNetIdIndex = 0;
        this.mContext = context;
        this.mSrvConfig = config;
        this.mUidFdTagger = uidFdTagger;
    }

    public void systemReady() {
        if (isNetdAlive()) {
            Slog.d(TAG, "IpSecService is ready");
        } else {
            Slog.wtf(TAG, "IpSecService not ready: failed to connect to NetD Native Service!");
        }
    }

    /* JADX WARN: Type inference failed for: r0v0, types: [com.android.server.IpSecService$1] */
    private void connectNativeNetdService() {
        new Thread() { // from class: com.android.server.IpSecService.1
            @Override // java.lang.Thread, java.lang.Runnable
            public void run() {
                synchronized (IpSecService.this) {
                    NetdService.get(5000L);
                }
            }
        }.start();
    }

    synchronized boolean isNetdAlive() {
        try {
            INetd netd = this.mSrvConfig.getNetdInstance();
            if (netd == null) {
                return false;
            }
            return netd.isAlive();
        } catch (RemoteException e) {
            return false;
        }
    }

    private static void checkInetAddress(String inetAddress) {
        if (TextUtils.isEmpty(inetAddress)) {
            throw new IllegalArgumentException("Unspecified address");
        }
        InetAddress checkAddr = NetworkUtils.numericToInetAddress(inetAddress);
        if (checkAddr.isAnyLocalAddress()) {
            throw new IllegalArgumentException("Inappropriate wildcard address: " + inetAddress);
        }
    }

    private static void checkDirection(int direction) {
        if (direction == 0 || direction == 1) {
            return;
        }
        throw new IllegalArgumentException("Invalid Direction: " + direction);
    }

    /* JADX WARN: Removed duplicated region for block: B:44:0x00a8 A[Catch: all -> 0x00b2, TRY_LEAVE, TryCatch #1 {, blocks: (B:4:0x0007, B:9:0x0011, B:10:0x0018, B:11:0x0019, B:13:0x0033, B:15:0x003b, B:18:0x0043, B:20:0x004d, B:22:0x0052, B:23:0x0086, B:37:0x0099, B:38:0x009d, B:42:0x00a2, B:44:0x00a8, B:47:0x00b1), top: B:51:0x0007 }] */
    /* JADX WARN: Removed duplicated region for block: B:47:0x00b1 A[Catch: all -> 0x00b2, TRY_ENTER, TRY_LEAVE, TryCatch #1 {, blocks: (B:4:0x0007, B:9:0x0011, B:10:0x0018, B:11:0x0019, B:13:0x0033, B:15:0x003b, B:18:0x0043, B:20:0x004d, B:22:0x0052, B:23:0x0086, B:37:0x0099, B:38:0x009d, B:42:0x00a2, B:44:0x00a8, B:47:0x00b1), top: B:51:0x0007 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public synchronized android.net.IpSecSpiResponse allocateSecurityParameterIndex(java.lang.String r17, int r18, android.os.IBinder r19) throws android.os.RemoteException {
        /*
            r16 = this;
            r7 = r16
            r8 = r18
            r9 = r19
            monitor-enter(r16)
            checkInetAddress(r17)     // Catch: java.lang.Throwable -> Lb2
            if (r8 <= 0) goto L19
            r0 = 256(0x100, float:3.59E-43)
            if (r8 < r0) goto L11
            goto L19
        L11:
            java.lang.IllegalArgumentException r0 = new java.lang.IllegalArgumentException     // Catch: java.lang.Throwable -> Lb2
            java.lang.String r1 = "ESP SPI must not be in the range of 0-255."
            r0.<init>(r1)     // Catch: java.lang.Throwable -> Lb2
            throw r0     // Catch: java.lang.Throwable -> Lb2
        L19:
            java.lang.String r0 = "Null Binder passed to allocateSecurityParameterIndex"
            com.android.internal.util.Preconditions.checkNotNull(r9, r0)     // Catch: java.lang.Throwable -> Lb2
            int r0 = android.os.Binder.getCallingUid()     // Catch: java.lang.Throwable -> Lb2
            r10 = r0
            com.android.server.IpSecService$UserResourceTracker r0 = r7.mUserResourceTracker     // Catch: java.lang.Throwable -> Lb2
            com.android.server.IpSecService$UserRecord r0 = r0.getUserRecord(r10)     // Catch: java.lang.Throwable -> Lb2
            r11 = r0
            int r0 = r7.mNextResourceId     // Catch: java.lang.Throwable -> Lb2
            int r1 = r0 + 1
            r7.mNextResourceId = r1     // Catch: java.lang.Throwable -> Lb2
            r12 = r0
            r1 = 0
            r13 = -1
            com.android.server.IpSecService$ResourceTracker r0 = r11.mSpiQuotaTracker     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            boolean r0 = r0.isAvailable()     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            if (r0 != 0) goto L43
            android.net.IpSecSpiResponse r0 = new android.net.IpSecSpiResponse     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            r2 = 1
            r0.<init>(r2, r13, r1)     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            monitor-exit(r16)
            return r0
        L43:
            com.android.server.IpSecService$IpSecServiceConfiguration r0 = r7.mSrvConfig     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            android.net.INetd r0 = r0.getNetdInstance()     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            java.lang.String r2 = ""
            r14 = r17
            int r0 = r0.ipSecAllocateSpi(r10, r2, r14, r8)     // Catch: android.os.RemoteException -> L91 android.os.ServiceSpecificException -> L93 java.lang.Throwable -> Lb2
            r15 = r0
            java.lang.String r0 = "IpSecService"
            java.lang.StringBuilder r1 = new java.lang.StringBuilder     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            r1.<init>()     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            java.lang.String r2 = "Allocated SPI "
            r1.append(r2)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            r1.append(r15)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            java.lang.String r1 = r1.toString()     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            android.util.Log.d(r0, r1)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            com.android.server.IpSecService$RefcountedResourceArray<com.android.server.IpSecService$SpiRecord> r0 = r11.mSpiRecords     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            com.android.server.IpSecService$RefcountedResource r6 = new com.android.server.IpSecService$RefcountedResource     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            com.android.server.IpSecService$SpiRecord r5 = new com.android.server.IpSecService$SpiRecord     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            java.lang.String r4 = ""
            r1 = r5
            r2 = r16
            r3 = r12
            r13 = r5
            r5 = r17
            r8 = r6
            r6 = r15
            r1.<init>(r3, r4, r5, r6)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            r1 = 0
            com.android.server.IpSecService$RefcountedResource[] r2 = new com.android.server.IpSecService.RefcountedResource[r1]     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            r8.<init>(r13, r9, r2)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            r0.put(r12, r8)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            android.net.IpSecSpiResponse r0 = new android.net.IpSecSpiResponse     // Catch: java.lang.Throwable -> Lb2
            r0.<init>(r1, r12, r15)     // Catch: java.lang.Throwable -> Lb2
            monitor-exit(r16)
            return r0
        L8d:
            r0 = move-exception
            goto L99
        L8f:
            r0 = move-exception
            goto La2
        L91:
            r0 = move-exception
            goto L98
        L93:
            r0 = move-exception
            goto La1
        L95:
            r0 = move-exception
            r14 = r17
        L98:
            r15 = r1
        L99:
            java.lang.RuntimeException r1 = r0.rethrowFromSystemServer()     // Catch: java.lang.Throwable -> Lb2
            throw r1     // Catch: java.lang.Throwable -> Lb2
        L9e:
            r0 = move-exception
            r14 = r17
        La1:
            r15 = r1
        La2:
            int r1 = r0.errorCode     // Catch: java.lang.Throwable -> Lb2
            int r2 = android.system.OsConstants.ENOENT     // Catch: java.lang.Throwable -> Lb2
            if (r1 != r2) goto Lb1
            android.net.IpSecSpiResponse r1 = new android.net.IpSecSpiResponse     // Catch: java.lang.Throwable -> Lb2
            r2 = 2
            r3 = -1
            r1.<init>(r2, r3, r15)     // Catch: java.lang.Throwable -> Lb2
            monitor-exit(r16)
            return r1
        Lb1:
            throw r0     // Catch: java.lang.Throwable -> Lb2
        Lb2:
            r0 = move-exception
            monitor-exit(r16)
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.IpSecService.allocateSecurityParameterIndex(java.lang.String, int, android.os.IBinder):android.net.IpSecSpiResponse");
    }

    private void releaseResource(RefcountedResourceArray resArray, int resourceId) throws RemoteException {
        resArray.getRefcountedResourceOrThrow(resourceId).userRelease();
    }

    public synchronized void releaseSecurityParameterIndex(int resourceId) throws RemoteException {
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mSpiRecords, resourceId);
    }

    private int bindToRandomPort(FileDescriptor sockFd) throws IOException {
        for (int i = 10; i > 0; i--) {
            try {
                FileDescriptor probeSocket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP);
                Os.bind(probeSocket, INADDR_ANY, 0);
                int port = ((InetSocketAddress) Os.getsockname(probeSocket)).getPort();
                Os.close(probeSocket);
                Log.v(TAG, "Binding to port " + port);
                Os.bind(sockFd, INADDR_ANY, port);
                return port;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EADDRINUSE) {
                    throw e.rethrowAsIOException();
                }
            }
        }
        throw new IOException("Failed 10 attempts to bind to a port");
    }

    public synchronized IpSecUdpEncapResponse openUdpEncapsulationSocket(int port, IBinder binder) throws RemoteException {
        if (port != 0 && (port < 1024 || port > PORT_MAX)) {
            throw new IllegalArgumentException("Specified port number must be a valid non-reserved UDP port");
        }
        Preconditions.checkNotNull(binder, "Null Binder passed to openUdpEncapsulationSocket");
        int callingUid = Binder.getCallingUid();
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(callingUid);
        int resourceId = this.mNextResourceId;
        this.mNextResourceId = resourceId + 1;
        try {
            if (!userRecord.mSocketQuotaTracker.isAvailable()) {
                return new IpSecUdpEncapResponse(1);
            }
            FileDescriptor sockFd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP);
            this.mUidFdTagger.tag(sockFd, callingUid);
            Os.setsockoptInt(sockFd, OsConstants.IPPROTO_UDP, OsConstants.UDP_ENCAP, OsConstants.UDP_ENCAP_ESPINUDP);
            this.mSrvConfig.getNetdInstance().ipSecSetEncapSocketOwner(new ParcelFileDescriptor(sockFd), callingUid);
            if (port != 0) {
                Log.v(TAG, "Binding to port " + port);
                Os.bind(sockFd, INADDR_ANY, port);
            } else {
                port = bindToRandomPort(sockFd);
            }
            userRecord.mEncapSocketRecords.put(resourceId, new RefcountedResource<>(new EncapSocketRecord(resourceId, sockFd, port), binder, new RefcountedResource[0]));
            return new IpSecUdpEncapResponse(0, resourceId, port, sockFd);
        } catch (ErrnoException | IOException e) {
            IoUtils.closeQuietly((FileDescriptor) null);
            return new IpSecUdpEncapResponse(1);
        }
    }

    public synchronized void closeUdpEncapsulationSocket(int resourceId) throws RemoteException {
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mEncapSocketRecords, resourceId);
    }

    public synchronized IpSecTunnelInterfaceResponse createTunnelInterface(String localAddr, String remoteAddr, Network underlyingNetwork, IBinder binder, String callingPackage) {
        int okey;
        int ikey;
        int okey2;
        int ikey2;
        enforceTunnelFeatureAndPermissions(callingPackage);
        Preconditions.checkNotNull(binder, "Null Binder passed to createTunnelInterface");
        Preconditions.checkNotNull(underlyingNetwork, "No underlying network was specified");
        checkInetAddress(localAddr);
        checkInetAddress(remoteAddr);
        int callerUid = Binder.getCallingUid();
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(callerUid);
        if (!userRecord.mTunnelQuotaTracker.isAvailable()) {
            return new IpSecTunnelInterfaceResponse(1);
        }
        int i = this.mNextResourceId;
        this.mNextResourceId = i + 1;
        int resourceId = i;
        int ikey3 = reserveNetId();
        int okey3 = reserveNetId();
        String intfName = String.format("%s%d", INetd.IPSEC_INTERFACE_PREFIX, Integer.valueOf(resourceId));
        try {
            try {
                try {
                    INetd netd = this.mSrvConfig.getNetdInstance();
                    netd.ipSecAddTunnelInterface(intfName, localAddr, remoteAddr, ikey3, okey3, resourceId);
                    int[] iArr = ADDRESS_FAMILIES;
                    int length = iArr.length;
                    int i2 = 0;
                    while (i2 < length) {
                        try {
                            int selAddrFamily = iArr[i2];
                            int i3 = i2;
                            int i4 = length;
                            String intfName2 = intfName;
                            okey = okey3;
                            ikey = ikey3;
                            int resourceId2 = resourceId;
                            UserRecord userRecord2 = userRecord;
                            try {
                                netd.ipSecAddSecurityPolicy(callerUid, selAddrFamily, 1, localAddr, remoteAddr, 0, okey, -1, resourceId2);
                                int callerUid2 = callerUid;
                                try {
                                    netd.ipSecAddSecurityPolicy(callerUid2, selAddrFamily, 0, remoteAddr, localAddr, 0, ikey, -1, resourceId2);
                                    i2 = i3 + 1;
                                    userRecord = userRecord2;
                                    length = i4;
                                    intfName = intfName2;
                                    okey3 = okey;
                                    ikey3 = ikey;
                                    resourceId = resourceId2;
                                    callerUid = callerUid2;
                                } catch (RemoteException e) {
                                    e = e;
                                    okey2 = okey;
                                    ikey2 = ikey;
                                    releaseNetId(ikey2);
                                    releaseNetId(okey2);
                                    throw e.rethrowFromSystemServer();
                                } catch (Throwable th) {
                                    t = th;
                                    releaseNetId(ikey);
                                    releaseNetId(okey);
                                    throw t;
                                }
                            } catch (RemoteException e2) {
                                e = e2;
                                okey2 = okey;
                                ikey2 = ikey;
                            } catch (Throwable th2) {
                                t = th2;
                            }
                        } catch (RemoteException e3) {
                            e = e3;
                            okey2 = okey3;
                            ikey2 = ikey3;
                        } catch (Throwable th3) {
                            t = th3;
                            okey = okey3;
                            ikey = ikey3;
                        }
                    }
                    String intfName3 = intfName;
                    okey = okey3;
                    ikey = ikey3;
                    int resourceId3 = resourceId;
                    try {
                        try {
                            try {
                                userRecord.mTunnelInterfaceRecords.put(resourceId3, new RefcountedResource<>(new TunnelInterfaceRecord(resourceId3, intfName3, underlyingNetwork, localAddr, remoteAddr, ikey, okey, resourceId3), binder, new RefcountedResource[0]));
                                try {
                                    return new IpSecTunnelInterfaceResponse(0, resourceId3, intfName3);
                                } catch (RemoteException e4) {
                                    e = e4;
                                    okey2 = okey;
                                    ikey2 = ikey;
                                    releaseNetId(ikey2);
                                    releaseNetId(okey2);
                                    throw e.rethrowFromSystemServer();
                                } catch (Throwable th4) {
                                    t = th4;
                                    releaseNetId(ikey);
                                    releaseNetId(okey);
                                    throw t;
                                }
                            } catch (RemoteException e5) {
                                e = e5;
                                okey2 = okey;
                                ikey2 = ikey;
                            } catch (Throwable th5) {
                                t = th5;
                            }
                        } catch (RemoteException e6) {
                            e = e6;
                            okey2 = okey;
                            ikey2 = ikey;
                        } catch (Throwable th6) {
                            t = th6;
                        }
                    } catch (RemoteException e7) {
                        e = e7;
                        okey2 = okey;
                        ikey2 = ikey;
                    } catch (Throwable th7) {
                        t = th7;
                    }
                } catch (RemoteException e8) {
                    e = e8;
                    okey2 = okey3;
                    ikey2 = ikey3;
                }
            } catch (Throwable th8) {
                t = th8;
                okey = okey3;
                ikey = ikey3;
            }
        } catch (RemoteException e9) {
            e = e9;
            okey2 = okey3;
            ikey2 = ikey3;
        }
    }

    public synchronized void addAddressToTunnelInterface(int tunnelResourceId, LinkAddress localAddr, String callingPackage) {
        enforceTunnelFeatureAndPermissions(callingPackage);
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        TunnelInterfaceRecord tunnelInterfaceInfo = userRecord.mTunnelInterfaceRecords.getResourceOrThrow(tunnelResourceId);
        try {
            this.mSrvConfig.getNetdInstance().interfaceAddAddress(tunnelInterfaceInfo.mInterfaceName, localAddr.getAddress().getHostAddress(), localAddr.getPrefixLength());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public synchronized void removeAddressFromTunnelInterface(int tunnelResourceId, LinkAddress localAddr, String callingPackage) {
        enforceTunnelFeatureAndPermissions(callingPackage);
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        TunnelInterfaceRecord tunnelInterfaceInfo = userRecord.mTunnelInterfaceRecords.getResourceOrThrow(tunnelResourceId);
        try {
            this.mSrvConfig.getNetdInstance().interfaceDelAddress(tunnelInterfaceInfo.mInterfaceName, localAddr.getAddress().getHostAddress(), localAddr.getPrefixLength());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public synchronized void deleteTunnelInterface(int resourceId, String callingPackage) throws RemoteException {
        enforceTunnelFeatureAndPermissions(callingPackage);
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mTunnelInterfaceRecords, resourceId);
    }

    @VisibleForTesting
    void validateAlgorithms(IpSecConfig config) throws IllegalArgumentException {
        IpSecAlgorithm auth = config.getAuthentication();
        IpSecAlgorithm crypt = config.getEncryption();
        IpSecAlgorithm aead = config.getAuthenticatedEncryption();
        boolean z = false;
        Preconditions.checkArgument((aead == null && crypt == null && auth == null) ? false : true, "No Encryption or Authentication algorithms specified");
        Preconditions.checkArgument(auth == null || auth.isAuthentication(), "Unsupported algorithm for Authentication");
        Preconditions.checkArgument(crypt == null || crypt.isEncryption(), "Unsupported algorithm for Encryption");
        Preconditions.checkArgument(aead == null || aead.isAead(), "Unsupported algorithm for Authenticated Encryption");
        if (aead == null || (auth == null && crypt == null)) {
            z = true;
        }
        Preconditions.checkArgument(z, "Authenticated Encryption is mutually exclusive with other Authentication or Encryption algorithms");
    }

    private int getFamily(String inetAddress) {
        int family = OsConstants.AF_UNSPEC;
        InetAddress checkAddress = NetworkUtils.numericToInetAddress(inetAddress);
        if (checkAddress instanceof Inet4Address) {
            int family2 = OsConstants.AF_INET;
            return family2;
        } else if (checkAddress instanceof Inet6Address) {
            int family3 = OsConstants.AF_INET6;
            return family3;
        } else {
            return family;
        }
    }

    private void checkIpSecConfig(IpSecConfig config) {
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        int encapType = config.getEncapType();
        if (encapType != 0) {
            if (encapType == 1 || encapType == 2) {
                userRecord.mEncapSocketRecords.getResourceOrThrow(config.getEncapSocketResourceId());
                int port = config.getEncapRemotePort();
                if (port <= 0 || port > PORT_MAX) {
                    throw new IllegalArgumentException("Invalid remote UDP port: " + port);
                }
            } else {
                throw new IllegalArgumentException("Invalid Encap Type: " + config.getEncapType());
            }
        }
        validateAlgorithms(config);
        SpiRecord s = userRecord.mSpiRecords.getResourceOrThrow(config.getSpiResourceId());
        if (s.getOwnedByTransform()) {
            throw new IllegalStateException("SPI already in use; cannot be used in new Transforms");
        }
        if (TextUtils.isEmpty(config.getDestinationAddress())) {
            config.setDestinationAddress(s.getDestinationAddress());
        }
        if (!config.getDestinationAddress().equals(s.getDestinationAddress())) {
            throw new IllegalArgumentException("Mismatched remote addresseses.");
        }
        checkInetAddress(config.getDestinationAddress());
        checkInetAddress(config.getSourceAddress());
        String sourceAddress = config.getSourceAddress();
        String destinationAddress = config.getDestinationAddress();
        int sourceFamily = getFamily(sourceAddress);
        int destinationFamily = getFamily(destinationAddress);
        if (sourceFamily != destinationFamily) {
            throw new IllegalArgumentException("Source address (" + sourceAddress + ") and destination address (" + destinationAddress + ") have different address families.");
        } else if (config.getEncapType() != 0 && sourceFamily != OsConstants.AF_INET) {
            throw new IllegalArgumentException("UDP Encapsulation is not supported for this address family");
        } else {
            int mode = config.getMode();
            if (mode != 0 && mode != 1) {
                throw new IllegalArgumentException("Invalid IpSecTransform.mode: " + config.getMode());
            }
            config.setMarkValue(0);
            config.setMarkMask(0);
        }
    }

    private void enforceTunnelFeatureAndPermissions(String callingPackage) {
        if (!this.mContext.getPackageManager().hasSystemFeature("android.software.ipsec_tunnels")) {
            throw new UnsupportedOperationException("IPsec Tunnel Mode requires PackageManager.FEATURE_IPSEC_TUNNELS");
        }
        Preconditions.checkNotNull(callingPackage, "Null calling package cannot create IpSec tunnels");
        int noteOp = getAppOpsManager().noteOp(TUNNEL_OP, Binder.getCallingUid(), callingPackage);
        if (noteOp != 0) {
            if (noteOp == 3) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_IPSEC_TUNNELS", TAG);
                return;
            }
            throw new SecurityException("Request to ignore AppOps for non-legacy API");
        }
    }

    private void createOrUpdateTransform(IpSecConfig c, int resourceId, SpiRecord spiRecord, EncapSocketRecord socketRecord) throws RemoteException {
        int encapLocalPort;
        int encapRemotePort;
        String cryptName;
        int encapType = c.getEncapType();
        if (encapType == 0) {
            encapLocalPort = 0;
            encapRemotePort = 0;
        } else {
            int encapLocalPort2 = socketRecord.getPort();
            int encapRemotePort2 = c.getEncapRemotePort();
            encapLocalPort = encapLocalPort2;
            encapRemotePort = encapRemotePort2;
        }
        IpSecAlgorithm auth = c.getAuthentication();
        IpSecAlgorithm crypt = c.getEncryption();
        IpSecAlgorithm authCrypt = c.getAuthenticatedEncryption();
        if (crypt == null) {
            cryptName = authCrypt == null ? "ecb(cipher_null)" : "";
        } else {
            String cryptName2 = crypt.getName();
            cryptName = cryptName2;
        }
        this.mSrvConfig.getNetdInstance().ipSecAddSecurityAssociation(Binder.getCallingUid(), c.getMode(), c.getSourceAddress(), c.getDestinationAddress(), c.getNetwork() != null ? c.getNetwork().netId : 0, spiRecord.getSpi(), c.getMarkValue(), c.getMarkMask(), auth != null ? auth.getName() : "", auth != null ? auth.getKey() : new byte[0], auth != null ? auth.getTruncationLengthBits() : 0, cryptName, crypt != null ? crypt.getKey() : new byte[0], crypt != null ? crypt.getTruncationLengthBits() : 0, authCrypt != null ? authCrypt.getName() : "", authCrypt != null ? authCrypt.getKey() : new byte[0], authCrypt != null ? authCrypt.getTruncationLengthBits() : 0, encapType, encapLocalPort, encapRemotePort, c.getXfrmInterfaceId());
    }

    public synchronized IpSecTransformResponse createTransform(IpSecConfig c, IBinder binder, String callingPackage) throws RemoteException {
        EncapSocketRecord socketRecord;
        Preconditions.checkNotNull(c);
        if (c.getMode() == 1) {
            enforceTunnelFeatureAndPermissions(callingPackage);
        }
        checkIpSecConfig(c);
        Preconditions.checkNotNull(binder, "Null Binder passed to createTransform");
        int resourceId = this.mNextResourceId;
        this.mNextResourceId = resourceId + 1;
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        List<RefcountedResource> dependencies = new ArrayList<>();
        if (!userRecord.mTransformQuotaTracker.isAvailable()) {
            return new IpSecTransformResponse(1);
        }
        if (c.getEncapType() == 0) {
            socketRecord = null;
        } else {
            RefcountedResource<EncapSocketRecord> refcountedSocketRecord = userRecord.mEncapSocketRecords.getRefcountedResourceOrThrow(c.getEncapSocketResourceId());
            dependencies.add(refcountedSocketRecord);
            EncapSocketRecord socketRecord2 = refcountedSocketRecord.getResource();
            socketRecord = socketRecord2;
        }
        RefcountedResource<SpiRecord> refcountedSpiRecord = userRecord.mSpiRecords.getRefcountedResourceOrThrow(c.getSpiResourceId());
        dependencies.add(refcountedSpiRecord);
        SpiRecord spiRecord = refcountedSpiRecord.getResource();
        createOrUpdateTransform(c, resourceId, spiRecord, socketRecord);
        userRecord.mTransformRecords.put(resourceId, new RefcountedResource<>(new TransformRecord(resourceId, c, spiRecord, socketRecord), binder, (RefcountedResource[]) dependencies.toArray(new RefcountedResource[dependencies.size()])));
        return new IpSecTransformResponse(0, resourceId);
    }

    public synchronized void deleteTransform(int resourceId) throws RemoteException {
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mTransformRecords, resourceId);
    }

    public synchronized void applyTransportModeTransform(ParcelFileDescriptor socket, int direction, int resourceId) throws RemoteException {
        int callingUid = Binder.getCallingUid();
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(callingUid);
        checkDirection(direction);
        TransformRecord info = userRecord.mTransformRecords.getResourceOrThrow(resourceId);
        if (info.pid != getCallingPid() || info.uid != callingUid) {
            throw new SecurityException("Only the owner of an IpSec Transform may apply it!");
        }
        IpSecConfig c = info.getConfig();
        Preconditions.checkArgument(c.getMode() == 0, "Transform mode was not Transport mode; cannot be applied to a socket");
        this.mSrvConfig.getNetdInstance().ipSecApplyTransportModeTransform(socket, callingUid, direction, c.getSourceAddress(), c.getDestinationAddress(), info.getSpiRecord().getSpi());
    }

    public synchronized void removeTransportModeTransforms(ParcelFileDescriptor socket) throws RemoteException {
        this.mSrvConfig.getNetdInstance().ipSecRemoveTransportModeTransform(socket);
    }

    /* JADX WARN: Removed duplicated region for block: B:45:0x0119 A[Catch: all -> 0x0124, TryCatch #0 {, blocks: (B:5:0x0009, B:9:0x0042, B:11:0x004e, B:13:0x005e, B:15:0x006d, B:19:0x007a, B:21:0x0083, B:25:0x009c, B:27:0x00a1, B:29:0x00d8, B:43:0x0113, B:45:0x0119, B:46:0x0122, B:47:0x0123, B:36:0x0105, B:16:0x0073), top: B:51:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:47:0x0123 A[Catch: all -> 0x0124, TRY_LEAVE, TryCatch #0 {, blocks: (B:5:0x0009, B:9:0x0042, B:11:0x004e, B:13:0x005e, B:15:0x006d, B:19:0x007a, B:21:0x0083, B:25:0x009c, B:27:0x00a1, B:29:0x00d8, B:43:0x0113, B:45:0x0119, B:46:0x0122, B:47:0x0123, B:36:0x0105, B:16:0x0073), top: B:51:0x0009 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public synchronized void applyTunnelModeTransform(int r28, int r29, int r30, java.lang.String r31) throws android.os.RemoteException {
        /*
            Method dump skipped, instructions count: 295
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.IpSecService.applyTunnelModeTransform(int, int, int, java.lang.String):void");
    }

    protected synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        this.mContext.enforceCallingOrSelfPermission("android.permission.DUMP", TAG);
        pw.println("IpSecService dump:");
        StringBuilder sb = new StringBuilder();
        sb.append("NetdNativeService Connection: ");
        sb.append(isNetdAlive() ? "alive" : "dead");
        pw.println(sb.toString());
        pw.println();
        pw.println("mUserResourceTracker:");
        pw.println(this.mUserResourceTracker);
    }
}
