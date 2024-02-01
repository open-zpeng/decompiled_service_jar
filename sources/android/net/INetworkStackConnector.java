package android.net;

import android.net.IIpMemoryStoreCallbacks;
import android.net.INetworkMonitorCallbacks;
import android.net.dhcp.DhcpServingParamsParcel;
import android.net.dhcp.IDhcpServerCallbacks;
import android.net.ip.IIpClientCallbacks;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/* loaded from: classes.dex */
public interface INetworkStackConnector extends IInterface {
    public static final int VERSION = 3;

    void fetchIpMemoryStore(IIpMemoryStoreCallbacks iIpMemoryStoreCallbacks) throws RemoteException;

    int getInterfaceVersion() throws RemoteException;

    void makeDhcpServer(String str, DhcpServingParamsParcel dhcpServingParamsParcel, IDhcpServerCallbacks iDhcpServerCallbacks) throws RemoteException;

    void makeIpClient(String str, IIpClientCallbacks iIpClientCallbacks) throws RemoteException;

    void makeNetworkMonitor(Network network, String str, INetworkMonitorCallbacks iNetworkMonitorCallbacks) throws RemoteException;

    /* loaded from: classes.dex */
    public static class Default implements INetworkStackConnector {
        @Override // android.net.INetworkStackConnector
        public void makeDhcpServer(String ifName, DhcpServingParamsParcel params, IDhcpServerCallbacks cb) throws RemoteException {
        }

        @Override // android.net.INetworkStackConnector
        public void makeNetworkMonitor(Network network, String name, INetworkMonitorCallbacks cb) throws RemoteException {
        }

        @Override // android.net.INetworkStackConnector
        public void makeIpClient(String ifName, IIpClientCallbacks callbacks) throws RemoteException {
        }

        @Override // android.net.INetworkStackConnector
        public void fetchIpMemoryStore(IIpMemoryStoreCallbacks cb) throws RemoteException {
        }

        @Override // android.net.INetworkStackConnector
        public int getInterfaceVersion() {
            return -1;
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }
    }

    /* loaded from: classes.dex */
    public static abstract class Stub extends Binder implements INetworkStackConnector {
        private static final String DESCRIPTOR = "android.net.INetworkStackConnector";
        static final int TRANSACTION_fetchIpMemoryStore = 4;
        static final int TRANSACTION_getInterfaceVersion = 16777215;
        static final int TRANSACTION_makeDhcpServer = 1;
        static final int TRANSACTION_makeIpClient = 3;
        static final int TRANSACTION_makeNetworkMonitor = 2;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static INetworkStackConnector asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && (iin instanceof INetworkStackConnector)) {
                return (INetworkStackConnector) iin;
            }
            return new Proxy(obj);
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        @Override // android.os.Binder
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            DhcpServingParamsParcel _arg1;
            Network _arg0;
            if (code == 1) {
                data.enforceInterface(DESCRIPTOR);
                String _arg02 = data.readString();
                if (data.readInt() != 0) {
                    _arg1 = DhcpServingParamsParcel.CREATOR.createFromParcel(data);
                } else {
                    _arg1 = null;
                }
                IDhcpServerCallbacks _arg2 = IDhcpServerCallbacks.Stub.asInterface(data.readStrongBinder());
                makeDhcpServer(_arg02, _arg1, _arg2);
                return true;
            } else if (code == 2) {
                data.enforceInterface(DESCRIPTOR);
                if (data.readInt() != 0) {
                    _arg0 = (Network) Network.CREATOR.createFromParcel(data);
                } else {
                    _arg0 = null;
                }
                String _arg12 = data.readString();
                INetworkMonitorCallbacks _arg22 = INetworkMonitorCallbacks.Stub.asInterface(data.readStrongBinder());
                makeNetworkMonitor(_arg0, _arg12, _arg22);
                return true;
            } else if (code == 3) {
                data.enforceInterface(DESCRIPTOR);
                String _arg03 = data.readString();
                IIpClientCallbacks _arg13 = IIpClientCallbacks.Stub.asInterface(data.readStrongBinder());
                makeIpClient(_arg03, _arg13);
                return true;
            } else if (code == 4) {
                data.enforceInterface(DESCRIPTOR);
                IIpMemoryStoreCallbacks _arg04 = IIpMemoryStoreCallbacks.Stub.asInterface(data.readStrongBinder());
                fetchIpMemoryStore(_arg04);
                return true;
            } else if (code != TRANSACTION_getInterfaceVersion) {
                if (code == 1598968902) {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } else {
                data.enforceInterface(DESCRIPTOR);
                reply.writeNoException();
                reply.writeInt(getInterfaceVersion());
                return true;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public static class Proxy implements INetworkStackConnector {
            public static INetworkStackConnector sDefaultImpl;
            private int mCachedVersion = -1;
            private IBinder mRemote;

            Proxy(IBinder remote) {
                this.mRemote = remote;
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.mRemote;
            }

            public String getInterfaceDescriptor() {
                return Stub.DESCRIPTOR;
            }

            @Override // android.net.INetworkStackConnector
            public void makeDhcpServer(String ifName, DhcpServingParamsParcel params, IDhcpServerCallbacks cb) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeString(ifName);
                    if (params != null) {
                        _data.writeInt(1);
                        params.writeToParcel(_data, 0);
                    } else {
                        _data.writeInt(0);
                    }
                    _data.writeStrongBinder(cb != null ? cb.asBinder() : null);
                    boolean _status = this.mRemote.transact(1, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().makeDhcpServer(ifName, params, cb);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.net.INetworkStackConnector
            public void makeNetworkMonitor(Network network, String name, INetworkMonitorCallbacks cb) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    if (network != null) {
                        _data.writeInt(1);
                        network.writeToParcel(_data, 0);
                    } else {
                        _data.writeInt(0);
                    }
                    _data.writeString(name);
                    _data.writeStrongBinder(cb != null ? cb.asBinder() : null);
                    boolean _status = this.mRemote.transact(2, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().makeNetworkMonitor(network, name, cb);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.net.INetworkStackConnector
            public void makeIpClient(String ifName, IIpClientCallbacks callbacks) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeString(ifName);
                    _data.writeStrongBinder(callbacks != null ? callbacks.asBinder() : null);
                    boolean _status = this.mRemote.transact(3, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().makeIpClient(ifName, callbacks);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.net.INetworkStackConnector
            public void fetchIpMemoryStore(IIpMemoryStoreCallbacks cb) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeStrongBinder(cb != null ? cb.asBinder() : null);
                    boolean _status = this.mRemote.transact(4, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().fetchIpMemoryStore(cb);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.net.INetworkStackConnector
            public int getInterfaceVersion() throws RemoteException {
                if (this.mCachedVersion == -1) {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(Stub.DESCRIPTOR);
                        this.mRemote.transact(Stub.TRANSACTION_getInterfaceVersion, data, reply, 0);
                        reply.readException();
                        this.mCachedVersion = reply.readInt();
                    } finally {
                        reply.recycle();
                        data.recycle();
                    }
                }
                return this.mCachedVersion;
            }
        }

        public static boolean setDefaultImpl(INetworkStackConnector impl) {
            if (Proxy.sDefaultImpl == null && impl != null) {
                Proxy.sDefaultImpl = impl;
                return true;
            }
            return false;
        }

        public static INetworkStackConnector getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }
    }
}
