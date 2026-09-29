package nrc;

/**
 * 상태 파일 내용과 그 규칙(DESIGN §5.6.4·§5.7). 안드로이드 의존 없음 → PC에서 시험(daemon/test).
 * §5.12부터 시작 때 원래 모드는 설정 키로 정한다(UserMode.decide). 이 파일은 컨트롤러가 남긴 값의 기록(긴급 복구·분석용)이다.
 * - original: 사용자가 고른 원래 USER 마스크
 * - lastWritten: 컨트롤러가 바꿔 두어 **지금도 남아 있는** 값(-1 = 없음)
 * - pending: 쓰기 직전 선기록한 시도 목표(쓰기 결과가 나오면 비운다. 남아 있으면 쓰는 중에 멈춘 것)
 * - clean: 정상 종료(원래 모드 복원까지 끝남) 여부
 */
final class Saved {
    boolean clean;
    long original = -1;
    int originalMode = -1;
    long lastWritten = -1;
    int lastWrittenMode = -1;
    long pending = -1;
    int pendingMode = -1;
    /** 이번 쓰기 거래가 시작되기 전의 lastWritten(롤백으로 거래 전 상태로 돌아가면 이 값으로 되돌린다). */
    private long prevLastWritten = -1;
    private int prevLastWrittenMode = -1;
    String state = "";
    int pid;
    int sub = -1;
    int slot = -1;

    /** 쓰기 직전 선기록. 한 거래 안의 두 번째 선기록(롤백)은 거래 전 값을 덮지 않는다. */
    void beginWrite(long target, int mode) {
        if (pending < 0) {
            prevLastWritten = lastWritten;
            prevLastWrittenMode = lastWrittenMode;
        }
        pending = target;
        pendingMode = mode;
    }

    /**
     * USER가 실제로 바뀐 것을 확인한 즉시 기록: 이 값이 지금 컨트롤러가 남긴 값이다.
     * 이후 단계(설정 키 쓰기·롤백 선기록)에서 멈추거나 실패해도 흔적을 잃지 않게 한다.
     */
    void applied(long user, int mode) {
        lastWritten = user;
        lastWrittenMode = mode;
    }

    /**
     * 쓰기 결과 반영. 성공이면 목표가 컨트롤러가 남긴 값이 된다. 실패·막힘·롤백(스냅샷으로 복귀)이면 이전 값 그대로다.
     * 사용자 선택 채택이면 컨트롤러가 남긴 값은 없다. 롤백 실패(불확실)면 지금 USER 값을 컨트롤러 흔적으로 보고
     * 다음 시작이 사용자에게 묻게 한다(원래 모드와 같으면 물을 것이 없다).
     */
    void endWrite(Policy.Kind kind, long target, int mode, long observedUser, boolean adopted) {
        if (adopted || kind == Policy.Kind.ADOPTED) {
            lastWritten = -1;
            lastWrittenMode = -1;
        } else if (kind == Policy.Kind.OK) {
            lastWritten = target;
            lastWrittenMode = mode;
        } else if (kind == Policy.Kind.ROLLED_BACK) {
            lastWritten = prevLastWritten; // 거래 전 상태로 돌아갔다
            lastWrittenMode = prevLastWrittenMode;
        } else if (kind == Policy.Kind.BROKEN) {
            if (observedUser >= 0) {
                lastWritten = observedUser; // 불확실: 지금 값을 컨트롤러 흔적으로 본다(원래 모드와 같으면 물을 것 없음)
                lastWrittenMode = -1;
            } else {
                lastWritten = target;
                lastWrittenMode = mode;
            }
        } else if (observedUser < 0) {
            lastWritten = target; // 실패·막힘인데 지금 값을 읽지 못함: 적용됐을 수 있으니 흔적으로 남긴다(보수적)
            lastWrittenMode = mode;
        }
        // FAILED·BLOCKED이고 지금 값을 읽었으면: 적용됐다면 applied()가 이미 기록했다. 아니면 이전 값 그대로
        pending = -1;
        pendingMode = -1;
    }

    /** 사용자가 모드를 골랐다(또는 "LTE로 계속 쓰기"로 확정): 그 값이 원래 모드이고 컨트롤러가 남긴 값은 없다. */
    void userChose(long v, int mode) {
        original = v;
        originalMode = mode;
        lastWritten = -1;
        lastWrittenMode = -1;
        pending = -1;
        pendingMode = -1;
    }

    /** 예전 시작 판단(상태 파일 기준)의 종료 코드. §5.12부터 시작 판단은 설정 키 기준(UserMode.decide)이라 쓰지 않는다. */
    static final int EXIT_NEED_LEFTOVER_DECISION = 6;
    static final int EXIT_STATE_UNREADABLE = 7;
}
