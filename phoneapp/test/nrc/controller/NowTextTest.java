package nrc.controller;

/** 관측 화면 "지금" 칸의 글 시험. 실행: bash phoneapp/test.sh */
public final class NowTextTest {
    private static int fails;
    private static int n;

    public static void main(String[] a) {
        TileText on = TileText.of(true, null, TileText.MODE_NR, false, null, true, true);
        TileText off = TileText.of(false, null, TileText.MODE_NR, false, null, true, false);
        long now = 1_000_000;

        // 엔진 없음: 타일 글이 머리글, 순정 동작 안내
        NowText t = NowText.of(null, off, now);
        is(t.headline.equals("자동 제어 꺼짐") && t.next.contains("타일을 한 번 탭"), "엔진 없음 · 꺼짐");

        // 감시 중: 판단 기준과 지금 센 끊김, 가장 오래된 끊김이 빠지는 시각
        Live l = base();
        l.state = "WATCH";
        l.drops = 1;
        l.oldestDrop = now - 30_000;
        t = NowText.of(l, on, now);
        is(t.headline.equals("5G 감시 중 · 지켜보는 중"), "감시 머리글 " + t.headline);
        is(has(t, "2분 안에 5G가 3번"), "판단 기준 문장");
        is(has(t, "지금: 1 / 3번(가장 오래된 끊김은 1:30 뒤 빠짐)"), "지금 센 끊김");
        is(has(t, "실제 연결: 5G(5G 칸 붙음) · 상단바 표시: 5G"), "실제 연결과 상단바 따로");

        // 판단 쉬는 중(화면 꺼짐)
        l.hold = "screen_off";
        t = NowText.of(l, on, now);
        is(has(t, "판단 쉬는 중: 화면이 꺼져 있음") && t.next.contains("끝나면 다시 셉니다"), "판단 쉼");
        l.hold = null;

        // Wi-Fi
        l.wifi = true;
        t = NowText.of(l, on, now);
        is(t.headline.equals("Wi-Fi · 대기"), "Wi-Fi");
        l.wifi = false;

        // 쉬는 중: 이유·시작·남은 시간
        l.state = "COOLDOWN";
        l.restWhy = "drops";
        l.coolUntil = now + 125_000;
        l.nrActual = false;
        l.display = 1;
        t = NowText.of(l, on, now);
        is(t.headline.equals("LTE로 쉬는 중"), "쉬기 머리글");
        is(has(t, "이유: 데이터를 쓰는 중 2분 안에 5G가 3번 이상 끊김"), "쉬기 이유");
        is(has(t, "남은 휴식: 2:05"), "남은 휴식");
        is(has(t, "실제 연결: LTE · 상단바 표시: LTE+"), "쉬는 중 연결");
        l.coolUntil = now - 1;
        l.hold = "screen_off";
        t = NowText.of(l, on, now);
        is(has(t, "휴식 끝") && has(t, "재시험은 이 조건이 끝난 뒤: 화면이 꺼져 있음"), "휴식 끝·보류");
        l.hold = null;

        // 재시험: 데이터 사용 확인 진행, 끊김, 마감
        l.state = "PROBE";
        l.useMs = 37_000;
        l.probeDrops = 1;
        l.evalStart = now - 60_000;
        t = NowText.of(l, on, now);
        is(t.headline.equals("5G 재시험 중"), "재시험 머리글");
        is(has(t, "데이터 사용 확인: 37 / 60초"), "재시험 진행");
        is(has(t, "재시험 중 끊김: 1 / 2번(2번이면 다시 쉼)"), "재시험 끊김");
        is(has(t, "판정 마감까지: 4:00"), "판정 마감");
        l.evalStart = -1;
        t = NowText.of(l, on, now);
        is(has(t, "자리 잡는 중"), "정착 중");

        // LTE 우선
        l.state = "INACTIVE";
        l.userNr = false;
        t = NowText.of(l, on, now);
        is(t.headline.equals("LTE 우선 · 대기") && has(t, "사용자 선택: LTE 우선"), "LTE 우선");

        // 관찰만(SIM 2개)과 문제 표시
        l.state = "OBSERVE";
        l.userNr = true;
        l.blocked = "dual_sim";
        l.problem = "통신사 칸 확인 필요";
        t = NowText.of(l, on, now);
        is(has(t, "이유: SIM이 2개(한 개일 때만 제어)") && has(t, "문제: 통신사 칸 확인 필요"), "관찰만·문제");

        is(NowText.display(3).equals("5G") && NowText.display(0).equals("LTE") && NowText.display(-1).equals("알 수 없음"), "표시 말");
        System.out.println(fails == 0 ? "NowTextTest OK (" + n + ")" : "NowTextTest FAILED " + fails);
        if (fails != 0) System.exit(1);
    }

    private static Live base() {
        Live l = new Live();
        l.userNr = true;
        l.nrActual = true;
        l.display = 3;
        l.nDrop = 3;
        l.windowMs = 120_000;
        l.oldestDrop = -1;
        l.pActive = 60_000;
        l.pMax = 300_000;
        l.nProbe = 2;
        l.evalStart = -1;
        l.stateSinceWall = 0;
        return l;
    }

    private static boolean has(NowText t, String s) {
        for (String x : t.lines) if (x.contains(s)) return true;
        return false;
    }

    private static void is(boolean ok, String what) {
        n++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }
}
