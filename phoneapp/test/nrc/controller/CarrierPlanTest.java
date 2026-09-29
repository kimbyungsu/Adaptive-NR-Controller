package nrc.controller;

/** 통신사 칸 쉬기·풀기 계획 시험. 실행: bash phoneapp/test.sh */
public final class CarrierPlanTest {
    private static int fails;
    private static int n;

    public static void main(String[] a) {
        long withNr = 840583L, lte = 316295L, lteNoCa = 54151L;
        // 목표값: NR 비트만 바뀐다
        eq(CarrierPlan.target(withNr, false), lte, "뺄 때 NR 비트만");
        eq(CarrierPlan.target(lte, true), withNr, "넣을 때 NR 비트만");
        eq(CarrierPlan.target(withNr, true), withNr, "이미 있으면 그대로");
        eq(CarrierPlan.target(-1, true), -1, "못 읽으면 -1");
        // 앱 공개 API가 LTE_CA 비트(1<<18)를 뺀 값(기기 실측 54151·578439)에서도 NR 비트만 다룬다
        eq(CarrierPlan.target(lteNoCa, true), 578439L, "LTE_CA 빠진 값에 NR 넣기");
        eq(CarrierPlan.target(578439L, false), lteNoCa, "LTE_CA 빠진 값에서 NR 빼기");
        // 판정: 우리가 남긴 값과 같을 때만 우리 막음
        is(CarrierPlan.classify(withNr, -1) == CarrierPlan.Carrier.OPEN, "NR 있음 = 열림");
        is(CarrierPlan.classify(withNr, lteNoCa) == CarrierPlan.Carrier.OPEN, "기록이 남아도 NR 있으면 열림");
        is(CarrierPlan.classify(lteNoCa, lteNoCa) == CarrierPlan.Carrier.OURS, "다시 읽은 값 그대로 = 우리 막음");
        is(CarrierPlan.classify(lteNoCa, lte) == CarrierPlan.Carrier.OURS, "쓰기 직전 기록(목표값)만 있고 저장값은 LTE_CA 빠짐 = 우리 막음");
        is(CarrierPlan.classify(lteNoCa, -1) == CarrierPlan.Carrier.EXTERNAL, "기록 없음 + NR 없음 = 외부 제한");
        // 외부 검증 반례: 우리가 쉬게 한 뒤 통신사 앱이 칸을 4096으로 바꿈 → 우리 것이 아니다
        is(CarrierPlan.classify(4096L, lteNoCa) == CarrierPlan.Carrier.EXTERNAL, "우리 뒤에 남이 바꾼 값 = 외부 제한");
        is(CarrierPlan.classify(-1, lteNoCa) == CarrierPlan.Carrier.UNKNOWN, "못 읽음");
        is(!CarrierPlan.matchesOurs(lteNoCa, -1), "기록 없으면 우리 값 아님");
        // 풀기
        is(CarrierPlan.mustLift(CarrierPlan.Carrier.OURS, false), "쉬는 중이 아닌데 우리 막음 → 푼다");
        is(!CarrierPlan.mustLift(CarrierPlan.Carrier.OURS, true), "쉬는 중이면 둔다");
        is(!CarrierPlan.mustLift(CarrierPlan.Carrier.EXTERNAL, false), "외부 제한은 건드리지 않는다");
        is(!CarrierPlan.mustLift(CarrierPlan.Carrier.OPEN, false), "열려 있으면 할 일 없음");
        is(!CarrierPlan.mustLift(CarrierPlan.Carrier.UNKNOWN, false), "못 읽으면 쓰지 않는다");
        System.out.println(fails == 0 ? "CarrierPlanTest OK (" + n + ")" : "CarrierPlanTest FAILED " + fails);
        if (fails != 0) System.exit(1);
    }

    private static void eq(long got, long want, String what) {
        is(got == want, what + " got=" + got + " want=" + want);
    }

    private static void is(boolean ok, String what) {
        n++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }
}
