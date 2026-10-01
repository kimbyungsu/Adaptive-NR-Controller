package nrc.controller;

/** 시작하기 체크리스트 단계 판정 시험(PC, 2026-10-01). 실행: bash phoneapp/test.sh */
public final class StartStepsTest {
    private static int fails;
    private static int n;

    public static void main(String[] a) {
        // 막 설치한 폰: 아무것도 안 됨, Wi-Fi부터
        StartSteps.Facts f = new StartSteps.Facts();
        StartSteps s = StartSteps.of(f);
        is(s.current == StartSteps.WIFI && s.remaining == 7, "새 폰은 Wi-Fi부터 7단계");
        is(s.modeLine.contains("아직 모름"), "모드 모름");

        // Wi-Fi·개발자 옵션까지: 무선 디버깅 차례, 설정값을 못 읽으면 "켜면 앱이 찾아서"
        f.wifi = true;
        f.devOptions = true;
        s = StartSteps.of(f);
        is(s.current == StartSteps.ADB && s.state[StartSteps.ADB].contains("켜면 앱이 찾아서"), "무선 디버깅 차례·설정값 모름");
        f.adbWifi = false;
        s = StartSteps.of(f);
        is(s.state[StartSteps.ADB].equals("꺼짐"), "무선 디버깅 꺼짐");

        // 설정값을 못 읽어도 앱이 접속·코드 창을 찾으면 켜진 것으로
        f.adbWifi = null;
        f.connectSeen = true;
        s = StartSteps.of(f);
        is(s.done[StartSteps.ADB] && s.current == StartSteps.CODE, "접속 포트를 찾으면 켜짐");
        f.connectSeen = false;
        f.pairingSeen = true;
        s = StartSteps.of(f);
        is(s.done[StartSteps.ADB] && s.state[StartSteps.CODE].contains("지금 넣으세요"), "코드 창이 보이면 코드 차례 안내");

        // 처음 설정이 끝나면(권한) 1~4는 건너뜀 — 무선 디버깅을 앱이 꺼도 ✓ 유지
        f = new StartSteps.Facts();
        f.privileged = true;
        f.userNr = true;
        s = StartSteps.of(f);
        is(s.done[StartSteps.WIFI] && s.done[StartSteps.DEV] && s.done[StartSteps.ADB] && s.done[StartSteps.CODE], "권한 있으면 1~4 끝");
        is(s.state[StartSteps.WIFI].contains("건너뜀"), "건너뜀 표시");
        is(s.current == StartSteps.TILE && s.remaining == 3, "다음은 타일, 3단계 남음");
        is(s.modeLine.contains("5G 우선"), "5G 우선 줄");

        // 마무리까지 다 되면 남은 단계 없음
        f.tileAdded = true;
        f.batteryExempt = true;
        f.auto = true;
        s = StartSteps.of(f);
        is(s.current == -1 && s.remaining == 0, "다 끝남");
        f.userNr = false;
        s = StartSteps.of(f);
        is(s.remaining == 0 && s.modeLine.contains("LTE 우선") && s.modeLine.contains("쉬어요"), "LTE 우선은 단계가 아니라 안내 줄");

        // 자동 제어를 끄면 그 단계만 남음
        f.auto = false;
        s = StartSteps.of(f);
        is(s.current == StartSteps.AUTO && s.remaining == 1 && s.state[StartSteps.AUTO].equals("꺼짐"), "자동 제어 꺼짐");

        is(StartSteps.TITLES.length == StartSteps.COUNT, "제목 수");
        System.out.println(fails == 0 ? "StartStepsTest OK (" + n + ")" : "StartStepsTest FAILED " + fails);
        if (fails != 0) System.exit(1);
    }

    private static void is(boolean ok, String what) {
        n++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }
}
