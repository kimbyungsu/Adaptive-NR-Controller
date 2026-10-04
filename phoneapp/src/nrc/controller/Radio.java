package nrc.controller;

import android.content.Context;
import android.os.RemoteException;
import android.telephony.PhysicalChannelConfig;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 폰의 허용 망 칸 읽기·쓰기, 통화 확인, 전화 상태 지켜보기. 통로가 둘이다(DESIGN §5.16 길 사다리):
 * - 길 1 = 통신사 인정(§5.13): 이 앱 프로세스가 공개 API로 직접 한다(open).
 * - 길 2 = Shizuku(§5.15): Shizuku가 shell 신분으로 띄운 도우미(HostService)가 대신 한다(shizuku). 앱은 통신사 권한이 없어도 된다.
 * 어느 길이든 같은 규칙:
 * - 읽기: 네 사유(USER 0 · POWER 1 · CARRIER 2 · ENABLE_2G 3). 설정 화면은 USER만 보여 준다.
 * - 쓰기: CARRIER만. USER는 절대 쓰지 않는다.
 * 공개 API로 쓰면 LTE_CA 비트(1<<18)가 빠져 저장된다(research §2.15). NR 비트만 보므로 판단에는 영향이 없다.
 */
final class Radio {
    static final int USER = 0, POWER = 1, CARRIER = 2, ENABLE_2G = 3;

    final int sub;
    private final TelephonyManager tm;
    private final SubscriptionManager sm;
    private final IHost host;
    /** 길 2에서 지금 사건을 받는 곳(새로 지켜보면 앞의 것은 버린다). */
    private Sink sink;

    private Radio(int sub, TelephonyManager tm, SubscriptionManager sm, IHost host) {
        this.sub = sub;
        this.tm = tm;
        this.sm = sm;
        this.host = host;
    }

