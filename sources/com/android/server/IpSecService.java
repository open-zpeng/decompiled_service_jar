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
import com.android.server.backup.BackupManagerConstants;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
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
    private static final int MAX_PORT_BIND_ATTEMPTS = 10;
    private static final int NETD_FETCH_TIMEOUT_MS = 5000;
    private static final String NETD_SERVICE_NAME = "netd";
    static final int PORT_MAX = 65535;
    @VisibleForTesting
    static final int TUN_INTF_NETID_RANGE = 1024;
    @VisibleForTesting
    static final int TUN_INTF_NETID_START = 64512;
    private final Context mContext;
    @GuardedBy("IpSecService.this")
    private int mNextResourceId;
    private int mNextTunnelNetIdIndex;
    private final IpSecServiceConfiguration mSrvConfig;
    private final SparseBooleanArray mTunnelNetIds;
    final UidFdTagger mUidFdTagger;
    @VisibleForTesting
    final UserResourceTracker mUserResourceTracker;
    private static final String TAG = "IpSecService";
    private static final boolean DBG = Log.isLoggable(TAG, 3);
    private static final int[] DIRECTIONS = {1, 0};
    private static final String[] WILDCARD_ADDRESSES = {"0.0.0.0", "::"};

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

        @GuardedBy("IpSecService.this")
        public void userRelease() throws RemoteException {
            if (this.mBinder == null) {
                return;
            }
            this.mBinder.unlinkToDeath(this, 0);
            this.mBinder = null;
            this.mResource.invalidate();
            releaseReference();
        }

        @GuardedBy("IpSecService.this")
        @VisibleForTesting
        public void releaseReference() throws RemoteException {
            this.mRefCount--;
            if (this.mRefCount > 0) {
                return;
            }
            if (this.mRefCount < 0) {
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
                IpSecService.this.mSrvConfig.getNetdInstance().ipSecDeleteSecurityAssociation(this.mResourceId, this.mConfig.getSourceAddress(), this.mConfig.getDestinationAddress(), spi, this.mConfig.getMarkValue(), this.mConfig.getMarkMask());
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
                    IpSecService.this.mSrvConfig.getNetdInstance().ipSecDeleteSecurityAssociation(this.mResourceId, this.mSourceAddress, this.mDestinationAddress, this.mSpi, 0, 0);
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
                try {
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
                } catch (Throwable th) {
                    throw th;
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
        private final int mIkey;
        private final String mInterfaceName;
        private final String mLocalAddress;
        private final int mOkey;
        private final String mRemoteAddress;
        private final Network mUnderlyingNetwork;

        TunnelInterfaceRecord(int resourceId, String interfaceName, Network underlyingNetwork, String localAddr, String remoteAddr, int ikey, int okey) {
            super(resourceId);
            this.mInterfaceName = interfaceName;
            this.mUnderlyingNetwork = underlyingNetwork;
            this.mLocalAddress = localAddr;
            this.mRemoteAddress = remoteAddr;
            this.mIkey = ikey;
            this.mOkey = okey;
        }

        @Override // com.android.server.IpSecService.OwnedResourceRecord, com.android.server.IpSecService.IResource
        public void freeUnderlyingResources() {
            String[] strArr;
            try {
                IpSecService.this.mSrvConfig.getNetdInstance().removeVirtualTunnelInterface(this.mInterfaceName);
                for (String wildcardAddr : IpSecService.WILDCARD_ADDRESSES) {
                    int[] iArr = IpSecService.DIRECTIONS;
                    int length = iArr.length;
                    for (int i = 0; i < length; i++) {
                        int direction = iArr[i];
                        int mark = direction == 0 ? this.mIkey : this.mOkey;
                        IpSecService.this.mSrvConfig.getNetdInstance().ipSecDeleteSecurityPolicy(0, direction, wildcardAddr, wildcardAddr, mark, -1);
                    }
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
        switch (direction) {
            case 0:
            case 1:
                return;
            default:
                throw new IllegalArgumentException("Invalid Direction: " + direction);
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:44:0x00a8 A[Catch: all -> 0x00b2, TRY_LEAVE, TryCatch #1 {, blocks: (B:4:0x0007, B:9:0x0011, B:10:0x0018, B:11:0x0019, B:13:0x0033, B:15:0x003b, B:18:0x0043, B:20:0x004d, B:22:0x0052, B:23:0x0086, B:37:0x0099, B:38:0x009d, B:42:0x00a2, B:44:0x00a8, B:47:0x00b1), top: B:51:0x0007 }] */
    /* JADX WARN: Removed duplicated region for block: B:47:0x00b1 A[Catch: all -> 0x00b2, TRY_ENTER, TRY_LEAVE, TryCatch #1 {, blocks: (B:4:0x0007, B:9:0x0011, B:10:0x0018, B:11:0x0019, B:13:0x0033, B:15:0x003b, B:18:0x0043, B:20:0x004d, B:22:0x0052, B:23:0x0086, B:37:0x0099, B:38:0x009d, B:42:0x00a2, B:44:0x00a8, B:47:0x00b1), top: B:51:0x0007 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public synchronized android.net.IpSecSpiResponse allocateSecurityParameterIndex(java.lang.String r18, int r19, android.os.IBinder r20) throws android.os.RemoteException {
        /*
            r17 = this;
            r7 = r17
            r8 = r19
            r9 = r20
            monitor-enter(r17)
            checkInetAddress(r18)     // Catch: java.lang.Throwable -> Lb2
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
            com.android.server.IpSecService$UserResourceTracker r0 = r7.mUserResourceTracker     // Catch: java.lang.Throwable -> Lb2
            int r1 = android.os.Binder.getCallingUid()     // Catch: java.lang.Throwable -> Lb2
            com.android.server.IpSecService$UserRecord r0 = r0.getUserRecord(r1)     // Catch: java.lang.Throwable -> Lb2
            r10 = r0
            int r0 = r7.mNextResourceId     // Catch: java.lang.Throwable -> Lb2
            int r1 = r0 + 1
            r7.mNextResourceId = r1     // Catch: java.lang.Throwable -> Lb2
            r11 = r0
            r0 = 0
            r1 = r0
            r12 = -1
            com.android.server.IpSecService$ResourceTracker r2 = r10.mSpiQuotaTracker     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            boolean r2 = r2.isAvailable()     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            if (r2 != 0) goto L43
            android.net.IpSecSpiResponse r0 = new android.net.IpSecSpiResponse     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            r2 = 1
            r0.<init>(r2, r12, r1)     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            monitor-exit(r17)
            return r0
        L43:
            com.android.server.IpSecService$IpSecServiceConfiguration r2 = r7.mSrvConfig     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            android.net.INetd r2 = r2.getNetdInstance()     // Catch: android.os.RemoteException -> L95 android.os.ServiceSpecificException -> L9e java.lang.Throwable -> Lb2
            java.lang.String r3 = ""
            r13 = r18
            int r2 = r2.ipSecAllocateSpi(r11, r3, r13, r8)     // Catch: android.os.RemoteException -> L91 android.os.ServiceSpecificException -> L93 java.lang.Throwable -> Lb2
            r14 = r2
            java.lang.String r1 = "IpSecService"
            java.lang.StringBuilder r2 = new java.lang.StringBuilder     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            r2.<init>()     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            java.lang.String r3 = "Allocated SPI "
            r2.append(r3)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            r2.append(r14)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            java.lang.String r2 = r2.toString()     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            android.util.Log.d(r1, r2)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            com.android.server.IpSecService$RefcountedResourceArray<com.android.server.IpSecService$SpiRecord> r15 = r10.mSpiRecords     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            com.android.server.IpSecService$RefcountedResource r6 = new com.android.server.IpSecService$RefcountedResource     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            com.android.server.IpSecService$SpiRecord r5 = new com.android.server.IpSecService$SpiRecord     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            java.lang.String r4 = ""
            r1 = r5
            r2 = r7
            r3 = r11
            r12 = r5
            r5 = r13
            r16 = r6
            r6 = r14
            r1.<init>(r3, r4, r5, r6)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            com.android.server.IpSecService$RefcountedResource[] r1 = new com.android.server.IpSecService.RefcountedResource[r0]     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            r2 = r16
            r2.<init>(r12, r9, r1)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            r15.put(r11, r2)     // Catch: android.os.RemoteException -> L8d android.os.ServiceSpecificException -> L8f java.lang.Throwable -> Lb2
            android.net.IpSecSpiResponse r1 = new android.net.IpSecSpiResponse     // Catch: java.lang.Throwable -> Lb2
            r1.<init>(r0, r11, r14)     // Catch: java.lang.Throwable -> Lb2
            monitor-exit(r17)
            return r1
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
            r13 = r18
        L98:
            r14 = r1
        L99:
            java.lang.RuntimeException r1 = r0.rethrowFromSystemServer()     // Catch: java.lang.Throwable -> Lb2
            throw r1     // Catch: java.lang.Throwable -> Lb2
        L9e:
            r0 = move-exception
            r13 = r18
        La1:
            r14 = r1
        La2:
            int r1 = r0.errorCode     // Catch: java.lang.Throwable -> Lb2
            int r2 = android.system.OsConstants.ENOENT     // Catch: java.lang.Throwable -> Lb2
            if (r1 != r2) goto Lb1
            android.net.IpSecSpiResponse r1 = new android.net.IpSecSpiResponse     // Catch: java.lang.Throwable -> Lb2
            r2 = 2
            r3 = -1
            r1.<init>(r2, r3, r14)     // Catch: java.lang.Throwable -> Lb2
            monitor-exit(r17)
            return r1
        Lb1:
            throw r0     // Catch: java.lang.Throwable -> Lb2
        Lb2:
            r0 = move-exception
            monitor-exit(r17)
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
        if (port != 0 && (port < 1024 || port > 65535)) {
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
            this.mSrvConfig.getNetdInstance().ipSecSetEncapSocketOwner(sockFd, callingUid);
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
        enforceTunnelPermissions(callingPackage);
        Preconditions.checkNotNull(binder, "Null Binder passed to createTunnelInterface");
        Preconditions.checkNotNull(underlyingNetwork, "No underlying network was specified");
        checkInetAddress(localAddr);
        checkInetAddress(remoteAddr);
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        int i = 1;
        if (!userRecord.mTunnelQuotaTracker.isAvailable()) {
            return new IpSecTunnelInterfaceResponse(1);
        }
        int resourceId = this.mNextResourceId;
        this.mNextResourceId = resourceId + 1;
        int ikey2 = reserveNetId();
        int okey3 = reserveNetId();
        String intfName = String.format("%s%d", INetd.IPSEC_INTERFACE_PREFIX, Integer.valueOf(resourceId));
        try {
            try {
                try {
                    int i2 = 0;
                    try {
                        this.mSrvConfig.getNetdInstance().addVirtualTunnelInterface(intfName, localAddr, remoteAddr, ikey2, okey3);
                        String[] strArr = WILDCARD_ADDRESSES;
                        int length = strArr.length;
                        int i3 = 0;
                        while (i3 < length) {
                            try {
                                String wildcardAddr = strArr[i3];
                                int[] iArr = DIRECTIONS;
                                int length2 = iArr.length;
                                int i4 = i2;
                                while (i4 < length2) {
                                    int direction = iArr[i4];
                                    int mark = direction == i ? okey3 : ikey2;
                                    this.mSrvConfig.getNetdInstance().ipSecAddSecurityPolicy(0, direction, wildcardAddr, wildcardAddr, 0, mark, -1);
                                    i4++;
                                    i = 1;
                                }
                                i3++;
                                i2 = 0;
                                i = 1;
                            } catch (RemoteException e) {
                                e = e;
                                okey2 = okey3;
                                ikey = ikey2;
                                releaseNetId(ikey);
                                releaseNetId(okey2);
                                throw e.rethrowFromSystemServer();
                            } catch (Throwable th) {
                                t = th;
                                okey = okey3;
                                ikey = ikey2;
                                releaseNetId(ikey);
                                releaseNetId(okey);
                                throw t;
                            }
                        }
                        okey = okey3;
                        ikey = ikey2;
                        try {
                            userRecord.mTunnelInterfaceRecords.put(resourceId, new RefcountedResource<>(new TunnelInterfaceRecord(resourceId, intfName, underlyingNetwork, localAddr, remoteAddr, ikey2, okey), binder, new RefcountedResource[0]));
                            try {
                                return new IpSecTunnelInterfaceResponse(0, resourceId, intfName);
                            } catch (RemoteException e2) {
                                e = e2;
                                okey2 = okey;
                                releaseNetId(ikey);
                                releaseNetId(okey2);
                                throw e.rethrowFromSystemServer();
                            } catch (Throwable th2) {
                                t = th2;
                                releaseNetId(ikey);
                                releaseNetId(okey);
                                throw t;
                            }
                        } catch (RemoteException e3) {
                            e = e3;
                            okey2 = okey;
                        } catch (Throwable th3) {
                            t = th3;
                        }
                    } catch (RemoteException e4) {
                        e = e4;
                        ikey = ikey2;
                        okey2 = okey3;
                    } catch (Throwable th4) {
                        t = th4;
                        okey = okey3;
                        ikey = ikey2;
                    }
                } catch (RemoteException e5) {
                    e = e5;
                    ikey = ikey2;
                    okey2 = okey3;
                }
            } catch (RemoteException e6) {
                e = e6;
                okey2 = okey3;
                ikey = ikey2;
            }
        } catch (Throwable th5) {
            t = th5;
            okey = okey3;
            ikey = ikey2;
        }
    }

    public synchronized void addAddressToTunnelInterface(int tunnelResourceId, LinkAddress localAddr, String callingPackage) {
        enforceTunnelPermissions(callingPackage);
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        TunnelInterfaceRecord tunnelInterfaceInfo = userRecord.mTunnelInterfaceRecords.getResourceOrThrow(tunnelResourceId);
        try {
            this.mSrvConfig.getNetdInstance().interfaceAddAddress(tunnelInterfaceInfo.mInterfaceName, localAddr.getAddress().getHostAddress(), localAddr.getPrefixLength());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public synchronized void removeAddressFromTunnelInterface(int tunnelResourceId, LinkAddress localAddr, String callingPackage) {
        enforceTunnelPermissions(callingPackage);
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        TunnelInterfaceRecord tunnelInterfaceInfo = userRecord.mTunnelInterfaceRecords.getResourceOrThrow(tunnelResourceId);
        try {
            this.mSrvConfig.getNetdInstance().interfaceDelAddress(tunnelInterfaceInfo.mInterfaceName, localAddr.getAddress().getHostAddress(), localAddr.getPrefixLength());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public synchronized void deleteTunnelInterface(int resourceId, String callingPackage) throws RemoteException {
        enforceTunnelPermissions(callingPackage);
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mTunnelInterfaceRecords, resourceId);
    }

    @VisibleForTesting
    void validateAlgorithms(IpSecConfig config) throws IllegalArgumentException {
        IpSecAlgorithm auth = config.getAuthentication();
        IpSecAlgorithm crypt = config.getEncryption();
        IpSecAlgorithm aead = config.getAuthenticatedEncryption();
        boolean z = true;
        Preconditions.checkArgument((aead == null && crypt == null && auth == null) ? false : true, "No Encryption or Authentication algorithms specified");
        Preconditions.checkArgument(auth == null || auth.isAuthentication(), "Unsupported algorithm for Authentication");
        Preconditions.checkArgument(crypt == null || crypt.isEncryption(), "Unsupported algorithm for Encryption");
        Preconditions.checkArgument(aead == null || aead.isAead(), "Unsupported algorithm for Authenticated Encryption");
        if (aead != null && (auth != null || crypt != null)) {
            z = false;
        }
        Preconditions.checkArgument(z, "Authenticated Encryption is mutually exclusive with other Authentication or Encryption algorithms");
    }

    private void checkIpSecConfig(IpSecConfig config) {
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        switch (config.getEncapType()) {
            case 0:
                break;
            default:
                throw new IllegalArgumentException("Invalid Encap Type: " + config.getEncapType());
            case 1:
            case 2:
                userRecord.mEncapSocketRecords.getResourceOrThrow(config.getEncapSocketResourceId());
                int port = config.getEncapRemotePort();
                if (port <= 0 || port > 65535) {
                    throw new IllegalArgumentException("Invalid remote UDP port: " + port);
                }
                break;
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
        switch (config.getMode()) {
            case 0:
            case 1:
                return;
            default:
                throw new IllegalArgumentException("Invalid IpSecTransform.mode: " + config.getMode());
        }
    }

    private void enforceTunnelPermissions(String callingPackage) {
        Preconditions.checkNotNull(callingPackage, "Null calling package cannot create IpSec tunnels");
        int noteOp = getAppOpsManager().noteOp(75, Binder.getCallingUid(), callingPackage);
        if (noteOp != 0) {
            if (noteOp == 3) {
                this.mContext.enforceCallingOrSelfPermission("android.permission.MANAGE_IPSEC_TUNNELS", TAG);
                return;
            }
            throw new SecurityException("Request to ignore AppOps for non-legacy API");
        }
    }

    private void createOrUpdateTransform(IpSecConfig c, int resourceId, SpiRecord spiRecord, EncapSocketRecord socketRecord) throws RemoteException {
        String cryptName;
        int encapType = c.getEncapType();
        int encapLocalPort = 0;
        int encapRemotePort = 0;
        if (encapType != 0) {
            encapLocalPort = socketRecord.getPort();
            encapRemotePort = c.getEncapRemotePort();
        }
        int encapLocalPort2 = encapLocalPort;
        int encapRemotePort2 = encapRemotePort;
        IpSecAlgorithm auth = c.getAuthentication();
        IpSecAlgorithm crypt = c.getEncryption();
        IpSecAlgorithm authCrypt = c.getAuthenticatedEncryption();
        if (crypt == null) {
            cryptName = authCrypt == null ? "ecb(cipher_null)" : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS;
        } else {
            cryptName = crypt.getName();
        }
        this.mSrvConfig.getNetdInstance().ipSecAddSecurityAssociation(resourceId, c.getMode(), c.getSourceAddress(), c.getDestinationAddress(), c.getNetwork() != null ? c.getNetwork().netId : 0, spiRecord.getSpi(), c.getMarkValue(), c.getMarkMask(), auth != null ? auth.getName() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, auth != null ? auth.getKey() : new byte[0], auth != null ? auth.getTruncationLengthBits() : 0, cryptName, crypt != null ? crypt.getKey() : new byte[0], crypt != null ? crypt.getTruncationLengthBits() : 0, authCrypt != null ? authCrypt.getName() : BackupManagerConstants.DEFAULT_BACKUP_FINISHED_NOTIFICATION_RECEIVERS, authCrypt != null ? authCrypt.getKey() : new byte[0], authCrypt != null ? authCrypt.getTruncationLengthBits() : 0, encapType, encapLocalPort2, encapRemotePort2);
    }

    public synchronized IpSecTransformResponse createTransform(IpSecConfig c, IBinder binder, String callingPackage) throws RemoteException {
        Preconditions.checkNotNull(c);
        if (c.getMode() == 1) {
            enforceTunnelPermissions(callingPackage);
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
        EncapSocketRecord socketRecord = null;
        if (c.getEncapType() != 0) {
            RefcountedResource<EncapSocketRecord> refcountedSocketRecord = userRecord.mEncapSocketRecords.getRefcountedResourceOrThrow(c.getEncapSocketResourceId());
            dependencies.add(refcountedSocketRecord);
            socketRecord = refcountedSocketRecord.getResource();
        }
        EncapSocketRecord socketRecord2 = socketRecord;
        RefcountedResource<SpiRecord> refcountedSpiRecord = userRecord.mSpiRecords.getRefcountedResourceOrThrow(c.getSpiResourceId());
        dependencies.add(refcountedSpiRecord);
        SpiRecord spiRecord = refcountedSpiRecord.getResource();
        createOrUpdateTransform(c, resourceId, spiRecord, socketRecord2);
        userRecord.mTransformRecords.put(resourceId, new RefcountedResource<>(new TransformRecord(resourceId, c, spiRecord, socketRecord2), binder, (RefcountedResource[]) dependencies.toArray(new RefcountedResource[dependencies.size()])));
        return new IpSecTransformResponse(0, resourceId);
    }

    public synchronized void deleteTransform(int resourceId) throws RemoteException {
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        releaseResource(userRecord.mTransformRecords, resourceId);
    }

    public synchronized void applyTransportModeTransform(ParcelFileDescriptor socket, int direction, int resourceId) throws RemoteException {
        UserRecord userRecord = this.mUserResourceTracker.getUserRecord(Binder.getCallingUid());
        checkDirection(direction);
        TransformRecord info = userRecord.mTransformRecords.getResourceOrThrow(resourceId);
        if (info.pid != getCallingPid() || info.uid != getCallingUid()) {
            throw new SecurityException("Only the owner of an IpSec Transform may apply it!");
        }
        IpSecConfig c = info.getConfig();
        Preconditions.checkArgument(c.getMode() == 0, "Transform mode was not Transport mode; cannot be applied to a socket");
        this.mSrvConfig.getNetdInstance().ipSecApplyTransportModeTransform(socket.getFileDescriptor(), resourceId, direction, c.getSourceAddress(), c.getDestinationAddress(), info.getSpiRecord().getSpi());
    }

    public synchronized void removeTransportModeTransforms(ParcelFileDescriptor socket) throws RemoteException {
        this.mSrvConfig.getNetdInstance().ipSecRemoveTransportModeTransform(socket.getFileDescriptor());
    }

    /* JADX WARN: Removed duplicated region for block: B:39:0x00ff A[Catch: all -> 0x010a, TryCatch #3 {, blocks: (B:5:0x0009, B:9:0x003f, B:11:0x004b, B:12:0x0058, B:14:0x0068, B:17:0x0072, B:19:0x007b, B:21:0x0087, B:23:0x00b5, B:37:0x00f9, B:39:0x00ff, B:40:0x0108, B:41:0x0109, B:30:0x00e9, B:15:0x006d), top: B:51:0x0009 }] */
    /* JADX WARN: Removed duplicated region for block: B:41:0x0109 A[Catch: all -> 0x010a, TRY_LEAVE, TryCatch #3 {, blocks: (B:5:0x0009, B:9:0x003f, B:11:0x004b, B:12:0x0058, B:14:0x0068, B:17:0x0072, B:19:0x007b, B:21:0x0087, B:23:0x00b5, B:37:0x00f9, B:39:0x00ff, B:40:0x0108, B:41:0x0109, B:30:0x00e9, B:15:0x006d), top: B:51:0x0009 }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public synchronized void applyTunnelModeTransform(int r25, int r26, int r27, java.lang.String r28) throws android.os.RemoteException {
        /*
            Method dump skipped, instructions count: 269
            To view this dump add '--comments-level debug' option
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
