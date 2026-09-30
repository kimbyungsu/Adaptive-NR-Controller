package nrc.controller;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 관측 화면 "지금" 칸의 글(2026-09-30 사용자 결정). 안드로이드 의존 없음(PC 시험).
 * 보강 규칙: "실제 연결(기지국 묶음 기준)"을 기본으로 보이고 상단바 표시는 따로 적는다. 판단에 센 끊김과 판단을 쉬는 이유를 보인다.
 * 확실히 관측한 사실만 쓴다(추정 숫자 없음).
 */
final class NowText {
    final String headline;
    final List<String> lines;
    final String next;

    private NowText(String headline, List<String> lines, String next) {
        this.headline = headline;
        this.lines = lines;
        this.next = next;
    }

    /** 상단바 표시 종류(TelephonyDisplayInfo override) → 말. */
    static String display(int d) {
        switch (d) {
            case 0:
                return "LTE";
            case 1:
                return "LTE+";
            case 2:
                return "LTE-A Pro";
            case 3:
                return "5G";
            case 4:
            case 5:
                return "5G+";
            default:
                return "알 수 없음";
        }
    }

    /** 남의(또는 기록 없는) 5G 막음 안내. 앱은 스스로 풀지 않고 사용자가 버튼으로 푼다. */
    static final String EXTERNAL_LINE = "5G가 다른 쪽에 의해 막혀 있어요 — 통신사 앱이거나, 앱을 지웠다 다시 설치해 앱의 기록이 "
            + "없어진 막음일 수 있어요. 아래 [남은 5G 막음 풀기]로 풀 수 있어요.";

