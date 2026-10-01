package nrc.controller;

/**
 * "이 폰에서 되는가" 판정과 보내기용 보고문(사용자 결정 2026-10-01: 기기별 capability test + 결과 한 화면, 개인정보 없음).
 * 배경: 구글이 2025-10에 통신사 인정 목록 쓰기(overrideConfig)를 shell 신분으로 못 부르게 막음(CVE-2025-48617).
 * 삼성은 2025-12 보안 업데이트에 넣음 → 버전이 아니라 보안 패치월·제조사로 갈린다(기기마다 실제로 해 봐야 안다).
 * 이 클래스는 안드로이드 의존 없이 사실(Info)만 받아 판정·보고문을 만든다(PC 시험). 기기 값 읽기는 화면이 넣는다.
 * 개인정보(전화번호·IMEI·SIM 일련번호·기기 식별자)는 담지 않는다. 통신사 '표시 이름'과 SIM '개수'만.
 */
final class SupportCheck {
    /** 처음 설정이 이 폰에서 어떻게 끝났는지. */
    static final String SETUP_UNKNOWN = "unknown";    // 아직 안 해 봄
    static final String SETUP_WORKS = "works";        // 등록 성공(또는 권한 확인)
    static final String SETUP_BLOCKED_PATCH = "blocked_patch"; // overrideConfig가 shell 차단(패치됨)
    static final String SETUP_BLOCKED_OTHER = "blocked_other"; // 다른 이유로 실패

    static final class Info {
        String appVersion;
        String manufacturer;
        String model;
        String device;
        String androidRelease;
        int sdk;
        String securityPatch;  // "YYYY-MM-DD" 또는 null
        String oneUi;          // 있으면, 없으면 null
        String carrier;        // 통신사 표시 이름(개인정보 아님), 없으면 null
        int simCount;          // 켜진 SIM 수, 모르면 -1
        boolean privileged;    // 지금 5G/LTE 전환 권한이 있는지
        Boolean allowedTypesCapable; // 허용망 비트마스크 방식 지원(모르면 null)
        String setupResult = SETUP_UNKNOWN;
    }

    final String verdict;   // 한 줄 판정(사람이 읽는 말)
    final boolean supported; // true = 이 폰에서 됨, false = 안 됨, null 대신 unknown은 아래 known으로
    final boolean known;    // 판정이 확실한지(처음 설정을 해 봤거나 권한이 이미 있으면 true)
    final String report;    // 보내기용 여러 줄(개인정보 없음)

    private SupportCheck(String verdict, boolean supported, boolean known, String report) {
        this.verdict = verdict;
        this.supported = supported;
        this.known = known;
        this.report = report;
    }

    /** 보안 패치월이 삼성이 이 잠금을 넣은 2025-12-01 이후인지(YYYY-MM-DD 문자열 비교, 못 읽으면 false). */
    static boolean patchedEra(String securityPatch) {
        return securityPatch != null && securityPatch.compareTo("2025-12-01") >= 0;
    }

    static boolean isSamsung(String manufacturer) {
        return manufacturer != null && manufacturer.toLowerCase(java.util.Locale.US).contains("samsung");
    }

    static SupportCheck of(Info f) {
        String verdict;
        boolean supported;
        boolean known;
        if (f.privileged || SETUP_WORKS.equals(f.setupResult)) {
            verdict = "이 폰에서 5G/LTE 전환 권한을 얻었어요 — 됩니다 ✓";
            supported = true;
            known = true;
        } else if (SETUP_BLOCKED_PATCH.equals(f.setupResult)) {
            verdict = "이 폰은 이 방식이 막혀 있어요(보안 업데이트로 잠김) — 지원하지 않아요";
            supported = false;
            known = true;
        } else if (SETUP_BLOCKED_OTHER.equals(f.setupResult)) {
            verdict = "처음 설정이 실패했어요(막힌 이유가 보안 잠금은 아님) — 아래 기록을 보내 주세요";
            supported = false;
            known = true;
        } else {
            // 아직 안 해 봄: 보안 패치월로 힌트만
            if (isSamsung(f.manufacturer) && patchedEra(f.securityPatch)) {
                verdict = "아직 처음 설정을 안 했어요. 보안 업데이트가 2025년 12월 이후라 이 폰에서는 막혀 있을 수 있어요(해 봐야 확실)";
            } else {
                verdict = "아직 처음 설정을 안 했어요. [시작하기]로 해 보면 이 폰에서 되는지 알 수 있어요";
            }
            supported = false;
            known = false;
        }
        return new SupportCheck(verdict, supported, known, buildReport(f, verdict));
    }

    private static String buildReport(Info f, String verdict) {
        StringBuilder b = new StringBuilder();
        b.append("[5G 자동 제어 · 이 폰 점검]\n");
        b.append("판정: ").append(verdict).append('\n');
        b.append("앱: ").append(nz(f.appVersion)).append('\n');
        b.append("기기: ").append(nz(f.manufacturer)).append(' ').append(nz(f.model))
                .append(" (").append(nz(f.device)).append(")\n");
        b.append("안드로이드: ").append(nz(f.androidRelease)).append(" (SDK ").append(f.sdk).append(")");
        if (f.oneUi != null) b.append(" · One UI ").append(f.oneUi);
        b.append('\n');
        b.append("보안 패치월: ").append(nz(f.securityPatch));
        if (patchedEra(f.securityPatch)) b.append(" (2025-12 이후: 삼성은 이 방식이 막혔을 수 있음)");
        b.append('\n');
        b.append("통신사: ").append(nz(f.carrier)).append(" · SIM ")
                .append(f.simCount < 0 ? "모름" : f.simCount + "개").append('\n');
        b.append("허용망 비트마스크 방식: ").append(f.allowedTypesCapable == null ? "모름"
                : f.allowedTypesCapable ? "지원" : "미지원").append('\n');
        b.append("지금 전환 권한: ").append(f.privileged ? "있음" : "없음").append('\n');
        b.append("처음 설정 결과: ").append(setupText(f.setupResult)).append('\n');
        b.append("(개인정보 없음 — 전화번호·기기 일련번호는 담지 않았어요)");
        return b.toString();
    }

    private static String setupText(String r) {
        if (SETUP_WORKS.equals(r)) return "성공(됨)";
        if (SETUP_BLOCKED_PATCH.equals(r)) return "막힘(보안 잠금, overrideConfig가 shell 차단)";
        if (SETUP_BLOCKED_OTHER.equals(r)) return "실패(다른 이유)";
        return "아직 안 해 봄";
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "모름" : s;
    }
}
