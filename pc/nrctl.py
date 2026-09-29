#!/usr/bin/env python3
"""nrctl - Adaptive NR Controller PC 도구.

폰 데몬(nrd)을 켜고/끄고, 명령을 보내고, 기록을 가져와 요약한다(DESIGN 5.9).
5G를 켜는 명령은 두지 않는다. 5G 우선은 사용자가 설정에서 고른다(DESIGN 5.6.4).

    python pc/nrctl.py doctor            # 기기·권한·현재 모드 점검(읽기 전용)
    python pc/nrctl.py start             # 컨트롤러 기동(5G 우선 모드일 때만 작동). --observe 는 기록만
    python pc/nrctl.py status            # 실행 여부·상태·최근 기록
    python pc/nrctl.py stop              # 종료(컨트롤러가 LTE로 내려 둔 상태면 원래 모드로 되돌림)
    python pc/nrctl.py pause | resume    # 일시정지(쓰기 중단·현재 유지) / 재개
    python pc/nrctl.py keep-lte          # (쓰지 않음, DESIGN 5.12) LTE로 계속 쓰려면 폰 설정에서 LTE 우선을 고른다
    python pc/nrctl.py restore           # 긴급 복구: 데몬 없이 실제 허용 모드를 저장된 사용자 선택(설정 키)에 맞춤
    python pc/nrctl.py pull              # 폰 기록을 PC로 모아 합치기
    python pc/nrctl.py report            # 요약 + 실제 제어 기록 + "전환했다면" 시뮬레이션

Python 3.8+ 표준 라이브러리만 쓴다. 경로는 셸 문자열로 조립하지 않고 인자 배열로 넘긴다(DESIGN 5.9).
"""

import argparse
import bisect
import glob
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from collections import Counter, deque
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOCAL_DEX = ROOT / "daemon" / "build" / "nrd.dex"
LOCAL_APK = ROOT / "app" / "build" / "nrc-companion.apk"
LOCAL_RTXT = ROOT / "app" / "build" / "R.txt"
COMPANION_PKG = "nrc.companion"
LOCAL_LOGS = ROOT / "logs"

REMOTE_HOME = "/data/local/tmp/nrc"
REMOTE_DEX = REMOTE_HOME + "/nrd.dex"
REMOTE_LOGS = REMOTE_HOME + "/logs"
REMOTE_LOG = REMOTE_LOGS + "/nrd.log"
REMOTE_PID = REMOTE_HOME + "/nrd.pid"
REMOTE_OUT = REMOTE_HOME + "/nrd.out"
MERGED_NAME = "nrd-merged.jsonl"

NR_BIT = 1 << 19

# 폰에서 pid 파일의 프로세스가 이 데몬이면 pid를 출력한다. 명령줄에 'nrc.Nrd'가 있어야 한다.
# '[.]' 패턴은 이 검사 명령을 실행하는 셸 자신의 명령줄과는 일치하지 않는다.
REMOTE_RUNNING = (
    'p=$(cat %s 2>/dev/null); '
    'if [ -n "$p" ] && tr "\\0" " " < /proc/$p/cmdline 2>/dev/null | grep -q "nrc[.]Nrd"; then echo $p; fi'
    % REMOTE_PID
)


# 이 pid가 아직 이 데몬(명령줄 nrc.Nrd)인지. 조회만 한다. PC 도구는 프로세스 번호로 신호를 보내지 않는다.
REMOTE_ALIVE = 'tr "\\0" " " < /proc/%d/cmdline 2>/dev/null | grep -q "nrc[.]Nrd" && echo yes'
REMOTE_STOP_REQ = REMOTE_HOME + "/stop.req"
REMOTE_CTL_REQ = REMOTE_HOME + "/ctl.req"
REMOTE_STATE = REMOTE_HOME + "/state.json"
STOP_WAIT_SEC = 30  # 데몬은 요청을 받고(되돌리기 쓰기 포함) 최대 20초 안에 끝나거나 스스로 끝난다(Nrd.STOP_WAIT_SEC)
EXIT_NEED_LEFTOVER = 6  # Controller.EXIT_NEED_LEFTOVER_DECISION
EXIT_STATE_UNREADABLE = 7


class NrctlError(Exception):
    pass


# ---------------------------------------------------------------- adb

def _adb_candidates():
    exe = "adb.exe" if os.name == "nt" else "adb"
    env = os.environ.get("NRC_ADB")
    if env:
        yield Path(env)
    found = shutil.which("adb")
    if found:
        yield Path(found)
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            yield Path(os.environ[var]) / "platform-tools" / exe
    home = Path.home()
    if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        yield Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk" / "platform-tools" / exe
    yield home / "Library" / "Android" / "sdk" / "platform-tools" / exe  # macOS
    yield home / "Android" / "Sdk" / "platform-tools" / exe  # Linux
    # 공식 zip을 내려받아 푼 흔한 위치(다운로드 폴더 바로 아래 또는 한 단계 아래)
    yield home / "Downloads" / "platform-tools" / exe
    for p in sorted(glob.glob(str(home / "Downloads" / "*" / "platform-tools" / exe))):
        yield Path(p)


def find_adb(explicit=None):
    if explicit:
        p = Path(explicit)
        if p.is_file():
            return p
        raise NrctlError("adb를 찾을 수 없음: %s" % explicit)
    for p in _adb_candidates():
        if p.is_file():
            return p
    raise NrctlError("adb를 찾지 못했다. --adb 경로 또는 환경변수 NRC_ADB를 지정한다.")


def _decode(b):
    return (b or b"").decode("utf-8", errors="replace").replace("\r\n", "\n")


class Device:
    def __init__(self, adb, serial):
        self.adb = str(adb)
        self.serial = serial

    def run(self, args, timeout=60, check=True):
        cmd = [self.adb, "-s", self.serial] + list(args)
        try:
            r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise NrctlError("adb 응답 시간 초과: %s" % " ".join(args[:2]))
        out, err = _decode(r.stdout), _decode(r.stderr)
        if check and r.returncode != 0:
            raise NrctlError("adb 실패(%d): %s %s" % (r.returncode, " ".join(args[:2]), (err or out).strip()))
        return out

    def shell(self, script, timeout=60, check=True):
        """폰 셸에서 한 줄 스크립트를 실행한다. 스크립트는 인자 하나로 넘긴다(PC 셸을 거치지 않음)."""
        return self.run(["shell", script], timeout=timeout, check=check)

    def running_pid(self):
        out = self.shell(REMOTE_RUNNING, check=False).strip()
        return int(out) if out.isdigit() else None


