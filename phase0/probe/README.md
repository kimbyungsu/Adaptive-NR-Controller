# Phase 0 점검 도구 (probe)

읽기 전용 점검 프로그램이다. 네트워크 설정을 바꾸는 호출은 하지 않는다. 폰에서 adb shell 권한(uid 2000)으로 `app_process`를 통해 실행한다.

## 빌드 (Windows Git Bash / macOS 공통)
```bash
export JAVA_HOME=...      # JDK 17
export ANDROID_HOME=...   # platforms/android-33, build-tools/37.0.0 필요
bash phase0/probe/build.sh   # → phase0/probe/build/probe.dex
```
PowerShell 스크립트를 쓰지 않는 이유가 있다. 이번 작업 중 프로젝트 경로의 `[` `]` 때문에 PowerShell 5.1에서 스크립트 실행이 `The specified wildcard character pattern is not valid` 오류로 실패했다.

## 실행
**Git Bash에서는 먼저 `export MSYS_NO_PATHCONV=1`을 실행한다.** 그러지 않으면 `/data/...` 같은 폰 쪽 경로가 Windows 경로로 바뀔 수 있다. macOS에서는 필요 없다.

```bash
adb shell mkdir -p /data/local/tmp/nrc
adb push phase0/probe/build/probe.dex /data/local/tmp/nrc/probe.dex
adb shell "CLASSPATH=/data/local/tmp/nrc/probe.dex app_process /system/bin nrc.Probe info"    # 권한·시그니처·상태
adb shell "CLASSPATH=/data/local/tmp/nrc/probe.dex app_process /system/bin nrc.Probe state"   # 상태 한 줄
```

상태 한 줄의 필드:
- `nrState`: 0 없음 / 1 제한 / 2 가능·미연결 / 3 연결
- `dataRat`: 14 = LTE
- `allowed[n]`: 사유 n(0 USER, 1 POWER, 2 CARRIER, 3 ENABLE_2G)의 허용 타입. 설정된 적 없는 사유도 기본값을 돌려주므로 "실제 제한"으로 해석하면 안 된다.

## 생존 기록 (케이블 분리·화면 잠금·도즈 후에도 사는지)
```bash
adb shell 'setsid sh -c "CLASSPATH=/data/local/tmp/nrc/probe.dex exec app_process /system/bin nrc.Probe heartbeat 60" </dev/null >/data/local/tmp/nrc/hb.out 2>&1 &'
adb shell cat /data/local/tmp/nrc/alive.log
adb shell 'pgrep -f "nrc[.]Probe [h]eartbeat"'   # 대상 확인 (heartbeat 프로세스 pid만 나와야 함)
adb shell 'pkill -f "nrc[.]Probe [h]eartbeat"'   # 중지: 실행 명령이 일치하는 프로세스만 끈다
```
- 패턴을 `[h]eartbeat`로 쓰는 이유: 그냥 `"nrc.Probe heartbeat"`로 쓰면 명령을 실행하는 셸 자신의 명령줄에도 같은 글자가 있어 셸까지 일치한다(레퍼런스 기기에서 확인). 괄호 패턴은 셸 명령줄의 `[h]` 글자와는 일치하지 않아 자기 자신을 제외한다.
- 간격은 10초 이상만 허용한다.
- 기록 간격은 "깨어 있는 시간 기준"이다. 폰이 깊은 잠에 들면 기록이 멈췄다가 깨어나면 이어진다. 폰을 깨우지 않는다.
- `elapsed`와 `uptime`의 차이가 깊은 잠 누적 시간이다.
- 현재 실행의 pid는 `/data/local/tmp/nrc/heartbeat.pid`에 남는다. `alive.log`는 누적 기록이라 첫 줄의 pid가 현재 실행이 아닐 수 있다.
- 재부팅하면 자동으로 종료된다.
