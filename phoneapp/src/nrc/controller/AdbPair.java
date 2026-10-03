/*
 * 아래 구현은 AOSP Android Debug Bridge(adb)의 pairing_connection.cpp(클라이언트 측)를 Java 로 옮긴 것입니다.
 * Copyright (C) 2020 The Android Open Source Project — Apache License, Version 2.0.
 * 전체 고지·라이선스: 저장소 루트 THIRD_PARTY_NOTICES.md(§4), licenses/Apache-2.0.txt.
 */
package nrc.controller;

import android.net.ssl.SSLSockets;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * 무선 디버깅 페어링 클라이언트(AOSP adb pairing_connection.cpp의 클라이언트 쪽을 옮김, 사용자 결정 2026-09-30: 직접 구현).
 * 순서: TLS 1.3(양쪽 자체 서명) → 키 재료 64바이트 내보내기("adb-label\0") → SPAKE2(코드 + 키 재료) 메시지 교환
 * → AES-128-GCM으로 PeerInfo(이 앱의 공개키) 교환. 성공하면 폰이 이 앱의 공개키를 "페어링된 기기"에 저장한다.
 */
final class AdbPair {
    private AdbPair() {
    }

    /** 실패 이유(화면에 그대로 쓰는 생활 말). */
    static final class Failure extends Exception {
        Failure(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    static void pair(AdbKey key, String host, int port, String code) throws Failure {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), 10_000);
            raw.setSoTimeout(15_000);
        } catch (Exception e) {
            close(raw);
            throw new Failure("페어링 창에 연결하지 못했어요(코드 창이 닫혔을 수 있어요)", e);
        }
        SSLSocket s = null;
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(new KeyManager[]{key.keyManager()}, new TrustManager[]{AdbKey.trustAnyLocalAdbd()}, new SecureRandom());
            s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s.setEnabledProtocols(new String[]{"TLSv1.3"});
            s.setUseClientMode(true);
            s.startHandshake();
            if (!SSLSockets.isSupportedSocket(s)) throw new IllegalStateException("tls export unsupported");
            byte[] ekm = SSLSockets.exportKeyingMaterial(s, PairCrypto.EXPORT_LABEL, null, PairCrypto.EXPORT_LEN);
            byte[] c = code.getBytes(StandardCharsets.US_ASCII);
            byte[] pw = new byte[c.length + ekm.length];
            System.arraycopy(c, 0, pw, 0, c.length);
            System.arraycopy(ekm, 0, pw, c.length, ekm.length);

            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            Spake2 sp = Spake2.adbClient();
            byte[] msg = sp.generateMsg(pw, new SecureRandom());
            out.write(PairCrypto.header(PairCrypto.TYPE_SPAKE2_MSG, msg.length));
            out.write(msg);
            out.flush();
            byte[] theirs = readPayload(in, PairCrypto.TYPE_SPAKE2_MSG);
            byte[] k = sp.processMsg(theirs);
            if (k == null) throw new IllegalStateException("bad spake2 msg");

            PairCrypto pc = new PairCrypto(k);
            byte[] sealed = pc.encrypt(PairCrypto.peerInfo(key.adbPublicKey()));
            out.write(PairCrypto.header(PairCrypto.TYPE_PEER_INFO, sealed.length));
            out.write(sealed);
            out.flush();
            byte[] peer = pc.decrypt(readPayload(in, PairCrypto.TYPE_PEER_INFO));
            if (peer.length != PairCrypto.PEER_INFO_SIZE) throw new IllegalStateException("peer info size " + peer.length);
        } catch (AEADBadTagException e) {
            throw new Failure("코드가 맞지 않아요(설정 화면의 6자리 코드를 다시 확인해 주세요)", e);
        } catch (EOFException e) {
            throw new Failure("폰이 연결을 끊었어요(코드가 틀렸거나 코드 창이 닫혔을 수 있어요)", e);
        } catch (Exception e) {
            throw new Failure("페어링 중 오류: " + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : ""), e);
        } finally {
            close(s != null ? s : raw);
        }
    }

    private static byte[] readPayload(DataInputStream in, int type) throws Exception {
        byte[] h = new byte[6];
        in.readFully(h);
        int len = PairCrypto.parseHeader(h, type)[1];
        byte[] p = new byte[len];
        in.readFully(p);
        return p;
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
            // 닫기 실패는 무시
        }
    }
}
