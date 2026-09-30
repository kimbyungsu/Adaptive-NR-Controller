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

    static String clock(long wall) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(wall));
    }

    /**
     * @param l    엔진의 지금 모습(엔진이 없으면 null)
     * @param tile 타일 글(엔진이 없을 때 머리글로 쓴다)
     * @param now      부팅 후 경과(ms, Live와 같은 시계)
     * @param leftover 이 SIM에 앱이 걸어 둔 5G 막음 기록이 남아 있는지(엔진이 없어도 해제를 기다리는 중일 수 있다)
     */
    static NowText of(Live l, TileText tile, long now, boolean leftover) {
        List<String> ls = new ArrayList<>();
        if (l == null) {
            // 엔진이 없다고 곧 순정은 아니다(외부 검증 지적): 남은 막음·문제를 먼저 알린다
            if (leftover) {
                ls.add("앱이 걸어 둔 5G 막음이 아직 남아 있습니다. 통화 중이면 통화가 끝난 뒤, 아니면 30초마다 다시 풀기를 시도합니다.");
            }
            if (tile.look == TileText.Look.UNAVAILABLE) ls.add("문제: " + tile.subtitle + " — 지금은 앱이 망을 바꿀 수 없습니다.");
            if (!leftover && tile.look != TileText.Look.UNAVAILABLE) {
                ls.add("판단 엔진이 돌고 있지 않고 남은 막음도 없어 삼성 설정대로 순정 동작합니다.");
            }
            String next = leftover ? "막음이 풀리면 순정 동작으로 돌아갑니다."
                    : "자동 제어 꺼짐".equals(tile.subtitle) ? "타일을 한 번 탭하면 자동 제어를 켭니다."
                    : TileText.STOPPED.equals(tile.subtitle) ? "타일을 탭하면 다시 시작합니다."
                    : TileText.STARTING.equals(tile.subtitle) ? "곧 시작합니다."
                    : "상세 칸에서 원인을 확인하세요.";
            return new NowText(tile.subtitle, ls, next);
        }
        // 확인되지 않은 것은 단정하지 않는다(외부 검증 지적): 서비스 없음·보고 전·연결 없음을 따로 보인다
        String conn = !l.dataIn ? "서비스 없음" : !l.pccKnown ? "확인 전" : (l.nrActual ? "5G(5G 칸 붙음)" : "LTE(5G 칸 없음)");
        String net = l.wifi ? "Wi-Fi" : (l.dataConnected ? "모바일 데이터" : "연결 안 됨·확인 중");
        ls.add("실제 연결: " + conn + " · 상단바 표시: " + display(l.display));
        ls.add("사용자 선택: " + (l.userNr ? "5G 우선" : "LTE 우선") + " · 인터넷: " + net);
        if (l.problem != null) ls.add("문제: " + l.problem);
        if (l.selfTesting) ls.add("자가 점검 중");
        String headline;
        String next;
        switch (l.state == null ? "" : l.state) {
            case "INACTIVE":
                headline = "LTE 우선 · 대기";
                ls.add("사용자가 LTE 우선을 골라 앱은 아무것도 바꾸지 않습니다.");
                next = "5G 우선을 고르면 곧바로 감시를 시작합니다.";
                break;
            case "OBSERVE":
                headline = "관찰만 (제어 불가)";
                ls.add("이유: " + (l.blocked != null ? Words.blocked(l.blocked) : Words.why(l.stateWhy)));
                next = "조건이 풀리면 자동으로 다시 제어합니다.";
                break;
            case "SAFE_STOP":
                headline = "안전 정지";
                ls.add("이유: " + Words.why(l.stateWhy));
                next = "타일을 껐다 켜면 다시 시작합니다.";
                break;
            case "COOLDOWN": {
                headline = "LTE로 쉬는 중";
                ls.add("이유: " + Words.why(l.restWhy));
                ls.add("쉬기 시작: " + clock(l.stateSinceWall));
                long left = l.coolUntil - now;
                ls.add(left > 0 ? "남은 휴식: " + Words.mmss(left) : "휴식 끝 · 재시험할 수 있을 때를 기다리는 중");
                if (l.hold != null) ls.add("재시험은 이 조건이 끝난 뒤: " + Words.hold(l.hold));
                next = "휴식이 끝나면 5G를 다시 시험합니다.";
                break;
            }
            case "PROBE": {
                headline = "5G 재시험 중";
                ls.add("재시험 시작: " + clock(l.stateSinceWall));
                ls.add("데이터 사용 확인: " + (l.useMs / 1000) + " / " + (l.pActive / 1000) + "초");
                ls.add("재시험 중 끊김: " + l.probeDrops + " / " + l.nProbe + "번(" + l.nProbe + "번이면 다시 쉼)");
                if (l.evalStart >= 0) ls.add("판정 마감까지: " + Words.mmss(l.evalStart + l.pMax - now));
                else ls.add("전환 직후 망이 자리 잡는 중(끝나면 확인 시작)");
                if (l.hold != null) ls.add("지금은 확인을 쉬는 중: " + Words.hold(l.hold));
                next = "데이터를 " + (l.pActive / 1000) + "초 쓰는 동안 끊김이 " + l.nProbe + "번 미만이면 통과입니다.";
                break;
            }
            default: {
                if (l.wifi && l.userNr) {
                    headline = "Wi-Fi · 대기";
                    ls.add("Wi-Fi로 인터넷을 쓰는 동안은 5G를 판단하지 않습니다(순정).");
                    next = "Wi-Fi가 끊기면 다시 감시합니다.";
                    break;
                }
                headline = "5G 감시 중 · " + ("GOOD".equals(l.state) ? "안정" : "지켜보는 중");
                ls.add("판단 기준: 화면이 켜져 있고 데이터를 쓰는 중에 " + (l.windowMs / 60_000) + "분 안에 5G가 "
                        + l.nDrop + "번 끊기면 LTE로 쉽니다.");
                String now1 = "지금: " + l.drops + " / " + l.nDrop + "번";
                if (l.oldestDrop >= 0) now1 += "(가장 오래된 끊김은 " + Words.mmss(l.oldestDrop + l.windowMs - now) + " 뒤 빠짐)";
                ls.add(now1);
                if (l.hold != null) {
                    ls.add("판단 쉬는 중: " + Words.hold(l.hold));
                    next = "이 조건이 끝나면 다시 셉니다.";
                } else {
                    next = "계속 감시합니다.";
                }
                break;
            }
        }
        return new NowText(headline, ls, next);
    }
}
