package nrc;

/** Actuator가 쓰는 폰 입출력의 실제 구현(명령 실행, USER 값 조회, 삼성 모드 번호 변환). */
final class AndroidIo implements Actuator.Io {
    private final int subId;

    AndroidIo(int subId) {
        this.subId = subId;
    }

    @Override
    public String exec(String... argv) {
        return Exec.run(argv);
    }

    @Override
    public long user() {
        return Phone.allowedUser(subId);
    }

    @Override
    public int modeOf(long mask) {
        return modeOfMask(mask);
    }

    @Override
    public String callGuard() {
        return Phone.callGuard(subId);
    }

    /** 삼성 설정 화면이 쓰는 모드 번호(숨은 RadioAccessFamily 변환, 삼성 내부와 같은 변환). 실패 시 -1. */
    static int modeOfMask(long mask) {
        try {
            return (Integer) Class.forName("android.telephony.RadioAccessFamily")
                    .getMethod("getNetworkTypeFromRaf", int.class).invoke(null, (int) mask);
        } catch (Throwable e) {
            return -1;
        }
    }
}
