#!/usr/bin/env bash
# Phase 0 점검 도구 빌드: javac -> d8 -> build/probe.dex
# macOS·Linux·Windows(Git Bash) 공통. d8은 .bat 대신 d8.jar를 java로 직접 실행한다.
# 필요: JAVA_HOME, ANDROID_HOME (platforms/android-33, build-tools/37.0.0)
# Windows: 프로젝트 경로에 한글·'[' ']'가 있어 Git Bash 자동 경로 변환이 깨진다 →
#          실행 파일 경로는 cygpath -u, 도구에 넘기는 인자는 cygpath -m(D:/...)으로 직접 변환한다.
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

if command -v cygpath >/dev/null 2>&1; then
  to_exe() { cygpath -u "$1"; }
  to_arg() { cygpath -m "$1"; }
else
  to_exe() { printf '%s' "$1"; }
  to_arg() { printf '%s' "$1"; }
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="$(to_exe "${JAVA_HOME:?JAVA_HOME not set}")"
SDK="$(to_exe "${ANDROID_HOME:?ANDROID_HOME not set}")"
BUILD_TOOLS="${BUILD_TOOLS:-37.0.0}"
OUT="$HERE/build"

rm -rf "$OUT/classes"
mkdir -p "$OUT/classes"

ANDROID_JAR="$(to_arg "$SDK/platforms/android-33/android.jar")"
D8_JAR="$(to_arg "$SDK/build-tools/$BUILD_TOOLS/lib/d8.jar")"
CLASSES="$(to_arg "$OUT/classes")"

"$JDK/bin/javac" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -d "$CLASSES" "$(to_arg "$HERE/src/nrc/Probe.java")"

CLASS_FILES=()
while IFS= read -r f; do CLASS_FILES+=("$(to_arg "$f")"); done < <(find "$OUT/classes" -name '*.class')

"$JDK/bin/java" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 31 --lib "$ANDROID_JAR" \
  --output "$(to_arg "$OUT")" "${CLASS_FILES[@]}"

mv -f "$OUT/classes.dex" "$OUT/probe.dex"
echo "built: $OUT/probe.dex"
