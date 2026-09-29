package android.telephony;

/**
 * 컴파일 전용 선언. 실제 `TelephonyCallback.AllowedNetworkTypesListener`는 @hide라 android.jar에 없다.
 * 바이너리 이름이 같도록 최상위 인터페이스로 선언한다. dex에는 넣지 않는다(기기 프레임워크의 것을 쓴다).
 * 시그니처: AOSP Android 12~13 `onAllowedNetworkTypesChanged(int reason, long allowedNetworkType)`.
 */
public interface TelephonyCallback$AllowedNetworkTypesListener {
    void onAllowedNetworkTypesChanged(int reason, long allowedNetworkType);
}
