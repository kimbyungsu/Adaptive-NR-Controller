package nrc;

/**
 * 실행 계층(DESIGN §5.6·§5.12): `cmd phone set-allowed-network-types-for-users`(USER 사유)만 쓴다.
 * **삼성 설정 화면 키(`preferred_network_mode*`)는 절대 쓰지 않는다** — 그 키는 사용자가 고른 고정값이다(§5.12).
 * **폰 설정을 바꾸는 코드는 이 클래스에만 있다.** 자동 재시도는 하지 않는다(§5.6.3).
 * 폰 입출력은 Io로 받는다(실제 = AndroidIo, 시험 = 가짜 폰) → 안드로이드 없이 PC에서 순서·판정을 시험한다.
 */
final class Actuator {
    /** 폰 입출력. */
    interface Io {
        /** 외부 명령 실행(셸을 거치지 않고 인자 배열로). 출력을 돌려준다. */
        String exec(String... argv);

        /** 대상 SIM의 USER 허용 타입. 실패 시 -1. */
        long user();

        /** 마스크 → 삼성 설정 화면 모드 번호. 실패 시 -1. */
        int modeOf(long mask);

        /** 모든 SIM 기준 통화 가드(통화·긴급·긴급 콜백). 문제 없으면 null. USER 쓰기 명령 바로 앞에서 부른다. */
        String callGuard();
    }

    /** 상태 파일 기록(DESIGN §5.7 불변식). */
    interface BeforeWrite {
        /** 쓰기 직전 선기록: 컨트롤러가 곧 쓸 USER 마스크와 모드 번호. 기록 실패면 false(→ 쓰지 않는다). */
        boolean record(long targetMask, int targetMode);

        /** USER가 실제로 바뀐 것을 확인한 즉시: 지금 컨트롤러가 남긴 값. */
        default void applied(long user, int mode) {
        }
    }

    static final class Outcome {
        final Policy.Kind kind;
        /** 쓰기 처리 뒤 관측한 USER 값(-1 = 읽기 실패). */
        final long user;
        /** 쓰는 사이 USER가 목표도 이전 값도 아닌 값이 됨(사용자·외부 변경 채택). */
        final boolean adopted;
        /** 쓰기 직전 설정 키(사용자 선택)가 기대와 달라 쓰지 않음. key = 지금 키 모드. */
        final boolean keyChanged;
        final int key;

        Outcome(Policy.Kind kind, long user) {
            this(kind, user, kind == Policy.Kind.ADOPTED, false, -1);
        }

        private Outcome(Policy.Kind kind, long user, boolean adopted, boolean keyChanged, int key) {
            this.kind = kind;
            this.user = user;
            this.adopted = adopted;
            this.keyChanged = keyChanged;
            this.key = key;
        }

        static Outcome keyChanged(int key, long user) {
            return new Outcome(Policy.Kind.ADOPTED, user, true, true, key);
        }
    }

    static final String KEY_LIST = "preferred_network_mode";
    static final int NR_BIT = 1 << 19;

    private final Log log;
    private final int slot;
    private final String keySub;
    private final Io io;

    Actuator(Log log, int subId, int slot, Io io) {
        this.log = log;
        this.slot = slot;
        this.keySub = KEY_LIST + subId;
        this.io = io;
    }

    int modeOf(long mask) {
        return io.modeOf(mask);
    }

    /** 사용자 선택을 담은 설정 키 이름(`preferred_network_mode<subId>`). 읽기·변경 감시에만 쓴다. */
    String keyName() {
        return keySub;
    }

    /**
     * 제어할 수 있는지(DESIGN §5.12 시작 검사): 슬롯이 있고, 설정 키를 읽을 수 있고, 모드 번호 변환이 되며
     * NR 포함/미포함이 서로 다른 모드다(= 사용자의 5G 우선과 LTE 우선을 키로 가릴 수 있다). 되면 null, 아니면 이유.
     */
    String checkKey(long original) {
        if (slot < 0) return "no_slot";
        if (original < 0) return "no_user_value";
        if (keyMode() < 0) return "no_key";
        int withNr = io.modeOf(original | NR_BIT);
        int withoutNr = io.modeOf(original & ~NR_BIT);
        if (withNr < 0 || withoutNr < 0) return "no_conversion";
        if (withNr == withoutNr) return "conversion_same";
        return null;
    }

