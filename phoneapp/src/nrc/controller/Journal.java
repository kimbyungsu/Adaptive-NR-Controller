package nrc.controller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * JSONL 사건 기록(daemon Journal과 같은 형식, 실사용 분석용). 파일이 MAX_BYTES를 넘으면 .1, .2로 밀어내고 KEEP개만 남긴다.
 * 위치: 앱 전용 외부 폴더(/sdcard/Android/data/nrc.controller/files/logs/nrc.log). 기록 실패로 엔진이 멈추지 않는다.
 */
final class Journal {
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int KEEP = 3;

    private final File file;

    Journal(File dir) {
        File d = new File(dir, "logs");
        d.mkdirs();
        this.file = new File(d, "nrc.log");
    }

    synchronized void write(String event, Object... kv) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("ev", event);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
            }
            rotateIfNeeded();
            try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
                w.println(o.toString());
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            // 기록 실패는 무시하고 다음 사건에서 다시 시도한다
        }
    }

    private void rotateIfNeeded() {
        if (file.length() < MAX_BYTES) return;
        for (int i = KEEP - 1; i >= 1; i--) {
            File from = i == 1 ? file : new File(file.getPath() + "." + (i - 1));
            File to = new File(file.getPath() + "." + i);
            if (from.exists()) {
                to.delete();
                from.renameTo(to);
            }
        }
    }
}
