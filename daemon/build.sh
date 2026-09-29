#!/usr/bin/env bash
# nrd(폰 데몬) 빌드: javac -> d8 -> daemon/build/nrd.dex
# macOS·Linux·Windows(Git Bash) 공통. 경로 처리 이유는 phase0/probe/build.sh 머리말 참고.
# 필요: JAVA_HOME(JDK 17), ANDROID_HOME(platforms/android-33, build-tools/37.0.0)
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
SDK="$(to_exe "${ANDROID_HOME:?ANDROID_HOME not set}")"
BUILD_TOOLS="${BUILD_TOOLS:-37.0.0}"
OUT="$HERE/build"

rm -rf "$OUT/classes" "$OUT/stubs"
mkdir -p "$OUT/classes" "$OUT/stubs"

ANDROID_JAR="$(to_arg "$SDK/platforms/android-33/android.jar")"
D8_JAR="$(to_arg "$SDK/build-tools/$BUILD_TOOLS/lib/d8.jar")"
# 람다용 선언(LambdaMetafactory)은 android.jar에 없다 → build-tools의 core-lambda-stubs.jar를 함께 둔다(d8이 람다를 풀어 준다).
LAMBDA_JAR="$(to_arg "$SDK/build-tools/$BUILD_TOOLS/core-lambda-stubs.jar")"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; *) SEP=':' ;; esac
BOOT="$ANDROID_JAR$SEP$LAMBDA_JAR"

sources() { # $1: 폴더 → 인자용 경로 목록(한 줄에 하나)
  find "$1" -name '*.java' | while IFS= read -r f; do to_arg "$f"; done
}

# 1) 숨은 API 선언(컴파일 전용). dex에는 넣지 않는다.
STUB_SRC=(); while IFS= read -r f; do STUB_SRC+=("$f"); done < <(sources "$HERE/stubs")
"$JDK/bin/javac" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -bootclasspath "$BOOT" -d "$(to_arg "$OUT/stubs")" "${STUB_SRC[@]}"

# 2) 데몬
SRC=(); while IFS= read -r f; do SRC+=("$f"); done < <(sources "$HERE/src")
"$JDK/bin/javac" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -bootclasspath "$BOOT" -classpath "$(to_arg "$OUT/stubs")" \
  -d "$(to_arg "$OUT/classes")" "${SRC[@]}"

# 3) dex. 선언용 인터페이스는 --classpath로만 알려 준다(참조만, 포함 안 함).
CLASS_FILES=(); while IFS= read -r f; do CLASS_FILES+=("$(to_arg "$f")"); done < <(find "$OUT/classes" -name '*.class')
"$JDK/bin/java" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 31 --lib "$ANDROID_JAR" \
  --classpath "$(to_arg "$OUT/stubs")" --output "$(to_arg "$OUT")" "${CLASS_FILES[@]}"

mv -f "$OUT/classes.dex" "$OUT/nrd.dex"
echo "built: $OUT/nrd.dex"
