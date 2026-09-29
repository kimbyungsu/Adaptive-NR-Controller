# nrd — 폰 데몬

폰에서 adb shell 권한(uid 2000)으로 `app_process`를 통해 실행한다. 두 가지로 실행할 수 있다.

- `--observe`: 기록만 한다. 설정은 바꾸지 않는다.
- `--control`: 사용자가 설정에서 **5G 우선**을 골랐을 때만 작동한다. 데이터를 쓰는 중 5G가 자주 끊기면 실제 허용 모드만 LTE로 쉬었다가 다시 5G를 시험한다(DESIGN §5.5·§5.11). 설정 화면 값은 사용자가 고른 고정값이라 쓰지 않는다(§5.12). 사용자가 LTE를 고르면 손대지 않는다.

기록은 `/data/local/tmp/nrc/logs/nrd.log`(JSONL), 상태는 `/data/local/tmp/nrc/state.json`에 남는다. 기록 종류는 [DESIGN §5.10](../docs/DESIGN.md)에 있다.

## 빌드·시험 (Windows Git Bash / macOS 공통)
```bash
export JAVA_HOME=...      # JDK 17
export ANDROID_HOME=...   # platforms/android-33, build-tools/37.0.0 필요
bash daemon/build.sh      # → daemon/build/nrd.dex
bash daemon/test.sh       # 안드로이드 없이 PC에서: 사용 구간 계산·정책 엔진·USER 쓰기(가짜 폰)·상태 파일·사용자 선택(설정 키) 규칙 시험
```
- `stubs/`는 공개 SDK에 없는 허용 타입 알림 인터페이스의 **컴파일 전용** 선언이다. dex에는 들어가지 않는다. 실행 때는 기기 프레임워크의 것을 쓴다.
- 람다를 쓰므로 build-tools의 `core-lambda-stubs.jar`를 함께 참조한다.

## 실행
보통은 PC 도구로 켜고 끈다: [`pc/nrctl.py`](../pc/nrctl.py) `start` / `stop` / `status` 등.

직접 실행할 때(Git Bash는 먼저 `export MSYS_NO_PATHCONV=1`):
```bash
adb shell mkdir -p /data/local/tmp/nrc
adb push daemon/build/nrd.dex /data/local/tmp/nrc/nrd.dex
adb shell 'setsid sh -c "CLASSPATH=/data/local/tmp/nrc/nrd.dex exec app_process /system/bin nrc.Nrd --control" </dev/null >/data/local/tmp/nrc/nrd.out 2>&1 &'
```
- 제어 모드 인자:
  - (없어짐) `--leftover`·`--original`: §5.12부터 시작 때 원래 모드는 설정 화면 값으로 정한다. 줘도 무시하고 기록만 남긴다
  - `--indicator-slot=<자리>`: 상단바 자리. 기본은 레퍼런스 기기의 빈 SIM2 VoLTE 자리
  - `--indicator-icon=<패키지:번호>`: 동반 앱의 점 아이콘
- `--query`: 읽기 전용 조회. USER 허용 타입을 마스크 숫자로 출력하고 끝난다(`nrctl restore`의 재확인용, 잠금·기록 없음).
- 시작 거부 종료 코드:
  - 3: 이미 실행 중
  - 8: USER 허용 타입을 읽을 수 없음
  - (6·7은 예전 시작 판단용으로 §5.12부터 나오지 않는다)
- 중복 기동은 배타 파일 잠금(`nrd.lock`)으로 거부한다. `nrd.pid`는 PC 도구가 데몬을 찾는 용도다.
- PC 요청은 파일로 받는다. 파일 첫 칸에 적힌 pid가 이 데몬일 때만 받는다.
  - `stop.req`: `<pid>`. 컨트롤러가 내려 둔 LTE면 원래 모드로 되돌리고 끝난다. 통화 중이면 통화가 끝날 때까지 미룬다.
  - `ctl.req`: `<pid> pause|resume|keep-lte|test-cooldown`
- 케이블을 뽑아도 산다(약 18.8시간 실측, research §2.5). 재부팅하면 끝난다. USB 디버깅 끄기(adbd 재시작) 때도 끝난다는 보고가 있으나 이 기기에서는 미시험이다.
- 30초 주기 확인과 예약 시각은 폰이 깨어 있을 때만 돈다. 폰을 깨우지 않는다.

## 구성
| 파일 | 역할 |
|---|---|
| `Nrd.java` | 진입점. 배타 잠금, 알림 등록, 30초 주기 확인, PC 요청 처리, 레지스트리 종료 감지 |
| `Observer.java` | 알림 처리. 원값을 기록하고 사건을 Controller에 넘긴다 |
| `AllowedObserver.java` | 허용 타입 변경 알림까지 받는 관찰기. 기기에 인터페이스가 없으면 `Observer`로 내려간다 |
| `Controller.java` | 제어 연결부: 원래 모드·사용자 변경 감지·상단바·상태 파일 |
| `Policy.java`, `Params.java` | 정책 엔진과 기준값(안드로이드 의존 없음) |
| `Actuator.java` | **폰 설정을 바꾸는 데몬의 유일한 곳**: USER 허용 타입만 쓴다(`cmd phone`). 삼성 설정 화면 키는 쓰지 않는다(DESIGN §5.12). 쓰기 직전 통화·설정 키 재확인. 폰 입출력은 `Io`로 받는다 |
| `UserMode.java` | 사용자 선택 = 설정 키: 키↔USER 짝짓기, 시작 때 원래 모드(키 우선) 판단(안드로이드 의존 없음) |
| `KeyWatch.java` | 설정 키 변경 감시(사용자가 설정 화면에서 모드를 고름). 등록 실패면 USER 알림·주기 확인으로 대신한다 |
| `AndroidIo.java`, `Exec.java`, `Log.java` | `Actuator`의 실제 폰 입출력, 외부 명령 실행(15초 제한), 기록 인터페이스 |
| `Indicator.java` | 상단바 작동 표시(빈 자리일 때만 올리고, 우리 것일 때만 지움) |
| `NetWatch.java` | 기본 인터넷 경로 감시(Wi-Fi면 컨트롤러가 쉬고 점을 숨김, DESIGN §5.11 결정 17). 등록 실패면 관찰만 |
| `StateStore.java`, `Saved.java` | 상태 파일 저장(쓰기 전에 먼저 기록)과 그 규칙(시도 값·남겨 둔 값, 다음 시작의 원래 모드 확정 — 안드로이드 의존 없음) |
| `ShellContext.java` | app_process용 컨텍스트(패키지 이름 `com.android.shell`) |
| `Phone.java` | 전화·전원·발열·통화·데이터량 **조회** 도우미 |
| `UseSegments.java` | 데이터 사용 구간 계산(안드로이드 의존 없음) |
| `Journal.java` | JSONL 기록, 4MB 순환(최근 3개) |
