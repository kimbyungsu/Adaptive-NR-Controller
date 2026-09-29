package nrc;

/**
 * 데이터 사용 구간 계산(안드로이드 의존 없음 → PC에서 바로 시험, daemon/test).
 * - 활동(IN/OUT/INOUT)이 gapMs 넘게 멈추면 한 구간이 끝난다. gapMs 이하의 멈춤은 구간 안에 포함한다.
 * - 주기 기록(flush)은 논리 구간을 끊지 않고 지금까지를 조각으로 내보낸다. 조각은 앞 조각 끝에서 바로 이어지므로
 *   PC가 이어 붙이면 원래 구간과 같다.
 * 시각 t는 부팅 후 경과(ms), wall은 같은 순간의 벽시계(ms). 조각의 시작은 벽시계로 내보낸다.
 */
final class UseSegments {
    interface Sink {
        void segment(long fromWall, long ms);
    }

    private final long gapMs;
    private final Sink sink;
    private boolean active;
    private long lastActiveAt = -1; // 마지막으로 활동이 확인된 시각(활동 중이면 가장 최근 알림, 멈춘 뒤면 멈춘 시각)
    private long openAt = -1;       // 아직 내보내지 않은 부분의 시작(-1 = 열린 구간 없음)
    private long openWall;

    UseSegments(long gapMs, Sink sink) {
        this.gapMs = gapMs;
        this.sink = sink;
    }

    void activity(long t, long wall, boolean isActive) {
        if (isActive) {
            closeIfIdle(t);
            if (openAt < 0) {
                openAt = t;
                openWall = wall;
            }
            active = true;
            lastActiveAt = t;
        } else if (active) {
            active = false;
            lastActiveAt = t;
        }
    }

    /** 마지막 데이터 활동 이후 경과(ms). 지금 활동 중이면 0, 기록이 없으면 -1. */
    long sinceActive(long t) {
        if (active) return 0;
        return lastActiveAt < 0 ? -1 : t - lastActiveAt;
    }

    /** 멈춘 지 gapMs가 넘었으면 구간을 닫는다. */
    void closeIfIdle(long t) {
        if (openAt >= 0 && !active && t - lastActiveAt > gapMs) {
            emit(lastActiveAt);
            openAt = -1;
        }
    }

    /** 주기 기록: 끝난 구간은 닫고, 진행 중(또는 gapMs 안의 멈춤)이면 구간을 유지한 채 지금까지를 내보낸다. */
    void flush(long t) {
        if (openAt < 0) return;
        if (!active && t - lastActiveAt > gapMs) {
            closeIfIdle(t);
            return;
        }
        emit(active ? t : lastActiveAt);
    }

    /** 종료: 열린 구간을 지금(활동 중) 또는 마지막 활동 시각에서 닫는다. */
    void shutdown(long t) {
        if (openAt < 0) return;
        emit(active ? t : lastActiveAt);
        openAt = -1;
    }

    private void emit(long end) {
        if (end <= openAt) return;
        sink.segment(openWall, end - openAt);
        openWall += end - openAt;
        openAt = end;
    }
}
