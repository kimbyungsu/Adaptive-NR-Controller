package nrc.controller;

import java.math.BigInteger;

/**
 * edwards25519 점 연산(무선 디버깅 페어링의 SPAKE2용, 2026-09-30 사용자 결정: 제3자 라이브러리 없이 직접 구현).
 * RFC 8032 §5.1의 식을 그대로 옮겼다(확장 좌표 X:Y:Z:T, a=-1 덧셈식). 안드로이드 의존 없음(PC 시험).
 * 상수 시간이 아니다: 한 번뿐인 폰 안(자기 자신과의) 페어링에만 쓴다.
 */
final class Ed25519 {
    private Ed25519() {
    }

    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /** 소수 위수 부분군의 위수 l. */
    static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    /** 확장 좌표의 점: x = X/Z, y = Y/Z, x·y = T/Z. */
    static final class Point {
        final BigInteger x, y, z, t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }
    }

    static final Point IDENTITY = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
    /** 기준점 B(y = 4/5, x 짝수). */
    static final Point BASE = decode(hex("5866666666666666666666666666666666666666666666666666666666666666"));

    static Point add(Point p, Point q) {
        BigInteger a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P);
        BigInteger b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P);
        BigInteger c = p.t.multiply(D2).multiply(q.t).mod(P);
        BigInteger d = p.z.multiply(TWO).multiply(q.z).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
    }

    static Point negate(Point p) {
        return new Point(P.subtract(p.x).mod(P), p.y, p.z, P.subtract(p.t).mod(P));
    }

    static Point sub(Point p, Point q) {
        return add(p, negate(q));
    }

    /** k·p(k ≥ 0, 비트 높은 쪽부터 두 배·더하기). */
    static Point mul(BigInteger k, Point p) {
        Point r = IDENTITY;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (k.testBit(i)) r = add(r, p);
        }
        return r;
    }

    static boolean equal(Point p, Point q) {
        // x1/z1 == x2/z2 ∧ y1/z1 == y2/z2
        return p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P).signum() == 0
                && p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P).signum() == 0;
    }

    /** 32바이트 표준 인코딩(y 작은 끝 + 최상위 비트에 x의 홀짝). */
    static byte[] encode(Point p) {
        BigInteger zi = p.z.modInverse(P);
        BigInteger x = p.x.multiply(zi).mod(P);
        BigInteger y = p.y.multiply(zi).mod(P);
        byte[] out = toLe(y, 32);
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    /** RFC 8032 §5.1.3 복원. 곡선 위 점이 아니면 null. */
    static Point decode(byte[] s) {
        if (s == null || s.length != 32) return null;
        byte[] b = s.clone();
        boolean xSign = (b[31] & 0x80) != 0;
        b[31] &= 0x7f;
        BigInteger y = fromLe(b);
        if (y.compareTo(P) >= 0) return null;
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(yy).add(BigInteger.ONE).mod(P);
        BigInteger v3 = v.pow(3).mod(P);
        BigInteger x = u.multiply(v3).multiply(u.multiply(v3).multiply(v3).multiply(v).mod(P)
                .modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P);
        BigInteger vxx = v.multiply(x).multiply(x).mod(P);
        if (vxx.equals(u)) {
            // x 그대로
        } else if (vxx.equals(P.subtract(u).mod(P))) {
            x = x.multiply(SQRT_M1).mod(P);
        } else {
            return null;
        }
        if (x.signum() == 0 && xSign) return null;
        if (x.testBit(0) != xSign) x = P.subtract(x);
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    /** 64바이트(작은 끝) → mod l → 32바이트(작은 끝). BoringSSL x25519_sc_reduce와 같은 값. */
    static byte[] scReduce(byte[] le64) {
        return toLe(fromLe(le64).mod(L), 32);
    }

    static BigInteger fromLe(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) be[i] = le[le.length - 1 - i];
        return new BigInteger(1, be);
    }

    static byte[] toLe(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        if (be.length > len && !(be.length == len + 1 && be[0] == 0)) throw new IllegalArgumentException("too big");
        return out;
    }

    static byte[] hex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
