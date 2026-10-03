/*
 * 아래 구현의 adb 공개키 포맷(android_pubkey)·RSA 키 처리는 AOSP system/core/libcrypto_utils(android_pubkey.c)
 * 와 adb crypto/rsa_2048_key.cpp 를 Java 로 옮긴 것입니다.
 * Copyright (C) The Android Open Source Project — Apache License, Version 2.0.
 * 전체 고지·라이선스: 저장소 루트 THIRD_PARTY_NOTICES.md(§4), licenses/Apache-2.0.txt.
 */
package nrc.controller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * 무선 디버깅용 이 앱의 열쇠(RSA 2048)와 자체 서명 인증서, adb 공개키 문자열.
 * PC의 ~/.android/adbkey와 같은 역할이다. 앱 전용 저장소(다른 앱이 못 읽음)에 둔다.
 * - adb 공개키 문자열 = base64(android_pubkey 구조: 낱말 수 64, n0inv, 모듈러스·R² 작은 끝, 지수) + " 이름@기기"
 *   (AOSP libcrypto_utils android_pubkey.cpp, adb crypto/rsa_2048_key.cpp와 같음). 폰은 이 문자열을 "페어링된 기기"에 저장하고
 *   접속 때 인증서의 공개키가 이것과 같은지만 본다(adb daemon/auth.cpp adbd_tls_verify_cert).
 * 안드로이드 의존 없음(PC 시험).
 */
final class AdbKey {
    /** "페어링된 기기" 목록에 보이는 이름. */
    static final String NAME = "nrc.controller@phone";
    static final String ALIAS = "nrc";

    final PrivateKey privateKey;
    final X509Certificate cert;
    final RSAPublicKey publicKey;

    private AdbKey(PrivateKey privateKey, X509Certificate cert) {
        this.privateKey = privateKey;
        this.cert = cert;
        this.publicKey = (RSAPublicKey) cert.getPublicKey();
    }

    /** dir에 있으면 읽고, 없으면 새로 만들어 저장한다. */
    static AdbKey loadOrCreate(File dir) throws Exception {
        File k = new File(dir, "adbkey.pk8");
        File c = new File(dir, "adbkey.crt");
        if (k.isFile() && c.isFile()) {
            PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(k.toPath())));
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(Files.readAllBytes(c.toPath())));
            return new AdbKey(pk, cert);
        }
        AdbKey n = create();
        write(k, n.privateKey.getEncoded());
        write(c, n.cert.getEncoded());
        return n;
    }

    /** 이 폴더에 열쇠가 있는지(없으면 페어링한 적이 없다). */
    static boolean exists(File dir) {
        return new File(dir, "adbkey.pk8").isFile() && new File(dir, "adbkey.crt").isFile();
    }

    static AdbKey create() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new java.security.spec.RSAKeyGenParameterSpec(2048, BigInteger.valueOf(65537)));
        KeyPair kp = g.generateKeyPair();
        return new AdbKey(kp.getPrivate(), selfSigned(kp));
    }

    private static void write(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
            o.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("rename " + f);
    }

    /** adb 공개키 문자열(PeerInfo에 넣는다). */
    String adbPublicKey() {
        return Base64.getEncoder().encodeToString(androidPubkey(publicKey)) + " " + NAME;
    }

    /** android_pubkey_encode: 4+4+256+256+4 = 524바이트, 모든 정수 작은 끝. */
    static byte[] androidPubkey(RSAPublicKey k) {
        BigInteger n = k.getModulus();
        if (n.bitLength() != 2048) throw new IllegalArgumentException("need RSA-2048");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        long n0inv = r32.subtract(n.mod(r32).modInverse(r32)).longValue(); // -1/n mod 2^32
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).pow(2).mod(n);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        le32(o, 64);
        le32(o, n0inv);
        o.write(Ed25519.toLe(n, 256), 0, 256);
        o.write(Ed25519.toLe(rr, 256), 0, 256);
        le32(o, k.getPublicExponent().longValue());
        return o.toByteArray();
    }

    private static void le32(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 4; i++) o.write((int) (v >>> (8 * i)) & 0xff);
    }

    // ---------------------------------------------------------------- 자체 서명 인증서(DER 직접 조립)

    private static final byte[] OID_SHA256_RSA = {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};
    private static final byte[] OID_CN = {0x55, 0x04, 0x03};

    static X509Certificate selfSigned(KeyPair kp) throws Exception {
        byte[] algId = seq(tlv(0x06, OID_SHA256_RSA), new byte[]{0x05, 0x00});
        byte[] name = seq(tlv(0x31, seq(tlv(0x06, OID_CN), tlv(0x0c, "nrc.controller".getBytes("UTF-8")))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(now - 24L * 3600_000), utcTime(Math.min(now + 20L * 365 * 24 * 3600_000, 2524607999000L)));
        byte[] tbs = seq(
                tlv(0xa0, tlv(0x02, new byte[]{2})),                   // v3
                tlv(0x02, BigInteger.valueOf(now).toByteArray()),      // 일련번호(양수)
                algId, name, validity, name,
                kp.getPublic().getEncoded());                          // SubjectPublicKeyInfo
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, algId, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static byte[] utcTime(long ms) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(ms)).getBytes("US-ASCII"));
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return tlv(0x30, o.toByteArray());
    }

    static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = v.length;
        if (n < 0x80) {
            o.write(n);
        } else if (n < 0x100) {
            o.write(0x81);
            o.write(n);
        } else if (n < 0x10000) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n & 0xff);
        } else {
            throw new IllegalArgumentException("too long");
        }
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    // ---------------------------------------------------------------- TLS에 쓰는 열쇠·신뢰 관리자

    /** 폰이 어떤 인증 기관 목록을 보내든 이 앱의 인증서 하나를 낸다. */
    X509ExtendedKeyManager keyManager() {
        return new X509ExtendedKeyManager() {
            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return ALIAS;
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                return ALIAS;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return ALIAS.equals(alias) ? new X509Certificate[]{cert} : null;
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return ALIAS.equals(alias) ? privateKey : null;
            }

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[]{ALIAS};
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }
        };
    }

    /**
     * 폰(adbd)의 인증서는 스스로 서명한 것이라 검사하지 않는다(adb 원본도 같음). 접속 대상은 이 폰 자신(127.0.0.1)뿐이고,
     * 페어링은 SPAKE2(6자리 코드 + TLS 키 재료)가, 설정 결과는 앱이 직접 확인하는 5G/LTE 전환 권한이 확인한다.
     */
    static X509ExtendedTrustManager trustAnyLocalAdbd() {
        return new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
