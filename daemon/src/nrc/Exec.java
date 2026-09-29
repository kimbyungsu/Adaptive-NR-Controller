package nrc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** 외부 명령 실행(안드로이드 의존 없음). 셸을 거치지 않고 인자 배열로 실행한다. */
final class Exec {
    static final long TIMEOUT_SEC = 15;

    private Exec() {
    }

    /**
     * 출력(표준 오류 포함)을 돌려준다. 출력은 별도 스레드가 읽고, 명령이 TIMEOUT_SEC 안에 끝나지 않으면 끊는다
     * (작업 스레드가 멈추지 않게).
     */
    static String run(String... argv) {
        Process pr = null;
        try {
            pr = new ProcessBuilder(argv).redirectErrorStream(true).start();
            pr.getOutputStream().close();
            final InputStream in = pr.getInputStream();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                byte[] b = new byte[4096];
                int n;
                try {
                    while ((n = in.read(b)) > 0) {
                        synchronized (buf) {
                            buf.write(b, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                    // 끊긴 출력은 여기까지만
                }
            }, "nrd-exec");
            reader.setDaemon(true);
            reader.start();
            boolean finished = pr.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) pr.destroy();
            reader.join(1000);
            String out;
            synchronized (buf) {
                out = buf.toString("UTF-8");
            }
            return finished ? out : out + "[timeout]";
        } catch (Exception e) {
            if (pr != null) pr.destroy();
            return "[exec error] " + e;
        }
    }
}
