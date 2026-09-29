package nrc;

/**
 * 사용자 선택 = 삼성 설정 화면 키(DESIGN §5.12). 컨트롤러는 그 키를 쓰지 않으므로 키가 곧 사용자의 고정값이다.
 * 안드로이드 의존 없음(모드 번호 변환은 인자로 받는다) → PC 시험(UserModeTest).
 */
final class UserMode {
    /** 마스크 → 설정 화면 모드 번호(실패 -1). */
    interface ModeOf {
        int of(long mask);
    }

    private UserMode() {
    }

    /**
     * 키 모드 k에 맞는 USER 마스크: 지금 값 u 그대로, 또는 u에서 NR만 더하거나 뺀 값. 짝이 없으면 -1.
     * 예: 키 9(LTE 우선)·USER 840583(5G 포함) → 316295. 키 26(5G 우선)·USER 316295(컨트롤러가 쉬게 함) → 840583.
     */
    static long maskForKey(int k, long u, long nrBit, ModeOf m) {
        if (k < 0 || u < 0) return -1;
        if (m.of(u) == k) return u;
        long with = u | nrBit;
        long without = u & ~nrBit;
        if (m.of(with) == k) return with;
        if (m.of(without) == k) return without;
        return -1;
    }

    /** 시작 판단 결과. */
    static final class Start {
        /** 사용자 선택(키)에 맞는 USER 마스크 = 원래 모드. */
        long original = -1;
        /** 사용자 선택은 5G 우선인데 USER에 NR이 없다(쉬는 중 멈춘 흔적 등) → 조건이 되면 재시험한다. */
        boolean leftoverLte;
        /** 사용자 선택(LTE 등)과 USER가 다르다(예: 종료 되돌리기와 사용자 LTE 선택이 겹침) → 이 값으로 맞춘다. -1 = 없음. */
        long alignTo = -1;
        /** 설정 키를 읽지 못함 → 사용자 선택을 가릴 수 없어 관찰만. */
        boolean keyUnavailable;
        String source = "";
    }

    /**
     * 시작 때 원래 모드 확정(DESIGN §5.12 "켤 때 원래 설정 판단"): 원래 모드 = 설정 키가 가리키는 모드.
     * 상태 파일은 판단에 쓰지 않는다(키가 사용자 선택의 유일한 근거).
     */
    static Start decide(long cur, int key, long nrBit, ModeOf m) {
        Start s = new Start();
        if (key < 0) {
            s.original = cur;
            s.keyUnavailable = true;
            s.source = "key_unreadable";
            return s;
        }
        long mask = maskForKey(key, cur, nrBit, m);
        if (mask < 0) {
            s.original = cur; // 키와 짝을 지을 수 없다(예: 다른 모드 계열) → 지금 값을 사용자 선택으로(예전 규칙)
            s.source = "unpaired";
            return s;
        }
        s.original = mask;
        if (mask == cur) {
            s.source = "consistent";
        } else if ((mask & nrBit) != 0) {
            s.leftoverLte = true;
            s.source = "key_nr_user_lte";
        } else {
            s.alignTo = mask;
            s.source = "key_lte_user_nr";
        }
        return s;
    }
}
