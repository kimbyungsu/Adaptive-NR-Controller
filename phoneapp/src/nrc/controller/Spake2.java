/*
 * 이 파일은 BoringSSL 의 SPAKE2 구현(crypto/curve25519/spake25519)을 Java 로 옮긴 것입니다.
 * 원저작권: Copyright (c) 2016, Google Inc. / The BoringSSL Authors
 * 원본은 이식 당시 ISC 계열 고지로 배포되었고, 이후 BoringSSL 은 같은 파일을 Apache License 2.0 으로
 * 재라이선스했습니다. 전체 라이선스·고지는 저장소 루트의 THIRD_PARTY_NOTICES.md 및
 * licenses/Apache-2.0.txt 를 참조하세요.
 */
package nrc.controller;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * SPAKE2(edwards25519) — BoringSSL crypto/curve25519/spake25519.cc를 옮긴 것(안드로이드 adb 페어링이 쓰는 그 구현).
 * adb 페어링에서 폰(adbd)은 bob, 이 앱은 alice다. 이름에는 C 문자열 끝의 NUL까지 들어간다("adb pair client\0", 16바이트).
 * 원본과 같게 하는 점:
 * - 비밀 스칼라 = 64 무작위 바이트 mod l, 그다음 ×8(보조 인수 지우기).
 * - 암호 스칼라 = SHA-512(암호) mod l에 l·2l·4l을 조건부로 더해 8의 배수로 만든다("password scalar hack", 기본 켜짐).
 * - 키 = SHA-512(8바이트 작은 끝 길이 + 값)을 이름·메시지·공유점·암호 해시 순서로(역할마다 순서가 정해짐).
 * 안드로이드 의존 없음(PC 시험).
 */
final class Spake2 {
    static final byte[] CLIENT_NAME = nul("adb pair client");
    static final byte[] SERVER_NAME = nul("adb pair server");
    /** N, M 점(원본 주석의 인코딩, 시드 "edwards25519 point generation seed (N|M)"에서 나온 값 — 시험이 다시 만들어 대조). */
    static final Ed25519.Point N = Ed25519.decode(Ed25519.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));
    static final Ed25519.Point M = Ed25519.decode(Ed25519.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));

    private final boolean alice;
    private final byte[] myName;
    private final byte[] theirName;
    private BigInteger privateKey;
    private BigInteger passwordScalar;
    private byte[] passwordHash;
    private byte[] myMsg;

    Spake2(boolean alice, byte[] myName, byte[] theirName) {
        this.alice = alice;
        this.myName = myName;
        this.theirName = theirName;
    }

    /** adb 페어링 클라이언트(이 앱 쪽). */
    static Spake2 adbClient() {
        return new Spake2(true, CLIENT_NAME, SERVER_NAME);
    }

    /** 시험용 상대(폰 쪽 역할). */
    static Spake2 adbServer() {
        return new Spake2(false, SERVER_NAME, CLIENT_NAME);
    }

    /** 내 메시지(32바이트)를 만든다. random은 64바이트를 채운다(시험에서 고정값을 넣을 수 있게). */
    byte[] generateMsg(byte[] password, SecureRandom random) throws Exception {
        byte[] priv = new byte[64];
        random.nextBytes(priv);
        privateKey = Ed25519.fromLe(Ed25519.scReduce(priv)).shiftLeft(3); // ×8(left_shift_3)
        Ed25519.Point p = Ed25519.mul(privateKey, Ed25519.BASE);

        passwordHash = MessageDigest.getInstance("SHA-512").digest(password);
        passwordScalar = passwordScalarOf(passwordHash);

        Ed25519.Point mask = Ed25519.mul(passwordScalar, alice ? M : N);
        myMsg = Ed25519.encode(Ed25519.add(p, mask));
        return myMsg.clone();
    }

    /** 원본의 password scalar hack: mod l 값에 l, 2l, 4l을 아래 비트에 따라 더해 8의 배수로(값은 mod l로 같다). */
    static BigInteger passwordScalarOf(byte[] sha512) {
        BigInteger s = Ed25519.fromLe(Ed25519.scReduce(sha512));
        BigInteger order = Ed25519.L;
        if (s.testBit(0)) s = s.add(order);
        order = order.shiftLeft(1);
        if (s.testBit(1)) s = s.add(order);
        order = order.shiftLeft(1);
        if (s.testBit(2)) s = s.add(order);
        return s;
    }

    /** 상대 메시지로 64바이트 키를 만든다. 상대 점이 곡선 위에 없으면 null. */
    byte[] processMsg(byte[] theirMsg) throws Exception {
        if (myMsg == null || theirMsg == null || theirMsg.length != 32) return null;
        Ed25519.Point qStar = Ed25519.decode(theirMsg);
        if (qStar == null) return null;
        Ed25519.Point peersMask = Ed25519.mul(passwordScalar, alice ? N : M);
        Ed25519.Point q = Ed25519.sub(qStar, peersMask);
        byte[] dh = Ed25519.encode(Ed25519.mul(privateKey, q));

        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        if (alice) {
            lp(sha, myName);
            lp(sha, theirName);
            lp(sha, myMsg);
            lp(sha, theirMsg);
        } else {
            lp(sha, theirName);
            lp(sha, myName);
            lp(sha, theirMsg);
            lp(sha, myMsg);
        }
        lp(sha, dh);
        lp(sha, passwordHash);
        return sha.digest();
    }

    private static void lp(MessageDigest sha, byte[] data) {
        long l = data.length;
        byte[] le = new byte[8];
        for (int i = 0; i < 8; i++) {
            le[i] = (byte) (l & 0xff);
            l >>>= 8;
        }
        sha.update(le);
        sha.update(data);
    }

    private static byte[] nul(String s) {
        byte[] a = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[a.length + 1];
        System.arraycopy(a, 0, out, 0, a.length);
        return out;
    }
}
