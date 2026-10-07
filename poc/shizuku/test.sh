#!/usr/bin/env bash
# no-wifi 시작하기 화면의 안드로이드 없는 부분 시험(PC). 필요: JAVA_HOME(JDK 17)
# 대상: ProbeSteps(단계 판정 + 재부팅 관측 기록 판정)
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then to_arg() { cygpath -m "$1"; }; JDK="$(cygpath -u "${JAVA_HOME:?}")"; EXE=.exe
else to_arg() { printf '%s\n' "$1"; }; JDK="${JAVA_HOME:?}"; EXE=; fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$JDK/bin/javac$EXE" -encoding UTF-8 -d "$(to_arg "$OUT")" \
  "$(to_arg "$HERE/src/nrc/shizupoc/ProbeSteps.java")" "$(to_arg "$HERE/test/nrc/shizupoc/ProbeStepsTest.java")"
"$JDK/bin/java$EXE" -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$(to_arg "$OUT")" nrc.shizupoc.ProbeStepsTest