    /** 길 1: 기본 데이터 SIM으로 연다. SIM이 아직 안 올라왔으면 null. */
    static Radio open(Context c) {
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(sub)) return null;
        TelephonyManager tm = c.getSystemService(TelephonyManager.class).createForSubscriptionId(sub);
        return new Radio(sub, tm, c.getSystemService(SubscriptionManager.class), null);
    }

    /** 길 2: 붙어 있는 도우미로 기본 데이터 SIM을 연다. SIM이 아직 안 올라왔으면 null. */
    static Radio shizuku(IHost host) {
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (host == null || !SubscriptionManager.isValidSubscriptionId(sub)) return null;
        return new Radio(sub, null, null, host);
    }

    boolean viaShizuku() {
        return host != null;
    }

    /** 지금 바꿀 통로가 있는지: 길 1은 통신사 인정, 길 2는 도우미가 살아 있는지. */
    boolean privileged() {
        if (host != null) {
            try {
                return host.asBinder().pingBinder();
            } catch (RuntimeException e) {
                return false;
            }
        }
        try {
            return tm.hasCarrierPrivileges();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 통로를 잃었을 때 타일·화면에 보일 짧은 말. */
    String lostText() {
        return host != null ? "Shizuku 통로 끊김" : "다시 설정 필요";
    }

    /** 통로가 없을 때 자가 점검 등이 보일 말. */
    String noPathText() {
        return host != null ? "Shizuku 통로가 없음(Shizuku가 꺼졌거나 승인이 없음)" : "5G/LTE 전환 권한이 없음(처음 설정 필요)";
    }

    /** 사유별 허용 망. 못 읽으면 -1. */
    long read(int reason) {
        if (host != null) {
            try {
                return host.read(sub, reason);
            } catch (RemoteException | RuntimeException e) {
                return -1;
            }
        }
        try {
            return tm.getAllowedNetworkTypesForReason(reason);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** 통신사 칸에 쓴다. 호출이 거절되면 false(결과 확인은 부른 쪽이 다시 읽어서 한다). */
    boolean writeCarrier(long mask) {
        if (host != null) {
            try {
                return host.writeCarrier(sub, mask);
            } catch (RemoteException | RuntimeException e) {
                return false;
            }
        }
        try {
            tm.setAllowedNetworkTypesForReason(CARRIER, mask);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 쓰기 직전 통화 확인. 통화 중이면 "call", 확인 불가면 "call_unknown"(이때도 쓰지 않는다), 괜찮으면 null. */
    String callGuard() {
        int s;
        if (host != null) {
            try {
                s = host.callState(sub);
            } catch (RemoteException | RuntimeException e) {
                return "call_unknown";
            }
            if (s < 0) return "call_unknown";
        } else {
            try {
                s = tm.getCallStateForSubscription();
            } catch (RuntimeException e) {
                return "call_unknown";
            }
        }
        return s == TelephonyManager.CALL_STATE_IDLE ? null : "call";
    }

    /** 켜진 SIM 수. 모르면 -1. */
    int activeSims() {
        if (host != null) {
            try {
                return host.activeSims();
            } catch (RemoteException | RuntimeException e) {
                return -1;
            }
        }
        try {
            return sm.getActiveSubscriptionInfoCount();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * 전화 상태 지켜보기를 시작한다. 사건은 exec(엔진 작업 스레드)에서 w로 온다. 성공하면 null, 아니면 실패 이유.
     * 길 2는 도우미가 받은 사건을 그대로 넘겨받아 같은 Watcher 메서드를 부른다.
     */
    String watch(Executor exec, Watcher w) {
        if (host == null) {
            try {
                tm.registerTelephonyCallback(exec, w);
                return null;
            } catch (RuntimeException e) {
                return String.valueOf(e);
            }
        }
        Sink s = new Sink(exec, w);
        synchronized (this) {
            if (sink != null) sink.dead = true;
            sink = s;
        }
        String err;
        try {
            err = host.watch(sub, s);
        } catch (RemoteException | RuntimeException e) {
            err = "도우미 호출 실패: " + e;
        }
        if (err != null) s.dead = true;
        return err;
    }

    /** 지켜보기를 멈춘다(늦게 도착하는 사건도 버린다). */
    void unwatch(Watcher w) {
        if (host == null) {
            try {
                tm.unregisterTelephonyCallback(w);
            } catch (RuntimeException ignored) {
                // 이미 풀렸으면 무시
            }
            return;
        }
        synchronized (this) {
            if (sink != null) sink.dead = true;
            sink = null;
        }
        try {
            host.unwatch();
        } catch (RemoteException | RuntimeException ignored) {
            // 도우미가 이미 없으면 지켜보기도 없다
        }
    }

    /** 도우미가 넘긴 사건을 엔진 작업 스레드의 Watcher로 옮긴다. 버려진 뒤(dead)에는 아무것도 하지 않는다. */
    private static final class Sink extends ISink.Stub {
        private final Executor exec;
        private final Watcher w;
        volatile boolean dead;

        Sink(Executor exec, Watcher w) {
            this.exec = exec;
            this.w = w;
        }

        private void post(Runnable r) {
            if (dead) return;
            try {
                exec.execute(() -> {
                    if (!dead) r.run();
                });
            } catch (RejectedExecutionException ignored) {
                // 엔진이 끝나는 중
            }
        }

        @Override
        public void onServiceState(ServiceState ss) {
            if (ss != null) post(() -> w.onServiceStateChanged(ss));
        }

        @Override
        public void onPcc(List<PhysicalChannelConfig> configs) {
            if (configs != null) post(() -> w.onPhysicalChannelConfigChanged(configs));
        }

        @Override
        public void onSignal(SignalStrength s) {
            if (s != null) post(() -> w.onSignalStrengthsChanged(s));
        }

        @Override
        public void onDataActivity(int direction) {
            post(() -> w.onDataActivity(direction));
        }

        @Override
        public void onDataConn(int state, int networkType) {
            post(() -> w.onDataConnectionStateChanged(state, networkType));
        }

        @Override
        public void onCallState(int state) {
            post(() -> w.onCallStateChanged(state));
        }

        @Override
        public void onDisplay(TelephonyDisplayInfo info) {
            if (info != null) post(() -> w.onDisplayInfoChanged(info));
        }
    }
}
