package nrc.controller;

/**
 * 빠른 설정 타일에 보일 모양(켜짐/꺼짐/사용 불가)과 부제목(DESIGN §5.13 "상태 표시 구조", 2026-09-30 사용자 결정).
 * 타일 색은 시스템이 정하므로 상태는 글자로 알린다. 안드로이드 의존 없음(PC 시험: bash phoneapp/test.sh).
 */
final class TileText {
    enum Look { ACTIVE, INACTIVE, UNAVAILABLE }

    static final String LABEL = "5G 자동";

    /** 사용자가 고른 모드: 모름 / LTE 우선 / 5G 우선. */
    static final int MODE_UNKNOWN = -1, MODE_LTE = 0, MODE_NR = 1;

    /** 컨트롤러가 하는 일(엔진 연결 뒤 채워진다). */
    static final String PHASE_RESTING = "resting", PHASE_PROBING = "probing", PHASE_OBSERVE = "observe";

    /** 자동 제어는 켜져 있는데 상주 서비스가 없을 때. 이때 타일 탭은 끄기가 아니라 다시 시작이다. */
    static final String STOPPED = "멈춤 · 눌러서 다시 시작";
    /** 서비스는 떠 있는데 엔진이 아직 돌지 않을 때(시작 중, 또는 끄기를 마무리한 뒤 다시 켜는 중). */
    static final String STARTING = "시작하는 중";

    final Look look;
    final String subtitle;

    private TileText(Look look, String subtitle) {
        this.look = look;
        this.subtitle = subtitle;
    }

    /**
     * @param auto    자동 제어 켜짐(타일 탭으로 바꾼다)
     * @param problem 사용자가 PC로 풀어야 하는 문제 문구(없으면 null). 예: "PC 연결 필요"
     * @param mode    사용자가 삼성 설정에서 고른 모드(MODE_*)
     * @param wifi    인터넷이 Wi-Fi로 나가는 중
     * @param phase   컨트롤러가 하는 일(PHASE_*, 없으면 null = 5G 관리 중)
     * @param alive   상주 서비스가 지금 떠 있는지. 자동 제어가 켜져 있는데 서비스가 없으면 관리 중인 척하지 않는다
     * @param engine  판단 엔진이 지금 돌고 있는지. 서비스는 떠 있어도 엔진이 없으면(시작 전·끄기 마무리 중) 관리 중인 척하지 않는다
     */
    static TileText of(boolean auto, String problem, int mode, boolean wifi, String phase, boolean alive, boolean engine) {
        if (problem != null) return new TileText(Look.UNAVAILABLE, problem);
        if (!auto) return new TileText(Look.INACTIVE, "자동 제어 꺼짐");
        if (!alive) return new TileText(Look.INACTIVE, STOPPED);
        if (!engine) return new TileText(Look.INACTIVE, STARTING);
        if (mode == MODE_UNKNOWN) return new TileText(Look.ACTIVE, "확인 중");
        if (mode == MODE_LTE) return new TileText(Look.ACTIVE, "LTE 우선 · 대기");
        if (wifi) return new TileText(Look.ACTIVE, "Wi-Fi · 대기");
        if (PHASE_OBSERVE.equals(phase)) return new TileText(Look.ACTIVE, "관찰만 (제어 불가)");
        if (PHASE_RESTING.equals(phase)) return new TileText(Look.ACTIVE, "LTE로 쉬는 중");
        if (PHASE_PROBING.equals(phase)) return new TileText(Look.ACTIVE, "5G 확인 중");
        return new TileText(Look.ACTIVE, "5G 관리 중");
    }

    @Override
    public String toString() {
        return look + ":" + subtitle;
    }
}
