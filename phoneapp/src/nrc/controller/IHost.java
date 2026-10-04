package nrc.controller;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 앱 ↔ 도우미 프로세스(HostService) 약속(DESIGN §5.15 방향 A). 도우미는 Shizuku가 shell 신분으로 띄운다.
 * AIDL 생성 코드를 손으로 썼다(aidl 도구가 한글 경로를 못 연다). 거래 번호 = FIRST_CALL_TRANSACTION(1) + .aidl 번호.
 * destroy는 Shizuku 서버가 도우미를 끝낼 때 쓰는 고정 번호 16777115(= 1 + 16777114)다.
 * 쓰기 거래는 통신사 칸(CARRIER) 하나뿐이다 — 사용자 칸(USER, 설정 화면 값)을 쓰는 거래는 아예 없다.
 */
public interface IHost extends IInterface {
    String DESCRIPTOR = "nrc.controller.IHost";
    int T_DESTROY = 16777115;
    int T_HELLO = 2;
    int T_READ = 3;
    int T_WRITE_CARRIER = 4;
    int T_CALL_STATE = 5;
    int T_ACTIVE_SIMS = 6;
    int T_WATCH = 7;
    int T_UNWATCH = 8;

    /** 도우미를 끝낸다(관측 해제 후 프로세스 종료). */
    void destroy() throws RemoteException;

    /** 첫 인사: 도우미가 client(앱 쪽 토큰)의 죽음을 지켜보게 하고(앱이 죽으면 도우미도 끝남), 신분 한 줄을 돌려준다. */
    String hello(IBinder client) throws RemoteException;

    /** 사유별 허용 망. 못 읽으면 -1. */
    long read(int sub, int reason) throws RemoteException;

    /** 통신사 칸에 쓴다. 호출이 거절되면 false. */
    boolean writeCarrier(int sub, long mask) throws RemoteException;

    /** 통화 상태(TelephonyManager.CALL_STATE_*). 모르면 -1. */
    int callState(int sub) throws RemoteException;

    /** 켜진 SIM 수. 모르면 -1. */
    int activeSims() throws RemoteException;

    /** 전화 상태 지켜보기를 시작해 사건을 sink(ISink)로 넘긴다. 첫 사건이 실제로 와야 성공(null), 아니면 실패 이유. */
    String watch(int sub, IBinder sink) throws RemoteException;

    /** 지켜보기를 멈춘다. */
    void unwatch() throws RemoteException;

    abstract class Stub extends android.os.Binder implements IHost {
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IHost asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IHost) return (IHost) iin;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(DESCRIPTOR);
                    return true;
                case T_DESTROY:
                    data.enforceInterface(DESCRIPTOR);
                    destroy();
                    reply.writeNoException();
                    return true;
                case T_HELLO: {
                    data.enforceInterface(DESCRIPTOR);
                    String r = hello(data.readStrongBinder());
                    reply.writeNoException();
                    reply.writeString(r);
                    return true;
                }
                case T_READ: {
                    data.enforceInterface(DESCRIPTOR);
                    long r = read(data.readInt(), data.readInt());
                    reply.writeNoException();
                    reply.writeLong(r);
                    return true;
                }
                case T_WRITE_CARRIER: {
                    data.enforceInterface(DESCRIPTOR);
                    int sub = data.readInt();
                    boolean r = writeCarrier(sub, data.readLong());
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case T_CALL_STATE: {
                    data.enforceInterface(DESCRIPTOR);
                    int r = callState(data.readInt());
                    reply.writeNoException();
                    reply.writeInt(r);
                    return true;
                }
                case T_ACTIVE_SIMS: {
                    data.enforceInterface(DESCRIPTOR);
                    int r = activeSims();
                    reply.writeNoException();
                    reply.writeInt(r);
                    return true;
                }
                case T_WATCH: {
                    data.enforceInterface(DESCRIPTOR);
                    int sub = data.readInt();
                    String r = watch(sub, data.readStrongBinder());
                    reply.writeNoException();
                    reply.writeString(r);
                    return true;
                }
                case T_UNWATCH:
                    data.enforceInterface(DESCRIPTOR);
                    unwatch();
                    reply.writeNoException();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static final class Proxy implements IHost {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            /** 거래 하나: 인자를 쓰고(w), 보내고, 예외를 확인한 뒤 답을 읽는다(r). */
            private <T> T call(int code, ParcelWriter w, ParcelReader<T> r) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    if (w != null) w.write(data);
                    remote.transact(code, data, reply, 0);
                    reply.readException();
                    return r == null ? null : r.read(reply);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void destroy() throws RemoteException {
                call(T_DESTROY, null, null);
            }

            @Override
            public String hello(IBinder client) throws RemoteException {
                return call(T_HELLO, p -> p.writeStrongBinder(client), Parcel::readString);
            }

            @Override
            public long read(int sub, int reason) throws RemoteException {
                return call(T_READ, p -> {
                    p.writeInt(sub);
                    p.writeInt(reason);
                }, Parcel::readLong);
            }

            @Override
            public boolean writeCarrier(int sub, long mask) throws RemoteException {
                return call(T_WRITE_CARRIER, p -> {
                    p.writeInt(sub);
                    p.writeLong(mask);
                }, p -> p.readInt() != 0);
            }

            @Override
            public int callState(int sub) throws RemoteException {
                return call(T_CALL_STATE, p -> p.writeInt(sub), Parcel::readInt);
            }

            @Override
            public int activeSims() throws RemoteException {
                return call(T_ACTIVE_SIMS, null, Parcel::readInt);
            }

            @Override
            public String watch(int sub, IBinder sink) throws RemoteException {
                return call(T_WATCH, p -> {
                    p.writeInt(sub);
                    p.writeStrongBinder(sink);
                }, Parcel::readString);
            }

            @Override
            public void unwatch() throws RemoteException {
                call(T_UNWATCH, null, null);
            }
        }

        private interface ParcelWriter {
            void write(Parcel p);
        }

        private interface ParcelReader<T> {
            T read(Parcel p);
        }
    }
}
