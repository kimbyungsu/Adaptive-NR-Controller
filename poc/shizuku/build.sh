#!/usr/bin/env bash
# Shizuku 경로 PoC 빌드(Gradle 없이): 공식 Shizuku-API aar를 받아 classes.jar를 꺼내 함께 dex에 넣는다.
#   aapt2 link(manifest only) → javac → d8(+shizuku jars) → zipalign → apksigner(개발 키)
# 산출물: poc/shizuku/build/nrc-shizuku.apk. 서명 키는 app/build/debug.keystore(동반 앱과 같은 개발 키).
# Windows aapt2/zipalign은 한글 경로를 못 열어 영문 임시 폴더에서 빌드한다.
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
if command -v cygpath >/dev/null 2>&1; then
  to_exe() { cygpath -u "$1"; }
  to_arg() { cygpath -m "$1"; }
  EXE=.exe
  SEP=';'
else
  to_exe() { printf '%s\n' "$1"; }
  to_arg() { printf '%s\n' "$1"; }
  EXE=
  SEP=':'
fi
HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="$(to_exe "${JAVA_HOME:?JAVA_HOME not set}")"
SDK="$(to_exe "${ANDROID_HOME:?ANDROID_HOME not set}")"
BT="$SDK/build-tools/${BUILD_TOOLS:-37.0.0}"
JAR="$(to_arg "$SDK/platforms/android-33/android.jar")"
KS="$HERE/../../app/build/debug.keystore"
[ -f "$KS" ] || { echo "no keystore: run bash app/build.sh first" >&2; exit 1; }

# --- 공식 Shizuku-API 부품 받아서 classes.jar 꺼내기(없을 때만) ---
LIBS="$HERE/libs"
mkdir -p "$LIBS"
SHIZUKU_VER=13.1.5
fetch_aar() { # name
  local n="$1" out="$LIBS/shizuku-$1.jar"
  [ -f "$out" ] && return 0
  local url="https://repo1.maven.org/maven2/dev/rikka/shizuku/$n/$SHIZUKU_VER/$n-$SHIZUKU_VER.aar"
  echo "fetch $n.aar"; curl -fsSL -o "$LIBS/$n.aar" "$url"
  local tmp; tmp="$(mktemp -d)"; (cd "$tmp" && unzip -oq "$LIBS/$n.aar" classes.jar) && cp "$tmp/classes.jar" "$out"; rm -rf "$tmp"
}
for n in api aidl shared provider; do fetch_aar "$n"; done
if [ ! -f "$LIBS/annotation.jar" ]; then
  echo "fetch annotation.jar"
  curl -fsSL -o "$LIBS/annotation.jar" "https://maven.google.com/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar"
fi
# HiddenApiBypass(LSPosed, Apache-2.0): 앱 프로세스가 ITelephony 숨은 메서드를 찾게 해 준다. 저장소에 vendored.
[ -f "$LIBS/hiddenapibypass.jar" ] || { echo "need poc/shizuku/libs/hiddenapibypass.jar (vendored)" >&2; exit 1; }
SHIZUKU_JARS=("$LIBS/shizuku-api.jar" "$LIBS/shizuku-aidl.jar" "$LIBS/shizuku-shared.jar" "$LIBS/shizuku-provider.jar" "$LIBS/hiddenapibypass.jar")
CP_LIBS="$(to_arg "$LIBS/annotation.jar")"
for j in "${SHIZUKU_JARS[@]}"; do CP_LIBS="$CP_LIBS$SEP$(to_arg "$j")"; done

OUT="$HERE/build"
mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$HERE/AndroidManifest.xml" "$WORK/"
mkdir -p "$WORK/classes" "$WORK/dex"
W="$(to_arg "$WORK")"

# R.java 불필요(res 없음). manifest만 링크.
"$BT/aapt2$EXE" link -o "$W/unsigned.apk" -I "$JAR" --manifest "$W/AndroidManifest.xml" --min-sdk-version 29

# AIDL 도구는 한글 경로를 못 열어, UserService 인터페이스(INrObserver)는 생성 코드를 src/에 직접 작성해 둔다.
SRC=()
while IFS= read -r f; do SRC+=("$(to_arg "$f")"); done < <(find "$HERE/src" -name '*.java')
"$JDK/bin/javac$EXE" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -cp "$JAR$SEP$CP_LIBS" -d "$W/classes" "${SRC[@]}"

# d8: 우리 클래스 + Shizuku 런타임 클래스(jar)를 함께 dex. android.jar와 annotation은 --lib(패키징 안 함).
CLS=()
while IFS= read -r f; do CLS+=("$(to_arg "$f")"); done < <(find "$WORK/classes" -name '*.class')
D8_INPUTS=("${CLS[@]}")
for j in "${SHIZUKU_JARS[@]}"; do D8_INPUTS+=("$(to_arg "$j")"); done
"$JDK/bin/java$EXE" -cp "$(to_arg "$BT/lib/d8.jar")" com.android.tools.r8.D8 --min-api 29 \
  --lib "$JAR" --lib "$(to_arg "$LIBS/annotation.jar")" --output "$W/dex" "${D8_INPUTS[@]}"

(cd "$WORK/dex" && "$JDK/bin/jar$EXE" uf "$W/unsigned.apk" classes.dex)
"$BT/zipalign$EXE" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
"$JDK/bin/java$EXE" -jar "$(to_arg "$BT/lib/apksigner.jar")" sign --ks "$(to_arg "$KS")" --ks-pass pass:android \
  --ks-key-alias nrcdebug --out "$W/signed.apk" "$W/aligned.apk"
cp "$WORK/signed.apk" "$OUT/nrc-shizuku.apk"
echo "built: $OUT/nrc-shizuku.apk"
"$JDK/bin/java$EXE" -jar "$(to_arg "$BT/lib/apksigner.jar")" verify --print-certs "$(to_arg "$OUT/nrc-shizuku.apk")" | grep -i "SHA-256"
