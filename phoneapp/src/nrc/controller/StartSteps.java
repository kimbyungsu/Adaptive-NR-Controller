package nrc.controller;

/**
 * "시작하기" 체크리스트의 단계와 상태(사용자 결정 2026-10-01: 체크리스트 한 장, 끝난 단계는 폰 상태를 읽어 ✓, 지금 할 단계만 펼침).
 * 1~4는 처음 설정(폰이 이 앱을 인정 목록에 올리기), 5~7은 마무리. 이미 5G/LTE 전환 권한이 있으면 1~4는 건너뛴다(끝난 것으로).
 * 안드로이드 의존 없음(PC 시험) — 사실(Facts)은 화면이 읽어 넣는다.
 */
final class StartSteps {
    static final int WIFI = 0, DEV = 1, ADB = 2, CODE = 3, TILE = 4, BATTERY = 5, AUTO = 6, COUNT = 7;
    static final String[] TITLES = {
            "Wi-Fi에 연결",
            "개발자 옵션 켜기",
            "무선 디버깅 켜기",
            "6자리 코드 넣기(처음 설정)",
            "빠른 설정에 '5G 자동' 타일 추가",
            "배터리 최적화에서 빼기",
            "자동 제어 켜기",
    };

    /** 화면이 폰에서 읽은 사실. 읽을 수 없는 것은 null. */
    static final class Facts {
        boolean wifi;
        boolean devOptions;
        /** 무선 디버깅 설정값(앱이 읽지 못하면 null). */
        Boolean adbWifi;
        /** 페어링 코드 창이 열려 있는 것을 앱이 찾았는지. */
        boolean pairingSeen;
        /** 무선 디버깅 접속 포트를 앱이 찾았는지(켜져 있다는 뜻). */
        boolean connectSeen;
        boolean privileged;
        boolean tileAdded;
        boolean batteryExempt;
        boolean auto;
        /** 삼성 설정 네트워크 모드가 5G 우선인지(권한이 없어 못 읽으면 null). */
        Boolean userNr;
    }

    final boolean[] done = new boolean[COUNT];
    final String[] state = new String[COUNT];
    /** 지금 할 단계(다 끝났으면 -1). */
    final int current;
    final int remaining;
    final String modeLine;

    private StartSteps(Facts f) {
        String skip = "이미 권한이 있어 건너뜀";
        done[WIFI] = f.privileged || f.wifi;
        state[WIFI] = f.privileged ? skip : f.wifi ? "연결됨" : "연결 안 됨";
        done[DEV] = f.privileged || f.devOptions;
        state[DEV] = f.privileged ? skip : f.devOptions ? "켜짐" : "꺼짐";
        boolean adbOn = Boolean.TRUE.equals(f.adbWifi) || f.connectSeen || f.pairingSeen;
        done[ADB] = f.privileged || adbOn;
        state[ADB] = f.privileged ? skip : adbOn ? "켜짐" : f.adbWifi == null ? "아직(켜면 앱이 찾아서 ✓)" : "꺼짐";
        done[CODE] = f.privileged;
        state[CODE] = f.privileged ? "끝남 — 5G/LTE 전환 권한 있음" : f.pairingSeen ? "코드 창이 열려 있어요 — 지금 넣으세요" : "아직";
        done[TILE] = f.tileAdded;
        state[TILE] = f.tileAdded ? "추가됨" : "아직(이미 있으면 빠른 설정 패널을 한 번 내리면 ✓)";
        done[BATTERY] = f.batteryExempt;
        state[BATTERY] = f.batteryExempt ? "빠짐" : "아직";
        done[AUTO] = f.auto;
        state[AUTO] = f.auto ? "켜짐" : "꺼짐";
        int cur = -1, left = 0;
        for (int i = 0; i < COUNT; i++) {
            if (done[i]) continue;
            left++;
            if (cur < 0) cur = i;
        }
        current = cur;
        remaining = left;
        modeLine = f.userNr == null ? "삼성 네트워크 모드: 아직 모름(처음 설정이 끝나면 보여요)"
                : f.userNr ? "삼성 네트워크 모드: 5G 우선 → 자동 제어가 켜져 있으면 앱이 지켜봐요"
                : "삼성 네트워크 모드: LTE 우선 → 앱은 쉬어요(5G 우선을 고르면 움직여요)";
    }

    static StartSteps of(Facts f) {
        return new StartSteps(f);
    }
}
