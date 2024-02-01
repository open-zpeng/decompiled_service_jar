package android.car.hardware.eps;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/* loaded from: classes.dex */
public interface IEpsEventListener extends IInterface {
    void onEpsSteeringAngleEvent(float f) throws RemoteException;

    void onEpsSteeringAngleSpeedEvent(float f) throws RemoteException;

    /* loaded from: classes.dex */
    public static class Default implements IEpsEventListener {
        @Override // android.car.hardware.eps.IEpsEventListener
        public void onEpsSteeringAngleEvent(float angle) throws RemoteException {
        }

        @Override // android.car.hardware.eps.IEpsEventListener
        public void onEpsSteeringAngleSpeedEvent(float angleSpeed) throws RemoteException {
        }

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return null;
        }
    }

    /* loaded from: classes.dex */
    public static abstract class Stub extends Binder implements IEpsEventListener {
        private static final String DESCRIPTOR = "android.car.hardware.eps.IEpsEventListener";
        static final int TRANSACTION_onEpsSteeringAngleEvent = 1;
        static final int TRANSACTION_onEpsSteeringAngleSpeedEvent = 2;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IEpsEventListener asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin != null && (iin instanceof IEpsEventListener)) {
                return (IEpsEventListener) iin;
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
                float _arg0 = data.readFloat();
                onEpsSteeringAngleEvent(_arg0);
                return true;
            } else if (code != 2) {
                if (code == 1598968902) {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } else {
                data.enforceInterface(DESCRIPTOR);
                float _arg02 = data.readFloat();
                onEpsSteeringAngleSpeedEvent(_arg02);
                return true;
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        /* loaded from: classes.dex */
        public static class Proxy implements IEpsEventListener {
            public static IEpsEventListener sDefaultImpl;
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

            @Override // android.car.hardware.eps.IEpsEventListener
            public void onEpsSteeringAngleEvent(float angle) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeFloat(angle);
                    boolean _status = this.mRemote.transact(1, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().onEpsSteeringAngleEvent(angle);
                    }
                } finally {
                    _data.recycle();
                }
            }

            @Override // android.car.hardware.eps.IEpsEventListener
            public void onEpsSteeringAngleSpeedEvent(float angleSpeed) throws RemoteException {
                Parcel _data = Parcel.obtain();
                try {
                    _data.writeInterfaceToken(Stub.DESCRIPTOR);
                    _data.writeFloat(angleSpeed);
                    boolean _status = this.mRemote.transact(2, _data, null, 1);
                    if (!_status && Stub.getDefaultImpl() != null) {
                        Stub.getDefaultImpl().onEpsSteeringAngleSpeedEvent(angleSpeed);
                    }
                } finally {
                    _data.recycle();
                }
            }
        }

        public static boolean setDefaultImpl(IEpsEventListener impl) {
            if (Proxy.sDefaultImpl == null && impl != null) {
                Proxy.sDefaultImpl = impl;
                return true;
            }
            return false;
        }

        public static IEpsEventListener getDefaultImpl() {
            return Proxy.sDefaultImpl;
        }
    }
}
