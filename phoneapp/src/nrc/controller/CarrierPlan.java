package nrc.controller;

/**
 * 통신사 칸(허용 망 CARRIER 사유)으로 쉬기·풀기 계획(DESIGN §5.13). 안드로이드 의존 없음(PC 시험: bash phoneapp/test.sh).
 * - 쉬기 = 통신사 칸에서 NR 비트만 뺀다. 사용자 칸(USER, 설정 화면이 보여 주는 값)은 절대 쓰지 않는다.
 * - 풀기 = 통신사 칸에 NR 비트만 다시 넣는다. 다른 비트는 건드리지 않는다.
 * - "우리 막음"은 SIM별로, 우리가 실제로 남긴 값(ownMask)까지 기억해 가린다(외부 검증 지적: 불 하나로는 통신사 앱이 나중에
 *   바꾼 제한이나 다른 SIM의 제한까지 우리 것으로 알고 풀었다). 지금 값이 우리가 남긴 값과 다르면 남의 제한으로 보고 손대지 않는다.
 */
final class CarrierPlan {
    static final long NR_BIT = 1L << 19;
    /** 앱 공개 API로 쓰면 이 비트(LTE_CA)가 빠져 저장된다(기기 실측 316295 → 54151). */
    static final long LTE_CA_BIT = 1L << 18;

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

    /**
     * 지금 칸 값이 우리가 남긴 값인지. 쓴 직후 다시 읽은 값을 기억하므로 보통 같다. 쓰기 직전 기록만 남기고 프로세스가 죽었으면
     * 기록은 목표값이고 저장값은 LTE_CA가 빠졌을 수 있어 그 경우도 우리 값으로 본다.
     */
    static boolean matchesOurs(long carrier, long ownMask) {
        return ownMask >= 0 && carrier >= 0 && (carrier == ownMask || carrier == (ownMask & ~LTE_CA_BIT));
    }

    enum Carrier {
        /** NR이 허용돼 있다. */
        OPEN,
        /** 우리가 남긴 값 그대로 NR이 빠져 있다. */
        OURS,
        /** NR이 빠져 있지만 우리가 남긴 값이 아니다(통신사 앱 등, 또는 우리 기록이 없음). */
        EXTERNAL,
        /** 읽지 못했다. */
        UNKNOWN
    }

    /** 이 SIM의 통신사 칸 상태. ownMask = 이 SIM에 우리가 남긴 값(없으면 -1). */
    static Carrier classify(long carrier, long ownMask) {
        if (carrier < 0) return Carrier.UNKNOWN;
        if (hasNr(carrier)) return Carrier.OPEN;
        return matchesOurs(carrier, ownMask) ? Carrier.OURS : Carrier.EXTERNAL;
    }

    /**
     * 우리 막음을 지금 풀어야 하는지. 엔진이 쉬는 중(쿨다운, NR 막음)이 아닌데 우리 막음이 남아 있으면 푼다.
     * 예: 앱이 다시 떴을 때, 사용자가 LTE 우선을 골랐을 때, 자동 제어를 껐을 때, Wi-Fi 되돌리기 뒤.
     */
    static boolean mustLift(Carrier c, boolean engineResting) {
        return c == Carrier.OURS && !engineResting;
    }
}