def connect(args):
    adb = find_adb(args.adb)
    r = subprocess.run([str(adb), "devices"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    lines = _decode(r.stdout).splitlines()[1:]
    devices = [ln.split()[0] for ln in lines if ln.strip().endswith("\tdevice") or ln.split()[-1:] == ["device"]]
    others = [ln.strip() for ln in lines if ln.strip() and ln.split()[-1:] != ["device"]]
    serial = args.serial or os.environ.get("ANDROID_SERIAL")
    if serial:
        if serial not in devices:
            raise NrctlError("기기 %s 연결 안 됨(목록: %s %s)" % (serial, devices, others))
    elif len(devices) == 1:
        serial = devices[0]
    elif not devices:
        hint = " / 상태: " + ", ".join(others) if others else ""
        raise NrctlError("연결된 기기가 없다. USB 디버깅 허용 여부를 확인한다%s" % hint)
    else:
        raise NrctlError("기기가 여러 대다. --serial로 고른다: %s" % ", ".join(devices))
    return Device(adb, serial)


# ---------------------------------------------------------------- 기록 읽기

def parse_lines(text):
    recs, bad = [], 0
    for ln in text.splitlines():
        ln = ln.strip()
        if not ln:
            continue
        try:
            o = json.loads(ln)
        except ValueError:
            bad += 1  # 강제 종료 중 끊긴 줄 등
            continue
        if isinstance(o, dict) and isinstance(o.get("t"), (int, float)) and "ev" in o:
            recs.append(o)
        else:
            bad += 1
    return recs, bad


def fmt_time(ms):
    return datetime.fromtimestamp(ms / 1000.0).strftime("%m-%d %H:%M:%S")


def fmt_rec(o):
    rest = " ".join("%s=%s" % (k, v) for k, v in o.items() if k not in ("t", "ev"))
    return "%s  %-10s %s" % (fmt_time(o["t"]), o["ev"], rest)


def mask_has_nr_text(text):
    """`cmd phone get-allowed-network-types-for-users` 출력(예: 'LTE|NR|...')에 NR이 있는지."""
    return "NR" in [x.strip() for x in text.strip().split("|")]


# ---------------------------------------------------------------- 명령: doctor

def cmd_doctor(args):
    dev = connect(args)
    print("adb      : %s" % dev.adb)
    print("기기     : %s" % dev.serial)
    props = {}
    for key in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk",
                "ro.build.version.oneui", "ro.build.version.incremental", "gsm.sim.state"):
        props[key] = dev.shell("getprop " + key, check=False).strip()
    print("모델     : %s / Android %s (SDK %s) / One UI %s / 빌드 %s" % (
        props["ro.product.model"], props["ro.build.version.release"], props["ro.build.version.sdk"],
        props["ro.build.version.oneui"] or "-", props["ro.build.version.incremental"]))
    sims = [s for s in props["gsm.sim.state"].split(",") if s]
    loaded = sum(1 for s in sims if s.strip() in ("LOADED", "READY"))
    print("SIM 상태 : %s (사용 중 %d개)%s" % (props["gsm.sim.state"] or "-", loaded,
          "  ← 2개 이상이면 v1은 관찰만 한다(DESIGN 6)" if loaded > 1 else ""))
    try:
        sdk = int(props["ro.build.version.sdk"])
    except ValueError:
        sdk = 0
    if sdk and sdk < 31:
        print("주의     : Android 12(SDK 31) 미만은 지원 대상이 아니다")
    oneui = props["ro.build.version.oneui"]
    if oneui.isdigit() and int(oneui) >= 60000:
        print("안내     : One UI 6 이상은 '자동 차단(Auto Blocker)'이 켜져 있으면 USB 명령이 막힌다. 설정 > 보안에서 확인")

    perms = dev.shell("dumpsys package com.android.shell", timeout=60, check=False)
    need = ("READ_PRIVILEGED_PHONE_STATE", "READ_PRECISE_PHONE_STATE", "DEVICE_POWER", "STATUS_BAR")
    got = {n: ("android.permission.%s: granted=true" % n) in perms for n in need}
    print("shell 권한: " + ", ".join("%s=%s" % (n, "O" if ok else "X") for n, ok in got.items()))

    allowed = dev.shell("cmd phone get-allowed-network-types-for-users -s 0", check=False).strip()
    print("USER 허용: %s" % allowed)
    print("현재 모드: %s" % ("5G 포함(5G 우선)" if mask_has_nr_text(allowed) else "5G 미포함(LTE)"))
    keys = dev.shell("settings list global | grep '^preferred_network_mode'", check=False).strip()
    if keys:
        print("삼성 표시 키: " + " / ".join(keys.splitlines()))

    print("로컬 dex : %s" % ("있음" if LOCAL_DEX.is_file() else "없음 - bash daemon/build.sh 로 빌드"))
    pid = dev.running_pid()
    print("데몬     : %s" % ("실행 중(pid %d)" % pid if pid else "꺼짐"))
    return 0


# ---------------------------------------------------------------- 명령: start / stop / status

def tail_records(dev, n=60):
    out = dev.shell("tail -n %d %s 2>/dev/null" % (n, REMOTE_LOG), check=False)
    return parse_lines(out)[0]


def _launch(dev, mode_args):
    """데몬을 띄우고 등록 또는 시작 거부를 기다린다. (pid, start_refused 기록 또는 None)"""
    base = dev.shell("grep -c '' %s 2>/dev/null || echo 0" % REMOTE_LOG, check=False).strip().splitlines()
    base = int(base[0]) if base and base[0].isdigit() else 0
    # setsid로 adb 셸 세션과 분리해야 케이블을 뽑아도 산다(research 2.5 실측). 인자는 고정 선택지만 들어간다.
    dev.shell('setsid sh -c "CLASSPATH=%s exec app_process /system/bin nrc.Nrd %s" '
              '</dev/null >%s 2>&1 &' % (REMOTE_DEX, " ".join(mode_args), REMOTE_OUT))
    deadline = time.time() + 20
    while time.time() < deadline:
        time.sleep(1)
        pid = dev.running_pid()
        recs = parse_lines(dev.shell("tail -n +%d %s 2>/dev/null" % (base + 1, REMOTE_LOG), check=False))[0]
        refused = [o for o in recs if o["ev"] == "start_refused"]
        if refused and not pid:
            return None, refused[-1], recs
        mine = [o for o in recs if o.get("pid") == pid and o["ev"] == "start"] if pid else []
        if mine:
            after = [o for o in recs if o["t"] >= mine[-1]["t"]]
            if any(o["ev"] == "registered" for o in after):
                return pid, None, after
        fatal = [o for o in recs if o["ev"] == "fatal"]
        if not pid and fatal:
            raise NrctlError("데몬이 시작 중 종료됐다: %s" % fatal[-1].get("msg"))
    err = dev.shell("cat %s" % REMOTE_OUT, check=False).strip()
    raise NrctlError("데몬 시작을 확인하지 못했다(20초). stderr: %s" % (err or "-"))


def _ask(question, choices):
    """터미널에서 고르게 한다. 대화형이 아니면 None."""
    if not sys.stdin.isatty():
        return None
    while True:
        ans = input(question).strip().lower()
        if ans in choices:
            return choices[ans]


def cmd_start(args):
    dev = connect(args)
    pid = dev.running_pid()
    if pid:
        print("이미 실행 중이다(pid %d). 다시 켜려면 먼저 stop." % pid)
        return 0
    if not LOCAL_DEX.is_file():
        raise NrctlError("dex가 없다: %s  (bash daemon/build.sh 로 빌드)" % LOCAL_DEX)
    dev.shell("mkdir -p %s" % REMOTE_LOGS)
    dev.run(["push", str(LOCAL_DEX), REMOTE_DEX], timeout=120)
    if args.observe:
        mode_args = ["--observe"]
    else:
        mode_args = ["--control"]
        icon = _companion_icon(dev)
        if icon:
            mode_args.append("--indicator-icon=" + icon)
        else:
            print("동반 앱이 없어 상단바 표시는 기본 원 아이콘을 쓴다(작은 점: python pc/nrctl.py install).")
        if args.leftover:
            mode_args.append("--leftover=" + args.leftover)
        if args.original:
            mode_args.append("--original=" + args.original)
    pid, refused, recs = _launch(dev, mode_args)
    if refused is not None and refused.get("code") == EXIT_NEED_LEFTOVER and not args.leftover:
        # 이전 실행이 LTE로 내려 둔 채 꺼졌고, 그게 컨트롤러 흔적인지 사용자가 다시 고른 LTE인지 구분할 수 없다(DESIGN 5.6.4)
        print("컨트롤러가 쉬는 중(LTE로 내려 둔 상태)에 꺼져서 지금 LTE로 남아 있습니다. 원래 모드는 5G 우선입니다.")
        pick = _ask("원래 모드(5G 우선)로 되돌릴까요? [r] 되돌리기 / [k] 지금 LTE를 원래 모드로: ",
                    {"r": "restore", "k": "keep"})
        if pick is None:
            raise NrctlError("대화형이 아니다. --leftover=restore 또는 --leftover=keep 으로 다시 실행한다.")
        pid, refused, recs = _launch(dev, mode_args + ["--leftover=" + pick])
    elif refused is not None and refused.get("code") == EXIT_STATE_UNREADABLE and not args.original:
        print("상태 파일을 읽을 수 없습니다. 원래 쓰던 모드를 알려 주세요(추정하지 않습니다).")
        pick = _ask("원래 모드는? [5] 5G 우선 / [l] LTE: ", {"5": "nr", "l": "lte"})
        if pick is None:
            raise NrctlError("대화형이 아니다. --original=nr 또는 --original=lte 로 다시 실행한다.")
        pid, refused, recs = _launch(dev, mode_args + ["--original=" + pick])
    if refused is not None:
        raise NrctlError("시작 거부(코드 %s): %s" % (refused.get("code"), refused.get("msg")))
    reg = [o for o in recs if o["ev"] == "registered"][-1]
    cb = "등록됨(변경 때 기록)" if reg.get("allowedCb") else "등록 안 됨(공개 알림만 기록)"
    if args.observe:
        print("관찰 데몬 시작(pid %d). 허용 타입 알림 구독: %s. 설정은 바꾸지 않는다." % (pid, cb))
        return 0
    print("컨트롤러 시작(pid %d). 허용 타입 알림 구독: %s." % (pid, cb))
    time.sleep(2)
    _save_state_copy(dev)
    _print_control_state(dev, pid)
    print("5G 우선 모드일 때만 작동한다. 불안정하면 LTE로 쉬었다가 다시 5G를 시험한다"
          "(쉬는 동안 상단바와 설정 화면 모두 LTE로 보인다 — DESIGN 5.12 기기 확인 결과).")
    return 0


def _companion_icon(dev):
    """동반 앱이 설치돼 있으면 "패키지:점 아이콘 번호"(app/build/R.txt 기준), 아니면 None."""
    if COMPANION_PKG not in dev.shell("pm path %s 2>/dev/null" % COMPANION_PKG, check=False):
        return None
    if not LOCAL_RTXT.is_file():
        return None
    for ln in LOCAL_RTXT.read_text(encoding="utf-8").splitlines():
        parts = ln.split()
        if len(parts) == 4 and parts[1] == "drawable" and parts[2] == "nrc_dot":
            return "%s:%s" % (COMPANION_PKG, parts[3])
    return None


def cmd_install(args):
    """동반 앱 설치(DESIGN 5.9). 지금은 상단바 작은 점 아이콘만 담고 있다(실행 코드 없음)."""
    dev = connect(args)
    if not LOCAL_APK.is_file():
        raise NrctlError("APK가 없다: %s  (bash app/build.sh 로 빌드)" % LOCAL_APK)
    out = dev.run(["install", "-r", str(LOCAL_APK)], timeout=180, check=False)
    if "Success" not in out:
        raise NrctlError("설치 실패: %s" % out.strip())
    print("동반 앱 설치됨(%s). 다음 start부터 상단바 표시가 작은 점으로 바뀐다." % COMPANION_PKG)
    return 0


def _read_state(dev):
    out = dev.shell("cat %s 2>/dev/null" % REMOTE_STATE, check=False).strip()
    if not out:
        return None
    try:
        return json.loads(out)
    except ValueError:
        return {"_raw": out}


def _save_state_copy(dev):
    """PC에도 상태 파일 사본을 둔다(폰 파일 유실 대비, DESIGN 5.6.4)."""
    st = dev.shell("cat %s 2>/dev/null" % REMOTE_STATE, check=False).strip()
    if not st:
        return
    out = default_dir(dev.serial)
    out.mkdir(parents=True, exist_ok=True)
    (out / ("state-%s.json" % datetime.now().strftime("%Y%m%d-%H%M%S"))).write_text(st + "\n", encoding="utf-8")


def _print_control_state(dev, pid):
    st = _read_state(dev)
    recs = tail_records(dev, 80)
    mine = [o for o in recs if o.get("pid") == pid and o["ev"] == "start"]
    since = mine[-1]["t"] if mine else 0
    check = [o for o in recs if o["ev"] == "control_check" and o["t"] >= since]
    states = [o for o in recs if o["ev"] == "state" and o["t"] >= since]
    if check:
        b = check[-1].get("blocked")
        print("제어 가능 여부: %s" % ("가능" if b == "none" else "불가 - 관찰만(%s)" % b))
    if states:
        print("현재 상태: %s" % STATE_WORDS.get(states[-1].get("to"), states[-1].get("to")))
    if st and "_raw" not in st:
        print("원래 모드: %s / 상태 파일: %s" % ("5G 우선" if st.get("originalNr") else "LTE", st.get("state")))


STATE_WORDS = {
    "INACTIVE": "쉼(사용자 LTE 모드 - 손대지 않음)",
    "OBSERVE": "관찰만(제어 불가 조건)",
    "GOOD": "5G 양호",
    "WATCH": "5G 경계(지켜보는 중)",
    "COOLDOWN": "LTE로 쉬는 중",
    "PROBE": "5G 재시험 중",
    "SAFE_STOP": "안전 정지(원래 모드로 되돌리고 멈춤)",
}


def _wait_gone(dev, pid, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if dev.shell(REMOTE_ALIVE % pid, check=False).strip() != "yes":
            return True
        time.sleep(0.5)
    return False


def cmd_stop(args):
    dev = connect(args)
    pid = dev.running_pid()
    if not pid:
        print("실행 중인 데몬이 없다.")
        return 0
    # 종료 요청 파일에 대상 pid를 적는다. 그 pid의 데몬만 받아(다른 실행은 무시) 원래 모드로 되돌리고 스스로 끝난다.
    # 작업이 멈춰 있어도 데몬이 스스로 끝낸다. PC는 프로세스 번호로 신호를 보내지 않는다
    # (확인과 신호 사이에 그 번호가 다른 프로세스로 바뀌면 엉뚱한 작업을 끌 수 있어서).
    dev.shell("echo %d > %s" % (pid, REMOTE_STOP_REQ), check=False)
    if not _wait_gone(dev, pid, STOP_WAIT_SEC):
        recs = tail_records(dev, 40)
        deferred = [o for o in recs if o["ev"] == "stop_deferred"]
        if deferred:
            # 통화 중 등으로 되돌리기·맞추기가 막힘: 풀리면 데몬이 마저 하고 스스로 끝난다. pid 파일은 남긴다.
            print("되돌리기·맞추기를 미뤘다(이유: %s). 풀리면 스스로 마치고 종료한다(pid %d)." % (
                deferred[-1].get("why"), pid))
            return 0
        # 종료가 확인되지 않았으면 pid 파일을 남긴다: status가 계속 "실행 중"을 보이고, stop을 다시 시도할 수 있게.
        raise NrctlError("데몬(pid %d)이 종료 요청에 응답하지 않았다. 다시 stop 하거나, 폰을 재부팅하면 꺼진다." % pid)
    # 종료가 확인된 뒤에만, 아직 이 pid를 가리키는 요청 파일·pid 파일을 지운다(그사이 새 데몬이 떴다면 그 파일은 남긴다).
    dev.shell('for f in %s %s; do [ "$(cat $f 2>/dev/null)" = "%d" ] && rm -f $f; done'
              % (REMOTE_STOP_REQ, REMOTE_PID, pid), check=False)
    recs = tail_records(dev, 30)
    restored = [o for o in recs if o["ev"] == "stop_restore"]
    if restored:
        print("종료(pid %d). 원래 모드로 되돌림: %s" % (pid, restored[-1].get("result")))
    else:
        print("종료(pid %d). 컨트롤러가 바꿔 둔 모드가 없어 되돌릴 것이 없었다." % pid)
    _warn_if_mismatch(dev)
    return 0


def _key_and_user(dev):
    """(설정 화면 키 모드, USER 마스크, sub, slot) 또는 None. 설정 화면 키 = 사용자가 고른 모드(DESIGN 5.12)."""
    q = _query_user(dev)
    if q is None or q[0] < 0 or q[1] < 0:
        return None
    k = dev.shell("settings get global preferred_network_mode%d" % q[1], check=False).strip()
    if not k.isdigit():
        return None
    return int(k), q[0], q[1], q[2]


def _key_has_nr(key):
    """설정 모드 번호가 5G(NR)를 포함하는 계열인지(안드로이드 모드 번호 23 이상이 NR 계열)."""
    return key >= 23


def _warn_if_mismatch(dev):
    """종료 뒤 저장된 사용자 선택(설정 키)과 실제 허용 모드(USER)가 5G 포함 여부에서 어긋나면 알린다(DESIGN 5.12 보호 규칙 4)."""
    ku = _key_and_user(dev)
    if ku is None:
        return
    key, user = ku[0], ku[1]
    if _key_has_nr(key) != bool(user & NR_BIT):
        print("주의: 저장된 사용자 선택은 %s인데 실제는 %s입니다. python pc/nrctl.py restore 로 저장된 선택에 맞춥니다." % (
            "5G 우선" if _key_has_nr(key) else "LTE 우선", "5G 포함" if user & NR_BIT else "LTE"))


def cmd_command(args):
    """pause / resume / keep-lte: 대상 pid를 적은 명령 파일을 쓴다(그 데몬만 받는다)."""
    if args.verb == "keep-lte":
        print("DESIGN 5.12부터 사용자 선택은 폰 설정에서 고른다(PC 명령 없음). LTE로 계속 쓰려면 설정 > 연결 > 모바일 네트워크 >"
              " 네트워크 모드에서 고른다. 단 컨트롤러가 쉬는 중이면 화면이 이미 LTE 우선으로 보여 그대로 누르면 알아채지 못한다:"
              " 5G 우선을 한 번 눌렀다가 LTE 우선을 누른다(DESIGN 5.12 기기 확인 결과).")
        return 0
    dev = connect(args)
    pid = dev.running_pid()
    if not pid:
        raise NrctlError("실행 중인 데몬이 없다.")
    dev.shell("echo '%d %s' > %s" % (pid, args.verb, REMOTE_CTL_REQ), check=False)
    time.sleep(2)
    _print_control_state(dev, pid)
    recs = [o for o in tail_records(dev, 10)
            if o["ev"] in ("paused", "resumed", "keep_lte_ignored", "test_cooldown_ignored", "suppressed", "state", "warn")]
    for o in recs[-3:]:
        print("  " + fmt_rec(o))
    return 0


QUERY_RE = re.compile(r"user=(-?[0-9]+) sub=(-?[0-9]+) slot=(-?[0-9]+)")
LIST_RE = re.compile(r"[0-9]+(,[0-9]+)*")  # fullmatch로만 쓴다(줄바꿈·다른 문자 거절)


def _query_user(dev):
    """폰의 USER 허용 타입을 마스크 숫자로 읽는다(데몬 dex의 읽기 전용 조회). (user, sub, slot) 또는 None."""
    out = dev.shell("CLASSPATH=%s app_process /system/bin nrc.Nrd --query" % REMOTE_DEX, check=False)
    m = QUERY_RE.search(out)
    return tuple(int(x) for x in m.groups()) if m else None


def cmd_restore(args):
    """긴급 복구(DESIGN 5.9·5.12): 데몬 없이 실제 허용 모드(USER)를 저장된 사용자 선택(설정 키 = 사용자가 마지막으로 고른 모드)에 맞춘다.
    컨트롤러는 5G 포함 여부만 바꾸므로, 저장된 선택이 5G 계열이면 NR을 더하고 아니면 뺀다. 설정 키는 쓰지 않는다.
    자동 재시도는 하지 않고, 재확인 값이 목표와 정확히 같을 때만 성공으로 본다. 폰 셸 명령에는 검증한 숫자만 들어간다."""
    dev = connect(args)
    if dev.running_pid():
        raise NrctlError("데몬이 실행 중이다. stop 이 원래 모드로 되돌린다.")
    if "OK" not in dev.shell("ls %s >/dev/null 2>&1 && echo OK" % REMOTE_DEX, check=False):
        if not LOCAL_DEX.is_file():
            raise NrctlError("폰과 PC 모두에 데몬 dex가 없다(bash daemon/build.sh). 재확인 없이 쓰지 않는다.")
        dev.run(["push", str(LOCAL_DEX), REMOTE_DEX], timeout=120)
    ku = _key_and_user(dev)
    if ku is None:
        raise NrctlError("설정 화면 모드나 지금 허용 모드를 읽을 수 없다. 설정 화면에서 원하는 모드를 직접 고른다.")
    key, user, sub, slot = ku
    want_nr = _key_has_nr(key)
    target = (user | NR_BIT) if want_nr else (user & ~NR_BIT)
    print("저장된 사용자 선택(설정 키): %s(번호 %d). 지금 허용 모드 마스크 %d(%s)" % (
        "5G 우선" if want_nr else "LTE 우선", key, user, "5G 포함" if user & NR_BIT else "LTE"))
    if target == user:
        print("이미 설정 화면과 같다. 바꿀 것이 없다.")
        return 0
    if not args.yes:
        if _ask("저장된 선택에 맞춰 %s로 바꿀까요? [y/n]: " % ("5G 포함" if want_nr else "LTE"),
                {"y": True, "n": False}) is not True:
            print("취소했다.")
            return 0
    # 확인 질문 동안 사용자가 폰에서 모드를 바꿨을 수 있다: 쓰기 직전에 다시 읽고, 달라졌으면 쓰지 않는다
    if _key_and_user(dev) != ku:
        raise NrctlError("확인하는 사이 폰의 설정이나 허용 모드가 바뀌었다. 아무것도 쓰지 않았다. 다시 실행한다.")
    calls = dev.shell("dumpsys telephony.registry | grep -E 'mCallState=|mRingingCallState='", check=False)
    states = [ln.split("=", 1)[1].strip() for ln in calls.splitlines() if "=" in ln]
    if not states or any(v != "0" for v in states):
        raise NrctlError("통화 중이거나 통화 상태를 확인할 수 없다. 통화가 끝난 뒤 다시 실행한다.")
    out = dev.shell("cmd phone set-allowed-network-types-for-users -s %d %s" % (slot, format(target, "020b")),
                    check=False).strip()
    after = _key_and_user(dev)
    if "completed" not in out or after is None or after[1] != target:
        raise NrctlError("맞추기 실패: 출력 %r, 재확인 %s, 목표 %d(재시도하지 않는다)." % (
            out, after[1] if after else "?", target))
    if after[0] != key:
        raise NrctlError("쓰는 사이 설정 화면 모드가 %d → %d로 바뀌었다. 다시 실행해 새 설정에 맞춘다." % (key, after[0]))
    print("맞춤 완료: 허용 모드 %d(%s). 설정 키는 건드리지 않았다." % (after[1], "5G 포함" if want_nr else "LTE"))
    return 0


def cmd_status(args):
    dev = connect(args)
    pid = dev.running_pid()
    if pid:
        etime = dev.shell("ps -o etime= -p %d" % pid, check=False).strip()
        print("데몬     : 실행 중(pid %d, 실행 %s)" % (pid, etime or "?"))
    else:
        print("데몬     : 꺼짐")
    allowed = dev.shell("cmd phone get-allowed-network-types-for-users -s 0", check=False).strip()
    print("현재 모드: %s" % ("5G 포함(5G 우선)" if mask_has_nr_text(allowed) else "5G 미포함(LTE)"))
    recs = tail_records(dev, args.lines)
    if recs:
        print("최근 기록:")
        for o in recs:
            print("  " + fmt_rec(o))
    return 0


# ---------------------------------------------------------------- 명령: pull

def default_dir(serial):
    return LOCAL_LOGS / serial.replace(":", "_")


def merge_into(merged_path, new_recs):
    old_lines = merged_path.read_text(encoding="utf-8").splitlines() if merged_path.is_file() else []
    seen = set(old_lines)
    added = []
    for o in new_recs:
        ln = json.dumps(o, ensure_ascii=False, separators=(",", ":"))
        if ln not in seen:
            seen.add(ln)
            added.append(ln)
    lines = old_lines + added
    lines.sort(key=lambda s: json.loads(s)["t"])  # 파이썬 정렬은 안정 정렬: 같은 시각은 원래 순서 유지
    merged_path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    return len(added), len(lines)


def cmd_pull(args):
    dev = connect(args)
    out = Path(args.out) if args.out else default_dir(dev.serial)
    out.mkdir(parents=True, exist_ok=True)
    names = [n.strip() for n in dev.shell("ls %s 2>/dev/null" % REMOTE_LOGS, check=False).split() if n.strip()]
    names = [n for n in names if n.startswith("nrd.log")]
    if not names:
        print("폰에 기록이 없다(%s)." % REMOTE_LOGS)
        return 0
    recs, bad = [], 0
    with tempfile.TemporaryDirectory() as tmp:
        for n in names:
            dst = Path(tmp) / n
            dev.run(["pull", REMOTE_LOGS + "/" + n, str(dst)], timeout=300)
            r, b = parse_lines(dst.read_text(encoding="utf-8", errors="replace"))
            recs += r
            bad += b
    err = dev.shell("cat %s 2>/dev/null" % REMOTE_OUT, check=False)
    if err.strip():
        (out / "nrd.out").write_text(err, encoding="utf-8")
    added, total = merge_into(out / MERGED_NAME, recs)
    print("가져옴: 파일 %d개, 새 기록 %d줄, 누적 %d줄%s" % (
        len(names), added, total, (", 읽지 못한 줄 %d" % bad) if bad else ""))
    print("저장: %s" % (out / MERGED_NAME))
    return 0


# ---------------------------------------------------------------- 구간 계산

def merge_iv(ivs):
    out = []
    for a, b in sorted(ivs):
        if b <= a:
            continue
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return [(a, b) for a, b in out]


def intersect(A, B):
    i = j = 0
    out = []
    while i < len(A) and j < len(B):
        a = max(A[i][0], B[j][0])
        b = min(A[i][1], B[j][1])
        if a < b:
            out.append((a, b))
        if A[i][1] < B[j][1]:
            i += 1
        else:
            j += 1
    return out


def total(ivs):
    return sum(b - a for a, b in ivs)


def covered(ivs, a, b):
    """[a, b]가 구간 하나 안에 통째로 들어가는지."""
    k = bisect.bisect_right([x for x, _ in ivs], a) - 1
    return k >= 0 and ivs[k][0] <= a and b <= ivs[k][1]


def inside(ivs, t):
    k = bisect.bisect_right([x for x, _ in ivs], t) - 1
    return k >= 0 and ivs[k][0] <= t <= ivs[k][1]


def after_start_inside(ivs, t):
    """a < t <= b 인 구간이 있는지(구간을 연 사건 자체는 제외)."""
    k = bisect.bisect_left([x for x, _ in ivs], t) - 1
    return k >= 0 and ivs[k][0] < t <= ivs[k][1]


def time_to_accumulate(ivs, start, amount):
    """start부터 ivs 안에서 amount만큼 쌓이는 시각. 끝까지 모자라면 None."""
    acc = 0
    for a, b in ivs:
        if b <= start:
            continue
        a = max(a, start)
        if acc + (b - a) >= amount:
            return a + (amount - acc)
        acc += b - a
    return None


# ---------------------------------------------------------------- 세션 분해

class Session:
    """start 기록부터 다음 start/stopped/fatal(또는 기록 끝)까지."""

    def __init__(self, start):
        self.start = start
        self.recs = [start]
        self.end_t = start["t"]
        self.ended_by = None

    def build(self):
        s = self.start["t"]
        e = self.end_t
        self.screen, self.nr, self.mode = [], [], []
        scr_on = nr_on = mode_on = None
        self.bursts = []
        for o in self.recs:
            ev, t = o["ev"], o["t"]
            if ev == "screen":
                if o.get("on") and scr_on is None:
                    scr_on = t
                elif not o.get("on") and scr_on is not None:
                    self.screen.append((scr_on, t))
                    scr_on = None
            elif ev == "nr_on" and nr_on is None:
                nr_on = t
            elif ev == "nr_off" and nr_on is not None:
                self.nr.append((nr_on, t))
                nr_on = None
            elif ev == "mode":
                if o.get("nr") and mode_on is None:
                    mode_on = t
                elif not o.get("nr") and mode_on is not None:
                    self.mode.append((mode_on, t))
                    mode_on = None
            elif ev == "data" and isinstance(o.get("from"), (int, float)):
                self.bursts.append((o["from"], o["from"] + max(0, o.get("ms", 0))))
        for opened, lst in ((scr_on, self.screen), (nr_on, self.nr), (mode_on, self.mode)):
            if opened is not None:
                lst.append((opened, e))
        self.screen = merge_iv(self.screen)
        self.nr = merge_iv(self.nr)
        self.mode = merge_iv(self.mode)
        self.bursts = merge_iv(self.bursts)
        self.use = intersect(self.bursts, self.screen)  # 화면 켜짐 중 데이터 사용
        self.span = (s, e)


def split_sessions(recs):
    sessions, cur = [], None
    for o in recs:
        if o["ev"] == "start":
            cur = Session(o)
            sessions.append(cur)
            continue
        if cur is None or cur.ended_by:
            continue  # 세션 밖 기록(이전 세션 종료 뒤)은 버린다
        if o["ev"] in ("stopped", "fatal"):
            spid = o.get("pid")
            if spid is not None and spid != cur.start.get("pid"):
                continue  # 다른 실행의 종료 기록(늦게 덧붙은 것)은 이 세션을 닫지 않는다
            cur.ended_by = o["ev"]
        cur.recs.append(o)
        cur.end_t = o["t"]
    for s in sessions:
        s.build()
    return sessions


# ---------------------------------------------------------------- 시뮬레이션

class Params:
    def __init__(self, a):
        ms = 1000
        self.W = a.w * ms
        self.T_active = a.t_active * ms
        self.N_drop = a.n_drop
        self.D_min = a.d_min * ms
        self.k = a.k
        self.T_clear = a.t_clear * ms
        self.C_base = a.c_base * ms
        self.C_max = a.c_max * ms
        self.P_active = a.p_active * ms
        self.P_max = a.p_max * ms
        self.N_probe = a.n_probe
        self.T_stable = a.t_stable * ms
        self.B_hour = a.b_hour
        self.B_gap = a.b_gap * ms
        self.T_settle = a.t_settle * ms
        self.T_call_grace = a.t_call_grace * ms
        self.raw = a

    def describe(self):
        a = self.raw
        return ("W=%gs T_active=%gs N_drop=%d D_min=%gs/k=%d T_clear=%gs C=%g~%gs P_active=%gs P_max=%gs "
                "N_probe=%d T_stable=%gs B=%d/h,%gs T_settle=%gs T_call_grace=%gs" % (
                    a.w, a.t_active, a.n_drop, a.d_min, a.k, a.t_clear, a.c_base, a.c_max, a.p_active,
                    a.p_max, a.n_probe, a.t_stable, a.b_hour, a.b_gap, a.t_settle, a.t_call_grace))


def is_active(o, p):
    s = o.get("sinceDataMs")
    return isinstance(s, (int, float)) and 0 <= s <= p.T_active


class Sim:
    """DESIGN 5.5.2 상태기계를 관찰 기록에 다시 돌린다(쓰기 없음).

    보류(HOLD) 사유: 통화 중·통화 종료 후 유예, 화면 꺼짐(관측 공백), 로밍, USER 외 사유의 NR 제한(가드 8), 듀얼 SIM.
      - 보류가 시작되면 특징 창을 비운다. 보류 중 사건은 창에 넣지 않는다. 풀리면 새 창으로 시작한다.
      - 재시험 중이면 끊김 수를 비우고 평가 창을 멈춘다. 풀린 시각부터 평가 창을 다시 연다. 걸려 있던 실패 판정도 버린다.
    전환하려는 순간에만 보는 가드: 서비스 없음, 예산(시간당 횟수·최소 간격).
    해석(DESIGN 5.5.2 "Phase 1 시뮬레이션", 🧪):
      1) 서비스 없음은 창을 비우지 않는다. 복구 때 창 만료를 반영해 다시 판정한다.
      2) 재시험 실패 판정이 서비스 없음·예산에 막히면 판정을 유지하고, 풀리는 때 LTE로 내린다.
    """

    def __init__(self, p, sess):
        self.p = p
        self.use = sess.use
        subs = sess.start.get("activeSubs")
        self.dual = isinstance(subs, int) and subs > 1
        self.state = "INACTIVE"
        self.level = 0
        self.switches = []            # (t, "lte"|"nr")
        self.drops = deque()          # (t, dwellMs)
        self.oos = deque()
        self.last_bad = None
        self.watch_since = None
        self.stable_since = None
        self.now = None               # 마지막으로 처리한 시각(판정은 이보다 과거로 가지 않는다)
        self.cool_until = None
        self.cool_start = None
        self.cool_ivs = []
        self.eval_start = None        # 재시험 평가 창 시작. 보류 중에는 None(멈춤)
        self.quiet_until = None       # 사용자 모드 변경 직후의 조용한 구간 끝(망 재접속 끊김을 세지 않음)
        self.settle_end = None        # 재시험 전환 뒤 정착 구간 끝(이보다 앞선 사건은 판정에 쓰지 않음)
        self.probe_drops = 0
        self.probe_oos = 0
        self.pending = None           # 재시험 실패 판정은 났고 전환만 막힌 상태: ("cooldown", 원인, 레벨 증가)
        self.retry_at = None
        self.hold = None              # 현재 보류 사유
        self.screen = None
        self.call = 0
        self.call_end = None
        self.in_service = True
        self.roaming = False
        self.wifi = False             # 기본 인터넷 경로가 Wi-Fi(셀룰러 데이터를 쓰지 않음) → 보류
        self.wifi_restores = 0        # 쉬는 중 Wi-Fi 연결로 원래 모드(5G 우선)로 되돌린 횟수(재시험 아님)
        self.restrict = {}            # USER 외 사유 -> 마지막 값의 NR 허용 여부(알림이 온 사유만)
        self.triggers = Counter()
        self.probes = Counter()
        self.suppressed = Counter()
        self.holds = Counter()
        self.log = []

    # -- 보조
    def to(self, t, new, why):
        if new == self.state:
            return
        if self.state == "COOLDOWN" and self.cool_start is not None:
            self.cool_ivs.append((self.cool_start, t))
            self.cool_start = None
        if new == "COOLDOWN":
            self.cool_start = t
        if new in ("GOOD", "WATCH") and self.state not in ("GOOD", "WATCH"):
            self.stable_since = t
        if new == "WATCH":
            self.watch_since = t
        self.log.append((t, self.state, new, why))
        self.state = new

    def clear(self):
        self.drops.clear()
        self.oos.clear()

    def hold_reason(self, t):
        if self.state == "INACTIVE":
            return None
        if self.call != 0 or (self.call_end is not None and t - self.call_end < self.p.T_call_grace):
            return "call"
        if self.wifi:
            return "wifi"
        if self.screen is not True:
            return "screen_off"
        if self.roaming:
            return "roaming"
        if any(v is False for v in self.restrict.values()):
            return "restricted"   # USER 외 사유들의 교집합에 NR 없음(DESIGN 5.6.6 3번)
        if self.dual:
            return "dual_sim"
        return None

    def guard(self, t):
        why = self.hold_reason(t)
        if why:
            return why
        if not self.in_service:
            return "no_service"
        recent = [s for s, _ in self.switches if t - s < 3600_000]
        if len(recent) >= self.p.B_hour:
            return "budget_hour"
        if self.switches and t - self.switches[-1][0] < self.p.B_gap:
            return "budget_gap"
        return None

    def retry_time(self, why, t):
        """시간이 지나면 풀리는 가드면 풀리는 시각."""
        if why == "call" and self.call == 0 and self.call_end is not None:
            return self.call_end + self.p.T_call_grace
        if why == "budget_gap":
            return self.switches[-1][0] + self.p.B_gap
        if why == "budget_hour":
            recent = sorted(s for s, _ in self.switches if t - s < 3600_000)
            return recent[len(recent) - self.p.B_hour] + 3600_000
        return None

    def sync_hold(self, t):
        why = self.hold_reason(t)
        if why == "wifi" and self.state == "COOLDOWN":
            # Wi-Fi에 붙으면 LTE로 쉬게 해 둔 것을 사용자의 원래 모드(5G 우선)로 되돌리고 경계 상태로 쉰다(사용자 결정)
            self.switches.append((t, "nr"))
            self.wifi_restores += 1
            self.clear()
            self.pending = self.retry_at = None
            self.last_bad = None
            self.to(t, "WATCH", "wifi_restore")
            why = self.hold_reason(t)
        if why and not self.hold:
            self.hold = why
            self.holds[why] += 1
            self.clear()
            if self.state == "PROBE":
                self.probe_drops = self.probe_oos = 0
                self.eval_start = None
                self.pending = self.retry_at = None
        elif not why and self.hold:
            self.hold = None
            self.clear()
            if self.state == "PROBE" and self.eval_start is None:
                self.eval_start = max(t, self.settle_end)  # 정착 구간을 앞당기지 않는다
            self.retry_after_event(t)
        elif why and why != self.hold:
            self.hold = why
            self.holds[why] += 1

    def prune(self, t):
        while self.drops and t - self.drops[0][0] > self.p.W:
            self.drops.popleft()
        while self.oos and t - self.oos[0] > self.p.W:
            self.oos.popleft()

    def cause(self, t):
        self.prune(t)
        if len(self.drops) >= self.p.N_drop:
            return "drops"
        if len(self.oos) >= 1:
            return "oos"
        dw = [d for _, d in self.drops if isinstance(d, (int, float)) and d >= 0]
        if len(dw) >= self.p.k and statistics.median(dw) < self.p.D_min:
            return "dwell"
        return None

    # -- 전환 시도
    def try_cooldown(self, t, cause, level_inc):
        why = self.guard(t)
        if why:
            self.suppressed[why] += 1
            if self.state == "PROBE" and (why == "no_service" or why.startswith("budget")):
                self.pending = ("cooldown", cause, level_inc)       # 해석 2: 판정 유지
                self.retry_at = self.retry_time(why, t)
            elif self.state == "PROBE":
                self.pending = self.retry_at = None
                self.probe_drops = self.probe_oos = 0
            elif why != "no_service":
                self.clear()  # 보류 후 특징 창을 비우고 새로 시작(DESIGN 5.5.2). 서비스 없음은 해석 1
            return False
        self.pending = self.retry_at = None
        self.level += level_inc
        self.switches.append((t, "lte"))
        self.cool_until = t + min(self.p.C_base * (2 ** self.level), self.p.C_max)
        self.triggers[cause] += 1
        self.clear()
        self.to(t, "COOLDOWN", cause)
        return True

    def try_probe(self, t):
        why = self.guard(t)
        if why:
            self.retry_at = self.retry_time(why, t)
            return False
        self.retry_at = None
        self.switches.append((t, "nr"))
        self.settle_end = t + self.p.T_settle
        self.eval_start = self.settle_end
        self.probe_drops = self.probe_oos = 0
        self.to(t, "PROBE", "cooldown_end")
        return True

    def quiet(self, t):
        return self.quiet_until is not None and t < self.quiet_until

    def retry_after_event(self, t):
        if self.state == "COOLDOWN" and t >= self.cool_until:
            self.try_probe(t)
        elif self.pending:
            _, cause, inc = self.pending
            self.try_cooldown(t, cause, inc)

    # -- 시간 흐름
    def deadlines(self):
        c = []
        if self.hold == "call" and self.call == 0 and self.call_end is not None:
            c.append((self.call_end + self.p.T_call_grace, "hold_check"))
        if self.state == "WATCH":
            c.append((max(self.watch_since, self.last_bad or 0) + self.p.T_clear, "clear"))
        if self.state in ("GOOD", "WATCH") and self.level > 0 and self.stable_since is not None:
            c.append((max(self.stable_since, self.last_bad or 0) + self.p.T_stable, "stable"))
        if self.state == "COOLDOWN":
            c.append((max(self.cool_until, self.retry_at or 0), "probe"))
        if self.state == "PROBE":
            if self.pending:
                if self.retry_at is not None:
                    c.append((self.retry_at, "retry"))
            elif self.eval_start is not None:
                c.append((self.eval_start + self.p.P_max, "p_max"))
                done = time_to_accumulate(self.use, self.eval_start, self.p.P_active)
                if done is not None:
                    c.append((done, "p_pass"))
        return sorted(c)

    def snapshot(self):
        return (self.state, self.level, self.retry_at, self.pending, self.cool_until, self.hold, self.eval_start)

    def advance(self, t):
        for _ in range(10000):
            ds = [d for d in self.deadlines() if d[0] <= t]
            if not ds:
                return
            when, what = ds[0]
            if self.now is not None:
                when = max(when, self.now)  # 이미 지난 시각의 상태로 과거를 판정하지 않는다
            self.now = when
            before = self.snapshot()
            if what == "hold_check":
                self.sync_hold(when)
            elif what == "clear":
                self.to(when, "GOOD", "t_clear")
                self.last_bad = None
            elif what == "stable":
                self.level = 0
                self.stable_since = when
            elif what == "probe":
                if not self.try_probe(when) and self.retry_at is None:
                    return  # 사건(화면 켜짐·통화 종료·제한 해제 등)을 기다린다
            elif what == "retry":
                _, cause, inc = self.pending
                if not self.try_cooldown(when, cause, inc) and self.retry_at is None:
                    return
            elif what == "p_max":
                self.probes["undecided"] += 1
                self.to(when, "WATCH", "probe_undecided")
                self.last_bad = None
            elif what == "p_pass":
                self.probes["pass"] += 1
                self.to(when, "WATCH", "probe_pass")
                self.last_bad = None
            if before == self.snapshot():
                return  # 진전 없음(안전장치)

    # -- 사건
    def feed(self, o):
        t, ev = o["t"], o["ev"]
        self.advance(t)
        self.now = t
        if ev == "mode":
            nr = bool(o.get("nr"))
            if nr and self.state == "INACTIVE":
                self.level = 0
                self.clear()
                self.last_bad = None
                self.to(t, "WATCH", "activate")
                if o.get("from") != "start":
                    # 사용자가 모드를 바꾼 직후 망 재접속으로 데이터가 잠깐 끊긴다(기기 실측) → 정착 시간 동안 세지 않는다
                    self.quiet_until = t + self.p.T_settle
            elif not nr and self.state != "INACTIVE":
                self.pending = self.retry_at = None
                self.to(t, "INACTIVE", "mode_lte")
            self.sync_hold(t)
            return
        if ev == "screen":
            self.screen = bool(o.get("on"))
            self.sync_hold(t)
            return
        if ev == "call":
            st = o.get("state", 0)
            if st == 0 and self.call != 0:
                self.call_end = t
            self.call = st
            self.sync_hold(t)
            return
        if ev == "ss":
            self.roaming = bool(o.get("roaming"))
            self.sync_hold(t)
            return
        if ev == "net":
            self.wifi = bool(o.get("wifi"))
            self.sync_hold(t)
            return
        if ev == "allowed":
            r = o.get("reason")
            if isinstance(r, int) and r != 0:
                self.restrict[r] = bool(o.get("nr"))
            self.sync_hold(t)
            return
        if ev == "oos":
            self.in_service = False
            if self.state == "INACTIVE" or o.get("screen") is not True or self.hold or self.quiet(t):
                return
            if self.state == "PROBE":
                if self.eval_start is not None and t >= self.eval_start and not self.pending:
                    self.probe_oos += 1
                    self.try_cooldown(t, "probe_oos", 1)
            elif self.state in ("GOOD", "WATCH"):
                self.oos.append(t)
                self.last_bad = t
                self.to(t, "WATCH", "oos")
                c = self.cause(t)
                if c:
                    self.try_cooldown(t, c, 0)  # 서비스 없음으로 막힘 → 복구 때 다시 판정(해석 1)
            return
        if ev == "service":
            self.in_service = True
            if self.hold:
                return
            if self.pending or self.state == "COOLDOWN":
                self.retry_after_event(t)
            elif self.state == "WATCH":
                c = self.cause(t)  # 창에서 만료된 사건은 여기서 빠진다
                if c:
                    self.try_cooldown(t, c, 0)
            return
        if ev == "nr_off":
            if (self.state == "INACTIVE" or o.get("screen") is not True or not is_active(o, self.p)
                    or self.hold or self.quiet(t)):
                return
            if self.state == "PROBE":
                if self.eval_start is not None and t >= self.eval_start and not self.pending:
                    self.probe_drops += 1
                    if self.probe_drops >= self.p.N_probe:
                        self.try_cooldown(t, "probe_drops", 1)
                return
            if self.state in ("GOOD", "WATCH"):
                self.drops.append((t, o.get("dwellMs")))
                self.last_bad = t
                self.to(t, "WATCH", "active_drop")
                c = self.cause(t)
                if c:
                    self.try_cooldown(t, c, 0)

    def finish(self, t):
        self.advance(t)
        if self.state == "COOLDOWN" and self.cool_start is not None:
            self.cool_ivs.append((self.cool_start, t))
            self.cool_start = None


# ---------------------------------------------------------------- 명령: report

def pct(a, b):
    return "%.0f%%" % (100.0 * a / b) if b else "-"


def hours(ms):
    return "%.1f시간" % (ms / 3600_000.0)


def minutes(ms):
    return "%.1f분" % (ms / 60_000.0)


def quartiles(xs):
    xs = sorted(xs)
    if not xs:
        return None
    if len(xs) < 4:
        return xs[0], statistics.median(xs), xs[-1]
    q = statistics.quantiles(xs, n=4)
    return q[0], q[1], q[2]


SINCE_BUCKETS = [(0, "0초(활동 중)"), (1000, "~1초"), (2000, "~2초"), (5000, "~5초"),
                 (10000, "~10초"), (30000, "~30초"), (None, "30초 넘음")]

# 전환 1회 비용 참고값: research 2.2, 각 1회 측정(일반화 금지)
COST = {"lte": (2.23, 3.36), "nr": (3.10, 7.06)}  # (ping 공백 초, IMS 미등록 초)


def load_records(args):
    if args.file:
        paths = [Path(f) for f in args.file]
    else:
        base = LOCAL_LOGS
        if args.serial:
            paths = [default_dir(args.serial) / MERGED_NAME]
        else:
            paths = sorted(base.glob("*/" + MERGED_NAME))
            if len(paths) > 1:
                raise NrctlError("기기별 기록이 여러 개다. --serial 또는 --file로 고른다: %s" % ", ".join(map(str, paths)))
    if not paths:
        raise NrctlError("가져온 기록이 없다. 먼저 pull 한다.")
    recs, bad = [], 0
    for p in paths:
        if not p.is_file():
            raise NrctlError("기록 파일이 없다: %s  (먼저 pull)" % p)
        r, b = parse_lines(p.read_text(encoding="utf-8", errors="replace"))
        recs += r
        bad += b
    recs.sort(key=lambda o: o["t"])
    return recs, bad, paths


def cmd_report(args):
    recs, bad, paths = load_records(args)
    p = Params(args)
    sessions = split_sessions(recs)
    print("기록: %s (%d줄%s)" % (", ".join(str(x) for x in paths), len(recs), ", 읽지 못한 줄 %d" % bad if bad else ""))
    if not sessions:
        print("start 기록이 없다.")
        return 0

    obs = scr = scr5 = use5 = nr5 = nruse5 = 0
    offs = []           # 5G 우선 모드·화면 켜짐 중 nr_off
    oos = []
    services = []
    thermal_max = -1
    first_kinds = set()
    allowed_cb = set()
    allowed_recs = Counter()
    errors = Counter()
    ended = Counter()
    sims = []
    controls = []
    for s in sessions:
        obs += s.span[1] - s.span[0]
        scr += total(s.screen)
        scr5_iv = intersect(s.screen, s.mode)
        scr5 += total(scr5_iv)
        use5_iv = intersect(s.use, s.mode)
        use5 += total(use5_iv)
        nr5 += total(intersect(s.nr, scr5_iv))
        nruse5 += total(intersect(s.nr, use5_iv))
        ended[s.ended_by or "기록 끝(실행 중이거나 기록 없이 종료)"] += 1
        for o in s.recs:
            ev = o["ev"]
            if ev == "nr_off" and o.get("screen") is True and inside(s.mode, o["t"]):
                o = dict(o)
                on_at = o["t"] - o["dwellMs"] if isinstance(o.get("dwellMs"), (int, float)) and o["dwellMs"] >= 0 else None
                o["_dwell_ok"] = on_at is not None and covered(s.screen, on_at, o["t"])
                offs.append(o)
            elif ev == "oos" and o.get("screen") is True and inside(s.mode, o["t"]):
                oos.append(o)
            elif ev == "service" and o.get("screen") is True and inside(s.mode, o["t"]):
                services.append(o)
            elif ev == "thermal" and isinstance(o.get("status"), int):
                thermal_max = max(thermal_max, o["status"])
            elif ev == "first":
                first_kinds.add(o.get("kind"))
            elif ev == "registered":
                allowed_cb.add(bool(o.get("allowedCb")))
            elif ev == "allowed":
                allowed_recs[o.get("reason")] += 1
            elif ev in ("error", "fatal", "warn"):
                errors[ev] += 1
        if s.start.get("mode") == "control":
            controls.append(s)  # 제어 중 기록에는 컨트롤러 자신의 전환이 섞여 "전환했다면" 계산에 쓰지 않는다
            continue
        sim = Sim(p, s)
        for o in s.recs:
            sim.feed(o)
        sim.finish(s.span[1])
        sims.append((s, sim))

    print()
    print("== 관찰 범위 ==")
    print("세션 %d개(종료: %s), 관찰 %s, 화면 켜짐 %s, 그중 5G 우선 모드 %s" % (
        len(sessions), ", ".join("%s %d" % kv for kv in ended.items()), hours(obs), hours(scr), hours(scr5)))
    print("5G 우선 모드·화면 켜짐 중: 데이터 사용 %s, 5G 연결 %s, 데이터 사용 중 5G 연결 %s(데이터 사용의 %s)" % (
        minutes(use5), minutes(nr5), minutes(nruse5), pct(nruse5, use5)))
    if scr5 == 0:
        print("※ 5G 우선 모드였던 화면 켜짐 시간이 없다. 끊김 통계와 시뮬레이션은 5G 우선 모드 기록이 있어야 나온다.")

    print()
    print("== 5G 끊김 (5G 우선 모드·화면 켜짐) ==")
    act = [o for o in offs if is_active(o, p)]
    print("끊김 %d회, 그중 데이터 사용 중 끊김(직전 %g초 안에 데이터 활동) %d회, 데이터 사용 1시간당 %s회" % (
        len(offs), args.t_active, len(act), ("%.1f" % (len(act) / (use5 / 3600_000.0))) if use5 else "-"))
    buckets = Counter()
    for o in offs:
        s_ = o.get("sinceDataMs")
        if not isinstance(s_, (int, float)) or s_ < 0:
            buckets["기록 없음"] += 1
            continue
        for lim, name in SINCE_BUCKETS:
            if lim is None or s_ <= lim:
                buckets[name] += 1
                break
    if offs:
        order = [n for _, n in SINCE_BUCKETS] + ["기록 없음"]
        print("끊길 때 직전 데이터 활동 간격: " + ", ".join("%s %d" % (n, buckets[n]) for n in order if buckets[n]))
    dw = [o["dwellMs"] / 1000.0 for o in act if o["_dwell_ok"]]
    q = quartiles(dw)
    if q:
        print("데이터 사용 중 끊김 직전 5G 유지 시간: 중앙값 %.1f초 (하위 25%% %.1f초, 상위 25%% %.1f초, 표본 %d)" % (
            q[1], q[0], q[2], len(dw)))
    outs = [o["outMs"] / 1000.0 for o in services if isinstance(o.get("outMs"), (int, float))]
    print("데이터 서비스 끊김(OOS) %d회, 그중 데이터 사용 중 %d회%s" % (
        len(oos), sum(1 for o in oos if is_active(o, p)),
        (", 복구까지 중앙값 %.1f초" % statistics.median(outs)) if outs else ""))

    print()
    if controls:
        print_control_summary(controls)
        print()
    print("== \"전환했다면\" 시뮬레이션 (관찰 전용 실행만, 쓰기 없음) ==")
    print("기준값: " + p.describe())
    trig, probes, supp, holds = Counter(), Counter(), Counter(), Counter()
    sw = Counter()
    cool = cool_scr = 0
    avoided_off = avoided_act = lost_nr_use = 0
    wifi_restores = 0
    for s, sim in sims:
        wifi_restores += sim.wifi_restores
        trig += sim.triggers
        probes += sim.probes
        supp += sim.suppressed
        holds += sim.holds
        sw.update(k for _, k in sim.switches)
        civ = merge_iv(sim.cool_ivs)
        cool += total(civ)
        cool_scr += total(intersect(civ, s.screen))
        lost_nr_use += total(intersect(intersect(s.nr, s.use), civ))
        for o in s.recs:
            if o["ev"] == "nr_off" and o.get("screen") is True and after_start_inside(civ, o["t"]):
                avoided_off += 1
                avoided_act += 1 if is_active(o, p) else 0
    print("LTE로 내림 %d회(원인: %s), 5G 재시험 %d회(통과 %d, 판정 불가 %d, 실패 후 다시 LTE %d), "
          "Wi-Fi 연결로 원래 모드 되돌림 %d회" % (
              sw["lte"], ", ".join("%s %d" % kv for kv in trig.items()) or "-", sw["nr"] - wifi_restores,
              probes["pass"], probes["undecided"], trig["probe_drops"] + trig["probe_oos"], wifi_restores))
    gap = sum(COST[k][0] * n for k, n in sw.items())
    ims = sum(COST[k][1] * n for k, n in sw.items())
    print("전환 %d회의 추정 비용(1회 측정값 기준 참고치): 인터넷 공백 약 %.0f초, 통화망(IMS) 미등록 약 %.0f초" % (
        sum(sw.values()), gap, ims))
    print("쿨다운(LTE 유지) %s, 그중 화면 켜짐 %s" % (minutes(cool), minutes(cool_scr)))
    print("쿨다운 중 실제로 일어난 5G 끊김 %d회(데이터 사용 중 %d회) - 컨트롤러가 있었다면 5G를 끄고 있어 없었을 끊김" % (
        avoided_off, avoided_act))
    print("대가: 쿨다운 중 잃은 '데이터 사용 중 5G 연결' %s" % minutes(lost_nr_use))
    if holds:
        print("보류 시작(사유별): " + ", ".join("%s %d" % kv for kv in holds.most_common()))
    if supp:
        print("전환 조건이 됐지만 막힘(가드별): " + ", ".join("%s %d" % kv for kv in supp.most_common()))
    if args.verbose:
        for s, sim in sims:
            for t, a, b, why in sim.log:
                print("  %s  %s -> %s (%s)" % (fmt_time(t), a, b, why))

    print()
    print("== 참고 ==")
    print("첫 알림 수신 종류: %s" % (", ".join(sorted(k for k in first_kinds if k)) or "-"))
    print("허용 타입 알림 구독 등록: %s / 실제 받은 변경 알림: %s" % (
        "/".join("됨" if x else "안 됨" for x in sorted(allowed_cb)) or "-",
        ", ".join("사유 %s %d건" % kv for kv in sorted(allowed_recs.items(), key=lambda kv: str(kv[0]))) or "없음"))
    print("발열 단계 최대: %s" % (thermal_max if thermal_max >= 0 else "-"))
    if errors:
        print("오류 기록: " + ", ".join("%s %d" % kv for kv in errors.items()))
    print("시뮬레이션 규칙(DESIGN 5.5.2 'Phase 1 시뮬레이션'): 5G 우선 모드·화면 켜짐 기록만 쓴다. "
          "통화(종료 후 유예 포함)·Wi-Fi(기본 인터넷 경로)·화면 꺼짐·로밍·USER 외 사유의 NR 제한·듀얼 SIM은 보류다. "
          "쉬는 중 Wi-Fi에 붙으면 원래 모드로 되돌리고 경계 상태로 쉰다(DESIGN §5.11 결정 17). 보류가 시작되면 창을 비우고, "
          "보류 중 사건은 세지 않으며, 재시험 평가 창은 보류가 풀린 시각부터 다시 연다. "
          "해석(🧪) 1: 서비스 없음은 창을 비우지 않고 복구 때 창 만료를 반영해 다시 판정한다. "
          "해석 2: 재시험 실패 판정이 서비스 없음·예산에 막히면 판정을 유지하고 풀릴 때 LTE로 내린다. "
          "재시험 정착 구간은 실제 전환이 없으므로 T_settle 고정값이다.")
    return 0


def print_control_summary(controls):
    """제어 모드 세션의 실제 전환·비용·보류를 요약한다(조정용 근거)."""
    ok, fail, costs, oos_ms, user_costs = Counter(), Counter(), [], [], []
    legacy = 0
    holds, supp, states, safe, users, mismatch, ind = Counter(), Counter(), Counter(), 0, 0, 0, Counter()
    cool = 0
    for s in controls:
        cool_from = None
        for o in s.recs:
            ev = o["ev"]
            if ev in ("s2_begin", "w_begin"):
                why = o.get("why")
            elif ev in ("s2_ok", "w_ok"):
                ok[why] += 1
            elif ev in ("s2_cmd_failed", "s2_refused", "s2_rolled_back", "s2_rollback_failed", "s2_adopted",
                        "w_cmd_failed", "w_refused", "w_adopted", "w_key_changed"):
                fail[ev] += 1
            elif ev == "switch_cost":
                if "recoverMs" not in o or "outMs" not in o:
                    legacy += 1  # 이전 형식(정착 시계 수정 전 기록): 값이 달라 섞지 않는다
                    continue
                costs.append(o["recoverMs"] / 1000.0)
                oos_ms.append(o["outMs"] / 1000.0)
            elif ev == "mode_change_cost":
                user_costs.append(o.get("outMs", 0) / 1000.0)
            elif ev == "hold":
                holds[o.get("why")] += 1
            elif ev == "suppressed":
                supp[o.get("why")] += 1
            elif ev == "state":
                states[o.get("to")] += 1
                if o.get("to") == "COOLDOWN":
                    cool_from = o["t"]
                elif cool_from is not None:
                    cool += o["t"] - cool_from
                    cool_from = None
                if o.get("to") == "SAFE_STOP":
                    safe += 1
            elif ev == "user_mode":
                users += 1
            elif ev == "display_mismatch":
                mismatch += 1
            elif ev == "indicator":
                ind[o.get("result")] += 1
        if cool_from is not None:
            cool += s.span[1] - cool_from
    print("== 실제 제어 (제어 모드 세션 %d개) ==" % len(controls))
    to_nr = ("probe", "restore", "stop", "wifi_restore")  # 5G 우선으로 쓰는 쓰기
    align = sum(n for w, n in ok.items() if w and w.startswith("align_"))  # 사용자 선택(설정 화면)에 맞춘 쓰기(DESIGN 5.12)
    downs = [(w, n) for w, n in ok.items() if w not in to_nr and not (w and w.startswith("align_"))]
    lte = sum(n for _, n in downs)
    print("LTE로 내림 %d회(원인: %s), 5G로 재시험 %d회, 원래 모드로 되돌림 %d회(종료 %d, Wi-Fi 연결 %d 포함),"
          " 사용자 설정에 맞춤 %d회" % (
              lte, ", ".join("%s %d" % kv for kv in downs) or "-",
              ok["probe"], ok["restore"] + ok["stop"] + ok["wifi_restore"], ok["stop"], ok["wifi_restore"], align))
    print("LTE로 쉰 시간 %s, 사용자 모드 선택 감지 %d회, 안전 정지 %d회" % (minutes(cool), users, safe))
    if costs:
        print("컨트롤러 전환 뒤 인터넷 다시 붙기까지: 중앙값 %.1f초(최대 %.1f초, 표본 %d), 끊긴 시간 중앙값 %.1f초" % (
            statistics.median(costs), max(costs), len(costs), statistics.median(oos_ms)))
    if legacy:
        print("이전 형식 전환 비용 기록 %d건은 집계에서 뺐다(정착 시계 수정 전 값)" % legacy)
    if user_costs:
        print("사용자 모드 변경 뒤 끊긴 시간: 중앙값 %.1f초(표본 %d) - 이 구간 끊김은 판정에 세지 않았다" % (
            statistics.median(user_costs), len(user_costs)))
    if fail:
        print("쓰기 문제: " + ", ".join("%s %d" % kv for kv in fail.items()))
    if holds:
        print("보류 시작(사유별): " + ", ".join("%s %d" % kv for kv in holds.most_common()))
    if supp:
        print("전환 조건이 됐지만 막힘(가드별): " + ", ".join("%s %d" % kv for kv in supp.most_common()))
    if mismatch:
        print("설정 화면과 실제 모드 불일치 감지 %d회(컨트롤러는 고치지 않고 기록만)" % mismatch)
    if ind:
        print("상단바 표시: " + ", ".join("%s %d" % kv for kv in ind.items()))


# ---------------------------------------------------------------- main

def build_parser():
    ap = argparse.ArgumentParser(prog="nrctl", description="Adaptive NR Controller PC 도구")
    ap.add_argument("--adb", help="adb 실행 파일 경로(기본: 자동 탐지)")
    ap.add_argument("--serial", help="기기 일련번호(여러 대 연결 시)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("doctor", help="기기·권한·현재 모드 점검(읽기 전용)").set_defaults(fn=cmd_doctor)
    sp = sub.add_parser("start", help="컨트롤러 기동(--observe: 기록만)")
    sp.add_argument("--observe", action="store_true", help="설정을 바꾸지 않고 기록만")
    sp.add_argument("--leftover", choices=["restore", "keep"],
                    help="이전 실행이 LTE로 남겼을 때: restore=원래 모드로, keep=지금 LTE를 원래 모드로")
    sp.add_argument("--original", choices=["nr", "lte"], help="상태 파일을 읽을 수 없을 때 원래 모드")
    sp.set_defaults(fn=cmd_start)
    sub.add_parser("stop", help="종료(컨트롤러가 바꿔 둔 모드는 원래대로)").set_defaults(fn=cmd_stop)
    for verb, text in (("pause", "일시정지(쓰기 중단·현재 상태 유지)"), ("resume", "재개"),
                       ("keep-lte", "(쓰지 않음) LTE로 계속 쓰려면 폰 설정에서 LTE 우선을 고른다"),
                       ("test-cooldown", "시험용: 끊김 조건 없이 지금 LTE로 2분 쉬고 재시험(가드는 평소대로)")):
        c = sub.add_parser(verb, help=text)
        c.set_defaults(fn=cmd_command, verb=verb)
    sub.add_parser("install", help="동반 앱 설치(상단바 작은 점 아이콘)").set_defaults(fn=cmd_install)
    rs = sub.add_parser("restore", help="긴급 복구: 데몬 없이 실제 허용 모드를 저장된 사용자 선택(설정 키)에 맞춤")
    rs.add_argument("--yes", action="store_true", help="확인 질문 없이")
    rs.set_defaults(fn=cmd_restore)
    st = sub.add_parser("status", help="실행 여부·최근 기록")
    st.add_argument("--lines", type=int, default=15)
    st.set_defaults(fn=cmd_status)
    pl = sub.add_parser("pull", help="폰 기록을 PC로 가져와 합치기")
    pl.add_argument("--out", help="저장 폴더(기본: logs/<기기>)")
    pl.set_defaults(fn=cmd_pull)
    rp = sub.add_parser("report", help="요약 + '전환했다면' 시뮬레이션")
    rp.add_argument("--file", nargs="+", help="합친 기록 파일(기본: logs/<기기>/%s)" % MERGED_NAME)
    rp.add_argument("--verbose", action="store_true", help="시뮬레이션 상태 변화 목록 출력")
    # DESIGN 5.5.6 초기 가설값(초 단위)
    for name, default in (("w", 120), ("t-active", 2), ("n-drop", 3), ("d-min", 20), ("k", 3), ("t-clear", 300),
                          ("c-base", 300), ("c-max", 3600), ("p-active", 60), ("p-max", 300), ("n-probe", 2),
                          ("t-stable", 600), ("b-hour", 4), ("b-gap", 120), ("t-settle", 10),
                          ("t-call-grace", 30)):
        rp.add_argument("--" + name, type=(int if name in ("n-drop", "k", "n-probe", "b-hour") else float),
                        default=default)
    rp.set_defaults(fn=cmd_report)
    return ap


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")  # 콘솔 인코딩(예: cp949)에 없는 글자로 죽지 않게
        except AttributeError:
            pass
    args = build_parser().parse_args(argv)
    try:
        return args.fn(args)
    except NrctlError as e:
        print("오류: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
