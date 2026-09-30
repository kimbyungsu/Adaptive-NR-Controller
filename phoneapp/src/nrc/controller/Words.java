package nrc.controller;

/**
 * 엔진 기록의 이유 코드를 생활 말투로 바꾼다(앱 안 관측 화면, 2026-09-30 사용자 결정: 동작 이름을 그대로 옮긴 말 대신 생활 말투).
 * 기술 이름("통신사 칸" 등)은 [상세] 칸에만 쓴다. 안드로이드 의존 없음(PC 시험).
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
                return "LTE 우선이라 대기";
            case "OBSERVE":
                return "지켜보기만(바꿀 수 없음)";
            case "GOOD":
                return "안정적";
            case "WATCH":
                return "끊김이 있어 지켜보는 중";
            case "COOLDOWN":
                return "LTE로 잠깐 쉬는 중";
            case "PROBE":
                return "5G 다시 확인 중";
            case "SAFE_STOP":
                return "안전하게 멈춤";
            default:
                return "(" + s + ")";
        }
    }

    /** 상태가 바뀐 이유(쉬기 원인 포함). */
    static String why(String w) {
        if (w == null) return "";
        if (w.endsWith("_from_lte")) return "시작할 때 LTE로 남아 있어 곧 5G를 다시 확인";
        switch (w) {
            case "drops":
                return "화면을 켜고 데이터를 주고받는 중 2분 안에 5G가 3번 이상 끊김";
            case "oos":
                return "데이터가 아예 끊김";
            case "dwell":
                return "5G가 붙었다가 금방 떨어지는 일이 잦음";
            case "probe_drops":
                return "다시 확인하는 중 5G가 2번 끊김";
            case "probe_oos":
                return "다시 확인하는 중 데이터가 끊김";
            case "manual_test":
                return "시험 명령";
            case "cooldown_end":
                return "쉬는 시간이 끝남";
            case "probe_pass":
                return "통과(데이터를 60초 주고받는 동안 끊김이 2번 미만)";
            case "probe_undecided":
                return "판정 못 함(5분 안에 데이터를 주고받은 시간이 60초가 안 됨)";
            case "t_clear":
                return "5분 동안 끊김 없음";
            case "active_drop":
                return "데이터를 주고받는 중 5G가 끊김";
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
                return "다시 바꿀 수 있게 됨";
            case "resume":
                return "다시 시작";
            case "block_gone":
                return "앱이 걸어 둔 5G 막음이 없어져 쉬기를 끝냄";
            case "settle_timeout":
                return "바꾼 뒤 30초 안에 연결이 안정되지 않음";
            default:
                String b = blocked(w);
                return b.startsWith("(") ? "(" + w + ")" : b;
        }
    }

    /** 지켜보지 않는 이유(보류) — 짧은 말. */
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

    /** 지켜보지 않는 이유 — 문장. */
    static String holdSentence(String h) {
        if (h == null) return "";
        switch (h) {
            case "screen_off":
                return "화면이 꺼져 있어 지금은 지켜보지 않아요";
            case "wifi":
                return "Wi-Fi로 인터넷을 쓰고 있어 지금은 지켜보지 않아요";
            case "call":
                return "통화 중이라(끝나고 30초까지) 지금은 지켜보지 않아요";
            case "roaming":
                return "로밍 중이라 지금은 지켜보지 않아요";
            case "restricted":
                return "다른 쪽(절전·통신사 앱 등)이 5G를 막고 있어 지금은 지켜보지 않아요";
            default:
                return "지금은 지켜보지 않아요(" + h + ")";
        }
    }

    /** 쉬거나 다시 확인하려다 미룬 이유(가드). */
    static String guard(String g) {
        if (g == null) return "";
        switch (g) {
            case "budget_hour":
                return "1시간에 4번까지만 바꿀 수 있어 기다림";
            case "budget_gap":
                return "바꾼 지 2분이 안 됨";
            case "settling":
                return "바꾼 직후 연결이 자리 잡는 중";
            case "heavy_traffic":
                return "큰 파일을 주고받는 중(끝나면 다시)";
            case "no_service":
                return "서비스 없음";
            case "already_blocked":
                return "5G가 이미 막혀 있음";
            case "call":
            case "call_unknown":
                return "통화 중이거나 통화 확인이 안 됨";
            case "emergency":
            case "emergency_callback":
                return "긴급 통화 관련";
            default:
                String h = hold(g);
                return h.startsWith("(") ? "(" + g + ")" : h;
        }
    }

    /** 바꿀 수 없는 이유. */
    static String blocked(String b) {
        if (b == null) return "";
        switch (b) {
            case "dual_sim":
                return "SIM이 2개(한 개일 때만 바꿈)";
            case "sim_count_unknown":
                return "SIM 수를 확인할 수 없음";
            case "user_unreadable":
                return "고른 모드를 읽을 수 없음";
            default:
                return "(" + b + ")";
        }
    }

    /** 남은 시간 "분:초". 음수면 0:00. */
    static String mmss(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** 남은 시간 "N분 N초"(1분 미만은 "N초"). 음수면 "0초". */
    static String minSec(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return s >= 60 ? (s / 60) + "분 " + (s % 60) + "초" : s + "초";
    }
}
