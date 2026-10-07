package nrc.shizupoc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * no-wifi PoC '시작하기' 단계와 재부팅 시험 판정(DESIGN §5.18 '새 후보 탐색 2차'). 제품 코드 아님.
 * phoneapp StartSteps와 같은 모양: 화면(ProbeControl)이 폰에서 읽은 사실(Facts)을 넣으면 단계별 ✓/지금 할 단계/남은 수를 낸다.
 * 마지막 단계(재부팅 뒤 통로 복구)는 관측 기록(nowifi_probe.log) 줄을 읽어 판정한다. 안드로이드 의존 없음(PC 시험: poc/shizuku/test.sh).
 *
 * 판정 한정(Codex f-8330ba15·DESIGN §5.18): 통로 생존(ping=true)은 '관측 신호'일 뿐 — 어떤 시작 경로로 떴는지는 이 기록만으로
 * 구분하지 못한다. 또 기록은 10초 간격 표본이라 표본 사이(깊은 잠·서비스 재시작)에 일어난 일과, 이 화면이 아닌 다른 앱
 * (Shizuku-Next 등)을 사람이 연 것은 이 기록에 안 남는다. 그래서 가장 좋은 결과(ALIVE_CLEAN)도 "적힌 표본 안에서는 Wi-Fi 없음·
 * 이 화면 안 열림·빈틈 없음"까지만 말하고, 무개입 최종 확인은 같은 부팅의 시스템 기록·앱 사용 기록을 PC로 대조해 정한다.
 */
final class ProbeSteps {
    /** 시험 창: 관측 시작(부팅이면 첫 잠금해제 뒤 관측이 뜬 때)부터 이 시간 안에 살아나야 시험 성공 후보. BootProbe도 이 값을 쓴다. */
    static final long WINDOW_MS = 30 * 60_000L;
    /** 표본 사이가 이보다 벌어지면 '기록 빈틈'(10초 간격 3번분). */
    static final long GAP_MS = 30_000L;
    static final int NEXT = 0, RUN = 1, PERM = 2, NOWIFI = 3, PROBE = 4, AIR = 5, REBOOT = 6, COUNT = 7;
    static final String[] TITLES = {
            "Shizuku-Next 깔기",
            "Shizuku-Next 한 번 켜기(무선 디버깅 페어링)",
            "이 앱 사용 허락",
            "Shizuku-Next 설정 3가지(Wi-Fi 없이 시작·부팅 시 시작·TCP 모드 끄기)",
            "관측 켜기",
            "Wi-Fi 끄고 모바일 데이터 켜기",
            "폰 다시 켜기(재부팅) → 잠금만 풀고 기다리기",
    };

    /** 화면이 폰에서 읽은 사실. 못 읽은 것은 null 또는 "unknown". */
    static final class Facts {
        /** Shizuku-Next가 깔려 있는지(서명으로 확인). */
        boolean nextInstalled;
        /** 같은 이름의 다른 Shizuku(공식판 등)가 깔려 있는지 — 이게 있으면 Shizuku-Next가 안 깔린다. */
        boolean otherShizuku;
        /** Shizuku 통로(binder)가 지금 살아 있는지(pingBinder). */
        boolean alive;
        /**
         * 무선 디버깅 페어링까지 했다고 사용자가 [페어링까지 했어요]를 눌렀는지. 통로가 살아 있는 것만으로는 부족하다 —
         * PC USB로 켠 서버도 살아 있지만 그때는 재부팅 뒤 혼자 켜질 때 쓰는 짝(앱의 ADB 키)이 안 생긴다.
         */
        boolean pairConfirmed;
        /** 이 앱의 Shizuku 사용 허락. 통로가 없으면 물어볼 수 없어 null. */
        Boolean permitted;
        /** 'Wi-Fi 없이 시작'을 켰다고 사용자가 [켰어요]를 눌렀는지(다른 앱 설정이라 이 앱이 직접 못 읽음). */
        boolean noWifiConfirmed;
        /** 관측 켜기 표시(파일)가 있는지. */
        boolean probeOn;
        /** 이 앱 알림이 보일 수 있는지(안드로이드 13 알림 허락 + 앱·채널 알림이 안 꺼짐). 결과를 알림창에서 보려면 필요. */
        boolean notifOk;
        /** Wi-Fi 스위치가 켜져 있는지(못 읽으면 null). */
        Boolean wifiOn;
        /** 모바일 데이터 통로가 있는지: yes/no/unknown. */
        String cell = "unknown";
        /** 관측 기록 줄(없으면 빈 목록). */
        List<String> log = new ArrayList<>();
    }

