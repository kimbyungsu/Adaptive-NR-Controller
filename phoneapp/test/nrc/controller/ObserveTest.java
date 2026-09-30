package nrc.controller;

/** 관측 화면의 순수 부분 시험: 이유 코드 번역(Words), 오늘의 활동 셈(DaySummary). 실행: bash phoneapp/test.sh */
public final class ObserveTest {
    private static int fails;
    private static int n;

    public static void main(String[] a) {
        // 판단 규칙이 쓰는 이유 코드는 모두 사람 말로 바뀐다(코드가 그대로 새지 않는다)
        String[] whys = {"drops", "oos", "dwell", "probe_drops", "probe_oos", "manual_test", "cooldown_end", "probe_pass",
                "probe_undecided", "t_clear", "active_drop", "start", "user_mode", "mode_lte", "keep_lte", "wifi_restore",
                "restored", "unblocked", "resume", "settle_timeout", "start_from_lte", "user_mode_from_lte",
                "dual_sim", "sim_count_unknown", "user_unreadable"};
        for (String w : whys) is(!Words.why(w).startsWith("("), "why " + w + " -> " + Words.why(w));
        for (String s : new String[]{"INACTIVE", "OBSERVE", "GOOD", "WATCH", "COOLDOWN", "PROBE", "SAFE_STOP"}) {
            is(!Words.state(s).startsWith("("), "state " + s);
        }
        for (String h : new String[]{"screen_off", "wifi", "call", "roaming", "restricted"}) is(!Words.hold(h).startsWith("("), "hold " + h);
        for (String g : new String[]{"budget_hour", "budget_gap", "settling", "heavy_traffic", "no_service", "call", "call_unknown",
                "emergency", "emergency_callback", "screen_off", "wifi", "roaming", "restricted"}) {
            is(!Words.guard(g).startsWith("("), "guard " + g);
        }
        is(Words.why("zzz").equals("(zzz)"), "모르는 코드는 괄호로 그대로");
        is(Words.mmss(125_000).equals("2:05"), "mmss 2:05 got " + Words.mmss(125_000));
        is(Words.mmss(-5).equals("0:00"), "mmss 음수");
        is(Words.mmss(1).equals("0:01"), "mmss 올림");

        // 오늘의 활동: 쉬기 → 재시험 → 통과, 쉬기 → 재시험 → 실패(다시 쉬기) → 재시험 → 판정 못 함
        DaySummary d = new DaySummary();
        d.roll("2026-09-30", 0);
        d.onState(1_000, "WATCH", "COOLDOWN", "drops");
        d.onState(121_000, "COOLDOWN", "PROBE", "cooldown_end");
        d.onState(200_000, "PROBE", "WATCH", "probe_pass");
        d.onState(300_000, "WATCH", "COOLDOWN", "drops");
        d.onState(420_000, "COOLDOWN", "PROBE", "cooldown_end");
        d.onState(450_000, "PROBE", "COOLDOWN", "probe_drops");
        d.onState(690_000, "COOLDOWN", "PROBE", "cooldown_end");
        d.onState(990_000, "PROBE", "WATCH", "probe_undecided");
        d.onCountedDrop();
        d.onCountedDrop();
        is(d.rests == 3, "쉬기 3 got " + d.rests);
        is(d.restMs == 120_000 + 120_000 + 240_000, "쉰 시간 got " + d.restMs);
        is(d.probes == 3 && d.pass == 1 && d.fail == 1 && d.undecided == 1, "재시험 3·통과 1·실패 1·판정 못 함 1");
        is(d.countedDrops == 2, "센 끊김 2");
        // 쉬는 중 지금까지 포함, 자정을 넘기면 자정부터
        d.onState(1_000_000, "WATCH", "COOLDOWN", "oos");
        is(d.resting() && d.restMsNow(1_060_000) == 480_000 + 60_000, "쉬는 중 지금까지 포함");
        d.roll("2026-10-01", 1_100_000);
        is(d.rests == 0 && d.restMs == 0 && d.resting(), "날이 바뀌면 비우고 쉬는 중은 유지");
        is(d.restMsNow(1_130_000) == 30_000, "자정부터 잰다 got " + d.restMsNow(1_130_000));
        d.roll("2026-10-01", 9);
        is(d.restMsNow(1_130_000) == 30_000, "같은 날 다시 불러도 그대로");
        // 저장·읽기
        d.onAction(1_100_500, "5G 막음 | LTE로 쉬기");
        DaySummary e = DaySummary.load(d.save());
        is(e.day.equals(d.day) && e.restMsNow(1_130_000) == 30_000 && e.lastActionWall == 1_100_500
                && e.lastAction.equals("5G 막음 / LTE로 쉬기"), "저장·읽기");
        is(DaySummary.load("망가진|값").rests == 0, "망가진 저장값은 빈 셈");
        System.out.println(fails == 0 ? "ObserveTest OK (" + n + ")" : "ObserveTest FAILED " + fails);
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
