package nrc.controller;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.telephony.PhysicalChannelConfig;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyDisplayInfo;

import java.util.List;

/**
 * 도우미(HostService) → 앱 전화 상태 사건 통로(DESIGN §5.15 방향 A의 '눈'). 앱 프로세스는 통신사 권한 없이는
 * 기지국 묶음(PCC) 같은 사건을 못 받으므로, shell 신분 도우미가 받아 그대로 넘긴다. 앱은 같은 Watcher 메서드를 부른다.
 * 모두 단방향(oneway): 도우미가 앱을 기다리지 않는다. 같은 binder로 가는 단방향 거래는 보낸 순서대로 도착한다.
 * AIDL 생성 코드를 손으로 썼다(aidl 도구가 한글 경로를 못 연다).
 */
public interface ISink extends IInterface {
    String DESCRIPTOR = "nrc.controller.ISink";
    int T_SS = 1;
    int T_PCC = 2;
    int T_SIGNAL = 3;
    int T_DATA_ACTIVITY = 4;
    int T_DATA_CONN = 5;
    int T_CALL = 6;
    int T_DISPLAY = 7;

    void onServiceState(ServiceState ss) throws RemoteException;

    void onPcc(List<PhysicalChannelConfig> configs) throws RemoteException;

    void onSignal(SignalStrength s) throws RemoteException;

    void onDataActivity(int direction) throws RemoteException;

    void onDataConn(int state, int networkType) throws RemoteException;

    void onCallState(int state) throws RemoteException;

    void onDisplay(TelephonyDisplayInfo info) throws RemoteException;

    abstract class Stub extends android.os.Binder implements ISink {
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ISink asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ISink) return (ISink) iin;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code < T_SS || code > T_DISPLAY) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case T_SS:
                    onServiceState(data.readTypedObject(ServiceState.CREATOR));
                    break;
                case T_PCC:
                    onPcc(data.createTypedArrayList(PhysicalChannelConfig.CREATOR));
                    break;
                case T_SIGNAL:
                    onSignal(data.readTypedObject(SignalStrength.CREATOR));
                    break;
                case T_DATA_ACTIVITY:
                    onDataActivity(data.readInt());
                    break;
                case T_DATA_CONN: {
                    int state = data.readInt();
                    onDataConn(state, data.readInt());
                    break;
                }
                case T_CALL:
                    onCallState(data.readInt());
                    break;
                default:
                    onDisplay(data.readTypedObject(TelephonyDisplayInfo.CREATOR));
                    break;
            }
            return true;
        }

        private static final class Proxy implements ISink {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            private void send(int code, Parcel data) throws RemoteException {
                try {
                    remote.transact(code, data, null, IBinder.FLAG_ONEWAY);
                } finally {
                    data.recycle();
                }
            }

            private static Parcel start() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                return p;
            }

            @Override
            public void onServiceState(ServiceState ss) throws RemoteException {
                Parcel p = start();
                p.writeTypedObject(ss, 0);
                send(T_SS, p);
            }

            @Override
            public void onPcc(List<PhysicalChannelConfig> configs) throws RemoteException {
                Parcel p = start();
                p.writeTypedList(configs);
                send(T_PCC, p);
            }

            @Override
            public void onSignal(SignalStrength s) throws RemoteException {
                Parcel p = start();
                p.writeTypedObject(s, 0);
                send(T_SIGNAL, p);
            }

            @Override
            public void onDataActivity(int direction) throws RemoteException {
                Parcel p = start();
                p.writeInt(direction);
                send(T_DATA_ACTIVITY, p);
            }

            @Override
            public void onDataConn(int state, int networkType) throws RemoteException {
                Parcel p = start();
                p.writeInt(state);
                p.writeInt(networkType);
                send(T_DATA_CONN, p);
            }

            @Override
            public void onCallState(int state) throws RemoteException {
                Parcel p = start();
                p.writeInt(state);
                send(T_CALL, p);
            }

            @Override
            public void onDisplay(TelephonyDisplayInfo info) throws RemoteException {
                Parcel p = start();
                p.writeTypedObject(info, 0);
                send(T_DISPLAY, p);
            }
        }
    }
}
