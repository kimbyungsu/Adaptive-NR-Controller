package nrc.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 활동 기록(앱 안 관측 화면, 2026-09-30). 망 관찰 / 앱 판단 / 앱 조치를 사람이 읽는 문장으로 최근 것만 남긴다.
 * 원본 사건 기록(Journal)과 별개다: 이쪽은 보여 주기용 요약이고, 화면이 꺼진 동안의 5G 붙음·떨어짐 같은 잦은 사건은 싣지 않는다.
 * 프로세스가 다시 떠도 남도록 파일에 덧붙이고, 시작할 때 최근 것을 읽는다. 한 프로세스 안에서 하나만 쓴다(get).
 */
final class Timeline {
    enum Cat {
        OBS("관찰"), JUDGE("판단"), ACT("조치");

        final String label;

        Cat(String label) {
            this.label = label;
        }
    }

    static final class Entry {
        final long wall;
        final Cat cat;
        final String text;

        Entry(long wall, Cat cat, String text) {
            this.wall = wall;
            this.cat = cat;
            this.text = text;
        }
    }

    static final int CAP = 800;
    static final long KEEP_MS = 36L * 3_600_000;
    private static final long MAX_FILE = 512L * 1024;
    private static Timeline instance;

    private final ArrayDeque<Entry> q = new ArrayDeque<>();
    private final File file;

    static synchronized Timeline get(File dir) {
        if (instance == null) instance = new Timeline(dir);
        return instance;
    }

    private Timeline(File dir) {
        dir.mkdirs();
        file = new File(dir, "timeline.txt");
        load();
    }

    synchronized void add(Cat c, String text) {
        long wall = System.currentTimeMillis();
        Entry e = new Entry(wall, c, text.replace('\n', ' ').replace('\t', ' '));
        q.addLast(e);
        while (q.size() > CAP) q.pollFirst();
        try {
            if (file.length() > MAX_FILE) {
                rewrite(); // 새 사건까지 담아 다시 쓰므로 따로 덧붙이지 않는다(외부 검증 지적: 두 줄로 저장됐다)
                return;
            }
            try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
                w.println(e.wall + "\t" + e.cat.name() + "\t" + e.text);
            }
        } catch (IOException | RuntimeException ignored) {
            // 저장 실패는 무시(화면에는 메모리 것이 보인다)
        }
    }

    /** 지금부터 거꾸로(최신 먼저) sinceWall 이후 것. */
    synchronized List<Entry> recent(long sinceWall) {
        List<Entry> out = new ArrayList<>();
        java.util.Iterator<Entry> it = q.descendingIterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.wall < sinceWall) break;
            out.add(e);
        }
        return out;
    }

    private void load() {
        if (!file.exists()) return;
        long cut = System.currentTimeMillis() - KEEP_MS;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", 3);
                if (f.length < 3) continue;
                try {
                    long wall = Long.parseLong(f[0]);
                    if (wall < cut) continue;
                    q.addLast(new Entry(wall, Cat.valueOf(f[1]), f[2]));
                    while (q.size() > CAP) q.pollFirst();
                } catch (RuntimeException ignored) {
                    // 망가진 줄은 건너뛴다
                }
            }
        } catch (IOException ignored) {
            // 읽기 실패는 빈 기록으로 시작
        }
    }

    private void rewrite() throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(file, false))) {
            for (Entry e : q) w.println(e.wall + "\t" + e.cat.name() + "\t" + e.text);
        }
    }
}
