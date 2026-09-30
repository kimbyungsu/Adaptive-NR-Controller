package nrc.controller;

/**
 * 엔진 기록의 이유 코드를 사람이 읽는 말로 바꾼다(앱 안 관측 화면, 2026-09-30 사용자 결정). 안드로이드 의존 없음(PC 시험).
 * 모르는 코드는 코드 그대로 괄호에 넣어 보여 준다(숨기지 않는다).
 */
final class Words {
    private Words() {
    }

    /** 판단 규칙의 상태 이름. */
    static String state(String s) {
        if (s == null) return "알 수 없음";
        switch (s) {
            case "INACTIVE":
                return "대기(LTE 우선)";
            case "OBSERVE":
                return "관찰만(제어 불가)";
            case "GOOD":
                return "안정";
            case "WATCH":
                return "지켜보는 중";
            case "COOLDOWN":
                return "LTE로 쉬는 중";
            case "PROBE":
                return "5G 재시험 중";
            case "SAFE_STOP":
                return "안전 정지";
            default:
                return "(" + s + ")";
        }
    }

    /** 상태가 바뀐 이유(쉬기 원인 포함). */
    static String why(String w) {
        if (w == null) return "";
        if (w.endsWith("_from_lte")) return "시작할 때 LTE로 남아 있어 곧 5G 재시험";
        switch (w) {
            case "drops":
                return "데이터를 쓰는 중 2분 안에 5G가 3번 이상 끊김";
            case "oos":
                return "데이터 서비스가 끊김";
            case "dwell":
                return "5G가 붙었다가 금방 떨어지는 일이 잦음";
            case "probe_drops":
                return "재시험 중 5G가 2번 끊김";
            case "probe_oos":
                return "재시험 중 데이터 서비스가 끊김";
            case "manual_test":
                return "시험 명령";
            case "cooldown_end":
                return "쉬는 시간이 끝남";
            case "probe_pass":
                return "재시험 통과(데이터를 60초 쓰는 동안 끊김 2번 미만)";
            case "probe_undecided":
                return "재시험 판정 못 함(5분 안에 데이터를 60초 쓰지 않음)";
            case "t_clear":
                return "5분 동안 끊김 없음";
            case "active_drop":
                return "데이터를 쓰는 중 5G 끊김";
            case "start":
                return "자동 제어 시작";
            case "user_mode":
                return "사용자가 네트워크 모드를 고름";
            case "mode_lte":
                return "사용자가 LTE 우선을 고름";
            case "keep_lte":
                return "LTE로 계속 쓰기";
            case "wifi_restore":
                return "Wi-Fi에 붙어 5G를 되돌림";
            case "restored":
                return "5G를 되돌림";
            case "unblocked":
                return "제어할 수 있게 됨";
            case "resume":
                return "다시 시작";
            case "settle_timeout":
                return "전환 뒤 30초 안에 망이 안정되지 않음";
            default:
                String b = blocked(w);
                return b.startsWith("(") ? "(" + w + ")" : b;
        }
    }

    /** 판단을 쉬는 이유(보류). */
    static String hold(String h) {
        if (h == null) return "";
        switch (h) {
            case "screen_off":
                return "화면이 꺼져 있음";
            case "wifi":
                return "Wi-Fi로 인터넷을 쓰는 중";
            case "call":
                return "통화 중(끝나고 30초까지)";
            case "roaming":
                return "로밍 중";
            case "restricted":
                return "다른 쪽(절전·통신사 앱 등)이 5G를 막고 있음";
            default:
                return "(" + h + ")";
        }
    }

    /** 쉬거나 재시험하려다 미룬 이유(가드). */
    static String guard(String g) {
        if (g == null) return "";
        switch (g) {
            case "budget_hour":
                return "1시간 전환 한도(4번)에 걸림";
            case "budget_gap":
                return "전환 사이 최소 간격(2분) 전";
            case "settling":
                return "전환 직후 망이 자리 잡는 중";
            case "heavy_traffic":
                return "큰 전송 중(끝나면 다시)";
            case "no_service":
                return "서비스 없음";
            case "call":
            case "call_unknown":
                return "통화 중이거나 통화 확인 불가";
            case "emergency":
            case "emergency_callback":
                return "긴급 통화 관련";
            default:
                String h = hold(g);
                return h.startsWith("(") ? "(" + g + ")" : h;
        }
    }

    /** 제어 불가 이유. */
    static String blocked(String b) {
        if (b == null) return "";
        switch (b) {
            case "dual_sim":
                return "SIM이 2개(한 개일 때만 제어)";
            case "sim_count_unknown":
                return "SIM 수를 확인할 수 없음";
            case "user_unreadable":
                return "사용자 모드를 읽을 수 없음";
            default:
                return "(" + b + ")";
        }
    }

    /** 남은 시간 "분:초". 음수면 0:00. */
    static String mmss(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
