package nrc.controller;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.telephony.PhysicalChannelConfig;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 도우미 프로세스(DESIGN §5.15 방향 A, 길 2). 사용자가 설치·승인한 Shizuku가 이 클래스를 shell 신분(uid 2000) 별도 프로세스로
 * 띄운다(bindUserService). 앱은 통신사 권한 없이 이 도우미를 통해 허용 망 칸을 읽고·통신사 칸에 쓰고·전화 상태를 지켜본다.
 * - 쓰기는 통신사 칸(CARRIER)만. 사용자 칸(USER, 설정 화면 값)을 쓰는 길은 이 클래스에 없다.
 * - 전화 서비스는 "호출 패키지 = 호출 uid 소유"를 보므로 ShellContext(com.android.shell)를 숨은 생성자에 직접 넘긴다
 *   (getSystemService는 감싼 컨텍스트를 거치지 않아 소용없음 — poc/shizuku 실측).
 * - 지켜보기 성공은 첫 사건이 실제로 도착했을 때만으로 정한다(poc/shizuku Step2와 같음).
 * - 부를 수 있는 쪽: 우리 앱 uid와 Shizuku 서버(이 프로세스와 같은 uid)뿐이다. 앱이 죽으면 이 도우미도 끝낸다(남은 프로세스 없음).
 * 이 클래스는 앱 프로세스에서는 만들지 않는다.
 */
public final class HostService extends IHost.Stub {
    private static final String T = "NRCHOST";
    private static final String APP_PACKAGE = "nrc.controller";
    private static final long FIRST_EVENT_MS = 2_500;

    private final Object lock = new Object();
    private final SparseArray<TelephonyManager> tms = new SparseArray<>();
    private volatile int appUid = -1;
    private Context shell;
    private SubscriptionManager sm;
    private IBinder client;
    private TelephonyManager watchTm;
    private Fwd fwd;
    private ExecutorService watchExec;
    private IBinder watchSink;
    private IBinder.DeathRecipient sinkDeath;

    public HostService() {
    }

    /** Shizuku는 이 생성자가 있으면 우리 패키지 컨텍스트를 넘긴다. 앱 uid를 여기서 알아 둔다. */
    public HostService(Context c) {
        try {
            if (c != null) appUid = c.getApplicationInfo().uid;
        } catch (Throwable ignored) {
            // 못 알면 처음 부를 때 패키지 관리자로 찾는다
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != INTERFACE_TRANSACTION && !allowed(Binder.getCallingUid())) {
            Log.w(T, "rejected uid=" + Binder.getCallingUid() + " code=" + code);
            throw new SecurityException("not allowed");
        }
        return super.onTransact(code, data, reply, flags);
    }

    private boolean allowed(int uid) {
        if (uid == Process.myUid() || uid == 0) return true; // Shizuku 서버(종료 요청)
        int app = appUid;
        if (app < 0) {
            long id = Binder.clearCallingIdentity();
            try {
                app = shell().getPackageManager().getPackageUid(APP_PACKAGE, 0);
                appUid = app;
            } catch (Throwable e) {
                return false;
            } finally {
                Binder.restoreCallingIdentity(id);
            }
        }
        return uid == app;
    }

    // ================================================================ IHost

    @Override
    public void destroy() {
        Log.i(T, "destroy");
        synchronized (lock) {
            stopWatchLocked();
        }
        System.exit(0);
    }

    @Override
    public String hello(IBinder c) {
        synchronized (lock) {
            if (c != null && client == null) {
                try {
                    c.linkToDeath(() -> {
                        Log.i(T, "app died → exit");
                        synchronized (lock) {
                            stopWatchLocked();
                        }
                        System.exit(0);
                    }, 0);
                    client = c;
                } catch (RemoteException e) {
                    // 앱이 이미 죽었다: Shizuku 서버가 없을 수도 있으니(앱은 서버가 꺼진 뒤에도 살아 있는 도우미를 쓴다)
                    // 남에게 기대지 않고 여기서 끝낸다(외부 검증 지적)
                    Log.i(T, "app already dead at hello → exit");
                    stopWatchLocked();
                    System.exit(0);
                }
            }
        }
        return "uid=" + Process.myUid() + " pid=" + Process.myPid();
    }

