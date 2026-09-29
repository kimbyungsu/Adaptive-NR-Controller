#!/usr/bin/env bash
# 제품 앱의 안드로이드 없는 부분 시험(PC). 필요: JAVA_HOME(JDK 17)
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then to_arg() { cygpath -m "$1"; }; JDK="$(cygpath -u "${JAVA_HOME:?}")"; EXE=.exe
else to_arg() { printf '%s\n' "$1"; }; JDK="${JAVA_HOME:?}"; EXE=; fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$JDK/bin/javac$EXE" -encoding UTF-8 -d "$(to_arg "$OUT")" \
  "$(to_arg "$HERE/src/nrc/controller/MarkStyle.java")" "$(to_arg "$HERE/test/nrc/controller/MarkStyleTest.java")"
"$JDK/bin/java$EXE" -cp "$(to_arg "$OUT")" nrc.controller.MarkStyleTest
