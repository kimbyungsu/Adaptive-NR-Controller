package nrc;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 상태 파일(DESIGN §5.6.4·§5.7): 원래 마스크 / 마지막으로 쓴 마스크 / 현재 상태 / 정상 종료 여부.
 * 쓰기 전에 먼저 기록한다(크래시 후 `nrctl start`의 승계·`restore`의 근거). 임시 파일에 쓰고 이름을 바꿔 원자적으로 교체한다.
 */
final class StateStore {
    static final int SCHEMA = 1;

    private final File file;

    StateStore(File dir) {
        this.file = new File(dir, "state.json");
    }

    /** 없으면 null. 읽을 수 없거나 형식이 틀리면 예외. */
    Saved read() throws IOException, JSONException {
        if (!file.exists()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        JSONObject o = new JSONObject(sb.toString());
        if (o.getInt("schema") != SCHEMA) throw new JSONException("schema " + o.getInt("schema"));
        Saved s = new Saved();
        s.clean = o.getBoolean("clean");
        s.original = o.getLong("original");
        s.originalMode = o.optInt("originalMode", -1);
        s.lastWritten = o.optLong("lastWritten", -1);
        s.lastWrittenMode = o.optInt("lastWrittenMode", -1);
        s.pending = o.optLong("pending", -1);
        s.pendingMode = o.optInt("pendingMode", -1);
        s.state = o.optString("state", "");
        s.pid = o.optInt("pid", 0);
        s.sub = o.optInt("sub", -1);
        s.slot = o.optInt("slot", -1);
        return s;
    }

    /** 기록 실패는 호출자에게 알린다(쓰기 전 선기록이 실패하면 쓰지 않아야 하므로). */
    boolean write(Saved s) {
        try {
            JSONObject o = new JSONObject();
            o.put("schema", SCHEMA);
            o.put("t", System.currentTimeMillis());
            o.put("pid", android.os.Process.myPid());
            o.put("clean", s.clean);
            o.put("original", s.original);
            o.put("originalMode", s.originalMode);
            o.put("originalNr", s.original >= 0 && (s.original & Phone.NR_BIT) != 0);
            o.put("lastWritten", s.lastWritten);
            o.put("lastWrittenMode", s.lastWrittenMode);
            o.put("pending", s.pending);
            o.put("pendingMode", s.pendingMode);
            o.put("state", s.state);
            o.put("sub", s.sub);
            o.put("slot", s.slot);
            File tmp = new File(file.getPath() + ".tmp");
            try (FileWriter w = new FileWriter(tmp, false)) {
                w.write(o.toString());
            }
            return tmp.renameTo(file);
        } catch (IOException | JSONException e) {
            return false;
        }
    }
}
