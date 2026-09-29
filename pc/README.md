# nrctl — PC 도구

Python 3.8+ 표준 라이브러리만 쓴다. Windows·macOS 공통. **5G를 켜는 명령은 없다** — 5G 우선은 사용자가 폰 설정에서 고른다.

```bash
python pc/nrctl.py doctor         # 기기·권한·현재 모드 점검(읽기 전용)
python pc/nrctl.py install        # 동반 앱 설치(상단바 작은 점 아이콘, 먼저 bash app/build.sh)
python pc/nrctl.py start          # 컨트롤러 기동(먼저 bash daemon/build.sh). 5G 우선 모드일 때만 작동
python pc/nrctl.py start --observe  # 기록만(설정을 바꾸지 않음)
python pc/nrctl.py status         # 실행 여부·상태·최근 기록
python pc/nrctl.py stop           # 종료. 컨트롤러가 LTE로 내려 둔 상태면 원래 모드로 되돌린다(통화 중이면 통화 뒤)
python pc/nrctl.py pause          # 일시정지(쓰기 중단·현재 상태 유지) / resume 재개
python pc/nrctl.py keep-lte       # (쓰지 않음, DESIGN 5.12) LTE로 계속 쓰려면 폰 설정에서 LTE 우선을 고른다
python pc/nrctl.py test-cooldown  # 시험용: 끊김 조건 없이 LTE로 2분 쉬고 재시험(가드는 평소대로)
python pc/nrctl.py restore        # 긴급 복구: 데몬 없이 실제 허용 모드를 저장된 사용자 선택(설정 키)에 맞춤(확인 질문, --yes로 생략)
python pc/nrctl.py pull           # 폰 기록을 logs/<기기>/nrd-merged.jsonl 에 중복 없이 누적
python pc/nrctl.py report         # 요약 + 실제 제어 기록 + "전환했다면" 시뮬레이션(관찰 전용 실행만)
python pc/nrctl.py report --n-drop 2 --w 90 --verbose   # 기준값을 바꿔 같은 기록으로 다시 계산
```

- 사용자가 설정 화면에서 고른 모드가 사용자 선택이다(DESIGN 5.12). 컨트롤러는 설정 키를 쓰지 않고 실제 허용 모드만 바꾼다. `start`는 설정에 저장된 사용자 선택(설정 키 `preferred_network_mode<subId>`)을 보고 원래 모드를 정한다(예전 `--leftover`·`--original` 질문은 없어졌다).
- 처음 쓰는 사람을 위한 설치·사용 안내: [docs/INSTALL.md](../docs/INSTALL.md)
- `stop`·`pause` 등은 대상 pid를 적은 요청 파일로 보낸다. PC는 프로세스 번호로 신호를 보내지 않는다.
- `start`는 상태 파일 사본을 `logs/<기기>/state-<시각>.json`에 남긴다.
- adb 위치: `--adb` → 환경변수 `NRC_ADB` → PATH → `ANDROID_HOME`/`ANDROID_SDK_ROOT` → OS별 SDK 기본 위치 → 다운로드 폴더의 `platform-tools` 순서로 찾는다.
- 기기가 여러 대면 `--serial`로 고른다.
- 폰 기록은 4MB마다 순환하므로(최근 3개) 며칠에 한 번 `pull` 해 둔다.
- `report`의 기준값 기본값은 DESIGN §5.5.6 초기 가설값이다. 시뮬레이션 규칙은 DESIGN §5.5.2·§5.11에 있다.
- 콘솔에 한글이 깨지면 `PYTHONIOENCODING=utf-8`을 설정한다.

시험: `python -m unittest pc/test_nrctl.py` (가상 기록으로 시뮬레이션 규칙·요약·종료 명령 확인)