    /** 재부팅 시험 판정 종류. */
    enum Result {
        /** 관측을 켠 뒤 아직 재부팅 안 함(기록에 부팅 시작이 없음). */
        NOT_REBOOTED,
        /** 재부팅 뒤 관측 중 — 아직 통로 안 살아남. */
        WATCHING,
        /** 통로가 되살아남이 보임 — 적힌 표본 안에서는 Wi-Fi 없음·이 화면 안 열림·기록 빈틈 없음(무개입 최종 확인은 PC 대조). */
        ALIVE_CLEAN,
        /** 되살아났지만 그 전에 기록이 끊긴(재시작·빈틈) 때가 있어 그 사이 일을 모름. */
        ALIVE_GAP,
        /** 되살아났지만 그때 Wi-Fi 통로가 있었음 → 'Wi-Fi 없이'로 칠 수 없음. */
        ALIVE_WITH_WIFI,
        /** 되살아났지만 그때 Wi-Fi 유무를 못 읽음. */
        ALIVE_UNCLEAR,
        /** 되살아나기 전에 이 앱 화면이 열림 → 무개입이라고 말할 수 없음. */
        ALIVE_AFTER_OPEN,
        /** 시험 창(30분) 안에서 통로가 살아난 걸 확인 못 함(창이 지난 뒤 살아난 것도 여기). */
        NOT_RECOVERED,
        /** 관측이 끝까지 못 가고 꺼짐(관측 끄기·앱 강제 종료 등). */
        STOPPED,
        /** 재부팅 뒤 관측이 부팅 신호가 아닌 다른 이유로 먼저 시작됨 → 무개입 판정 불가. */
        NOT_BOOT_START,
    }

    static final class Verdict {
        final Result result;
        /** 통로가 처음 살아난 것이 보인 때의 부팅 후 경과(ms, 없으면 -1). */
        final long aliveSinceBootMs;
        /** 관측 시작 뒤 지금까지 본 시간(ms, 없으면 -1). */
        final long watchedMs;
        final String text;

        Verdict(Result r, long alive, long watched, String text) {
            this.result = r;
            this.aliveSinceBootMs = alive;
            this.watchedMs = watched;
            this.text = text;
        }

        /** 시험이 끝나 결과가 나온 상태인지(관측 중·재부팅 전이 아님). */
        boolean finished() {
            return result != Result.NOT_REBOOTED && result != Result.WATCHING;
        }
    }

    final boolean[] done = new boolean[COUNT];
    final String[] state = new String[COUNT];
    /** 지금 할 단계(다 끝났으면 -1). */
    final int current;
    final int remaining;
    final Verdict verdict;

