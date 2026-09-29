package nrc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Actuator(USER만 쓰기, DESIGN §5.12) 시험: 가짜 폰으로 순서·판정을 확인한다(JDK만 필요). 실행: bash daemon/test.sh */
public final class ActuatorTest {
    static final long NR = 840583;  // 레퍼런스 기기 5G 우선 마스크
    static final long LTE = 316295; // LTE 우선 마스크
    static final String K2 = "preferred_network_mode2";
    static final String KL = "preferred_network_mode";
    private static int failed;
    private static int passed;

    /** 가짜 폰: USER 값과 전역 설정. 명령을 흉내 내고, 특정 시점에 끼어드는 동작을 넣을 수 있다. */
    static final class FakePhone implements Actuator.Io {
        long user;
        final Map<String, String> settings = new HashMap<>();
        final List<String> execs = new ArrayList<>();
        boolean cmdFails;
        Long cmdResultOverride;   // cmd 뒤 USER가 이 값이 됨(쓰는 사이 사용자 변경 흉내)
        String call;              // 통화 가드 결과
        String cmdOutput;         // cmd 출력 바꾸기(예: "[timeout]") — USER는 그대로 적용됨
        Runnable beforeKeyRead;   // 설정 키 읽기 직전 끼어드는 동작(한 번)

        FakePhone(long user, String k2, String kl) {
            this.user = user;
            settings.put(K2, k2);
            settings.put(KL, kl);
        }

        @Override
        public String exec(String... a) {
            execs.add(String.join(" ", a));
            if (a[0].endsWith("cmd")) {
                if (cmdFails) return "failed";
                user = cmdResultOverride != null ? cmdResultOverride : Long.parseLong(a[5], 2);
                return cmdOutput != null ? cmdOutput : "completed\n";
            }
            if (a[1].equals("get")) {
                if (beforeKeyRead != null) {
                    Runnable r = beforeKeyRead;
                    beforeKeyRead = null;
                    r.run();
                }
                return settings.containsKey(a[3]) ? settings.get(a[3]) : "null";
            }
            if (a[1].equals("put")) settings.put(a[3], a[4]);
            return "";
        }

        @Override
        public long user() {
            return user;
        }

        @Override
        public int modeOf(long mask) {
            return mask == NR ? 26 : mask == LTE ? 9 : -1;
        }

        @Override
        public String callGuard() {
            return call;
        }

        int cmdCount() {
            int n = 0;
            for (String e : execs) if (e.contains("set-allowed-network-types-for-users")) n++;
            return n;
        }

        int putCount() {
            int n = 0;
            for (String e : execs) if (e.contains(" put ")) n++;
            return n;
        }
    }

    static final Log NOLOG = (ev, kv) -> { };

    static Actuator act(FakePhone ph) {
        return new Actuator(NOLOG, 2, 0, ph);
    }

    public static void main(String[] args) {
        okPathWritesOnlyUser();
        cmdFailed();
        adoptedAtReread();
        preRecordFailureDoesNotWrite();
        callBlocksBeforeCmd();
        callStartingDuringKeyReadBlocks();
        keyChangedJustBeforeWriteIsNotOverwritten();
        keyUnreadableBlocks();
        noKeyCheckWhenExpectedUnknown();
        appliedWithoutCompletedOutput();
        checkKey();
        System.out.println((failed == 0 ? "OK " : "FAILED " + failed + " / ") + passed + " checks passed");
        if (failed > 0) System.exit(1);
    }

    /** 쉬기: 설정 화면(키)은 사용자가 고른 5G 우선(26) 그대로, USER만 LTE. */
    static void okPathWritesOnlyUser() {
        FakePhone ph = new FakePhone(NR, "26", "26");
        int[] rec = {0};
        Actuator.Outcome o = act(ph).write(LTE, 26, "t", (m, md) -> ++rec[0] > 0);
        check("정상: OK", o.kind, Policy.Kind.OK);
        check("정상: USER LTE", ph.user, LTE);
        check("정상: 설정 화면 키는 사용자 값(26) 그대로", ph.settings.get(K2) + "/" + ph.settings.get(KL), "26/26");
        check("정상: 설정 쓰기 없음", ph.putCount(), 0);
        check("정상: 선기록 1회", rec[0], 1);
    }

    static void cmdFailed() {
        FakePhone ph = new FakePhone(NR, "26", "26");
        ph.cmdFails = true;
        Actuator.Outcome o = act(ph).write(LTE, 26, "t", (m, md) -> true);
        check("cmd 실패: FAILED(재시도 없음)", o.kind + "/" + ph.cmdCount(), "FAILED/1");
    }

