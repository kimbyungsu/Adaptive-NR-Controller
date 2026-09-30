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