    private ProbeSteps(Facts f) {
        verdict = judge(f.log);
        // 재부팅 시험이 이미 시작됐으면 준비 단계(1~6)는 끝난 것으로 본다 — 재부팅 직후엔 통로가 아직 없어 2·3단계가
        // 다시 '안 됨'으로 읽히는데, 그걸 지금 할 일로 되돌리면 시험 중인 사용자를 헷갈리게 한다.
        boolean started = verdict.result != Result.NOT_REBOOTED;
        String skip = "재부팅 시험이 시작돼 넘어감";
        done[NEXT] = started || f.nextInstalled;
        state[NEXT] = f.nextInstalled ? "깔려 있음" : started ? skip
                : f.otherShizuku ? "다른 Shizuku(공식판)가 깔려 있음 — 지우고 바꿔 깔아야 해요" : "아직";
        done[RUN] = started || (f.alive && f.pairConfirmed);
        state[RUN] = started && !(f.alive && f.pairConfirmed) ? skip
                : !f.alive ? "꺼져 있음"
                : f.pairConfirmed ? "켜져 있음 · 페어링 했다고 확인함"
                : "켜져 있음 — 페어링까지 했으면 [페어링까지 했어요]를 눌러요";
        boolean permitted = Boolean.TRUE.equals(f.permitted);
        done[PERM] = started || permitted;
        state[PERM] = permitted ? "허락됨" : started ? skip
                : f.permitted == null ? "아직(Shizuku-Next가 켜져 있어야 물을 수 있어요)" : "아직";
        done[NOWIFI] = started || f.noWifiConfirmed;
        state[NOWIFI] = f.noWifiConfirmed ? "켰다고 확인함" : started ? skip : "아직";
        done[PROBE] = started || (f.probeOn && f.notifOk);
        state[PROBE] = started && !(f.probeOn && f.notifOk) ? skip
                : !f.probeOn ? "꺼짐"
                : f.notifOk ? "켜짐(재부팅 때 저절로 시작)"
                : "켜짐 — 그런데 이 앱 알림이 꺼져 있어 결과를 알림창에서 못 봐요";
        boolean air = Boolean.FALSE.equals(f.wifiOn) && "yes".equals(f.cell);
        done[AIR] = started || air;
        state[AIR] = started ? (air ? "Wi-Fi 꺼짐 · 모바일 데이터 연결됨" : skip)
                : air ? "Wi-Fi 꺼짐 · 모바일 데이터 연결됨"
                : (f.wifiOn == null ? "Wi-Fi 상태 모름" : f.wifiOn ? "Wi-Fi 켜져 있음" : "Wi-Fi 꺼짐")
                + " · " + ("yes".equals(f.cell) ? "모바일 데이터 연결됨" : "no".equals(f.cell) ? "모바일 데이터 없음" : "모바일 데이터 모름");
        done[REBOOT] = verdict.finished();
        state[REBOOT] = verdict.text;
        int cur = -1, left = 0;
        for (int i = 0; i < COUNT; i++) {
            if (done[i]) continue;
            left++;
            if (cur < 0) cur = i;
        }
        current = cur;
        remaining = left;
    }

    static ProbeSteps of(Facts f) {
        return new ProbeSteps(f);
    }

    // ================================================================ 기록 판정

    private static final Pattern BOOT = Pattern.compile("\\bboot=(-?\\d+)");
    private static final Pattern SINCE_BOOT = Pattern.compile("\\bsinceBootMs=(\\d+)");
    private static final Pattern SINCE_TEST = Pattern.compile("\\bsinceTestMs=(-?\\d+)");
    private static final Pattern CAUSE = Pattern.compile("=== probe start cause=(\\S+)");
    private static final Pattern WIFI = Pattern.compile("\\bwifiPresent=(\\S+)");

