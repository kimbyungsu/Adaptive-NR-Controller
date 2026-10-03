package nrc.shizupoc;

/**
 * Shizuku UserService(관측 프로세스) 인터페이스 — AIDL 생성 코드를 손으로 작성(aidl 도구가 한글 경로를 못 열어).
 * 원본 .aidl 상당: interface INrObserver { void destroy() = 16777114; String snapshot() = 2; }
 * 주의: AIDL의 "= N"은 실제 Binder 거래 코드 FIRST_CALL_TRANSACTION(=1) + N 으로 생성된다.
 *   → destroy 거래 코드 = 1 + 16777114 = 16777115 (Shizuku 서버가 이 코드로 종료 요청을 보냄),
 *     snapshot 거래 코드 = 1 + 2 = 3. (서버가 16777115를 보내므로 Stub이 이를 꼭 받아야 호스트가 종료된다.)
 */
public interface INrObserver extends android.os.IInterface {

    String DESCRIPTOR = "nrc.shizupoc.INrObserver";
    int TRANSACTION_destroy = 16777115; // = FIRST_CALL_TRANSACTION(1) + 16777114, Shizuku 서버가 종료에 쓰는 코드
    int TRANSACTION_snapshot = 3;       // = FIRST_CALL_TRANSACTION(1) + 2 (.aidl의 snapshot()=2)

    void destroy() throws android.os.RemoteException;

    String snapshot() throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements INrObserver {

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static INrObserver asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof INrObserver) return (INrObserver) iin;
            return new Proxy(obj);
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                throws android.os.RemoteException {
            switch (code) {
                case INTERFACE_TRANSACTION:
                    reply.writeString(DESCRIPTOR);
                    return true;
                case TRANSACTION_destroy:
                    data.enforceInterface(DESCRIPTOR);
                    this.destroy();
                    reply.writeNoException();
                    return true;
                case TRANSACTION_snapshot:
                    data.enforceInterface(DESCRIPTOR);
                    String _result = this.snapshot();
                    reply.writeNoException();
                    reply.writeString(_result);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static final class Proxy implements INrObserver {
            private final android.os.IBinder mRemote;

            Proxy(android.os.IBinder remote) {
                mRemote = remote;
            }

            @Override
            public android.os.IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void destroy() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_destroy, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public String snapshot() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                String _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_snapshot, _data, _reply, 0);
                    _reply.readException();
                    _result = _reply.readString();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }
        }
    }
}
