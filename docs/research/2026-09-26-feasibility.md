# 타당성 조사 근거 (2026-09-26)

> DESIGN.md의 전제가 되는 실측·소스 근거 모음. 원본 덤프(dumpsys 전문)는 식별 정보가 섞일 수 있어 레포에 넣지 않았고, 필요한 값만 요약했다.
> 확신도 표기: **실측**(레퍼런스 기기에서 직접 확인) / **소스**(AOSP 소스 직접 확인) / **보고**(제3자 실사용 보고) / **미검증**.

## 1. 레퍼런스 기기

| 항목 | 값 | 확신도 |
|---|---|---|
| 모델 | SM-N986N (Galaxy Note20 Ultra 국내판), c2q, kona(SM8250 = Snapdragon 865+) | 실측 |
| OS | Android 13 / SDK 33, build N986NKSSAHYH1, 보안패치 2025-08-01, user 빌드 | 실측 |
| 무결성 | verifiedbootstate=green, `su` 없음, /data/adb 접근 거부 → 사용 가능한 루트 권한 미확인 | 실측 |
| 통신 | SK Telecom 유심(45005), 싱글심, CSC=LUC | 실측 |
| 라디오 HAL | 1.5 (TelephonyDebugService 덤프 `Hal Version:1.5`) | 실측 |
| 데이터 스택 | 구형 DcTracker 사용 | 실측 |
| SA 여부 | 이번 관측 환경(SKT 유심)은 NSA(EN-DC) — 실측. SKT는 2026-06 기준 SA 상용 전(연내 목표), 이 기기는 2025-08로 업데이트 종료, KT는 2022년 Note20 Ultra SA 지원 보도 — 보고. 삼성 공식 SM-N986N 업데이트 이력에 2021-09-07 "SA 서비스 지원" 항목(https://doc.samsungmobile.com/SM-N986N/013353200806/kor.html) — 검증자 확인. 이 기기의 SA 가능 범위(통신사별)는 미확정 | 실측+보고 |

## 2. 제어 경로 실측

### 2.1 명령 존재·권한
- `adb shell cmd phone help`에 `get/set-allowed-network-types-for-users` 존재(예시 마스크 포함). **실측**
- `cmd phone get-allowed-network-types-for-users -s 0` → shell uid로 성공, 출력은 RAT 이름을 `|`로 연결: `GPRS|EDGE|UMTS|HSDPA|HSUPA|HSPA|LTE|HSPA+|GSM|LTE_CA`. **실측**
- telephony.registry: `mAllowedNetworkTypeReason=0`(USER), `mAllowedNetworkTypeValue=316295`(NR 비트 없음). 삼성 `settings global preferred_network_mode=9`. **실측**

### 2.2 왕복 전환 테스트 (사용자 승인 후, 2026-09-26 20:38~20:39, Wi-Fi 꺼짐·셀룰러 데이터·USB 충전·화면 꺼짐·통화 없음)

| 단계 | 명령 출력 | get 재확인 | 성공 ping 응답 사이 공백(0.2s 간격, 호스트 수신 시각 기준) | 데이터 등록 OOS 기록 간격 | IMS(VoLTE) 미등록 기록 간격 |
|---|---|---|---|---|---|
| 동일값 재설정 `01001101001110000111` | `completed` | 변화 없음 | **없음** | 없음 | 없음 |
| NR 추가 `11001101001110000111` | `completed` | `…|LTE_CA|NR` | **3.10초**(14개) | 1.22초 | **7.06초** |
| 원래 값 복원 `01001101001110000111` | `completed` | 원래 값과 일치 | **2.23초**(10개) | 1.03초 | **3.36초** |

추가 관측(실측):
- 삼성 내부 로그에 `allow_nettype_list user 316295(9) -> 840583(26)`과 `setPreferredNetworkType - networkType: 26 (PhoneInterfaceManager 경로)`가 남음 → `cmd phone` 경로도 삼성 내부에서 모드 전환(9↔26)으로 처리됨. HAL 1.5라 `setPreferredNetworkType`으로 폴백하는 AOSP 동작과 일치.
- 동일값 재설정은 삼성 로그에도 흔적이 없음 → AOSP의 "같은 값이면 모뎀 호출 없이 반환"과 일치. **권한 확인용 무부작용 호출로 사용 가능**.
- 삼성 `preferred_network_mode`는 테스트 내내 `9` 유지 → **설정 화면 표시와 실제 허용 타입이 어긋날 수 있음**.
- NR STATE Log: 20:38:44.063 `nrEnabled=true, nrState=0` → 20:38:45.315 `nrState=2(NOT_RESTRICTED)` → 20:39:26.237 `nrEnabled=false, nrState=0`. 즉 NR 허용 기록 간격 약 42.17초, 상태 2 구간 약 40.92초 동안 `CONNECTED(3)` 기록 없음(ping 트래픽 약 5pkt/s). 같은 장소에서 오전엔 NR이 35분씩 연결됨 → 저트래픽에선 NR 보조셀이 추가되지 않는 것으로 보임(1회 관측, 원인 미확정: 망 트래픽 임계, 화면 꺼짐 등).
- 테스트 스크립트의 주기 샘플(`sample`)은 폰 grep 패턴 문제로 모두 빈 출력 → 위 결론은 테스트 직후 덤프의 상태 변경 로그에 근거함.
- NR 허용 직후 약 3.52초간 5G 아이콘(`override=NR_NSA`, 20:38:45.399→48.922)이 떴다가 `LTE_CA`로 바뀜 — **NR 미연결 상태에서 5G 아이콘 표시**.
- 복원 후 최종 상태: `mAllowedNetworkTypeReason=0`, `mAllowedNetworkTypeValue=316295`, get 출력 원래 값과 일치.

### 2.3 과거 모드 변경 이력 (삼성 덤프, 로그 시각은 초 단위)
- 09-21 01:39:49 → 9 (`SemGsmCdmaPhone` 경로, 부팅 초기로 보이나 벽시계 보정 중이라 경과 미확정)
- 삼성 설정 경로(`SamsungInternalServiceImpl.setPreferredNetworkType`) 6회: 09-21 19:31 →26, 09-22 00:54 →9, 03:04 →26, 07:06 →9, 09-25 15:36 →26, 09-26 12:03 →9
- 그중 4건 직후 데이터 등록 OOS→IN_SERVICE 기록 간격 2.03 / 1.56 / 1.10 / 1.07초(체감 단절 시간은 아님)
- 가설(미검증): 부팅 시 삼성이 자체 저장 모드를 재적용 → `cmd phone`으로만 바꾼 값이 재부팅 후 덮일 수 있음

### 2.4 Phase 0 잔여 점검 (2026-09-26 21:37~21:58)

**사용자 수동 테스트 (삼성 설정 화면 경로)** — 사용자 보고 + 삼성 로그 대조
- 모드 재선택: 21:37:29 →0(3G), 21:37:48 →26, 21:38:12 →9, 21:38:17 →26. 매번 `SamsungInternalServiceImpl.setPreferredNetworkType`과 이에 대응하는 `allow_nettype_list user …` 기록이 남음 → **설정 화면에서 고른 값이 USER 허용 타입에 반영됨**을 확인(사용자: "LTE로 하면 LTE 유지, 5G 선택하면 5G 유지"). 두 기록 사이 간격은 수 초 이내였으나(예: 모드 0 요청 21:37:29 → USER 갱신 21:37:33) 정확한 적용 지연은 미측정.
- 재부팅: 21:43:52 부팅 시 `allow_nettype_list: bootup or subId 2 DB updated, user=840583(26)`과 `SemGsmCdmaPhone` 경로 `setPreferredNetworkType 26` 기록 → **설정 화면으로 고른 값은 재부팅 후 유지**됨(사용자: 5G 표시 유지).
- 한계: 이번 재부팅 테스트는 삼성 설정값(26)과 USER 허용 타입(840583)이 같은 상태였다. 그래서 컨트롤러 방식(`cmd phone`만으로 바꿔 두 값이 다른 상태)에서 부팅 시 어느 값이 이기는지는 **아직 미확인**이다. 로그 문구("DB updated")는 USER 허용 타입 DB 쪽을 시사하지만 확정 근거는 아니다.
- 사용자가 보고한 "케이블 분리·화면 잠금 후 LTE/5G 신호 표시"는 폰 자체의 동작이다. 컨트롤러 데몬의 생존 여부와는 별개(당시 데몬 없음)이므로 아래 생존 기록으로 따로 확인한다.

**폰 안 직접 조회 (`phase0/probe`, app_process·shell uid, 읽기 전용)**
- 빌드 도구체인(JDK 17 + d8) → 폰에서 `app_process` 실행 → hidden API 리플렉션(`ServiceManager`, `ITelephony$Stub`, `ISub$Stub`) 동작 확인. **실측**
- 레퍼런스 기기 ITelephony 시그니처: `getServiceStateForSubscriber(int, boolean, boolean, String, String)`, `getAllowedNetworkTypesForReason(int, int)`, `setAllowedNetworkTypesForReason(int, int, long)`, `setNrDualConnectivityState(int, int)`, `isRadioInterfaceCapabilitySupported(String)`. **실측**
- `SubscriptionManager.getDefaultDataSubscriptionId()` 정적 호출은 app_process에서 -1(원인 미확인, 앱 컨텍스트 부재로 추정) → `ISub.getDefaultDataSubId()`로 sub=2 확보. **실측**
- `getServiceStateForSubscriber`: 위치 정보 포기(false,false)로 호출하면 **원격 NullPointerException**(원인 미확인), 위치 정보 포기(true,true)로 호출하면 정상. **실측**
- 위 호출로 **nrState 원값 읽기 성공**(예: `nrState=2 nrFreq=0 dataRat=14 dataReg=0`). dumpsys에서 `****`로 가려지는 값을 폰 안 호출로는 얻을 수 있음. **실측**
- 사유별 조회 `getAllowedNetworkTypesForReason(sub, 0..3)` → 네 사유 모두 `840583`. 삼성 모드 26의 값과 같아, 설정된 제한과 "설정된 적 없어 기본값을 돌려준 것"을 **구분할 수 없음** → 외부 제한 판정에 사유별 조회를 쓰지 않는 설계(§5.6.6)를 뒷받침. **실측**
- 생존 기록: 21:57 `setsid`로 heartbeat(60초 간격, 읽기 전용) 기동 → PPID 1(init), 자체 프로세스 그룹. 결과는 §2.5.

**개발 환경 설치 (2026-09-26, 사용자 요청)**
- Temurin JDK 17.0.20.1+1 → `%LOCALAPPDATA%\Programs\Temurin\jdk-17.0.20.1+1`
- Android SDK → `%LOCALAPPDATA%\Android\Sdk`
  - cmdline-tools 23.0 — `sdkmanager`가 폐기 예고되고 새 `android` CLI로 대체됨. 패키지 표기도 `build-tools/37.0.0` 형식으로 바뀜.
  - build-tools 37.0.0, platforms android-33·android-36, platform-tools 37.0.1
- Gradle 9.8.0 → `%LOCALAPPDATA%\Programs\Gradle\gradle-9.8.0`
- 사용자 환경변수 `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` 등록. PATH에는 4개 경로를 뒤에 추가했다.
  - 기존 PATH는 백업했다.
  - 기존 항목의 순서와 `%변수%` 표기, 확장 문자열 형식은 유지했다.
  - 빈 항목 1개는 정리돼 문자열이 완전히 같지는 않다.
- 내려받은 파일은 모두 배포처 체크섬과 일치.
- 경로 함정(이번 작업 중 관측, 콘솔 출력 원문은 작업 증거에 보관): 프로젝트 경로에 한글과 `[` `]`가 있을 때 두 가지가 실패했다.
  - PowerShell 5.1: 스크립트를 `&`로 실행하거나 `-File`로 실행할 때 `The specified wildcard character pattern is not valid: [LTE-5G] Adaptive NR Controller` 오류. 스크립트 안을 `-LiteralPath`로 바꿔도 같았다.
  - Git Bash: `/d/...` 경로를 Windows 프로그램(javac)에 넘기자 `file not found: \d\…\[LTE-5G] …`로 잘못 변환됐다.
  - 해결: 도구에 넘기는 인자를 `cygpath -m`(`D:/...`)으로 직접 변환해 빌드에 성공했다(`phase0/probe/build.sh`). 원인은 경로 문자에 따른 셸 처리로 추정하며, 개별 원인 분해는 하지 않았다.
- 폰의 toybox `pgrep -f "nrc.Probe heartbeat"`는 명령을 실행한 셸 자신도 일치시킨다(대상 외 pid 1개 추가) → 자기 자신을 제외하는 패턴 `nrc[.]Probe [h]eartbeat`로 대상 프로세스만 일치함을 확인. **실측**

### 2.5 Phase 0 잔여 점검 결과 (2026-09-27)

**① 데몬 생존** — 통과
- 기록 범위: heartbeat(pid 30918)가 09-26 21:57:11부터 09-27 16:46:04까지 기록을 이어갔다(약 18.8시간). 재기동 표시(추가 START)나 카운터 초기화는 없었다.
- 깊은 잠 누적: elapsed−uptime 증가분 기준 약 7.1시간.
  - **한 번에 몇 시간 잔 것이 아니다.** 짧은 잠들기·깨기를 반복했다. 가장 긴 기록 공백은 522초(그중 깊은 잠 약 462초)였다.
  - 시간대별로는 05:00~13:00에 시간당 약 42~49분(평균 약 46분)을 잤다. 22:00~04:00에는 시간당 수 분 이하로 거의 자지 않았다. 원인은 미확정이며, PC가 폰 핫스팟을 쓰던 시간으로 추정한다.
- 케이블 분리 상태였다는 점은 로그에 없고 사용자 보고에 따른다.
- 측정 종료 후 `pkill -f "nrc[.]Probe [h]eartbeat"`로 중지했고, 중지 전 대상 `30918`, 중지 1초 뒤 일치 프로세스 없음(`after: []`)을 확인했다(콘솔 출력 원문은 작업 증거에 보관).
- 한계: adbd 재시작(USB 디버깅 끄기 등)에서의 종료 여부는 이번에 시험하지 않았다(보고 기반 ❓ 유지).

**③ 화면 표시와 실제가 어긋난 상태에서 삼성 경로 재적용** — 삼성 기록 기준 확인
1. 16:57:22: 컨트롤러 방식으로 USER만 LTE로 바꿈. `allow_nettype_list user 840583(26) -> 316295(9)`, PhoneInterfaceManager 경로.
2. 이때 삼성 저장값은 26 그대로였다.
3. 16:58:13: 삼성 경로(`SamsungInternalServiceImpl`)로 모드 26 적용 → `user 316295(9) -> 840583(26)`.
   - **주체 정정(사용자 보고, 2026-09-27)**: 이 적용은 사람이 누른 것이 아니다. 사용자의 삼성 루틴("LTE에서 핫스팟을 켜면 5G로 변경")이 자동으로 한 것이다.
   - 16:58:29의 모드 9는 사용자가 직접 LTE로 되돌린 것이다.
   - 현재 사용자는 모든 루틴을 껐다.
- 결론: **저장값과 같은 모드를 삼성 경로로 다시 적용해도 실제 USER 값이 5G 포함으로 복구**됐다. 사람이 설정 화면에서 누르는 경우도 같은 삼성 경로를 거친다(09-26 21:37~21:38 수동 선택 기록). 다만 "사람이 이미 선택된 항목을 다시 누르는" 동작 자체는 직접 관측하지 않았다.
- 시사점: 삼성 루틴 같은 자동화도 설정 화면과 같은 경로로 네트워크 모드를 바꾼다. 컨트롤러 입장에서는 사람의 선택과 구분되지 않는 외부 변경이다.
- 한계: 화면 표시는 직접 보지 않았다. 저장값이 26이었으므로 화면도 5G로 보였을 것이라는 추정이다.

**② 재부팅 후 어느 값이 이기는가** — **USER 허용 타입(DB)이 이긴다**(역방향 재시험으로 확인)
- 1차 시도(무효):
  1. 16:57:22: USER만 LTE(316295)로 바꿈. 삼성 저장값은 26.
  2. 16:58:13에 26, 16:58:29에 9를 설정 화면에서 선택 → 삼성 저장값도 9가 됨.
  3. 19:59 재부팅: `bootup or subId 2 DB updated, user=316295(9)`와 `SemGsmCdmaPhone` 경로 `setPreferredNetworkType 9` 기록.
  - 재부팅 시점에 두 값이 같았으므로 판정할 수 없다.
- 1차 시도의 16:58:13 모드 26 적용은 사용자 루틴이 한 것이다(위 ③ 주체 정정).
- 역방향 재시험:
  1. 20:05:31: 삼성 저장값 9인 상태에서 USER만 5G 포함(840583)으로 바꿈 → `completed`, NR 연결(nrState=3) 확인.
  2. 사용자가 설정을 건드리지 않고 재부팅한 뒤 핫스팟을 켬(사용자 보고). 이 덤프에는 20:05 이후 삼성 경로(`SamsungInternalServiceImpl`)의 모드 변경 기록이 없어, 루틴·수동 조작이 없었다는 사용자 보고와 부합한다(모든 개입을 독립적으로 감시한 증거는 아님).
  3. 20:19:31 부팅: `bootup or subId 2 DB updated, user=840583(26)`와 `SemGsmCdmaPhone` 경로 `setPreferredNetworkType 26` 기록.
  4. 20:22 조회: 삼성 저장값 `preferred_network_mode=9`(`preferred_network_mode2=9`) 그대로, USER는 NR 포함(840583), `nrState=3`, 표시 `NR_NSA`.
  - 결론: **부팅 시 삼성 저장 모드(9)가 아니라 USER 허용 타입(840583, 26 상당)이 적용**됐다. `cmd phone`으로 바꾼 값은 재부팅 후에도 유지되고, 삼성 저장 모드는 이를 덮지 않는다(레퍼런스 기기, 1회).
  - 시사점: `cmd phone`만 쓰면 재부팅 뒤에도 설정 화면(저장 모드)과 실제가 어긋난 채 남는다.
- 시험 후 정리: 20:29:02 사용자 원칙("표시와 실제는 같아야 하고, LTE 모드면 순정")에 따라 USER를 316295(LTE 계열)로 되돌려 저장값 9와 일치시켰다. 통화 없음 확인 후 전환했고, `completed`, 표시 `LTE_CA`, `nrState=0`.

### 2.6 삼성 표시 동기화 경로 탐색 (2026-09-27, 읽기 전용 — 어떤 후보도 호출하지 않음)

배경: 사용자 원칙에 따라 컨트롤러가 LTE로 내릴 때 설정 화면 모드도 함께 바뀌어야 한다(DESIGN §5.6.5). 설정 화면·루틴이 쓰는 경로는 삼성 기록상 `com.samsung.telephonyui.SamsungInternalServiceImpl.setPreferredNetworkType`이다.

| 조사 | 결과 |
|---|---|
| `service list` | 삼성 전화 확장 `isemtelephony: [com.android.internal.telephony.ISemTelephony]` 등록 |
| `ISemTelephony` 메서드(이름·인자형만 조회) | 56개. 네트워크 모드(선호 네트워크 타입) 설정 메서드는 없음. `setNrMode(int,int,boolean,String)`/`getNrMode(int)`는 이름상 5G 방식(SA/NSA) 선택으로 추정(미확인) |
| 설정 화면 앱 | `com.samsung.android.app.telephonyui` (`/system/priv-app/TelephonyUI/TelephonyUI.apk`) |
| 공개 제공자(주소) | `com.samsung.android.app.telephonyui.command`(삼성 Command SDK `CommandProvider` — 루틴·Bixby용 통로로 알려짐), `com.samsung.android.app.telephonyui.internal`(`InternalContentProvider`), `com.android.phone.CapsuleProvider`(Bixby2) 외 |
| 패키지 조회 주의 | `cmd package list packages`가 보조 사용자(150) 접근 권한 오류로 실패 → `--user 0` 지정 시 성공 |

**앱 내부 분석(2026-09-27, 읽기 전용)**
- 도구: jadx 1.5.6. 배포처 체크섬 일치, `%LOCALAPPDATA%\Programs\jadx-1.5.6`에 설치.
- 대상: 폰에서 가져온 `TelephonyUI.apk`, `TeleService.apk`. 분석 산출물은 레포 밖 작업 폴더에만 두었다.

| 입구 | 보호 | shell 사용 가능? |
|---|---|---|
| 삼성 내부 서비스 `com.android.phone/com.samsung.telephonyui.SamsungInternalService`(action `com.sec.android.phone.action.BIND_INTERNAL_SERVICE`, 인터페이스 `ISamsungInternalService` — `setPreferredNetworkType(int,int,IOnResultListener)` 등) | 연결 권한 `com.sec.android.phone.permission.BIND_INTERNAL_SERVICE`, `signature\|privileged` | 불가(shell 미보유) |
| Command SDK 제공자 `com.samsung.android.app.telephonyui.command` | `com.samsung.android.permission.sdk.command.ACCESS_COMMANDS`, `signature\|privileged` | 불가 |
| 내부 설정 제공자 `com.samsung.android.app.telephonyui.internal` | `READ/WRITE_CALL_SETTINGS`, `signature\|privileged` | 불가 |
| Bixby 제공자 `com.android.phone.CapsuleProvider` | 매니페스트 권한은 없음. `call()`이 호출 패키지를 검사한다. 허용은 두 경우뿐이고 나머지는 `SecurityException`이다.<br>• Bixby 에이전트·루틴 앱: 전체 허용<br>• 'Windows와 연결'(`com.samsung.android.mdx`): 내보낸 동작만 허용<br>둘 다 판매용 빌드에서는 해당 앱의 삼성 서명까지 확인한다 | 불가 |

- **삼성 내부 모드 변경의 실제 동작**(TeleService `SamsungInternalServiceImpl.setPreferredNetworkType`): 두 단계다.
  1. `getPhone(i).setAllowedNetworkTypes(0 /*USER*/, RadioAccessFamily.getRafFromNetworkType(mode), …)`
  2. 완료 콜백에서 `Settings.Global.putInt("preferred_network_mode" + subId, mode)`
- **설정 화면이 표시에 쓰는 값**(TelephonyUI `PreferenceDataStore.getInt` 기본 분기): `Settings.Global "preferred_network_mode" + subId`. subId는 슬롯의 활성 SIM 식별 번호이며, 레퍼런스 기기에서는 2다.
- **설정 화면 경로가 쓰는 값**(`putPreferredNetworkModeValue`): `preferred_network_mode<subId>`에 더해, 번호 없는 `preferred_network_mode`의 슬롯 위치 항목(쉼표 목록)도 갱신한다.
- TeleService 안에는 이 설정 키를 지켜보는 코드가 없었다(키 사용처는 내부 서비스와 공장 초기화 도우미뿐). 시스템 프레임워크(telephony-common)는 분석하지 않았다.
- 결론: 설정 화면까지 바꾸는 **삼성 통로 네 곳은 모두 삼성·시스템 앱 전용으로 막혀 있다.** 그러나 삼성 내부 동작 자체는 "USER 허용 타입 변경 + 설정 키 기록" 두 단계이고, 둘 다 shell 권한으로 재현할 수 있다(`cmd phone`, `settings put global`) → S2. 실측은 §2.8.

### 2.7 상단바 작동 표시 시험 (2026-09-27 21:57~22:03, 사용자 승인. 네트워크 설정 변경 없음)

배경: 사용자 요구는 "5G 우선 모드에서 컨트롤러가 작동 중이면 상단바 5G/LTE 표시 근처에 표시(예: 초록 점), 알림창이 아님"이다.

| 항목 | 결과 |
|---|---|
| shell 권한 | `dumpsys package com.android.shell`: `STATUS_BAR`, `STATUS_BAR_SERVICE`, `EXPAND_STATUS_BAR` granted=true |
| 상단바 서비스 시그니처 | `setIcon(String, String, int, int, String)`, `setIconVisibility(String, boolean)`, `removeIcon(String)` (AOSP와 같은 형태) |
| 자리 순서(`cmd statusbar get-status-icons`) | 약 45개. 끝부분 `… wifi, ims_volte, mobile, ims_volte2, mobile2, airplane, battery, sensors_off` |
| 시험 아이콘 | 안드로이드 기본 `android.R.drawable.presence_online`(id 0x0108006b, 초록 원) |
| 새 자리 `nrc_active` | 상단바 서비스 기록에는 `visible`로 등록됨. **화면에는 안 보임**(사용자 육안 "안 보임" + 상단바 캡처로 확인) |
| 빈 자리 `ims_volte2`(SIM2 VoLTE, 단일 SIM이라 비어 있음 확인) | **신호 막대 바로 오른쪽, 배터리 앞에 보임**(사용자 육안 "lte표시/신호강도 표시/흰색 커다란 원/배터리 표시" + 잠금화면 상단바 캡처) |
| 색·크기 | 흰색 단색으로 칠해짐(초록색 유지 안 됨). 자리를 꽉 채운 큰 원 |
| 정리 | `ims_volte2`가 시험 아이콘(pkg=android, id=0x0108006b)임을 확인한 뒤 제거. 이후 `ims_volte2`·`nrc_active` 항목 없음. 원래 `ims_volte`(VoLTE) 표시는 그대로. 폰의 시험용 dex 삭제 |
| 캡처 방식 | 화면 전체를 찍은 뒤 상단 띠만 잘라 남기고 전체 이미지는 즉시 삭제. 두 번째 캡처는 화면 깨우기 신호(`KEYCODE_WAKEUP`)만 보냄(잠금 해제 안 함) |

### 2.8 삼성 표시 동기화 S2 실측 (2026-09-27 23:06~23:33, 사용자 승인)

방법: 삼성 내부 동작과 같은 순서로 두 단계를 실행했다.
1. `cmd phone set-allowed-network-types-for-users`
2. `settings put global preferred_network_mode2 <모드>`와 `preferred_network_mode <모드>`(단일 SIM이라 슬롯 항목 1개)

두 단계 모두 통화 없음을 확인한 뒤 실행했다.

| 단계 | 결과 | 사용자 육안(설정 > 연결 > 모바일 네트워크 > 네트워크 모드) |
|---|---|---|
| A: LTE → 5G | `completed`. 10초 후 `preferred_network_mode=26`, `preferred_network_mode2=26`, USER에 NR 포함(840583), `nrState=3`, `NR_NSA` | **"5G 우선(5G/LTE/3G/2G)"** 선택으로 표시 |
| B: 5G → LTE | `completed`. 10초 후 두 키 모두 9, USER 316295, `nrState=0`, `LTE_CA` | **"LTE 우선(LTE/3G/2G)"** 선택으로 표시 |

- 삼성 기록: 23:06:54와 23:33:19에 각각 `allow_nettype_list user …`와 PhoneInterfaceManager 경로 `setPreferredNetworkType` 1건씩만 남았다. 설정 키 기록 뒤 삼성 쪽이 추가로 모드를 다시 적용한 기록(SemGsmCdmaPhone·SamsungInternalServiceImpl 경로)은 없었다. 두 시점 사이 다른 모드 변경도 없었다.
- 결론: 레퍼런스 기기에서 **S2로 설정 화면 표시와 실제 허용 모드가 양방향으로 일치**했다(각 방향 1회).
- 시험 후 상태: 원래대로 LTE 모드. 설정 화면·저장 키·실제 모두 LTE.
- 미확인 ❓:
  - 시스템 프레임워크 쪽 부작용(2회 시험에서 관측 없음)
  - 설정 화면이 열려 있는 동안의 실시간 갱신(이번에는 화면을 다시 열어 확인)
  - 다른 삼성 기기·One UI 버전

### 2.9 Phase 1 관찰 데몬 기동 시험 (2026-09-28 03:23~03:33, 읽기 전용 — 설정 쓰기 없음)

환경: 사용자 모드 LTE(USER 316295, 설정 키 9), 화면 꺼짐, 핫스팟 켜짐, USB 충전 중, 단일 SIM(subId 2).

| 시도 | 결과 |
|---|---|
| 1차(03:23) | 시작 기록 후 등록 단계에서 종료. 기록 원문: `{"ev":"fatal","msg":"java.lang.NullPointerException: Attempt to invoke virtual method 'android.os.TelephonyServiceManager$ServiceRegisterer android.os.TelephonyServiceManager.getTelephonyServiceRegisterer()' on a null object reference"}` |
| 원인·수정 | app_process에는 앱 프로세스가 시작 때 하는 모듈 서비스 등록기 준비(`ActivityThread.initializeMainlineModules()`)가 없다 → 데몬이 직접 호출하도록 수정 |
| 2차(03:26) | 등록 성공(`"registered","allowedCb":true`). 첫 알림 도착(등록 후 ms): 서비스 상태 9, 통화 13, 데이터 활동 14, 신호 15, 표시 16, 물리채널 17 |
| 3차(03:31, `nrctl start`) | 같은 결과. 물리채널 첫 기록 `"pcc","nr":false,"n":5,"cells":"19p:b0:15,19s:b0:20,19s:b0:10,19s:b0:20,19s:b0:10"`(LTE 묶음 5개: 주 1 + 보조 4, 대역폭 MHz. 밴드 번호는 0으로 보고됨) |

- 등록에 성공한 7회 기동 모두 첫 알림 여섯 종류가 5~17ms 안에 도착했다(PC 합본 기록의 `first` 레코드 42개).
- 허용 타입 알림: 등록은 권한 오류 없이 성공(`registered allowedCb=true`, 7회 기동 모두). **등록 시점의 현재값은 오지 않았다**(7회 기동. 끝난 6회의 세션 길이 286.6·477.9·1501.2·1.9·1027.8·80.5초, 합계 약 56분 동안 `allowed` 기록 0건). 모드 변경이 없어 변경 알림 수신은 미확인 ❓.
- 화면 꺼짐인데 물리채널 목록이 왔다. 소스상 충전·테더링 중에는 화면 꺼짐에도 보고가 이어진다(§4) → 이번 환경(USB 충전·핫스팟)과 맞는다. 리포트는 보수적으로 화면 켜짐 구간만 통계에 쓴다.
- 3차 동안 첫 기록 뒤 추가 `sig` 기록은 없었다(정지 상태, 약 2분). 기록 조건(NR 신호 유무 변화, 또는 LTE rsrp 5dB 구간 변화 + 10초 간격)에 걸리는 변화가 없었다는 뜻이다. 신호 알림 자체가 오지 않았다는 증거는 아니다.
- 4차(03:39~04:04, 약 25분): 깨어 있는 10분마다 남기는 `alive`가 2회 기록됐다(30초 주기 확인 동작). 화면 켜짐·꺼짐도 주기 확인으로 잡혔다.
- 수정 후 기기 시험(04:04, 원문: 스크래치패드 `p1-evidence-2.txt`):
  - 배타 잠금: 실행 중 두 번째 기동은 `nrd already running` / 종료 코드 3.
  - 종료 요청: `stop` → 데몬이 열린 사용 구간(`data` 1,832ms)을 기록하고 `stopped by=request`를 남긴 뒤 스스로 종료했다. pid 파일과 요청 파일은 남지 않았다.
  - 신호 종료 경로: 요청 기능이 없던 이전 버전 데몬은 10초 안에 끝나지 않아, 당시 nrctl이 확인 후 신호로 종료했다(`stopped by=nrctl-kill`). 이 신호 경로는 이후 검증 지적(확인과 신호 사이 번호 재사용 위험)으로 **nrctl에서 제거했다**.
  - 핫스팟 사용 중에는 PC 트래픽도 폰의 셀룰러 데이터 활동으로 잡힌다(위 `data` 기록).
- 2차 수정 후 기기 시험(04:21, 원문: `p1-evidence-4.txt`):
  - 대상 pid를 적은 종료 요청: 다른 pid(99999)를 적은 요청은 무시했다(8초 뒤에도 실행 중, 요청 파일 그대로).
  - 자기 pid 요청: 사용 구간을 닫고 `stopped by=request pid=19040`을 남긴 뒤 종료했다.
  - 30초 사용 조각이 앞 조각 끝에서 정확히 이어졌다(예: from 1790536885076 + 29996 = 다음 from 1790536915072).
- `nrctl` 동작 확인(원문: `p1-evidence.txt`·`p1-evidence-2.txt`·`p1-evidence-4.txt`):
  - `doctor`: 권한 4종 O, 모드 LTE, 설정 키 9/9
  - `status`
  - `start`: 실행 중이면 거부("이미 실행 중이다")
  - `pull` 2회: 2회째 새 기록 0줄 = 중복 없이 합침
  - `report`
- 시험 후 상태: 모드·설정 키 모두 원래대로 LTE(9/316295). 관찰 데몬은 실행 중으로 둠(읽기 전용).

### 2.10 Phase 2 제어 기기 시험 (2026-09-28 05:23~06:05, 사용자 승인)

원문: 스크래치패드 `p2-smoke-inactive.txt`, `p2-test-1.txt`~`p2-test-4.txt`와 폰 기록(`nrd.log`).

| 단계 | 결과 |
|---|---|
| LTE 모드에서 제어 모드 시작 | `control_check blocked=none`(S2 가능·상단바 자리 비어 있음·데이터량 조회 가능). 쉼 상태, 쓰기·표시 없음 |
| 사용자가 5G 우선 선택(1차) | 05:26:54 알림으로 즉시 감지 → 경계 상태 + 점. **그러나 망 재접속 끊김(1.6초)을 불안정으로 세어 05:26:57 LTE로 내림** → 결함. 사용자 모드 변경 뒤 조용한 구간을 추가해 수정(DESIGN §5.11 결정 1) |
| 종료(되돌리기) | 컨트롤러가 내려 둔 LTE를 원래 모드(5G 우선)로 되돌리고 종료: USER에 NR 포함, 설정 키 26/26, 점 제거, 상태 파일 `clean:true` |
| 시험 명령으로 LTE 쉬기 | 05:39:23 S2 쓰기 0.6초(`s2_begin`→`s2_ok`): USER 316295, 설정 키 9/9, 점 유지. 데이터 끊김 0.8초 |
| 재시험 | 쉬는 시간이 끝난 뒤 화면이 꺼져 있어 기다림 → 05:48:58 화면 켜짐 → 1.2초 뒤 S2 쓰기 시작, 1.7초 뒤 완료(설정 키 26/26). 사용자가 설정 화면에서 5G 우선 확인 |
| 사용자가 LTE 우선 선택 | 05:52:35 알림으로 즉시 감지 → 쉼 상태, 점 제거, 원래 모드 LTE로 기록 |
| 수정본으로 5G 우선 선택(2차) | 05:58:03 감지 → 경계 상태 + 점. 재접속 끊김(1.2초)은 세지 않음(`mode_change_cost outMs=1962`). 사용자가 "5G 우선으로 남음" 확인 |
| 작은 점 | 동반 앱(8.6KB, 실행 코드 없음) 설치 → 점을 60초 표시. 사용자가 "작은 점, 좋음" 확인 |

- 정착 시계 결함(DESIGN §5.11 결정 2): 두 번의 컨트롤러 쓰기 모두 `switch_cost`가 쓰기 끝 0.3초 뒤에 나왔다. 전환 직후 끊김(0.6~0.8초)보다 먼저 "정상 확인"이 난 것이다. 쓰기 끝 시각부터 재도록 수정했다.
- 삼성 기록(TelephonyDebugService, 원문: 스크래치패드 `p2-samsung-dump.txt`):
  - 05:26~05:59 모드 변경 8건(`allow_nettype_list`)이 사용자 선택 4건과 컨트롤러 쓰기 4건에 하나씩 대응한다.
  - 사용자 선택(05:26:54·05:52:35·05:58:03·05:59:04)은 `SamsungInternalServiceImpl` 경로로 남았다.
  - 컨트롤러 쓰기(05:26:57·05:29:36·05:39:23·05:48:59)는 `PhoneInterfaceManager` 경로로 남았다.
  - 이 밖의 삼성 쪽 재적용 기록은 없었다.
- 빌드 도구: Windows의 aapt2·zipalign은 한글이 든 경로를 열지 못했다(`failed to open directory`). 동반 앱 빌드는 영문 임시 폴더에서 한다(`app/build.sh`).
- 시험 뒤 상태: 사용자가 고른 LTE 우선(USER 316295, 설정 키 9). 컨트롤러는 켜 둔 채 쉼 상태(사용자 결정).

### 2.11 실사용 첫날 (2026-09-28 07:03~19:07 약 12시간, 제어 모드 켜 둠)

원문: `logs/<기기일련번호>/nrd-merged.jsonl`(19:07 `nrctl pull`), 요약은 `nrctl report`. 아래 합계는 기록 전체(03:26 관찰 전용 세션·새벽 기기 시험 §2.10 포함) 기준이다. 실사용 세션은 07:03 시작분이다.

- 관찰 15.7시간(세션 17개), 화면 켜짐 6.0시간, 그중 5G 우선 5.8시간. 5G 우선·화면 켜짐 중 데이터 사용 49.4분, 그중 99%가 5G 연결 중.
- 5G 해제 155회 중 데이터 사용 중(직전 2초 안에 데이터 활동) 9회(활동 중 5, 1초 이하 3, 2초 이하 1). 나머지 146회는 직전 데이터 활동이 2초보다 전이었다: 2초 초과~5초 1, 5초 초과~10초 90, 10초 초과~30초 41, 30초 초과 14. 이 146회는 2초 기준으로 판정에서 뺐다. 데이터를 멈춘 뒤 풀리는 통상 해제와 들어맞는 분포지만, 기록만으로 원인을 확정하지는 않는다. 데이터 사용 중 해제 직전 5G 유지 시간 중앙값 11.4초(표본 8).
- 데이터 서비스 끊김(5G 우선·화면 켜짐) 7회, 그중 데이터 사용 중 5회, 복구 중앙값 1.2초. 기록 전체의 끊김 16건은 모두 LTE 모드 중이었거나 설정이 바뀐 지 0.5초 안(사용자 선택 또는 기기 시험 중 컨트롤러 쓰기)의 재접속이었다. 수정 뒤에는 조용한 구간이라 세지 않는다. 수정 전 1건(05:26:58)은 §2.10의 오판이다.
- 실사용 중 경계 상태(데이터 사용 중 끊김 1회)로 간 적 6번(07:30·07:37·08:16·08:40·09:12·18:16), 모두 5~10분 안에 양호로 돌아갔다(그 사이 끊김이 더 있었지만 2분에 3번에는 못 미쳤다).
- 실제 제어(기록 전체): LTE로 내림 2회(기기 시험의 수정 전 오판 1, 시험 명령 1), 재시험 1회, 종료 되돌리기 1회, 안전 정지 0회. 실사용 세션에서는 판정 기준(2분에 데이터 사용 중 끊김 3회)을 넘은 적이 없고 전환도 없었다.
- 보류: 화면 꺼짐 42회, 통화 1회.
- **발견된 결함**: Wi-Fi로 인터넷을 쓰는 동안에도 작동 표시가 떴다(사용자 보고). 표시가 "5G 우선인가"만 보고 기본 인터넷 경로를 보지 않았기 때문이다 → DESIGN §5.11 결정 17.

### 2.12 Wi-Fi 수정본 기기 확인 (2026-09-28 19:58~20:28, 사용자 승인)

원문: `logs/<기기일련번호>/nrd-merged.jsonl`(20:27 `nrctl pull`). 사용자는 한 단계씩 안내받아 조작하고 결과를 답했다.

| 시각 | 한 일 | 결과 |
|---|---|---|
| 19:58:56 | 새 버전 시작(원래 5G 우선) | `control_check blocked=none`, 기본 네트워크 알림 `cellular`, 점 표시 |
| 20:08:23 | 사용자가 Wi-Fi 연결 | `net default=wifi` → 보류 `wifi`, 점 숨김. 사용자 확인 |
| 20:09:10 | 사용자가 Wi-Fi 끔 | 보류 끝, 점 다시 표시. 사용자 확인(5G 우선 그대로) |
| 20:09:39·20:10:06 | 사용자 모드 선택(LTE → 5G) | 값이 바뀐 알림으로 즉시 감지 |
| 20:11:34 | 시험 명령 → LTE로 쉼 | S2 0.6초(키 9/9). 사용자가 열어 둔 네트워크 모드 화면은 **5G 우선으로 보였다** |
| 20:13:34 | 쉬는 시간 끝 → 재시험(5G) | S2 1초. 사용자가 영상을 틀었으나 판정 없이 20:17:13 화면 꺼짐(보류) |
| 20:19:48~50 | 재시험 상태를 벗어나려고 재시작(PC 종료 → 시작) | 쓰기 없음. 20:23:30 화면 켜짐으로 보류 해제 |
| 20:24:01 | 시험 명령 → LTE로 쉼 | 사용자가 홈 화면에서 설정을 다시 열자 네트워크 모드가 **5G 우선으로 보였다**(실제 USER 316295, 키 9/9) |
| 20:24:49 | 사용자가 LTE 우선을 누름 | **값이 같은데도 USER 알림 도착**(자기 쓰기 47초 뒤). 컨트롤러는 같은 값이라 무시 |
| 20:26:01 | 쉬는 시간 끝 → 재시험(5G) | **사용자가 고른 LTE를 72초 뒤 5G로 덮어씀**(결함 → DESIGN §5.11 결정 18) |
| 20:27 | 사용자가 뒤로 가기로 닫고 다시 엶 | 네트워크 모드가 실제(5G 우선)대로 보였다 |
| 20:51:42 | 같은 값 재선택 감지 수정본 시작(PC) | 쓰기 없음 |
| 20:54:04 | 시험 명령 → LTE로 쉼 | S2 1초(키 9/9) |
| 20:54~56 | 사용자가 설정을 새로 열어 LTE로 보이는 것을 확인하고 LTE 우선을 누름 | **USER 알림이 오지 않았다.** 이미 골라진 항목을 누르면 삼성 설정이 아무것도 쓰지 않는 것으로 보인다 |
| 20:56:04 | 쉬는 시간 끝 → 재시험(5G) | 사용자 누름을 알 수 없어 5G 우선으로 바뀜 |

- 자기 쓰기 알림 도착(쓰기 끝 기준): 0.113·0.320·0.316초(새벽), 0.010·0.002·0.003·0.003초(저녁) — 7건 모두 0.32초 이하(`s2_ok` 기록 기준).
- 설정 화면: 홈 버튼으로 나갔다 오면 예전 값을 보여 주고, 뒤로 가기로 닫고 다시 열면 실제 값을 읽는다(DESIGN §5.11 결정 19).
- 재시험 사용 시간: 정착 끝(20:13:47)부터 화면 꺼짐 보류(20:17:13)까지 205.9초 동안, 데이터 활동 조각(`data`의 `from`~`from+ms`) 19개가 이 구간에 걸쳤다(8초쯤마다 1~4초). 구간과 겹치는 사용 시간 합은 41.4초였다(기준 60초). 기록 출력 시각이 아니라 실제 구간으로 셌다.
- 같은 값 재선택의 두 경우:
  - 화면이 예전 값(5G 우선)을 보이는 채 LTE 우선을 누름 → 삼성이 "변경"으로 처리해 쓰고 USER 알림이 온다(20:24:49). 수정본은 이것을 사용자 선택으로 채택한다(PC 시험, 기기 재현 전).
  - 화면이 실제(LTE)대로 보이는 채 LTE 우선을 누름 → 아무 일도 없고 알림도 없다(20:54~56). 설정 화면으로는 "LTE로 계속"을 전할 길이 없다.
- 시험 뒤 상태: 5G 우선(컨트롤러의 재시험 쓰기). 사용자의 마지막 조작은 시험용 LTE 누름이었다. 컨트롤러(수정본)는 재시험 중이다.

### 2.13 §5.12 방식(USER만 쓰기) 설치·실사용·기기 확인 (2026-09-28 22:48 ~ 09-29 19:15)

원문: `logs/<기기일련번호>/nrd-merged.jsonl`(09-29 19:16 `nrctl pull`). 사용자 승인으로 설치했다.

**실제 불안정 구간에서 처음으로 스스로 쉬었다**(09-28 밤).
- 23:53:26~23:56:37 사이(약 3분) 5G가 8번 풀렸다(`nr_off` 8건). 그때마다 5G 유지 시간은 10.6~43.9초였다.
- 그중 데이터를 쓰는 중(직전 2초 안) 끊김은 23:55:12·23:56:10·23:56:37의 3번이었다(첫째~셋째 84.9초).
- 23:56:38에 원인 `drops`로 LTE로 쉬었다. 전환 비용: 끊긴 시간 2.6초, 회복 2.7초.
- 00:01:38에 5G를 다시 시험했고, 00:03:02에 통과했다(데이터 사용 60초 동안 끊김 2번 미만). 폰에서 재시험 통과가 처음 나왔다.
- 그 뒤 09-29 19시까지 쉰 적은 없다. 경계로 갔다 돌아온 일은 1번(18:23)이다.

**기기 확인(사용자와 함께, 09-29 19:05~19:10).**

| 시각 | 한 일 | 결과 |
|---|---|---|
| 19:05:18 | 시험 명령 | 화면이 꺼져 있어 폰이 스스로 미룸(`suppressed why=screen_off`) |
| 19:06:21 | 시험 명령 → 실제 연결만 LTE로 쉼 | USER 316295, **설정 키 26 그대로**(두 키 모두), 표시 `LTE_CA` |
| 19:06~08 | 사용자가 상단바와 네트워크 모드 화면을 봄 | 상단바 LTE + 점. **네트워크 모드 화면은 "LTE 우선"으로 보였다**(설정 키는 26인데도) |
| 19:08:21 | 쉬는 시간 끝 → 재시험 | 5G, 19:10:34 통과 |

**원인(삼성 코드, 읽기 전용 분석).**
- 네트워크 모드 항목(`NETWORK_MODE_PREFERENCE`)은 설정 키가 아니라 `getPreferredNetworkType` 조회로 값을 받는다. 스크래치패드 `apk/out/sources/c4/c.java` getInt 2번 경우 → `v6/h0.A` → `y6/h.y`.
- 그 조회의 실제 구현은 `RadioAccessFamily.getNetworkTypeFromRaf(phone.getAllowedNetworkTypes(0))`다. 스크래치패드 `apk/tele/sources/com/samsung/telephonyui/callsettings/SamsungTuiCallFunctions.java` 780행.
- 즉 **설정 화면은 USER 사유(0번) 허용 타입만 보여 준다.**
- 09-27에 "설정 화면은 접미사 키를 읽는다"고 본 것은 getInt의 기본 경우만 본 잘못된 결론이었다. 그때 두 값이 늘 같아 드러나지 않았다.
- 결론: 컨트롤러가 USER를 LTE로 바꾸면, 설정 키를 건드리지 않아도 설정 화면은 LTE 우선으로 보인다. §5.12의 "설정 화면은 사용자가 고른 값 그대로"는 USER만 바꾸는 방식으로는 이룰 수 없다.
- 이 사실이 뜻하는 것: USER는 그대로 두고 **다른 사유(POWER 등)로 NR을 막으면**, 설정 화면은 사용자가 고른 5G 우선 그대로 보일 것이다. 폰이 실제로 쓰는 허용 타입은 모든 사유의 교집합이기 때문이다(AOSP). 그러면 사용자의 LTE 누름은 늘 USER 값 변경이 되어 확실히 감지된다. 권한·재부팅 뒤 유지 여부·삼성 기능과의 충돌은 기기 시험이 필요하다(DESIGN §5.12).

### 2.14 절전 칸(POWER 사유)으로 NR 막기 기기 실험 (2026-09-29 19:36~19:52, 사용자 승인)

도구: 스크래치패드 `check/src/nrc/ReasonCheck.java`(폰 `/data/local/tmp/nrc/reasoncheck.dex`). 허용 타입 사유 0~3(USER·POWER·CARRIER·ENABLE_2G)을 읽고, USER가 아닌 사유 하나를 쓴다. sub=2.

| 시각 | 한 일 | 결과 |
|---|---|---|
| 19:37 | 읽기 | 네 사유 모두 840583(NR 포함) |
| 19:38 | ① POWER에 같은 값(840583) 쓰기 | `result=true` → shell 권한으로 POWER를 쓸 수 있다 |
| 19:38:35 | ② POWER=316295(NR 없음), USER는 840583 그대로 | 표시 `LTE_CA`, 설정 키 26. 컨트롤러(현재 방식)는 외부 제한으로 보고 `hold why=restricted` |
| 19:39~43 | 사용자 육안 | **상단바 LTE, 네트워크 모드 화면 5G 우선** |
| 19:44:21 | 사용자가 LTE 우선을 누름 | USER 840583→316295(값이 바뀐 알림). 컨트롤러가 USER 알림 0.111초 뒤에 설정 키 변경(26→9)을 관측해 사용자 LTE 선택으로 채택 → INACTIVE, 점 숨김. 0.111초는 컨트롤러가 알림을 받고 키를 읽기까지의 간격이며, 삼성이 키를 실제로 쓴 시각은 재지 않았다 |
| 19:44:44 | POWER=840583으로 되돌림 | 네 사유 중 USER만 316295(사용자 LTE 선택), 나머지 840583 |
| 19:45:38.936 | 사용자가 5G 우선을 누름 | USER 알림 → 19:45:39.251 점 다시 표시 |
| 19:47:49 | ③ 컨트롤러 정상 종료 → POWER=316295 | 표시 `LTE_CA` |
| ~19:49 | 사용자가 재부팅(핫스팟 재연결·USB 디버깅 허용) | — |
| 19:51:50 | 재부팅 150초 뒤 읽기 | **POWER=316295가 그대로 남았다.** USER 840583, 설정 키 26, 표시 `LTE_CA`. 사용자 육안: 상단바 LTE, 네트워크 설정 5G |
| 19:52:16 | POWER=840583으로 되돌림, 컨트롤러 다시 켬 | 표시 `NR_NSA` |

결론:
- **UX는 사용자가 원한 대로 된다.** 쉬는 동안 설정 화면은 5G 우선 그대로이고 상단바·실제는 LTE다. 이때 사용자가 LTE 우선을 누르면 USER 값이 실제로 바뀌므로 확실히 감지된다(설정 화면이 USER 사유만 보여 준다는 §2.13 분석과 일치).
- **위험: POWER 값은 재부팅 뒤에도 남는다.** 쉬는 중에 폰이 꺼졌다 켜지면(데몬 없음) 설정 화면은 5G 우선인데 실제는 LTE로 남는다. 사용자가 설정 화면에서 다른 모드를 눌렀다 돌아와도 풀리지 않는다(USER만 바뀜). 이번 시험에서는 실험 도구로 POWER를 직접 풀었다. 지금 컨트롤러와 `nrctl restore`는 USER만 쓰므로 POWER를 풀지 못한다. 컨트롤러 시작 때 자동으로 푸는 것은 앞으로 넣을 안전장치(후보, DESIGN §5.12)다.
- 지금 USER 방식도 재부팅 뒤 LTE가 남는 것은 같다(§2.5②). 다만 그때는 설정 화면이 LTE 우선으로 보여 사용자가 5G 우선을 눌러 스스로 풀 수 있다. 차이는 "사용자가 보고 스스로 풀 수 있느냐"다.
- 확인하지 못한 것: 삼성 절전 모드 등 다른 기능이 POWER 사유를 쓰는지. 쓰면 컨트롤러와 서로 덮어쓸 수 있다.
- 사용자 PC의 인터넷은 폰 핫스팟을 쓴다. 폰 재부팅 동안 PC 쪽 작업도 멈춘다.

## 3. 관측 경로 실측

| 신호 | 경로 | 결과 |
|---|---|---|
| nrState | `dumpsys telephony.registry` | **`****` 마스킹**(user 빌드) — 실측, 소스(NetworkRegistrationInfo.toString) |
| nrState 원값 | 삼성 TelephonyDebugService "NR STATE Log" | 원값 존재, 덤프당 16건 수준(보존 한도 미확인) — 실측 |
| nrState 원값 | 폰 안에서 binder로 `ServiceState.getNrState()` | 마스킹은 toString에만 있음 — 소스. 단발 조회로 원값 확인(위치 정보 포기 옵션 필요) — 실측(§2.4) |
| 물리채널(PCC) | telephony.registry `mPhysicalChannelConfigs` | 노출 — 실측. 화면 꺼짐 시 보고 중단(충전 중 등 예외) — 소스 |
| 5G 아이콘 규칙 | 실제 적용값 `connected:5G,not_restricted_rrc_idle:5G,not_restricted_rrc_con:NONE`, 타이머 `not_restricted_rrc_idle→rrc_con 3s; connected any 11s` | 실측 |
| 신호세기 | `mSignalStrength` (LTE rsrp/rsrq/rssnr, NR ss*) | 실측(NR 비허용 시 `mNr=Invalid`) |
| 셀 식별 | `mCellIdentity` — CI/TAC 부분 마스킹, PCI/EARFCN/band 노출 | 실측 |
| 오늘 NR 이력 | 10:51~12:01 CONNECTED 35분·34분 안정 → 12:01:34~12:03:02(87.8초) 인접 상태 변화 8회(마지막은 사용자 모드 변경) | 실측 |
| 배터리 | `/sys/class/power_supply/battery/{current_now,charge_counter,...}` shell 읽기 가능, `dumpsys battery`에 `current now` | 실측(USB 연결 중엔 충전 유입이 섞임) |

## 4. AOSP 소스 확인 사항 (android13-release 및 main)

- `cmd phone set-allowed-network-types-for-users`: `checkShellUid()`(shell/root만), **USER 사유 고정**, 마스크는 `Long.parseLong(s, 2)`, 결과 출력 분기(`completed`/`failed`)는 **성공·실패 모두 exit 0**, 잘못된 인자·uid 검사 실패·RemoteException 등은 -1 → exit code만으로 성공 판단 불가(출력으로 판별), `-s` 생략 시 subId=-1로 실패.
  - https://android.googlesource.com/platform/packages/services/Telephony/+/refs/heads/android13-release/src/com/android/phone/TelephonyShellCommand.java
- `PhoneInterfaceManager.setAllowedNetworkTypesForReason`: `MODIFY_PHONE_STATE`(또는 캐리어 권한) 필요, 현재값과 같으면 모뎀 호출 없이 반환, **통화 상태 검사 없음**.
- Shell 패키지 권한: `MODIFY_PHONE_STATE`, `READ_PRECISE_PHONE_STATE`, `READ_PRIVILEGED_PHONE_STATE`, `ACCESS_FINE_LOCATION`, `DUMP`, `WRITE_SECURE_SETTINGS` 보유(AOSP; 삼성 매니페스트는 미검증이나 get/set 동작으로 간접 확인).
  - https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/packages/Shell/AndroidManifest.xml
- 허용 타입 사유: USER=0, POWER=1(hidden), CARRIER=2, ENABLE_2G=3. 실효 마스크 = **설정된 사유들의 교집합**. **모든 사유가 SIM별 DB에 저장되어 재부팅 후 유지**(POWER 포함). "모바일 네트워크 설정 초기화"는 USER 기본값만 남기고 나머지 삭제.
  - https://android.googlesource.com/platform/frameworks/opt/telephony/+/refs/heads/android13-release/src/java/com/android/internal/telephony/Phone.java
- `setNrDualConnectivityState`(EN-DC만 끄기): HAL 1.6+ 필요 → **레퍼런스 기기(HAL 1.5) 불가**, 프레임워크가 저장하지 않음.
- `TelephonyDisplayInfo`: AOSP 기본 아이콘 규칙부터 RRC idle에서 5G 표시 → 판단 신호로 부적합. nrState CONNECTED는 PCC의 NR SecondaryServing(인터넷 컨텍스트)에서 도출.
- TelephonyRegistry 구독 권한: SERVICE_STATE·SIGNAL·DATA_ACTIVITY·DISPLAY_INFO 무권한, CALL_STATE는 READ_PHONE_STATE, PHYSICAL_CHANNEL_CONFIG는 READ_PRECISE_PHONE_STATE, ALLOWED_NETWORK_TYPE_LIST는 READ_PRIVILEGED_PHONE_STATE → shell 모두 충족. callingPackage는 `com.android.shell`.
- `app_process`로 띄운 프로세스는 hidden API 제한이 적용되지 않음.
- 화면 꺼짐 시 PCC/NR 추적 보고 중단(충전·테더링 예외), `settings global nr_nsa_tracking_screen_off_mode`로 변경 가능(전력 비용 있음).

## 5. 실사용 보고·생태계

- 삼성 동작 보고: One UI 8.5(S24, radio 로그로 확인)·One UI 8.0·One UI 6.1(루트)에서 `cmd phone` 경로 동작. **One UI 4/5/7 보고 없음** → 레퍼런스 기기에서 이번에 처음 실측.
  - https://github.com/Dhangofa/NetToggle/issues/28
- Android 16 QPR2에서 `ITelephony.setAllowedNetworkTypesForReason` AIDL 시그니처 변경(callingPackage 추가) → 리플렉션 앱 다수 고장, `cmd phone` 경로는 영향 없음.
  - https://github.com/aunchagaonkar/NetworkSwitch/issues/29
- NSA에서 NR-only → 약 33ms 내 서비스 불가(삼성 보고). 엄격한 LTE-only 마스크가 통화를 깨뜨린 사례(MIUI) → **NR 비트만 조작**.
  - https://github.com/aunchagaonkar/NetworkSwitch/issues/9
- 데몬 수명: adb로 띄운 프로세스는 **adbd 재시작(USB 디버깅 끄기, `adb tcpip`)·재부팅 시 종료**, 케이블 분리는 대체로 생존(삼성 보고, 중간 확신). One UI 8에서 잠금/도즈 후 종료 보고 있음.
  - https://github.com/RikkaApps/Shizuku/issues/311 · https://github.com/RikkaApps/Shizuku/issues/2475
- Shizuku 13.6.0: Android 13+에서 신뢰 Wi-Fi 연결 시 재부팅 후 무선 디버깅으로 자동 기동(WRITE_SECURE_SETTINGS 필요). 저장소 마지막 푸시 2025-06.
- 삼성 Auto Blocker(One UI 6.0+)는 USB 명령 차단 → 레퍼런스 기기(One UI 5)는 해당 없음, 향후 지원 기기 안내 필요.
- 배터리 측정: `dumpsys battery unplug`는 값 갱신을 멈춰 측정을 오염시킴, 소매 빌드에선 충전 중지 불가 → **케이블 분리 상태로 측정**.

## 6. 선행 기능·유사 도구

| 이름 | 전환 기준 | 이 프로젝트와의 차이 |
|---|---|---|
| Pixel 적응형 연결 | 사용 앱(웹·메신저 4G, 영상·다운로드 5G) | 불안정·플래핑 기준 아님 |
| iPhone 5G 자동 | 5G 속도 이득 체감 여부 | 쿨다운 로직 문서화 없음 |
| Smart5gService(커스텀 ROM) | Wi-Fi·절전·데이터 꺼짐 시 POWER 사유로 NR 끔 | 신호 기반 아님 |
| NetToggle·NetworkSwitch 등 | 수동 토글(QS 타일) | 자동 판단 없음 |
| Qualcomm 5G PowerSave 등 | 모뎀 내부 | 앱에서 관측·설정 불가 |

"플래핑 감지 → 쿨다운 → 재시험"을 구현한 공개 도구는 찾지 못함(중간~높은 확신).
- https://support.apple.com/en-us/108383 · https://github.com/AICP/frameworks_base/blob/w16.2/services/core/java/com/android/server/telephony/Smart5gService.java · https://github.com/Dhangofa/NetToggle

## 7. 검증 상태
- §1~§3의 실기기 증거와 그로부터 도출한 주장: Codex 교차검증 3회차 통과(2026-09-26). 단 §2.2 왕복 테스트는 이후 수행되어 DESIGN.md 검증 요청에 포함.
- §4~§6: 조사 에이전트가 1차 소스로 확인한 내용이며, DESIGN.md 검증 요청에 포함.
