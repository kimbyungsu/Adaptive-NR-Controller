package nrc.controller;

/**
 * 통신사 칸(허용 망 CARRIER 사유)으로 쉬기·풀기 계획(DESIGN §5.13). 안드로이드 의존 없음(PC 시험: bash phoneapp/test.sh).
 * - 쉬기 = 통신사 칸에서 NR 비트만 뺀다. 사용자 칸(USER, 설정 화면이 보여 주는 값)은 절대 쓰지 않는다.
 * - 풀기 = 통신사 칸에 NR 비트만 다시 넣는다. 다른 비트는 건드리지 않는다.
 * - 통신사 칸에 NR이 없을 때 "우리가 뺐는지"는 쓰기 전에 남겨 둔 기록(oursBlocked)으로 가린다.
 *   우리 것이 아니면(통신사 앱 등) 건드리지 않고 외부 제한으로 본다.
 */
final class CarrierPlan {
    static final long NR_BIT = 1L << 19;

    private CarrierPlan() {
    }

    static boolean hasNr(long mask) {
        return mask >= 0 && (mask & NR_BIT) != 0;
    }

    /** 목표값: 지금 값에서 NR 비트만 바꾼다. 지금 값을 못 읽었으면(-1) -1. */
    static long target(long cur, boolean allowNr) {
        if (cur < 0) return -1;
        return allowNr ? (cur | NR_BIT) : (cur & ~NR_BIT);
    }

    enum Carrier {
        /** NR이 허용돼 있다. */
        OPEN,
        /** 우리가 NR을 뺀 채다. */
        OURS,
        /** 우리 기록 없이 NR이 빠져 있다(통신사 앱 등). */
        EXTERNAL,
        /** 읽지 못했다. */
        UNKNOWN
    }

    /** 통신사 칸 상태. oursBlocked = 우리가 NR을 뺀다고 먼저 기록했고 아직 풀었다는 기록이 없음. */
    static Carrier classify(long carrier, boolean oursBlocked) {
        if (carrier < 0) return Carrier.UNKNOWN;
        if (hasNr(carrier)) return Carrier.OPEN;
        return oursBlocked ? Carrier.OURS : Carrier.EXTERNAL;
    }

    /**
     * 우리 막음을 지금 풀어야 하는지. 엔진이 쉬는 중(쿨다운, NR 막음)이 아닌데 우리 막음이 남아 있으면 푼다.
     * 예: 앱이 다시 떴을 때, 사용자가 LTE 우선을 골랐을 때, 자동 제어를 껐을 때, Wi-Fi 되돌리기 뒤.
     */
    static boolean mustLift(Carrier c, boolean engineResting) {
        return c == Carrier.OURS && !engineResting;
    }
}
