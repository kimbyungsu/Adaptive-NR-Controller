package nrc.controller;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

/**
 * 폰의 허용 망 칸 읽기·쓰기와 통화 확인(통신사 권한으로, DESIGN §5.13). 공개 API만 쓴다.
 * - 읽기: 네 사유(USER 0 · POWER 1 · CARRIER 2 · ENABLE_2G 3). 설정 화면은 USER만 보여 준다.
 * - 쓰기: CARRIER만. USER는 절대 쓰지 않는다.
 * 앱 공개 API로 쓰면 LTE_CA 비트(1<<18)가 빠져 저장된다(research §2.15). NR 비트만 보므로 판단에는 영향이 없다.
 */
final class Radio {
    static final int USER = 0, POWER = 1, CARRIER = 2, ENABLE_2G = 3;

    final int sub;
    private final TelephonyManager tm;
    private final SubscriptionManager sm;

    private Radio(int sub, TelephonyManager tm, SubscriptionManager sm) {
        this.sub = sub;
        this.tm = tm;
        this.sm = sm;
    }

    /** 기본 데이터 SIM으로 연다. SIM이 아직 안 올라왔으면 null. */
    static Radio open(Context c) {
        int sub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(sub)) return null;
        TelephonyManager tm = c.getSystemService(TelephonyManager.class).createForSubscriptionId(sub);
        return new Radio(sub, tm, c.getSystemService(SubscriptionManager.class));
    }

    TelephonyManager tm() {
        return tm;
    }

    /** 폰이 이 앱을 통신사가 인정한 앱으로 대하는지. */
    boolean privileged() {
        try {
            return tm.hasCarrierPrivileges();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 사유별 허용 망. 못 읽으면 -1. */
    long read(int reason) {
        try {
            return tm.getAllowedNetworkTypesForReason(reason);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** 통신사 칸에 쓴다. 호출이 거절되면 false(결과 확인은 부른 쪽이 다시 읽어서 한다). */
    boolean writeCarrier(long mask) {
        try {
            tm.setAllowedNetworkTypesForReason(CARRIER, mask);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 쓰기 직전 통화 확인. 통화 중이면 "call", 확인 불가면 "call_unknown"(이때도 쓰지 않는다), 괜찮으면 null. */
    String callGuard() {
        try {
            return tm.getCallStateForSubscription() == TelephonyManager.CALL_STATE_IDLE ? null : "call";
        } catch (RuntimeException e) {
            return "call_unknown";
        }
    }

    /** 켜진 SIM 수. 모르면 -1. */
    int activeSims() {
        try {
            return sm.getActiveSubscriptionInfoCount();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