    @Override
    public long read(int sub, int reason) {
        long id = Binder.clearCallingIdentity();
        try {
            return tm(sub).getAllowedNetworkTypesForReason(reason);
        } catch (Throwable e) {
            Log.w(T, "read reason=" + reason + ": " + name(e));
            return -1;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    @Override
    public boolean writeCarrier(int sub, long mask) {
        long id = Binder.clearCallingIdentity();
        try {
            tm(sub).setAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_CARRIER, mask);
            return true;
        } catch (Throwable e) {
            Log.w(T, "writeCarrier: " + name(e));
            return false;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    @Override
    public int callState(int sub) {
        long id = Binder.clearCallingIdentity();
        try {
            return tm(sub).getCallStateForSubscription();
        } catch (Throwable e) {
            Log.w(T, "callState: " + name(e));
            return -1;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    @Override
    public int activeSims() {
        long id = Binder.clearCallingIdentity();
        try {
            return sm().getActiveSubscriptionInfoCount();
        } catch (Throwable e) {
            Log.w(T, "activeSims: " + name(e));
            return -1;
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    @Override
    public String watch(int sub, IBinder sinkBinder) {
        if (sinkBinder == null) return "받을 곳이 없음";
        synchronized (lock) {
            stopWatchLocked();
            long id = Binder.clearCallingIdentity();
            try {
                TelephonyManager t = tm(sub);
                Fwd f = new Fwd(ISink.Stub.asInterface(sinkBinder));
                ExecutorService ex = Executors.newSingleThreadExecutor();
                Throwable regEx = null;
                try {
                    t.registerTelephonyCallback(ex, f);
                } catch (Throwable e) {
                    // 등록 직후 AppOps 기록 경로가 shell 신분에서 예외를 던져도 등록 자체는 된 경우가 있다(poc 실측) → 사건 도착으로 판정
                    regEx = e;
                }
                if (!f.awaitFirst(FIRST_EVENT_MS)) {
                    try {
                        t.unregisterTelephonyCallback(f);
                    } catch (Throwable ignored) {
                        // 등록이 안 됐으면 풀 것도 없다
                    }
                    ex.shutdownNow();
                    String why = regEx != null ? "등록 실패: " + name(regEx) : "2.5초 안에 첫 사건이 오지 않음";
                    Log.w(T, "watch failed: " + why);
                    return why;
                }
                IBinder.DeathRecipient dr = () -> {
                    synchronized (lock) {
                        stopWatchLocked();
                    }
                };
                try {
                    sinkBinder.linkToDeath(dr, 0);
                } catch (RemoteException e) {
                    try {
                        t.unregisterTelephonyCallback(f);
                    } catch (Throwable ignored) {
                        // 무시
                    }
                    ex.shutdownNow();
                    return "앱 연결이 끊김";
                }
                watchTm = t;
                fwd = f;
                watchExec = ex;
                watchSink = sinkBinder;
                sinkDeath = dr;
                Log.i(T, "watch ok sub=" + sub + (regEx != null ? " (등록 호출 예외 있었으나 사건 도착)" : ""));
                return null;
            } catch (Throwable e) {
                Log.w(T, "watch: " + name(e));
                return "관측 준비 실패: " + name(e);
            } finally {
                Binder.restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void unwatch() {
        synchronized (lock) {
            stopWatchLocked();
        }
    }

    // ================================================================ 내부

    private void stopWatchLocked() {
        if (fwd != null) fwd.dead = true;
        if (watchTm != null && fwd != null) {
            long id = Binder.clearCallingIdentity();
            try {
                watchTm.unregisterTelephonyCallback(fwd);
            } catch (Throwable ignored) {
                // 이미 풀렸으면 무시
            } finally {
                Binder.restoreCallingIdentity(id);
            }
        }
        if (watchExec != null) watchExec.shutdownNow();
        if (watchSink != null && sinkDeath != null) {
            try {
                watchSink.unlinkToDeath(sinkDeath, 0);
            } catch (Throwable ignored) {
                // 이미 죽었으면 무시
            }
        }
        watchTm = null;
        fwd = null;
        watchExec = null;
        watchSink = null;
        sinkDeath = null;
    }

    private Context shell() throws Exception {
        synchronized (lock) {
            if (shell == null) shell = ShellContext.create();
            return shell;
        }
    }

    /** 컨텍스트가 com.android.shell인 SIM별 TelephonyManager(숨은 생성자). */
    private TelephonyManager tm(int sub) throws Exception {
        synchronized (lock) {
            TelephonyManager t = tms.get(sub);
            if (t == null) {
                Constructor<TelephonyManager> c = TelephonyManager.class.getDeclaredConstructor(Context.class, int.class);
                c.setAccessible(true);
                t = c.newInstance(shell(), sub);
                tms.put(sub, t);
            }
            return t;
        }
    }

    /** 컨텍스트가 com.android.shell인 SubscriptionManager(숨은 생성자). */
    private SubscriptionManager sm() throws Exception {
        synchronized (lock) {
            if (sm == null) {
                Constructor<SubscriptionManager> c = SubscriptionManager.class.getDeclaredConstructor(Context.class);
                c.setAccessible(true);
                sm = c.newInstance(shell());
            }
            return sm;
        }
    }

    private static String name(Throwable e) {
        Throwable c = e;
        while (c instanceof InvocationTargetException && c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }

    /** 전화 상태 사건을 앱(ISink)으로 그대로 넘긴다. 앱 쪽 Watcher가 받는 것과 같은 일곱 가지. */
    private static final class Fwd extends TelephonyCallback implements
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.PhysicalChannelConfigListener,
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DataActivityListener,
            TelephonyCallback.DataConnectionStateListener,
            TelephonyCallback.CallStateListener,
            TelephonyCallback.DisplayInfoListener {
        private final ISink sink;
        private final CountDownLatch first = new CountDownLatch(1);
        volatile boolean dead;

        Fwd(ISink sink) {
            this.sink = sink;
        }

        boolean awaitFirst(long ms) {
            try {
                return first.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private interface Send {
            void run() throws RemoteException;
        }

        private void send(Send s) {
            first.countDown();
            if (dead) return;
            try {
                s.run();
            } catch (RemoteException | RuntimeException e) {
                // 앱이 죽었으면 죽음 알림이 정리한다
            }
        }

        @Override
        public void onServiceStateChanged(ServiceState ss) {
            send(() -> sink.onServiceState(ss));
        }

        @Override
        public void onPhysicalChannelConfigChanged(List<PhysicalChannelConfig> configs) {
            send(() -> sink.onPcc(configs));
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength s) {
            send(() -> sink.onSignal(s));
        }

        @Override
        public void onDataActivity(int direction) {
            send(() -> sink.onDataActivity(direction));
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            send(() -> sink.onDataConn(state, networkType));
        }

        @Override
        public void onCallStateChanged(int state) {
            send(() -> sink.onCallState(state));
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            send(() -> sink.onDisplay(info));
        }
    }
}
