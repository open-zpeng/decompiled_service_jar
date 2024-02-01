package android.car.hardware.vcu;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/* loaded from: classes.dex */
public interface IVcuEventListener extends IInterface {
    void onVcuChargeStatusEvent(int i) throws RemoteException;

    void onVcuCruiseControlStatusEvent(int i) throws RemoteException;

    void onVcuGearEvent(int i) throws RemoteException;

    void onVcuRawCarSpeedEvent(float f) throws RemoteException;

    /* loaded from: classes.dex */
    public static class Default implements IVcuEventListener {
        @Override // android.car.hardware.vcu.IVcuEventListener
        public void onVcuGearEvent(int gear) throws RemoteException {
        }

        @Override // android.car.hardware.vcu.IVcuEventListener
        public void onVcuRawCarSpeedEvent(float speed) throws RemoteException {
        }

        @Override // android.car.hardware.vcu.IVcuEventListener
        public void onVcuCruiseControlStatusEvent(int status) throws RemoteException {
        }

        @Override // android.car.hardware.vcu.IVcuEventListener
        public void onVcuChargeStatusEvent(int status) throws RemoteException {
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }
    }

    /* loaded from: classes.dex */
    public static abstract class Stub extends Binder implements IVcuEventListener {
        private static final String DESCRIPTOR = "android.car.hardware.vcu.IVcuEventListener";
        static final int TRANSACTION_onVcuChargeStatusEvent = 4;
        static final int TRANSACTION_onVcuCruiseControlStatusEvent = 3;
        static final int TRANSACTION_onVcuGearEvent = 1;
        static final int TRANSACTION_onVcuRawCarSpeedEvent = 2;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IVcuEventListener asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && (iin instanceof IVcuEventListener)) {
                return (IVcuEventListener) iin;
            }
            return new Proxy(obj);
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        @Override // android.os.Binder
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                data.enforceInterface(DESCRIPTOR);
                int _arg0 = data.readInt();
                onVcuGearEvent(_arg0);
                return true;
            } else if (code == 2) {
                data.enforceInterface(DESCRIPTOR);
                float _arg02 = data.readFloat();
                onVcuRawCarSpeedEvent(_arg02);
                return true;
            } else if (code == 3) {
                data.enforceInterface(DESCRIPTOR);
                int _arg03 = data.readInt();
                onVcuCruiseControlStatusEvent(_arg03);
                return true;
            } else if (code != 4) {
                if (code == 1598968902) {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } else {
                data.enforceInterface(DESCRIPTOR);
                int _arg04 = data.readInt();
                onVcuChargeStatusEvent(_arg04);
                return true;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public static class Proxy implements IVcuEventListener {
            public static IVcuEventListener sDefaultImpl;
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

            @Override // android.car.hardware.vcu.IVcuEventListener
            public void onVcuGearEvent(int gear) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeInt(gear);
                    boolean _status = this.mRemote.transact(1, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().onVcuGearEvent(gear);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.car.hardware.vcu.IVcuEventListener
            public void onVcuRawCarSpeedEvent(float speed) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeFloat(speed);
                    boolean _status = this.mRemote.transact(2, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().onVcuRawCarSpeedEvent(speed);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.car.hardware.vcu.IVcuEventListener
            public void onVcuCruiseControlStatusEvent(int status) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeInt(status);
                    boolean _status = this.mRemote.transact(3, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().onVcuCruiseControlStatusEvent(status);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.car.hardware.vcu.IVcuEventListener
            public void onVcuChargeStatusEvent(int status) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeInt(status);
                    boolean _status = this.mRemote.transact(4, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().onVcuChargeStatusEvent(status);
                    }
                } finally {
                    _data.recycle();
                }
            }
        }

        public static boolean setDefaultImpl(IVcuEventListener impl) {
            if (Proxy.sDefaultImpl == null && impl != null) {
                Proxy.sDefaultImpl = impl;
                return true;
            }
            return false;
        }

        public static IVcuEventListener getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }
    }
}