    static String clock(long wall) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(wall));
    }

    /**
     * @param l    엔진의 지금 모습(엔진이 없으면 null)
     * @param tile 타일 글(엔진이 없을 때 머리글로 쓴다)
     * @param now      부팅 후 경과(ms, Live와 같은 시계)
     * @param leftover 이 SIM에 앱이 걸어 둔 5G 막음 기록이 남아 있는지(엔진이 없어도 해제를 기다리는 중일 수 있다)
     * @param external 통신사 칸에 앱이 건 것이 아닌 5G 막음이 있는지(다른 쪽, 또는 앱을 지웠다 다시 설치해 기록이 없어진 막음)
     */
    static NowText of(Live l, TileText tile, long now, boolean leftover, boolean external) {
        List<String> ls = new ArrayList<>();
        if (external) ls.add(EXTERNAL_LINE);
        if (l == null) {
            // 판단이 꺼져 있다고 곧 순정은 아니다(외부 검증 지적): 남은 막음·문제를 먼저 알린다
            if (leftover) {
                ls.add("앱이 걸어 둔 5G 막음이 아직 남아 있어요. 통화 중이면 통화가 끝난 뒤, 아니면 30초마다 다시 풀어요.");
            }
            if (tile.look == TileText.Look.UNAVAILABLE) ls.add("문제: " + tile.subtitle + " — 지금은 앱이 5G/LTE를 바꿀 수 없어요.");
            if (!leftover && !external && tile.look != TileText.Look.UNAVAILABLE) {
                ls.add("판단이 꺼져 있고 남은 막음도 없어, 삼성 설정대로 순정(폰 기본 동작)이에요.");
            }
            String next = leftover ? "막음이 풀리면 순정으로 돌아가요."
                    : "자동 제어 꺼짐".equals(tile.subtitle) ? "타일을 한 번 탭하면 자동 제어를 켜요."
                    : TileText.STOPPED.equals(tile.subtitle) ? "타일을 탭하면 다시 시작해요."
                    : TileText.STARTING.equals(tile.subtitle) ? "곧 시작해요."
                    : "[상세] 칸에서 원인을 확인하세요.";
            return new NowText(tile.subtitle, ls, next);
        }
        // 확인되지 않은 것은 단정하지 않는다(외부 검증 지적): 서비스 없음·확인 중·연결 없음을 따로 보인다
        String conn = !l.dataIn ? "서비스 없음" : !l.pccKnown ? "확인 중" : (l.nrActual ? "5G 사용 중" : "LTE만 사용 중");
        boolean nrConfirmed = l.dataIn && l.pccKnown && l.nrActual;
        String connHead = !l.dataIn ? "서비스 없음" : !l.pccKnown ? "연결 확인 중" : "지금은 LTE로 연결";
        String net = l.wifi ? "Wi-Fi" : (l.dataConnected ? "모바일 데이터" : "연결 안 됨·확인 중");
        ls.add("지금 연결: " + conn + " (상단바 표시: " + display(l.display) + ")");
        ls.add("고른 모드: " + (l.userNr ? "5G 우선" : "LTE 우선") + " · 인터넷: " + net);
        if (l.problem != null) ls.add("문제: " + l.problem);
        if (l.selfTesting) ls.add("자가 점검 중이에요");
        String headline;
        String next;
        switch (l.state == null ? "" : l.state) {
            case "INACTIVE":
                headline = "LTE 우선 · 대기";
                ls.add("사용자가 LTE 우선을 골라 앱은 아무것도 바꾸지 않아요.");
                next = "5G 우선을 고르면 바로 지켜보기 시작해요.";
                break;
            case "OBSERVE":
                headline = "지켜보기만 (바꿀 수 없음)";
                ls.add("이유: " + (l.blocked != null ? Words.blocked(l.blocked) : Words.why(l.stateWhy)));
                next = "조건이 풀리면 자동으로 다시 시작해요.";
                break;
            case "SAFE_STOP":
                headline = "안전하게 멈춤";
                ls.add("이유: " + Words.why(l.stateWhy));
                next = "타일을 껐다 켜면 다시 시작해요.";
                break;
            case "COOLDOWN": {
                headline = "LTE로 잠깐 쉬는 중";
                ls.add("이유: " + Words.why(l.restWhy));
                ls.add("쉬기 시작: " + clock(l.stateSinceWall));
                long left = l.coolUntil - now;
                ls.add(left > 0 ? Words.minSec(left) + " 뒤 5G 다시 확인" : "쉬는 시간 끝 · 5G 다시 확인을 기다리는 중");
                if (l.hold != null) ls.add(Words.holdSentence(l.hold) + " — 이 조건이 끝나면 5G를 다시 확인해요");
                next = "쉬는 시간이 끝나면 5G를 다시 확인해요.";
                break;
            }
            case "PROBE": {
                headline = "5G 다시 확인 중";
                ls.add("다시 확인 시작: " + clock(l.stateSinceWall));
                ls.add("데이터를 주고받은 시간 " + (l.useMs / 1000) + "/" + (l.pActive / 1000) + "초 (화면이 켜져 있을 때만 셈)");
                ls.add("끊김 " + l.probeDrops + "번 (" + l.nProbe + "번이면 다시 쉬기)");
                if (l.evalStart >= 0) ls.add(Words.minSec(l.evalStart + l.pMax - now) + " 안에 판정");
                else ls.add("바꾼 직후라 연결이 자리 잡는 중(끝나면 확인 시작)");
                if (l.hold != null) ls.add(Words.holdSentence(l.hold));
                next = "데이터를 " + (l.pActive / 1000) + "초 주고받는 동안 끊김이 " + l.nProbe + "번 미만이면 통과예요.";
                break;
            }
            default: {
                if (l.wifi && l.userNr) {
                    headline = "Wi-Fi 사용 중 · 대기";
                    ls.add("Wi-Fi로 인터넷을 쓰는 동안은 5G를 지켜보지 않아요(폰 기본 동작).");
                    next = "Wi-Fi가 끊기면 다시 지켜봐요.";
                    break;
                }
                // 머리글의 연결은 확인된 실제 연결만(외부 검증 지적: 판단 상태만 보고 "5G 사용 중"이라 하면 실제 LTE와 모순)
                boolean good = "GOOD".equals(l.state);
                if (nrConfirmed) headline = "5G 사용 중 · " + (good ? "안정적" : "끊김이 있어 지켜보는 중");
                else headline = connHead + " · " + (good ? "5G를 지켜보는 중" : "끊김이 있어 지켜보는 중");
                ls.add("화면을 켜고 데이터를 주고받는 중 " + (l.windowMs / 60_000) + "분 안에 5G가 " + l.nDrop + "번 끊기면 LTE로 잠깐 쉬어요.");
                String count = "최근 " + (l.windowMs / 60_000) + "분 동안 끊김 " + l.drops + "번 (" + l.nDrop + "번이면 쉬기)";
                if (l.oldestDrop >= 0) count += " · 가장 오래된 끊김은 " + Words.minSec(l.oldestDrop + l.windowMs - now) + " 뒤 셈에서 빠져요";
                ls.add(count);
                if (l.hold != null) {
                    ls.add(Words.holdSentence(l.hold));
                    next = "이 조건이 끝나면 다시 지켜봐요.";
                } else {
                    next = "계속 지켜봐요.";
                }
                break;
            }
        }
        return new NowText(headline, ls, next);
    }
}
