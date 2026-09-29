package nrc;

import java.util.ArrayList;
import java.util.List;

/** UseSegments 시험(JDK만 필요). 실행: bash daemon/test.sh */
public final class UseSegmentsTest {
    private static final long S = 1000;
    private static final long WALL0 = 1_800_000_000_000L; // 경과 0초의 벽시계
    private static int failed;

    public static void main(String[] args) {
        gapWithinThreeSecondsStaysOneSegmentAcrossTicks();
        idleOverGapClosesAtLastActivity();
        continuousUseSplitByTicksIsContiguous();
        shutdownWhileActiveClosesAtNow();
        sinceActiveReportsElapsed();
        if (failed > 0) {
            System.out.println("FAILED " + failed);
            System.exit(1);
        }
        System.out.println("OK");
    }

    /** 검증 2회차 반례: 400초 시작, 420 주기, 449 멈춤, 450 주기, 451 재개, 470 멈춤, 480 주기 → [400,470] 70초 하나. */
    static void gapWithinThreeSecondsStaysOneSegmentAcrossTicks() {
        Rec r = new Rec();
        UseSegments u = new UseSegments(3 * S, r);
        u.activity(400 * S, wall(400), true);
        u.flush(420 * S);
        u.activity(449 * S, wall(449), false);
        u.flush(450 * S);
        u.activity(451 * S, wall(451), true);
        u.activity(470 * S, wall(470), false);
        u.flush(480 * S);
        check("2초 멈춤은 구간 안", r.merged(), "[400-470]");
        check("합계 70초", String.valueOf(r.totalMs() / S), "70");
    }

    static void idleOverGapClosesAtLastActivity() {
        Rec r = new Rec();
        UseSegments u = new UseSegments(3 * S, r);
        u.activity(10 * S, wall(10), true);
        u.activity(20 * S, wall(20), false);
        u.activity(30 * S, wall(30), true); // 10초 멈춤 → 새 구간
        u.activity(35 * S, wall(35), false);
        u.flush(60 * S);
        check("3초 넘는 멈춤은 구간을 나눔", r.merged(), "[10-20][30-35]");
    }

    static void continuousUseSplitByTicksIsContiguous() {
        Rec r = new Rec();
        UseSegments u = new UseSegments(3 * S, r);
        u.activity(0, wall(0), true);
        for (long t = 30; t <= 990; t += 30) u.flush(t * S);
        u.activity(1000 * S, wall(1000), false);
        u.flush(1010 * S);
        check("30초 조각이 빈틈 없이 이어짐", r.merged(), "[0-1000]");
        check("조각 수", String.valueOf(r.segs.size()), "34");
    }

    static void shutdownWhileActiveClosesAtNow() {
        Rec r = new Rec();
        UseSegments u = new UseSegments(3 * S, r);
        u.activity(100 * S, wall(100), true);
        u.flush(130 * S);
        u.shutdown(140 * S);
        check("종료 때 지금까지 닫음", r.merged(), "[100-140]");
    }

    static void sinceActiveReportsElapsed() {
        UseSegments u = new UseSegments(3 * S, (a, b) -> { });
        check("기록 없음", String.valueOf(u.sinceActive(5 * S)), "-1");
        u.activity(10 * S, wall(10), true);
        check("활동 중", String.valueOf(u.sinceActive(12 * S)), "0");
        u.activity(15 * S, wall(15), false);
        check("멈춘 뒤 경과", String.valueOf(u.sinceActive(16500)), "1500");
    }

    private static long wall(long sec) {
        return WALL0 + sec * S;
    }

    private static void check(String what, String got, String want) {
        if (got.equals(want)) {
            System.out.println("ok   " + what);
        } else {
            failed++;
            System.out.println("FAIL " + what + ": got " + got + ", want " + want);
        }
    }

    /** 받은 조각을 모아 PC처럼 이어 붙인다(겹치거나 맞닿으면 합침). */
    private static final class Rec implements UseSegments.Sink {
        final List<long[]> segs = new ArrayList<>();

        @Override
        public void segment(long fromWall, long ms) {
            segs.add(new long[]{fromWall, fromWall + ms});
        }

        long totalMs() {
            long sum = 0;
            for (long[] s : segs) sum += s[1] - s[0];
            return sum;
        }

        String merged() {
            StringBuilder sb = new StringBuilder();
            long a = -1, b = -1;
            for (long[] s : segs) {
                if (a >= 0 && s[0] <= b) {
                    b = Math.max(b, s[1]);
                    continue;
                }
                if (a >= 0) sb.append('[').append((a - WALL0) / S).append('-').append((b - WALL0) / S).append(']');
                a = s[0];
                b = s[1];
            }
            if (a >= 0) sb.append('[').append((a - WALL0) / S).append('-').append((b - WALL0) / S).append(']');
            return sb.toString();
        }
    }

    private UseSegmentsTest() {
    }
}
