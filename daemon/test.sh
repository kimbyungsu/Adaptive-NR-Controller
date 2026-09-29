#!/usr/bin/env bash
# 안드로이드 의존이 없는 데몬 부품 시험(JDK만 필요): UseSegments, Policy, Actuator(가짜 폰), Saved(상태 파일 규칙), UserMode(설정 키 = 사용자 선택)
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
if command -v cygpath >/dev/null 2>&1; then
  to_exe() { cygpath -u "$1"; }
  to_arg() { cygpath -m "$1"; }
else
  to_exe() { printf '%s\n' "$1"; }
  to_arg() { printf '%s\n' "$1"; }
fi
HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="$(to_exe "${JAVA_HOME:?JAVA_HOME not set}")"
OUT="$HERE/build/test"
rm -rf "$OUT"
mkdir -p "$OUT"
SRC=()
for f in src/nrc/UseSegments.java src/nrc/Params.java src/nrc/Policy.java src/nrc/Log.java src/nrc/Actuator.java \
         src/nrc/Saved.java src/nrc/UserMode.java \
         test/nrc/UseSegmentsTest.java test/nrc/PolicyTest.java test/nrc/ActuatorTest.java test/nrc/SavedTest.java \
         test/nrc/UserModeTest.java; do
  SRC+=("$(to_arg "$HERE/$f")")
done
"$JDK/bin/javac" -encoding UTF-8 -d "$(to_arg "$OUT")" "${SRC[@]}"
for t in nrc.UseSegmentsTest nrc.PolicyTest nrc.ActuatorTest nrc.SavedTest nrc.UserModeTest; do
  echo "== $t"
  "$JDK/bin/java" -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$(to_arg "$OUT")" "$t"
done
