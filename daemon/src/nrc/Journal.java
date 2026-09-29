package nrc;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * JSONL 이벤트 로그. 파일 하나가 MAX_BYTES를 넘으면 .1, .2로 밀어내고 KEEP개만 남긴다.
 * 로그를 못 써도 데몬은 계속 돈다(관찰이 기록 실패로 멈추지 않게).
 */
final class Journal implements Log {
    static final int SCHEMA = 1;
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int KEEP = 3;

    private final File file;

    Journal(File dir) {
        dir.mkdirs();
        this.file = new File(dir, "nrd.log");
    }

    @Override
    public synchronized void write(String event, Object... kv) {
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
        } catch (IOException | JSONException ignored) {
            // 기록 실패는 무시하고 다음 이벤트에서 다시 시도한다
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
