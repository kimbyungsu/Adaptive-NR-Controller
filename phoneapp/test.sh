#!/usr/bin/env bash
# 제품 앱의 안드로이드 없는 부분 시험(PC). 필요: JAVA_HOME(JDK 17)
# 대상: 판단 엔진(Policy·Params·UseSegments, daemon에서 옮김), 타일 표시 규칙(TileText), 통신사 칸 계획(CarrierPlan),
#       관측 화면 글(NowText·Words·DaySummary), 무선 디버깅 페어링 암호(Ed25519·Spake2·PairCrypto·AdbKey)
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then to_arg() { cygpath -m "$1"; }; JDK="$(cygpath -u "${JAVA_HOME:?}")"; EXE=.exe
else to_arg() { printf '%s\n' "$1"; }; JDK="${JAVA_HOME:?}"; EXE=; fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
SRC=()
for f in src/nrc/controller/Params.java src/nrc/controller/Policy.java src/nrc/controller/UseSegments.java \
         src/nrc/controller/TileText.java src/nrc/controller/CarrierPlan.java \
         src/nrc/controller/Words.java src/nrc/controller/DaySummary.java \
         src/nrc/controller/Live.java src/nrc/controller/NowText.java test/nrc/controller/NowTextTest.java \
         test/nrc/controller/PolicyTest.java test/nrc/controller/UseSegmentsTest.java \
         test/nrc/controller/TileTextTest.java test/nrc/controller/CarrierPlanTest.java \
         test/nrc/controller/ObserveTest.java          src/nrc/controller/Ed25519.java src/nrc/controller/Spake2.java src/nrc/controller/PairCrypto.java          src/nrc/controller/AdbKey.java test/nrc/controller/AdbCryptoTest.java          src/nrc/controller/StartSteps.java test/nrc/controller/StartStepsTest.java \n         src/nrc/controller/SupportCheck.java test/nrc/controller/SupportCheckTest.java; do
  [ -f "$HERE/$f" ] && SRC+=("$(to_arg "$HERE/$f")")
done
"$JDK/bin/javac$EXE" -encoding UTF-8 -d "$(to_arg "$OUT")" "${SRC[@]}"
for t in UseSegmentsTest PolicyTest TileTextTest CarrierPlanTest ObserveTest NowTextTest AdbCryptoTest StartStepsTest SupportCheckTest; do
  [ -f "$HERE/test/nrc/controller/$t.java" ] || continue
  echo "== $t"
  "$JDK/bin/java$EXE" -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$(to_arg "$OUT")" "nrc.controller.$t" | tail -2
done
