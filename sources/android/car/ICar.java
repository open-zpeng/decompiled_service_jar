package android.car;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/* loaded from: classes.dex */
public interface ICar extends IInterface {
    int getCarConnectionType() throws RemoteException;

    IBinder getCarService(String str) throws RemoteException;

    void onAutoWakeupResult(boolean z) throws RemoteException;

    void onSwitchUser(int i) throws RemoteException;

    void setCarServiceHelper(IBinder iBinder) throws RemoteException;

    void setUserLockStatus(int i, int i2) throws RemoteException;

    /* loaded from: classes.dex */
    public static class Default implements ICar {
        @Override // android.car.ICar
        public void setCarServiceHelper(IBinder helper) throws RemoteException {
        }

        @Override // android.car.ICar
        public void setUserLockStatus(int userHandle, int unlocked) throws RemoteException {
        }

        @Override // android.car.ICar
        public void onSwitchUser(int userHandle) throws RemoteException {
        }

        @Override // android.car.ICar
        public IBinder getCarService(String serviceName) throws RemoteException {
            return null;
        }

        @Override // android.car.ICar
        public int getCarConnectionType() throws RemoteException {
            return 0;
        }

        @Override // android.car.ICar
        public void onAutoWakeupResult(boolean success) throws RemoteException {
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }
    }

    /* loaded from: classes.dex */
    public static abstract class Stub extends Binder implements ICar {
        private static final String DESCRIPTOR = "android.car.ICar";
        static final int TRANSACTION_getCarConnectionType = 5;
        static final int TRANSACTION_getCarService = 4;
        static final int TRANSACTION_onAutoWakeupResult = 6;
        static final int TRANSACTION_onSwitchUser = 3;
        static final int TRANSACTION_setCarServiceHelper = 1;
        static final int TRANSACTION_setUserLockStatus = 2;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ICar asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && (iin instanceof ICar)) {
                return (ICar) iin;
            }
            return new Proxy(obj);
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        @Override // android.os.Binder
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1598968902) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case 1:
                    data.enforceInterface(DESCRIPTOR);
                    IBinder _arg0 = data.readStrongBinder();
                    setCarServiceHelper(_arg0);
                    return true;
                case 2:
                    data.enforceInterface(DESCRIPTOR);
                    int _arg02 = data.readInt();
                    int _arg1 = data.readInt();
                    setUserLockStatus(_arg02, _arg1);
                    return true;
                case 3:
                    data.enforceInterface(DESCRIPTOR);
                    int _arg03 = data.readInt();
                    onSwitchUser(_arg03);
                    return true;
                case 4:
                    data.enforceInterface(DESCRIPTOR);
                    String _arg04 = data.readString();
                    IBinder _result = getCarService(_arg04);
                    reply.writeNoException();
                    reply.writeStrongBinder(_result);
                    return true;
                case 5:
                    data.enforceInterface(DESCRIPTOR);
                    int _result2 = getCarConnectionType();
                    reply.writeNoException();
                    reply.writeInt(_result2);
                    return true;
                case 6:
                    data.enforceInterface(DESCRIPTOR);
                    boolean _arg05 = data.readInt() != 0;
                    onAutoWakeupResult(_arg05);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public static class Proxy implements ICar {
            public static ICar sDefaultImpl;
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

            @Override // android.car.ICar
            public void setCarServiceHelper(IBinder helper) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeStrongBinder(helper);
                    boolean _status = this.mRemote.transact(1, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().setCarServiceHelper(helper);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.car.ICar
            public void setUserLockStatus(int userHandle, int unlocked) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeInt(userHandle);
                    _data.writeInt(unlocked);
                    boolean _status = this.mRemote.transact(2, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().setUserLockStatus(userHandle, unlocked);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.car.ICar
            public void onSwitchUser(int userHandle) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeInt(userHandle);
                    boolean _status = this.mRemote.transact(3, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().onSwitchUser(userHandle);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.car.ICar
            public IBinder getCarService(String serviceName) throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeString(serviceName);
                    boolean _status = this.mRemote.transact(4, _data, _reply, 0);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getCarService(serviceName);
                    }
                    _reply.readException();
                    IBinder _result = _reply.readStrongBinder();
                    return _result;
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override // android.car.ICar
            public int getCarConnectionType() throws RemoteException {
                Parcel _data = Parcel.obtain();
                Parcel _reply = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    boolean _status = this.mRemote.transact(5, _data, _reply, 0);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        return Stub.getDefaultImpl().getCarConnectionType();
                    }
                    _reply.readException();
                    int _result = _reply.readInt();
                    return _result;
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override // android.car.ICar
            public void onAutoWakeupResult(boolean success) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeInt(success ? 1 : 0);
                    boolean _status = this.mRemote.transact(6, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().onAutoWakeupResult(success);
                    }
                } finally {
                    _data.recycle();
                }
            }
        }

        public static boolean setDefaultImpl(ICar impl) {
            if (Proxy.sDefaultImpl == null && impl != null) {
                Proxy.sDefaultImpl = impl;
                return true;
            }
            return false;
        }

        public static ICar getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }
    }
}
