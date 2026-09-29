package nrc;

/**
 * 허용 타입 변경 알림(숨은 인터페이스)까지 받는 관찰기.
 * 인터페이스는 공개 SDK에 없어 컴파일 때만 `daemon/stubs`의 같은 이름 선언을 쓰고, 실행 때는 기기 프레임워크의 것을 쓴다.
 * 기기에 인터페이스가 없으면 Nrd가 기본 Observer로 내려간다.
 */
final class AllowedObserver extends Observer
        implements android.telephony.TelephonyCallback$AllowedNetworkTypesListener {

    AllowedObserver(Journal log, int subId, Listener out) {
        super(log, subId, out);
    }

    @Override
    public void onAllowedNetworkTypesChanged(int reason, long allowedNetworkType) {
        onAllowed(reason, allowedNetworkType);
    }
}