    /** 지금 설정 키의 모드 번호(= 사용자가 설정 화면에서 고른 모드). 없거나 못 읽으면 -1. */
    int keyMode() {
        return parseMode(settingsGet(keySub));
    }

    /**
     * USER 쓰기(DESIGN §5.12). 1) 상태 파일 선기록(실패면 쓰지 않음) 2) 설정 키 재확인(보호 규칙 1:
     * 사용자가 방금 모드를 골랐으면 쓰지 않는다) 3) 통화 가드(명령 바로 앞) 4) cmd phone → USER 재확인.
     * expectedKey = 컨트롤러가 알고 있는 사용자 선택(키 모드). -1이면 키 확인을 하지 않는다.
     */
    Outcome write(long target, int expectedKey, String why, BeforeWrite before) {
        int mode = io.modeOf(target);
        long user0 = io.user();
        log.write("w_begin", "why", why, "target", target, "mode", mode, "user0", user0, "key", expectedKey);
        String bin = bin20(target);
        if (bin == null || slot < 0) {
            log.write("w_refused", "why", "precondition", "slot", slot);
            return new Outcome(Policy.Kind.FAILED, user0);
        }
        if (!before.record(target, mode)) {
            log.write("w_refused", "why", "state_file_write_failed");
            return new Outcome(Policy.Kind.FAILED, user0); // 선기록 없이 쓰지 않는다
        }
        if (expectedKey >= 0) {
            int k = keyMode();
            if (k < 0) {
                log.write("w_blocked", "guard", "key_unreadable");
                return new Outcome(Policy.Kind.BLOCKED, user0); // 사용자 선택을 확인할 수 없으면 쓰지 않고 나중에 다시
            }
            if (k != expectedKey) {
                log.write("w_key_changed", "expected", expectedKey, "key", k, "user", user0);
                return Outcome.keyChanged(k, user0);
            }
        }
        // 통화 가드는 준비(선기록·설정 키 조회)를 모두 마친 뒤, USER 쓰기 명령 바로 앞에서 본다
        String call = io.callGuard();
        if (call != null) {
            log.write("w_blocked", "guard", call);
            return new Outcome(Policy.Kind.BLOCKED, user0);
        }
        String out = io.exec("/system/bin/cmd", "phone", "set-allowed-network-types-for-users", "-s",
                String.valueOf(slot), bin);
        long u = io.user();
        boolean completed = out.contains("completed");
        if (u == target) {
            // 판정 기준은 재확인 값(실제 적용)이다. 출력이 completed가 아니어도(예: 응답 시간 초과) 적용됐으면 성공
            if (!completed) log.write("w_cmd_output_unexpected", "out", out.trim(), "user", u);
            before.applied(target, mode);
            log.write("w_ok", "user", u);
            return new Outcome(Policy.Kind.OK, u);
        }
        if (!completed && (u < 0 || u == user0)) {
            log.write("w_cmd_failed", "out", out.trim(), "user", u);
            return new Outcome(Policy.Kind.FAILED, u);
        }
        // 재확인이 목표와 다르고 이전 값과도 다름 → 쓰는 사이 사용자·외부 변경(§5.6.3)
        log.write("w_adopted", "user", u, "completed", completed);
        return new Outcome(Policy.Kind.ADOPTED, u);
    }

    // ---------------------------------------------------------------- 도우미

    /** `settings get global <key>`. 값이 없으면 null. */
    String settingsGet(String key) {
        String v = io.exec("/system/bin/settings", "get", "global", key).trim();
        return v.isEmpty() || "null".equals(v) ? null : v;
    }

    static String bin20(long mask) {
        if (mask < 0 || mask >= (1L << 20)) return null;
        String s = Long.toBinaryString(mask);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < 20; i++) sb.append('0');
        return sb.append(s).toString();
    }

    static int parseMode(String s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
