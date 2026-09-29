package nrc;

/**
 * 정책 기준값(DESIGN §5.5.6 초기 가설값 🧪, 단위 ms). 실사용 기록으로 조정한다.
 * 안드로이드 의존 없음(PC 시험에서 값을 바꿔 쓸 수 있게 필드로 둔다).
 */
final class Params {
    long w = 120_000;
    long tActive = 2_000;
    int nDrop = 3;
    long dMin = 20_000;
    int k = 3;
    long tClear = 300_000;
    long cBase = 300_000;
    long cMax = 3_600_000;
    long pActive = 60_000;
    long pMax = 300_000;
    int nProbe = 2;
    long tStable = 600_000;
    int bHour = 4;
    long bGap = 120_000;
    /** 전환 뒤 망 정상(데이터 등록 + 데이터 연결) 확인 후 두는 정착 시간. */
    long tSettle = 10_000;
    /** 전환 뒤 이 시간 안에 망 정상이 확인되지 않으면 SAFE_STOP. */
    long tSettleMax = 30_000;
    /** 전환 직후 이 시간 전의 "정상"은 확인으로 치지 않는다(전환이 실제로 반영되기 전 상태일 수 있음). */
    long settleMinWait = 2_000;
    long tCallGrace = 30_000;
    /** 데이터 사용 구간: 이 시간 이하의 멈춤은 사용 중으로 본다(UseSegments·기록과 같은 규칙). */
    long useGap = 3_000;
    /** 대용량 트래픽 기준(바이트/초, 가드 6). */
    long rHeavy = 1_000_000;
}
