package nrc.shizupoc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** no-wifi 시작하기 단계·재부팅 판정 시험(PC, 2026-10-08). 실행: bash poc/shizuku/test.sh */
public final class ProbeStepsTest {
    private static int fails;
    private static int n;
    private static final String T = "2026-10-08 10:00:00.000  ";

    public static void main(String[] a) {
        // 막 시작: 아무것도 없음 → 1단계부터, 7단계 남음, 판정은 '재부팅 전'
        ProbeSteps.Facts f = new ProbeSteps.Facts();
        ProbeSteps s = ProbeSteps.of(f);
        is(s.current == ProbeSteps.NEXT && s.remaining == 7, "새 폰은 1단계부터 7개");
        is(s.verdict.result == ProbeSteps.Result.NOT_REBOOTED, "기록 없음=재부팅 전");
        f.otherShizuku = true;
        is(ProbeSteps.of(f).state[ProbeSteps.NEXT].contains("공식판"), "공식 Shizuku가 있으면 바꿔 깔기 안내");

        // 준비를 차례로 끝내면 지금 할 단계가 따라 내려감
        f = prepared();
        s = ProbeSteps.of(f);
        is(s.current == ProbeSteps.REBOOT && s.remaining == 1, "준비 끝이면 재부팅만 남음");
        f.wifiOn = true;
        s = ProbeSteps.of(f);
        is(s.current == ProbeSteps.AIR && s.state[ProbeSteps.AIR].contains("Wi-Fi 켜져 있음"), "Wi-Fi 켜져 있으면 6단계");
        f.wifiOn = false;
        f.cell = "no";
        is(ProbeSteps.of(f).current == ProbeSteps.AIR, "모바일 데이터 없으면 6단계");
        f = prepared();
        f.permitted = null;
        f.alive = false;
        s = ProbeSteps.of(f);
        is(s.current == ProbeSteps.RUN && s.state[ProbeSteps.PERM].contains("켜져 있어야"), "통로 없으면 2단계·허락은 물을 수 없음");
        // 통로가 살아 있어도(예: PC로 켬) 페어링 확인 전엔 2단계가 안 끝남
        f = prepared();
        f.pairConfirmed = false;
        s = ProbeSteps.of(f);
        is(s.current == ProbeSteps.RUN && s.state[ProbeSteps.RUN].contains("페어링까지 했으면"), "통로만 켜짐≠페어링");
        // 관측은 켰지만 알림이 막혀 결과를 못 보면 5단계가 안 끝남
        f = prepared();
        f.notifOk = false;
        s = ProbeSteps.of(f);
        is(s.current == ProbeSteps.PROBE && s.state[ProbeSteps.PROBE].contains("알림이 꺼져"), "알림 막힘이면 5단계");

        // 관측을 손으로 켠 기록만(같은 부팅) → 아직 재부팅 전
        f = prepared();
        f.log = lines(start("manual", 10, 5000), tick(1, 10, 5000, 0, true, "yes"));
        s = ProbeSteps.of(f);
        is(s.verdict.result == ProbeSteps.Result.NOT_REBOOTED && s.current == ProbeSteps.REBOOT, "손으로 켠 관측만=재부팅 전");

        // 재부팅 뒤(부팅 횟수 11) Wi-Fi 없이 앱을 안 연 채 되살아남
        List<String> base = lines(start("manual", 10, 5000), tick(1, 10, 5000, 0, true, "yes"));
        f = new ProbeSteps.Facts(); // 재부팅 직후: 통로·허락·Wi-Fi 상태를 못 읽어도 준비 단계는 넘어감
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "no"),
                tick(2, 11, 50000, 10000, false, "no"), tick(3, 11, 61000, 21000, true, "no"));
        s = ProbeSteps.of(f);
        is(s.verdict.result == ProbeSteps.Result.ALIVE_CLEAN && s.verdict.aliveSinceBootMs == 61000, "Wi-Fi 없이 되살아남");
        is(s.remaining == 0 && s.current == -1, "판정이 나오면 모든 단계 끝");
        is(s.done[ProbeSteps.RUN] && s.state[ProbeSteps.RUN].contains("넘어감"), "재부팅 시험 중엔 준비 단계 넘어감");
        is(s.verdict.text.contains("61초"), "처음 보인 시각 표시");

        // 살아나기 전에 이 화면을 열었으면 무개입 아님(관측 시작 전·후 모두)
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "no"),
                uiOpen(11, 45000), tick(2, 11, 50000, 10000, true, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_AFTER_OPEN, "살기 전 화면 열림");
        f.log = plus(base, uiOpen(11, 30000), start("boot", 11, 40000), tick(1, 11, 40000, 0, true, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_AFTER_OPEN, "관측 시작 전 화면 열림도");
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, true, "no"), uiOpen(11, 45000));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_CLEAN, "살아난 뒤 화면 열림은 괜찮음");

        // 살아나기 전 한 번이라도 Wi-Fi가 보였거나 못 읽었으면 'Wi-Fi 없이' 아님
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "yes"),
                tick(2, 11, 50000, 10000, true, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_WITH_WIFI, "앞서 Wi-Fi 있었으면 탈락");
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "unknown"),
                tick(2, 11, 50000, 10000, true, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_UNCLEAR, "Wi-Fi 못 읽은 때 있으면 불확실");

        // 30분 동안 안 살아남 / 중간에 관측 꺼짐 / 아직 관측 중
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "no"),
                T + "=== probe stop (max run reached) firstAlive=false");
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.NOT_RECOVERED, "30분 미복구=실패");
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "no"),
                T + "=== probe off (user) boot=11 sinceBootMs=60000");
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.STOPPED, "살기 전 관측 끔=판정 불가");
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "no"),
                tick(2, 11, 160000, 120000, false, "no"));
        s = ProbeSteps.of(f);
        is(s.verdict.result == ProbeSteps.Result.WATCHING && s.current == ProbeSteps.REBOOT && s.remaining == 1, "관측 중");
        is(s.verdict.text.contains("2분 0초"), "관측 경과 표시");

        // 30분 시험 창이 지난 뒤 처음 살아난 것은 성공이 아님(Codex 반례)
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "no"),
                tick(2, 11, 1900000, 1860000, true, "no"), T + "=== probe stop (max run reached) firstAlive=true");
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.NOT_RECOVERED, "창 지난 뒤 생존=실패");
        // 창이 지났는데 끝 줄 없이 기록만 끊김 → 실패
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 1900000, 1860000, false, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.NOT_RECOVERED, "창 지남·끝 줄 없음=실패");
        // 관측 프로세스가 죽었다 다시 뜬 뒤 살아남 → 빈틈(Codex 반례)
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "no"),
                start("restart", 11, 140000), tick(1, 11, 140000, 100000, true, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_GAP, "재시작 끼면 빈틈");
        // 표본 사이가 30초 넘게 비었으면 빈틈(깊은 잠 등)
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, false, "no"),
                tick(2, 11, 95000, 55000, true, "no"));
        s = ProbeSteps.of(f);
        is(s.verdict.result == ProbeSteps.Result.ALIVE_GAP && s.verdict.text.contains("55초"), "긴 빈틈");
        // 관측 시작 줄과 첫 표본 사이 빈틈도 셈
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 80000, 40000, true, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_GAP, "시작~첫 표본 빈틈");
        // 깨끗한 결과도 최종 무개입은 PC 대조라고 말함
        f.log = plus(base, start("boot", 11, 40000), tick(1, 11, 40000, 0, true, "no"));
        is(ProbeSteps.of(f).verdict.text.contains("PC 기록 대조"), "깨끗해도 PC 대조 한정");

        // 재부팅 뒤 관측이 부팅 신호가 아니라 손으로 먼저 켜짐 → 판정 불가
        f.log = plus(base, start("manual", 11, 40000), tick(1, 11, 40000, 0, true, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.NOT_BOOT_START, "재부팅 뒤 손으로 시작=판정 불가");

        // 재부팅 뒤 화면만 열렸고 관측은 아직
        f.log = plus(base, uiOpen(11, 30000));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.WATCHING, "재부팅 뒤 관측 시작 전");

        // 짧게 켜졌던 앞 부팅: 새 부팅의 경과가 앞 부팅 마지막 경과보다 커도 부팅 횟수로 가른다
        f.log = lines(start("manual", 10, 5000), tick(1, 10, 6000, 1000, true, "yes"),
                start("boot", 11, 90000), tick(1, 11, 90000, 0, true, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_CLEAN, "부팅 횟수로 새 부팅 구분");

        // 부팅 횟수가 없는 옛 기록: 경과가 줄면 새 부팅
        f.log = lines(T + "=== probe start cause=manual sinceBootMs=500000", T + "tick#1 sinceBootMs=500000 sinceTestMs=0 ping=true wifiPresent=yes",
                T + "=== probe start cause=boot sinceBootMs=40000", T + "tick#1 sinceBootMs=40000 sinceTestMs=0 ping=true wifiPresent=no");
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_CLEAN, "옛 기록은 경과 감소로 새 부팅");

        // 지난 기록을 보관한 뒤 부팅 기록만 있는 경우도 판정
        f.log = lines(start("boot", 12, 40000), tick(1, 12, 40000, 0, true, "no"));
        is(ProbeSteps.of(f).verdict.result == ProbeSteps.Result.ALIVE_CLEAN, "부팅 기록 하나만 있어도 판정");

        is(ProbeSteps.TITLES.length == ProbeSteps.COUNT, "제목 수");
        System.out.println(fails == 0 ? "ProbeStepsTest OK (" + n + ")" : "ProbeStepsTest FAILED " + fails);
        if (fails != 0) System.exit(1);
    }

    private static ProbeSteps.Facts prepared() {
        ProbeSteps.Facts f = new ProbeSteps.Facts();
        f.nextInstalled = true;
        f.alive = true;
        f.pairConfirmed = true;
        f.permitted = true;
        f.noWifiConfirmed = true;
        f.probeOn = true;
        f.notifOk = true;
        f.wifiOn = false;
        f.cell = "yes";
        return f;
    }

    private static String start(String cause, int boot, long since) {
        return T + "=== probe start cause=" + cause + " boot=" + boot + " sinceBootMs=" + since + " testAnchorMs=" + since
                + " model=SM-N986N sdk=33  (cause=...)";
    }

    private static String tick(int i, int boot, long since, long test, boolean ping, String wifi) {
        return T + "tick#" + i + " boot=" + boot + " sinceBootMs=" + since + " sinceTestMs=" + test + " ping=" + ping
                + " wifiPresent=" + wifi + " wifiOn=off cellPresent=yes locked=false interactive=true"
                + (ping ? "  >>> FIRST_ALIVE uid=2000" : "");
    }

    private static String uiOpen(int boot, long since) {
        return T + "event: ui-open boot=" + boot + " sinceBootMs=" + since;
    }

    private static List<String> lines(String... l) {
        return new ArrayList<>(Arrays.asList(l));
    }

    private static List<String> plus(List<String> base, String... more) {
        List<String> out = new ArrayList<>(base);
        out.addAll(Arrays.asList(more));
        return out;
    }

    private static void is(boolean ok, String what) {
        n++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }
}
