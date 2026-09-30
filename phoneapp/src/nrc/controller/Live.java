package nrc.controller;

/**
 * 관측 화면이 읽는 엔진의 지금 모습(읽기 전용 사본). 엔진이 사건을 처리할 때마다 새로 만든다.
 * 시각은 부팅 후 경과(SystemClock.elapsedRealtime, 같은 프로세스라 화면도 같은 시계로 남은 시간을 센다)와 벽시계(wall) 두 가지다.
 */
final class Live {
    long at;
    String state;
    long stateSince;
    long stateSinceWall;
    String stateWhy;
    String hold;
    String blocked;
    boolean userNr;
    boolean wifi;
    boolean screen;
    boolean nrActual;
    /** 기지국 묶음 보고를 받았는지(안 받았으면 실제 연결 확인 전). */
    boolean pccKnown;
    /** 데이터 서비스에 등록돼 있는지(아니면 "서비스 없음"). */
    boolean dataIn = true;
    /** 모바일 데이터 연결이 이어져 있는지. */
    boolean dataConnected;
    int display;
    int drops;
    int nDrop;
    long windowMs;
    long oldestDrop;
    long coolUntil;
    String restWhy;
    long evalStart;
    long useMs;
    long pActive;
    long pMax;
    int probeDrops;
    int nProbe;
    int switchesHour;
    int bHour;
    long nextSwitchAt;
    int level;
    int lteRsrp = Integer.MAX_VALUE;
    int nrRsrp = Integer.MAX_VALUE;
    int nrSinr = Integer.MAX_VALUE;
    String problem;
    boolean selfTesting;
}