    static void adoptedAtReread() {
        FakePhone ph = new FakePhone(NR, "26", "26");
        ph.cmdResultOverride = 123L; // 쓰는 사이 다른 값이 됨
        Actuator.Outcome o = act(ph).write(LTE, 26, "t", (m, md) -> true);
        check("재확인 ≠ 목표·이전 값: ADOPTED", o.kind + "/" + o.adopted + "/" + o.keyChanged, "ADOPTED/true/false");
    }

    static void preRecordFailureDoesNotWrite() {
        FakePhone ph = new FakePhone(NR, "26", "26");
        Actuator.Outcome o = act(ph).write(LTE, 26, "t", (m, md) -> false);
        check("선기록 실패: FAILED, 쓰기 없음", o.kind + "/" + ph.cmdCount(), "FAILED/0");
    }

    static void callBlocksBeforeCmd() {
        FakePhone ph = new FakePhone(NR, "26", "26");
        ph.call = "call";
        Actuator.Outcome o = act(ph).write(LTE, 26, "t", (m, md) -> true);
        check("통화: BLOCKED, 쓰기 없음", o.kind + "/" + ph.cmdCount() + "/" + ph.user, "BLOCKED/0/" + NR);
    }

    /** 검증 지적: 통화 확인 뒤 설정 키 조회(외부 명령) 중 시작된 통화를 놓치던 문제 → 통화 확인은 키 조회 뒤. */
    static void callStartingDuringKeyReadBlocks() {
        FakePhone ph = new FakePhone(NR, "26", "26");
        ph.beforeKeyRead = () -> ph.call = "call";
        Actuator.Outcome o = act(ph).write(LTE, 26, "t", (m, md) -> true);
        check("키 조회 중 시작된 통화: BLOCKED, 쓰기 없음", o.kind + "/" + ph.cmdCount() + "/" + ph.user, "BLOCKED/0/" + NR);
    }

    /**
     * 보호 규칙 1(§5.12): 쉬는 중(USER LTE·키 26) 사용자가 LTE 우선을 눌러 키가 9가 된 직후 재시험이 5G를 쓰려 하면,
     * 쓰기 직전 키 확인에서 멈추고 바뀐 키를 알린다.
     */
    static void keyChangedJustBeforeWriteIsNotOverwritten() {
        FakePhone ph = new FakePhone(LTE, "26", "26");
        ph.beforeKeyRead = () -> {
            ph.settings.put(K2, "9"); // 사용자가 LTE 우선을 고름(삼성: USER 316295 그대로 + 키 9)
            ph.settings.put(KL, "9");
        };
        Actuator.Outcome o = act(ph).write(NR, 26, "probe", (m, md) -> true);
        check("키 바뀜: 쓰지 않음", ph.cmdCount() + "/" + ph.user, "0/" + LTE);
        check("키 바뀜: 알림(ADOPTED+keyChanged, 새 키 9)", o.kind + "/" + o.keyChanged + "/" + o.key, "ADOPTED/true/9");
    }

    static void keyUnreadableBlocks() {
        FakePhone ph = new FakePhone(LTE, "null", "26");
        Actuator.Outcome o = act(ph).write(NR, 26, "probe", (m, md) -> true);
        check("키 못 읽음: BLOCKED, 쓰기 없음", o.kind + "/" + ph.cmdCount(), "BLOCKED/0");
    }

    static void noKeyCheckWhenExpectedUnknown() {
        FakePhone ph = new FakePhone(LTE, "9", "9");
        Actuator.Outcome o = act(ph).write(NR, -1, "t", (m, md) -> true);
        check("기대 키 없음(-1): 키 확인 없이 씀", o.kind + "/" + ph.cmdCount(), "OK/1");
    }

    static void appliedWithoutCompletedOutput() {
        FakePhone ph = new FakePhone(NR, "26", "26");
        ph.cmdOutput = "[timeout]";
        long[] applied = {-1};
        Actuator.Outcome o = act(ph).write(LTE, 26, "t", new Actuator.BeforeWrite() {
            public boolean record(long m, int md) {
                return true;
            }

            public void applied(long user, int mode) {
                applied[0] = user;
            }
        });
        check("응답 끊겨도 적용됨: OK", o.kind, Policy.Kind.OK);
        check("응답 끊겨도 적용됨: 적용 즉시 기록", applied[0], LTE);
    }

    static void checkKey() {
        check("키 사용 가능", act(new FakePhone(NR, "26", "26")).checkKey(NR), null);
        check("키 없음", act(new FakePhone(NR, "null", "26")).checkKey(NR), "no_key");
        check("변환 없음", act(new FakePhone(123, "26", "26")).checkKey(123), "no_conversion");
        check("키 이름", act(new FakePhone(NR, "26", "26")).keyName(), K2);
    }

    static void check(String what, Object got, Object want) {
        String g = String.valueOf(got);
        String w = String.valueOf(want);
        if (g.equals(w)) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": got " + g + ", want " + w);
        }
    }

    private ActuatorTest() {
    }
}
