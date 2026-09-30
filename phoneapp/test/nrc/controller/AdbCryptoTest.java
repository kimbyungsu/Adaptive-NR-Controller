package nrc.controller;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 무선 디버깅 페어링 암호 부분 시험(PC, 2026-09-30). 실행: bash phoneapp/test.sh
 * 원본(BoringSSL·AOSP)과의 실제 호환은 폰 페어링으로만 확인된다. 여기서는 식·상수·형식을 확인한다.
 */
public final class AdbCryptoTest {
    private static int fails;
    private static int n;

    public static void main(String[] a) throws Exception {
        ed25519();
        spakePoints();
        spakeAgreement();
        hkdf();
        gcmAndFraming();
        adbKey();
        System.out.println(fails == 0 ? "AdbCryptoTest OK (" + n + ")" : "AdbCryptoTest FAILED " + fails);
        if (fails != 0) System.exit(1);
    }

    static void ed25519() {
        byte[] b = Ed25519.encode(Ed25519.BASE);
        is(Ed25519.hex(b).equals("5866666666666666666666666666666666666666666666666666666666666666"), "기준점 인코딩");
        is(Ed25519.equal(Ed25519.mul(Ed25519.L, Ed25519.BASE), Ed25519.IDENTITY), "l·B = 0");
        BigInteger x = new BigInteger("123456789012345678901234567890"), y = new BigInteger("987654321098765432109876543210");
        is(Ed25519.equal(Ed25519.mul(x.add(y), Ed25519.BASE), Ed25519.add(Ed25519.mul(x, Ed25519.BASE), Ed25519.mul(y, Ed25519.BASE))),
                "(x+y)B = xB + yB");
        is(Ed25519.equal(Ed25519.sub(Ed25519.mul(x, Ed25519.BASE), Ed25519.mul(x, Ed25519.BASE)), Ed25519.IDENTITY), "xB - xB = 0");
        // RFC 8032 7.1 시험 1: 비밀키 9d61b1…의 공개키 d75a98…(SHA-512 앞 32바이트를 다듬은 스칼라 × B)
        try {
            byte[] h = MessageDigest.getInstance("SHA-512").digest(Ed25519.hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"));
            byte[] s = Arrays.copyOf(h, 32);
            s[0] &= (byte) 248;
            s[31] &= 127;
            s[31] |= 64;
            byte[] pub = Ed25519.encode(Ed25519.mul(Ed25519.fromLe(s), Ed25519.BASE));
            is(Ed25519.hex(pub).equals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), "RFC 8032 공개키");
            Ed25519.Point p = Ed25519.decode(pub);
            is(p != null && Arrays.equals(Ed25519.encode(p), pub), "복원·재인코딩");
        } catch (Exception e) {
            is(false, "RFC 8032 " + e);
        }
        byte[] bad = new byte[32];
        bad[0] = 2; // y = 2는 곡선 위 점이 아님
        is(Ed25519.decode(bad) == null, "곡선 밖 점 거절");
    }

    /** 원본 주석의 생성 절차(SHA-256을 되풀이해 처음 복원되는 값)로 N·M을 다시 만들어 상수와 대조. */
    static void spakePoints() throws Exception {
        is(Ed25519.hex(genpoint("edwards25519 point generation seed (N)")).equals("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"), "N 점");
        is(Ed25519.hex(genpoint("edwards25519 point generation seed (M)")).equals("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"), "M 점");
        is(Spake2.N != null && Spake2.M != null, "N·M 복원");
        is(Spake2.CLIENT_NAME.length == 16 && Spake2.CLIENT_NAME[15] == 0, "이름 끝 NUL 포함");
    }

