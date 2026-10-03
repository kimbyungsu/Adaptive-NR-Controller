/*
 * 아래 구현은 AOSP adb 의 pairing_auth/aes_128_gcm.cpp 및 pairing_connection.cpp 를 Java 로 옮긴 것입니다.
 * Copyright (C) 2020 The Android Open Source Project — Apache License, Version 2.0.
 * 전체 고지·라이선스: 저장소 루트 THIRD_PARTY_NOTICES.md(§4), licenses/Apache-2.0.txt.
 */
package nrc.controller;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * adb 페어링의 암호 부분(AOSP packages/modules/adb pairing_auth/aes_128_gcm.cpp·pairing_connection.cpp를 옮김).
 * - SPAKE2 키(64바이트) → HKDF-SHA256(소금 없음, info "adb pairing_auth aes-128-gcm key") → AES-128-GCM 키.
 * - 논스 12바이트 = 보내기/받기 차례 번호(8바이트 작은 끝) + 0. 암호문 = 본문 + 16바이트 태그.
 * - 패킷 머리 6바이트 = 판(1) + 종류(0 SPAKE2 메시지, 1 PeerInfo) + 길이(4바이트 큰 끝).
 * - PeerInfo 8192바이트 = 종류(0 = RSA 공개키) + 공개키 문자열(나머지는 0).
 * 안드로이드 의존 없음(PC 시험).
 */
final class PairCrypto {
    static final int HEADER_VERSION = 1;
    static final int TYPE_SPAKE2_MSG = 0;
    static final int TYPE_PEER_INFO = 1;
    static final int PEER_INFO_SIZE = 8192;
    static final int MAX_PAYLOAD = PEER_INFO_SIZE * 2;
    /** TLS에서 내보내는 키 재료의 이름표. 원본은 sizeof("adb-label")로 넘겨 끝의 NUL까지 들어간다. */
    static final String EXPORT_LABEL = "adb-label\u0000";
    static final int EXPORT_LEN = 64;

    private final SecretKeySpec key;
    private long encSeq;
    private long decSeq;

    PairCrypto(byte[] spakeKey) throws Exception {
        key = new SecretKeySpec(hkdf(spakeKey, "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII), 16), "AES");
    }

    byte[] encrypt(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce(encSeq)));
        byte[] out = c.doFinal(plain);
        encSeq++;
        return out;
    }

    /** 태그가 맞지 않으면(코드가 틀렸거나 가로챔) 예외. */
    byte[] decrypt(byte[] sealed) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(decSeq)));
        byte[] out = c.doFinal(sealed);
        decSeq++;
        return out;
    }

    private static byte[] nonce(long seq) {
        byte[] n = new byte[12];
        for (int i = 0; i < 8; i++) n[i] = (byte) (seq >>> (8 * i));
        return n;
    }

    /** RFC 5869 HKDF-SHA256. 소금이 없으면 해시 길이만큼의 0(HMAC에서 빈 키와 같음). */
    static byte[] hkdf(byte[] ikm, byte[] info, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[len];
        byte[] t = new byte[0];
        int pos = 0;
        for (int i = 1; pos < len; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, len - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    static byte[] header(int type, int payload) {
        return ByteBuffer.allocate(6).put((byte) HEADER_VERSION).put((byte) type).putInt(payload).array();
    }

    /** 머리를 읽는다: {종류, 길이}. 판·종류·길이가 맞지 않으면 예외. */
    static int[] parseHeader(byte[] h, int expectType) {
        if (h.length != 6 || h[0] != HEADER_VERSION) throw new IllegalStateException("header version " + (h.length > 0 ? h[0] : -1));
        int type = h[1] & 0xff;
        int len = ByteBuffer.wrap(h, 2, 4).getInt();
        if (type != expectType) throw new IllegalStateException("header type " + type + " (want " + expectType + ")");
        if (len <= 0 || len > MAX_PAYLOAD) throw new IllegalStateException("header payload " + len);
        return new int[]{type, len};
    }

    /** 이 앱의 PeerInfo: 종류 0(RSA 공개키) + adb 공개키 문자열. */
    static byte[] peerInfo(String adbPublicKey) {
        byte[] s = adbPublicKey.getBytes(StandardCharsets.UTF_8);
        if (s.length > PEER_INFO_SIZE - 2) throw new IllegalArgumentException("key too long");
        byte[] out = new byte[PEER_INFO_SIZE];
        out[0] = 0;
        System.arraycopy(s, 0, out, 1, s.length);
        return out;
    }
}
