package nrc.controller;

/** 이 폰 점검 판정·보고문 시험(PC, 2026-10-01). 실행: bash phoneapp/test.sh */
public final class SupportCheckTest {
    private static int fails;
    private static int n;

    public static void main(String[] a) {
        // 아직 안 해 봄 + 구형(패치 전): 모름, 해 보라고
        SupportCheck.Info f = base();
        f.securityPatch = "2025-06-01";
        SupportCheck s = SupportCheck.of(f);
        is(!s.known && !s.supported && s.verdict.contains("[시작하기]로 해 보면"), "미시도·구형 = 모름");
        is(s.report.contains("처음 설정 결과: 아직 안 해 봄") && s.report.contains("개인정보 없음"), "보고문 뼈대");

        // 아직 안 해 봄 + 삼성 2025-12 이후: 막혔을 수 있다는 힌트
        f.securityPatch = "2025-12-01";
        s = SupportCheck.of(f);
        is(!s.known && s.verdict.contains("2025년 12월 이후") && s.verdict.contains("막혀 있을 수 있어요"), "미시도·패치기 힌트");
        is(s.report.contains("2025-12 이후: 삼성은 이 방식이 막혔을 수 있음"), "보고문 패치 힌트");
        f.securityPatch = "2026-03-01";
        is(SupportCheck.patchedEra(f.securityPatch), "패치월 비교 이후");
        is(!SupportCheck.patchedEra("2025-11-30"), "패치월 비교 이전");
        is(!SupportCheck.patchedEra(null), "패치월 모름");

        // 권한이 이미 있으면 됨(설정 결과와 무관)
        f = base();
        f.privileged = true;
        s = SupportCheck.of(f);
        is(s.known && s.supported && s.verdict.contains("됩니다"), "권한 있으면 됨");

        // 설정 성공
        f = base();
        f.setupResult = SupportCheck.SETUP_WORKS;
        s = SupportCheck.of(f);
        is(s.known && s.supported, "설정 성공 = 됨");

        // 보안 잠금으로 막힘(지원 안 함)
        f = base();
        f.setupResult = SupportCheck.SETUP_BLOCKED_PATCH;
        s = SupportCheck.of(f);
        is(s.known && !s.supported && s.verdict.contains("지원하지 않아요"), "보안 잠금 = 지원 안 함");
        is(s.report.contains("막힘(보안 잠금"), "보고문 잠금 표기");

        // 다른 이유로 실패
        f = base();
        f.setupResult = SupportCheck.SETUP_BLOCKED_OTHER;
        s = SupportCheck.of(f);
        is(s.known && !s.supported && s.verdict.contains("보안 잠금은 아님"), "다른 실패");

        // 삼성 판별
        is(SupportCheck.isSamsung("samsung") && SupportCheck.isSamsung("Samsung") && !SupportCheck.isSamsung("Google"), "삼성 판별");

        // 개인정보 금지: 보고문에 전화번호/IMEI 류 라벨이 없다
        f = base();
        f.carrier = "SKTelecom";
        f.simCount = 1;
        s = SupportCheck.of(f);
        is(!s.report.contains("IMEI") && !s.report.contains("전화번호:") && s.report.contains("통신사: SKTelecom"), "개인정보 없음·통신사 이름만");
        is(s.report.contains("SIM 1개"), "SIM 개수만");

        System.out.println(fails == 0 ? "SupportCheckTest OK (" + n + ")" : "SupportCheckTest FAILED " + fails);
        if (fails != 0) System.exit(1);
    }

    private static SupportCheck.Info base() {
        SupportCheck.Info f = new SupportCheck.Info();
        f.appVersion = "0.1.0";
        f.manufacturer = "samsung";
        f.model = "SM-N986N";
        f.device = "c2q";
        f.androidRelease = "13";
        f.sdk = 33;
        f.securityPatch = "2023-01-01";
        f.simCount = 1;
        f.allowedTypesCapable = true;
        return f;
    }

    private static void is(boolean ok, String what) {
        n++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }
}
