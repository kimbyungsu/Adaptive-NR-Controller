package nrc;

/** 사건 기록(Journal이 구현). 안드로이드 의존 없는 부품(Actuator 등)이 기록만 필요할 때 쓴다. */
interface Log {
    void write(String event, Object... kv);
}
