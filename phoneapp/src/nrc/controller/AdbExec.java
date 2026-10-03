/*
 * 아래 구현은 AOSP adb 의 전송 프로토콜(adb.cpp·protocol.txt)을 Java 로 옮긴 것입니다.
 * Copyright (C) 2007 The Android Open Source Project — Apache License, Version 2.0.
 * 전체 고지·라이선스: 저장소 루트 THIRD_PARTY_NOTICES.md(§4), licenses/Apache-2.0.txt.
 */
package nrc.controller;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * 페어링된 열쇠로 폰 자신의 adbd(무선 디버깅 접속 포트)에 붙어 명령 하나를 shell 권한으로 실행하고 출력을 받는다.
 * adb 프로토콜(AOSP adb.cpp·protocol.txt): 평문 CNXN → 폰이 STLS → STLS로 답하고 TLS 1.3(이 앱 인증서) → 폰의 CNXN
 * → OPEN("exec:명령") → OKAY → WRTE(출력)마다 OKAY → CLSE. 패킷 = 24바이트 머리(작은 끝) + 본문.
 */
final class AdbExec {
    private AdbExec() {
    }

    static final int A_CNXN = 0x4e584e43, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43,
            A_WRTE = 0x45545257, A_AUTH = 0x48545541, A_STLS = 0x534C5453;
    static final int A_VERSION = 0x01000001, A_STLS_VERSION = 0x01000000, MAX_DATA = 256 * 1024;

    static final class Failure extends Exception {
        Failure(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** 명령을 실행해 출력(stdout, UTF-8)을 돌려준다. */
    static String run(AdbKey key, String host, int port, String command) throws Failure {
        Socket raw = new Socket();
        Socket s = raw;
        try {
            try {
                raw.connect(new InetSocketAddress(host, port), 10_000);
                raw.setSoTimeout(20_000);
            } catch (Exception e) {
                throw new Failure("무선 디버깅에 연결하지 못했어요(무선 디버깅이 꺼졌을 수 있어요)", e);
            }
            send(raw.getOutputStream(), A_CNXN, A_VERSION, MAX_DATA, "host::features=shell_v2,cmd".getBytes(StandardCharsets.US_ASCII));
            int[] h = new int[4];
            byte[] body = read(new DataInputStream(raw.getInputStream()), h);
            if (h[0] == A_AUTH || h[0] == A_CNXN) {
                throw new Failure("이 폰의 무선 디버깅이 암호화 연결을 요구하지 않아요(예상과 다른 상태)", null);
            }
            if (h[0] != A_STLS) throw new Failure("예상하지 못한 응답(" + Integer.toHexString(h[0]) + ")", null);
            send(raw.getOutputStream(), A_STLS, A_STLS_VERSION, 0, new byte[0]);

            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(new KeyManager[]{key.keyManager()}, new TrustManager[]{AdbKey.trustAnyLocalAdbd()}, new SecureRandom());
            SSLSocket tls = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s = tls;
            tls.setEnabledProtocols(new String[]{"TLSv1.3"});
            tls.setUseClientMode(true);
            tls.startHandshake();
            DataInputStream in = new DataInputStream(tls.getInputStream());
            OutputStream out = tls.getOutputStream();
            try {
                read(in, h); // 폰의 CNXN(인증서가 받아들여지지 않았으면 여기서 경고로 끊긴다)
            } catch (Exception e) {
                throw new Failure("폰이 이 앱의 열쇠를 받아들이지 않았어요(페어링이 안 됐을 수 있어요)", e);
            }
            if (h[0] != A_CNXN) throw new Failure("연결 확인 응답이 아니에요(" + Integer.toHexString(h[0]) + ")", null);

            final int local = 1;
            send(out, A_OPEN, local, 0, ("exec:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                body = read(in, h);
                if (h[0] == A_OKAY) {
                    remote = h[1];
                } else if (h[0] == A_WRTE) {
                    result.write(body, 0, body.length);
                    send(out, A_OKAY, local, h[1], new byte[0]);
                } else if (h[0] == A_CLSE) {
                    if (remote != 0) send(out, A_CLSE, local, remote, new byte[0]);
                    break;
                }
            }
            return result.toString("UTF-8");
        } catch (Failure f) {
            throw f;
        } catch (Exception e) {
            throw new Failure("명령 실행 중 오류: " + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : ""), e);
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
                // 닫기 실패는 무시
            }
        }
    }

    static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws Exception {
        long sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer bb = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt((int) sum).putInt(~cmd);
        bb.put(data);
        out.write(bb.array());
        out.flush();
    }

    /** 패킷 하나를 읽는다. h = {명령, arg0, arg1, 길이}. */
    static byte[] read(InputStream is, int[] h) throws Exception {
        DataInputStream in = is instanceof DataInputStream ? (DataInputStream) is : new DataInputStream(is);
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer bb = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        h[0] = bb.getInt();
        h[1] = bb.getInt();
        h[2] = bb.getInt();
        h[3] = bb.getInt();
        bb.getInt(); // 검사합(판 0x01000001부터는 보지 않음)
        int magic = bb.getInt();
        if (magic != ~h[0]) throw new IllegalStateException("bad magic");
        if (h[3] < 0 || h[3] > 16 * 1024 * 1024) throw new IllegalStateException("bad length " + h[3]);
        byte[] body = new byte[h[3]];
        in.readFully(body);
        return body;
    }
}