    private static byte[] genpoint(String seed) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] v = sha.digest(seed.getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < 1000; i++) {
            Ed25519.Point p = Ed25519.decode(v);
            if (p != null) return Ed25519.encode(p);
            v = sha.digest(v);
        }
        return new byte[0];
    }

    static void spakeAgreement() throws Exception {
        SecureRandom r = new SecureRandom();
        byte[] pw = ("123456" + "x".repeat(64)).getBytes(StandardCharsets.US_ASCII);
        Spake2 c = Spake2.adbClient(), s = Spake2.adbServer();
        byte[] cm = c.generateMsg(pw, r), sm = s.generateMsg(pw, r);
        byte[] ck = c.processMsg(sm), sk = s.processMsg(cm);
        is(ck != null && ck.length == 64 && Arrays.equals(ck, sk), "같은 코드면 같은 키");
        Spake2 c2 = Spake2.adbClient(), s2 = Spake2.adbServer();
        byte[] cm2 = c2.generateMsg(pw, r);
        byte[] sm2 = s2.generateMsg(("654321" + "x".repeat(64)).getBytes(StandardCharsets.US_ASCII), r);
        byte[] ck2 = c2.processMsg(sm2), sk2 = s2.processMsg(cm2);
        is(ck2 != null && !Arrays.equals(ck2, sk2), "코드가 다르면 다른 키");
        is(c2.processMsg(new byte[5]) == null, "길이가 틀린 메시지 거절");
        // 암호 스칼라: 8의 배수이고 mod l 값은 그대로
        byte[] h = MessageDigest.getInstance("SHA-512").digest(pw);
        BigInteger ps = Spake2.passwordScalarOf(h);
        is(ps.mod(BigInteger.valueOf(8)).signum() == 0, "암호 스칼라 8의 배수");
        is(ps.mod(Ed25519.L).equals(Ed25519.fromLe(Ed25519.scReduce(h))), "암호 스칼라 mod l 유지");
        is(ps.bitLength() <= 256, "256비트 안");
    }

    /** RFC 5869 시험 3(소금·info 없음). */
    static void hkdf() throws Exception {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] okm = PairCrypto.hkdf(ikm, new byte[0], 42);
        is(Ed25519.hex(okm).equals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), "HKDF RFC 5869 시험 3");
    }

    static void gcmAndFraming() throws Exception {
        byte[] key = new byte[64];
        new SecureRandom().nextBytes(key);
        PairCrypto a = new PairCrypto(key), b = new PairCrypto(key);
        byte[] info = PairCrypto.peerInfo("QUFB user@host");
        is(info.length == 8192 && info[0] == 0 && info[1] == 'Q', "PeerInfo 형식");
        byte[] e1 = a.encrypt(info), e2 = a.encrypt(info);
        is(e1.length == 8192 + 16, "암호문 = 본문 + 태그 16");
        is(!Arrays.equals(e1, e2), "차례 번호로 논스가 바뀜");
        is(Arrays.equals(b.decrypt(e1), info) && Arrays.equals(b.decrypt(e2), info), "차례대로 풀림");
        PairCrypto wrong = new PairCrypto(new byte[64]);
        boolean rejected;
        try {
            wrong.decrypt(e1);
            rejected = false;
        } catch (Exception ex) {
            rejected = true;
        }
        is(rejected, "다른 키는 태그에서 거절");
        byte[] h = PairCrypto.header(PairCrypto.TYPE_PEER_INFO, 8208);
        is(Ed25519.hex(h).equals("010100002010"), "머리 = 판1 종류1 길이 큰 끝");
        is(PairCrypto.parseHeader(h, PairCrypto.TYPE_PEER_INFO)[1] == 8208, "머리 읽기");
        boolean threw;
        try {
            PairCrypto.parseHeader(h, PairCrypto.TYPE_SPAKE2_MSG);
            threw = false;
        } catch (IllegalStateException ex) {
            threw = true;
        }
        is(threw, "종류가 다르면 거절");
        is(PairCrypto.EXPORT_LABEL.getBytes(StandardCharsets.US_ASCII).length == 10, "내보내기 이름표 NUL 포함 10바이트");
    }

    static void adbKey() throws Exception {
        AdbKey k = AdbKey.create();
        k.cert.verify(k.publicKey);
        is(k.cert.getSubjectX500Principal().getName().contains("nrc.controller"), "인증서 이름");
        is(k.cert.getPublicKey().equals(k.publicKey), "인증서 공개키");
        byte[] enc = AdbKey.androidPubkey(k.publicKey);
        is(enc.length == 524, "android_pubkey 524바이트");
        BigInteger nMod = k.publicKey.getModulus();
        long words = le32(enc, 0), n0inv = le32(enc, 4), exp = le32(enc, 520);
        is(words == 64 && exp == 65537, "낱말 수·지수");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        is(nMod.multiply(BigInteger.valueOf(n0inv)).mod(r32).equals(r32.subtract(BigInteger.ONE)), "n0inv = -1/n mod 2^32");
        is(Ed25519.fromLe(Arrays.copyOfRange(enc, 8, 264)).equals(nMod), "모듈러스 작은 끝");
        is(Ed25519.fromLe(Arrays.copyOfRange(enc, 264, 520)).equals(BigInteger.ONE.shiftLeft(4096).mod(nMod)), "R² mod n");
        String s = k.adbPublicKey();
        is(s.endsWith(" " + AdbKey.NAME) && Base64.getDecoder().decode(s.substring(0, s.indexOf(' '))).length == 524, "adb 공개키 문자열");
        // 저장·다시 읽기
        java.io.File dir = java.nio.file.Files.createTempDirectory("adbkey").toFile();
        AdbKey k1 = AdbKey.loadOrCreate(dir), k2 = AdbKey.loadOrCreate(dir);
        is(k1.publicKey.equals(k2.publicKey) && k1.cert.equals(k2.cert), "저장한 열쇠를 그대로 다시 읽음");
    }

    private static long le32(byte[] b, int off) {
        long v = 0;
        for (int i = 3; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }

    private static void is(boolean ok, String what) {
        n++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }
}
