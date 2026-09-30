package nrc.controller;

/**
 * "오늘의 활동" 셈(앱 안 관측 화면). 판단 규칙의 상태 변화에서 센다. 안드로이드 의존 없음(PC 시험).
 * 확실히 관측한 사실만 센다(배터리 절약 같은 추정값은 없다).
 * - 쉬기 = COOLDOWN으로 들어간 횟수, 총 쉰 시간 = COOLDOWN에 머문 시간(날이 바뀌면 자정부터 다시 잰다).
 * - 재시험 = PROBE로 들어간 횟수. 통과 = probe_pass, 판정 못 함 = probe_undecided, 실패 = 재시험 중 끊김으로 다시 쉬기.
 * - 판단에 센 끊김 = 판단 규칙이 실제로 센 5G 끊김(화면 켜짐·데이터 쓰는 중).
 */
final class DaySummary {
    String day = "";
    int rests;
    long restMs;
    int probes;
    int pass;
    int fail;
    int undecided;
    int countedDrops;
    long lastActionWall = -1;
    String lastAction = "";
    private long restSinceWall = -1;

    /** 날이 바뀌었으면 셈을 비운다. 쉬는 중에 자정을 넘기면 오늘 쉰 시간은 자정부터 잰다. */
    void roll(String today, long dayStartWall) {
        if (today.equals(day)) return;
        day = today;
        rests = 0;
        restMs = 0;
        probes = 0;
        pass = 0;
        fail = 0;
        undecided = 0;
        countedDrops = 0;
        if (restSinceWall >= 0) restSinceWall = dayStartWall;
    }

    void onState(long wall, String from, String to, String why) {
        if ("COOLDOWN".equals(from) && restSinceWall >= 0) {
            restMs += Math.max(0, wall - restSinceWall);
            restSinceWall = -1;
        }
        if ("COOLDOWN".equals(to)) {
            rests++;
            restSinceWall = wall;
            if ("probe_drops".equals(why) || "probe_oos".equals(why)) fail++;
        }
        if ("PROBE".equals(to)) probes++;
        if ("probe_pass".equals(why)) pass++;
        if ("probe_undecided".equals(why)) undecided++;
    }

    /**
     * 판단 규칙이 상태를 바꾸지 않고 끝났을 때(자동 제어 끄기·폰 꺼짐·앱 종료) 쉬기 구간을 닫는다.
     * 외부 검증 지적: 쉬는 중에 끄면 COOLDOWN을 나가는 상태 사건이 없어 쉰 시간이 계속 늘었다.
     */
    void closeRest(long wall) {
        if (restSinceWall < 0) return;
        restMs += Math.max(0, wall - restSinceWall);
        restSinceWall = -1;
    }

    void onCountedDrop() {
        countedDrops++;
    }

    void onAction(long wall, String text) {
        lastActionWall = wall;
        lastAction = text;
    }

    /** 지금까지 쉰 시간(지금 쉬는 중이면 지금까지 포함). */
    long restMsNow(long wall) {
        return restMs + (restSinceWall >= 0 ? Math.max(0, wall - restSinceWall) : 0);
    }

    boolean resting() {
        return restSinceWall >= 0;
    }

    /** 저장 형식(한 줄). */
    String save() {
        return day + "|" + rests + "|" + restMs + "|" + probes + "|" + pass + "|" + fail + "|" + undecided + "|"
                + countedDrops + "|" + lastActionWall + "|" + restSinceWall + "|" + lastAction.replace('|', '/');
    }

    static DaySummary load(String s) {
        DaySummary d = new DaySummary();
        if (s == null) return d;
        String[] f = s.split("\\|", -1);
        if (f.length < 11) return d;
        try {
            d.day = f[0];
            d.rests = Integer.parseInt(f[1]);
            d.restMs = Long.parseLong(f[2]);
            d.probes = Integer.parseInt(f[3]);
            d.pass = Integer.parseInt(f[4]);
            d.fail = Integer.parseInt(f[5]);
            d.undecided = Integer.parseInt(f[6]);
            d.countedDrops = Integer.parseInt(f[7]);
            d.lastActionWall = Long.parseLong(f[8]);
            d.restSinceWall = Long.parseLong(f[9]);
            d.lastAction = f[10];
        } catch (NumberFormatException e) {
            return new DaySummary();
        }
        return d;
    }
}
