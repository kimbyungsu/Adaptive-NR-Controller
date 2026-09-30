package nrc.controller;

/** 관측 화면 "지금" 칸의 글 시험(생활 말투, 2026-09-30). 실행: bash phoneapp/test.sh */
public final class NowTextTest {
    private static int fails;
    private static int n;

    public static void main(String[] a) {
        TileText on = TileText.of(true, null, TileText.MODE_NR, false, null, true, true);
        TileText off = TileText.of(false, null, TileText.MODE_NR, false, null, true, false);
        long now = 1_000_000;

        // 판단이 꺼져 있음: 타일 글이 머리글, 남은 막음이 없을 때만 순정
        NowText t = NowText.of(null, off, now, false, false);
        is(t.headline.equals("자동 제어 꺼짐") && t.next.contains("타일을 한 번 탭"), "꺼짐");
        is(has(t, "순정(폰 기본 동작)"), "남은 막음 없음 = 순정");
        t = NowText.of(null, off, now, true, false);
        is(has(t, "5G 막음이 아직 남아") && !has(t, "순정(폰") && t.next.contains("막음이 풀리면"), "남은 막음");
        TileText lost = TileText.of(true, "5G 막힘 · 다시 설정 필요", TileText.MODE_NR, false, null, true, false);
        t = NowText.of(null, lost, now, true, false);
        is(has(t, "문제: 5G 막힘 · 다시 설정 필요") && !has(t, "순정(폰"), "권한 상실·남은 막음");

        // 지켜보는 중: 기준·최근 끊김·빠지는 시각
        Live l = base();
        l.state = "WATCH";
        l.drops = 1;
        l.oldestDrop = now - 30_000;
        t = NowText.of(l, on, now, false, false);
        is(t.headline.equals("5G 사용 중 · 끊김이 있어 지켜보는 중"), "머리글 " + t.headline);
        is(has(t, "폰을 쓰는 중 2분 안에 5G가 3번 끊기면 LTE로 잠깐 쉬어요."), "기준 문장");
        is(has(t, "최근 2분 동안 끊김 1번 (3번이면 쉬기) · 가장 오래된 끊김은 1분 30초 뒤 셈에서 빠져요"), "최근 끊김");
        is(has(t, "지금 연결: 5G 사용 중 (상단바 표시: 5G)"), "지금 연결과 상단바");
        is(has(t, "고른 모드: 5G 우선 · 인터넷: 모바일 데이터"), "고른 모드·인터넷");
        l.state = "GOOD";
        t = NowText.of(l, on, now, false, false);
        is(t.headline.equals("5G 사용 중 · 안정적"), "안정적");
        l.state = "WATCH";

        // 확인 안 된 연결은 단정하지 않는다
        l.dataIn = false;
        t = NowText.of(l, on, now, false, false);
        is(has(t, "지금 연결: 서비스 없음"), "서비스 없음");
        l.dataIn = true;
        l.pccKnown = false;
        t = NowText.of(l, on, now, false, false);
        is(has(t, "지금 연결: 확인 중"), "확인 중");
        l.pccKnown = true;
        l.dataConnected = false;
        t = NowText.of(l, on, now, false, false);
        is(has(t, "인터넷: 연결 안 됨·확인 중"), "데이터 연결 없음");
        l.dataConnected = true;

        // 지켜보지 않음(화면 꺼짐)
        l.hold = "screen_off";
        t = NowText.of(l, on, now, false, false);
        is(has(t, "화면이 꺼져 있어 지금은 지켜보지 않아요") && t.next.contains("끝나면 다시 지켜봐요"), "화면 꺼짐");
        l.hold = null;

        // Wi-Fi
        l.wifi = true;
        t = NowText.of(l, on, now, false, false);
        is(t.headline.equals("Wi-Fi 사용 중 · 대기") && has(t, "인터넷: Wi-Fi"), "Wi-Fi");
        l.wifi = false;

        // 쉬는 중: 이유·시작·남은 시간
        l.state = "COOLDOWN";
        l.restWhy = "drops";
        l.coolUntil = now + 125_000;
        l.nrActual = false;
        l.display = 1;
        t = NowText.of(l, on, now, false, false);
        is(t.headline.equals("LTE로 잠깐 쉬는 중"), "쉬기 머리글");
        is(has(t, "이유: 폰을 쓰는 중 2분 안에 5G가 3번 이상 끊김"), "쉬기 이유");
        is(has(t, "2분 5초 뒤 5G 다시 확인"), "남은 시간");
        is(has(t, "지금 연결: LTE만 사용 중 (상단바 표시: LTE+)"), "쉬는 중 연결");
        l.coolUntil = now - 1;
        l.hold = "screen_off";
        t = NowText.of(l, on, now, false, false);
        is(has(t, "쉬는 시간 끝") && has(t, "화면이 꺼져 있어 지금은 지켜보지 않아요 — 이 조건이 끝나면 5G를 다시 확인해요"), "쉬는 시간 끝·보류");
        l.hold = null;

        // 다시 확인 중: 쓴 시간, 끊김, 판정까지
        l.state = "PROBE";
        l.useMs = 37_000;
        l.probeDrops = 1;
        l.evalStart = now - 60_000;
        t = NowText.of(l, on, now, false, false);
        is(t.headline.equals("5G 다시 확인 중"), "다시 확인 머리글");
        is(has(t, "폰을 쓴 시간 37/60초"), "쓴 시간");
        is(has(t, "끊김 1번 (2번이면 다시 쉬기)"), "끊김");
        is(has(t, "4분 0초 안에 판정"), "판정까지");
        l.evalStart = -1;
        t = NowText.of(l, on, now, false, false);
        is(has(t, "자리 잡는 중"), "자리 잡는 중");

        // LTE 우선
        l.state = "INACTIVE";
        l.userNr = false;
        t = NowText.of(l, on, now, false, false);
        is(t.headline.equals("LTE 우선 · 대기") && has(t, "고른 모드: LTE 우선"), "LTE 우선");

        // 지켜보기만(SIM 2개)과 문제
        l.state = "OBSERVE";
        l.userNr = true;
        l.blocked = "dual_sim";
        l.problem = "통신사 칸 확인 필요";
        t = NowText.of(l, on, now, false, false);
        is(t.headline.equals("지켜보기만 (바꿀 수 없음)") && has(t, "이유: SIM이 2개(한 개일 때만 바꿈)")
                && has(t, "문제: 통신사 칸 확인 필요"), "지켜보기만·문제");

        // 남의(또는 기록 없는) 막음: 엔진이 있든 없든 안내가 맨 위, 순정이라고 하지 않는다
        t = NowText.of(null, off, now, false, true);
        is(t.lines.get(0).equals(NowText.EXTERNAL_LINE) && !has(t, "순정(폰"), "엔진 없음·남의 막음");
        l.problem = null;
        l.state = "WATCH";
        l.hold = "restricted";
        t = NowText.of(l, on, now, false, true);
        is(t.lines.get(0).equals(NowText.EXTERNAL_LINE) && has(t, "다른 쪽(절전·통신사 앱 등)이 5G를 막고 있어 지금은 지켜보지 않아요"),
                "엔진 있음·남의 막음");
        l.hold = null;

        is(NowText.display(3).equals("5G") && NowText.display(0).equals("LTE") && NowText.display(-1).equals("알 수 없음"), "표시 말");
        is(Words.minSec(125_000).equals("2분 5초") && Words.minSec(45_000).equals("45초") && Words.minSec(-1).equals("0초"), "minSec");
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
        l.pccKnown = true;
        l.dataIn = true;
        l.dataConnected = true;
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