    /**
     * 관측 기록에서 '마지막 부팅'의 결과를 낸다.
     * 부팅 구분: 줄의 boot=(부팅 횟수)가 바뀌면 새 부팅. 그 값이 없는(-1·옛 기록) 줄은 부팅 후 경과(sinceBootMs)가
     * 앞 줄보다 작아지면 새 부팅으로 본다. 판정은 마지막 부팅 묶음만 쓴다.
     */
    static Verdict judge(List<String> lines) {
        List<List<String>> boots = new ArrayList<>();
        List<String> cur = null;
        long lastBoot = Long.MIN_VALUE, lastSince = -1;
        for (String l : lines) {
            long b = num(BOOT, l, -1);
            long s = num(SINCE_BOOT, l, -1);
            boolean fresh = cur == null
                    || (b >= 0 && lastBoot != Long.MIN_VALUE && lastBoot >= 0 && b != lastBoot)
                    || ((b < 0 || lastBoot < 0) && s >= 0 && lastSince >= 0 && s < lastSince);
            if (fresh) {
                cur = new ArrayList<>();
                boots.add(cur);
                lastSince = -1;
            }
            cur.add(l);
            if (b >= 0) lastBoot = b;
            if (s >= 0) lastSince = s;
        }
        if (boots.isEmpty()) return new Verdict(Result.NOT_REBOOTED, -1, -1, "아직(관측을 켜고 재부팅하면 여기서 판정해요)");
        List<String> last = boots.get(boots.size() - 1);
        String cause = null;
        int startAt = -1;
        boolean openedBefore = false;
        for (int i = 0; i < last.size(); i++) {
            String l = last.get(i);
            Matcher m = CAUSE.matcher(l);
            if (m.find()) {
                cause = m.group(1);
                startAt = i;
                break;
            }
            if (l.contains("event: ui-open")) openedBefore = true;
        }
        if (startAt < 0) {
            // 이 부팅엔 관측 시작 줄이 없다: 기록이 한 부팅뿐이면 아직 준비 중, 아니면 재부팅 뒤 관측이 아직 안 뜬 것
            return boots.size() == 1
                    ? new Verdict(Result.NOT_REBOOTED, -1, -1, "아직 재부팅 전")
                    : new Verdict(Result.WATCHING, -1, -1, "재부팅됨 — 관측이 아직 시작 안 됨(잠금을 풀면 시작돼요)");
        }
        if (boots.size() == 1 && !"boot".equals(cause)) {
            // 기록이 한 부팅뿐이고 그게 손으로 켠 관측이면 = 재부팅 전(준비 중)
            return new Verdict(Result.NOT_REBOOTED, -1, -1, "아직 재부팅 전(관측은 켜져 있어요)");
        }
        if (!"boot".equals(cause)) {
            return new Verdict(Result.NOT_BOOT_START, -1, -1,
                    "판정 불가 — 재부팅 뒤 관측이 부팅 신호가 아니라 '" + cause + "'(으)로 먼저 시작됨. [새 시험 시작] 후 다시 해 주세요");
        }
        long watched = -1;
        // 통로가 살아나기 전까지 Wi-Fi가 한 번이라도 보였거나 못 읽힌 적이 있는지(살아난 그 순간만 보지 않는다 —
        // 부팅 뒤 잠깐 붙었던 Wi-Fi로 무선 디버깅이 켜졌다가 끊긴 경우를 'Wi-Fi 없이'로 오독하지 않게)
        boolean sawWifi = false, sawUnknown = false;
        // 기록 빈틈: 관측 시작(또는 앞 표본)과 다음 표본 사이가 GAP_MS보다 벌어졌거나, 관측 서비스가 새로 떠 이어졌는지.
        // 빈틈 동안의 Wi-Fi·다른 앱 개입은 표본에 안 남으므로 깨끗한 결과로 치지 않는다(Codex 지적: 공백을 무개입 증거로 오독)
        long prevSample = num(SINCE_BOOT, last.get(startAt), -1);
        long maxGap = 0;
        boolean restarted = false;
        for (int i = startAt + 1; i < last.size(); i++) {
            String l = last.get(i);
            if (l.contains("event: ui-open")) {
                openedBefore = true;
                continue;
            }
            if (l.contains("=== probe start ")) {
                restarted = true; // 같은 부팅에서 관측 프로세스가 새로 뜸(앞 프로세스가 죽었음) — 그 사이는 기록 없음
                continue;
            }
            if (l.contains("=== probe stop (max run reached)")) {
                return new Verdict(Result.NOT_RECOVERED, -1, watched,
                        "실패 — 시험 창(30분) 안에서 Shizuku 통로가 살아난 걸 확인하지 못했어요");
            }
            if (l.contains("=== probe onDestroy") || l.contains("=== probe off")) {
                return new Verdict(Result.STOPPED, -1, watched,
                        "판정 불가 — 통로가 살아나기 전에 관측이 꺼졌어요(" + minutes(watched) + " 관측). [새 시험 시작] 후 다시 해 주세요");
            }
            if (!l.contains("tick#")) continue;
            long t = num(SINCE_TEST, l, -1);
            if (t >= 0) watched = t;
            long since = num(SINCE_BOOT, l, -1);
            if (since >= 0) {
                if (prevSample >= 0) maxGap = Math.max(maxGap, since - prevSample);
                prevSample = since;
            }
            String wifi = str(WIFI, l);
            if ("yes".equals(wifi)) sawWifi = true;
            else if (!"no".equals(wifi)) sawUnknown = true;
            if (!l.contains(" ping=true")) continue;
            if (t > WINDOW_MS) {
                return new Verdict(Result.NOT_RECOVERED, since, watched,
                        "실패 — 시험 창(30분) 안에서는 살아난 걸 확인하지 못했어요(처음 보인 건 관측 시작 " + minutes(t)
                                + " 뒤 — 시험 성공으로 치지 않음)");
            }
            String at = "부팅 후 늦어도 " + seconds(since) + "에 처음 보임";
            if (t < 0) {
                return new Verdict(Result.ALIVE_UNCLEAR, since, watched,
                        "되살아났지만 시험 창(30분) 안이었는지 기록에서 못 읽었어요(" + at + ")");
            }
            if (openedBefore) {
                return new Verdict(Result.ALIVE_AFTER_OPEN, since, watched,
                        "되살아났지만 그 전에 이 앱 화면이 열려 '아무것도 안 건드림'이라 말할 수 없어요(" + at + ")");
            }
            if (sawWifi) {
                return new Verdict(Result.ALIVE_WITH_WIFI, since, watched,
                        "되살아났지만 그 전에 Wi-Fi가 붙어 있던 적이 있어요 → 'Wi-Fi 없이'로 칠 수 없음(" + at + ")");
            }
            if (sawUnknown) {
                return new Verdict(Result.ALIVE_UNCLEAR, since, watched,
                        "되살아났지만 그 전에 Wi-Fi 유무를 못 읽은 때가 있어요(" + at + ")");
            }
            if (restarted || maxGap > GAP_MS) {
                return new Verdict(Result.ALIVE_GAP, since, watched,
                        "되살아난 게 보였지만 그 전에 기록이 " + (restarted ? "끊겼다 다시 이어진" : seconds(maxGap) + " 비어 있던")
                                + " 때가 있어요 — 그동안 Wi-Fi·다른 앱 개입이 없었는지는 PC 기록 대조로 확인해요(" + at + ")");
            }
            return new Verdict(Result.ALIVE_CLEAN, since, watched,
                    "통로가 되살아난 게 보였어요(" + at + "). 적힌 기록 안에선 Wi-Fi 없음·이 화면 안 열림·빈틈 없음 — "
                            + "다른 앱(Shizuku-Next 등)을 연 적 없는지는 PC 기록 대조로 최종 확인해요");
        }
        if (watched > WINDOW_MS) {
            // 창이 지났는데 끝 줄이 없음(관측 프로세스가 죽은 채 끝남 등) — 창 안에서 확인 못 한 것은 같다
            return new Verdict(Result.NOT_RECOVERED, -1, watched,
                    "실패 — 시험 창(30분) 안에서 Shizuku 통로가 살아난 걸 확인하지 못했어요");
        }
        return new Verdict(Result.WATCHING, -1, watched,
                "관측 중 — 아직 통로 안 살아남(" + minutes(watched) + " 지남, 최대 30분)");
    }

    private static long num(Pattern p, String s, long def) {
        Matcher m = p.matcher(s);
        if (!m.find()) return def;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String seconds(long ms) {
        return ms < 0 ? "?초" : (ms / 1000) + "초";
    }

    private static String minutes(long ms) {
        if (ms < 0) return "0분";
        long s = ms / 1000;
        return s < 60 ? s + "초" : (s / 60) + "분 " + (s % 60) + "초";
    }
}
