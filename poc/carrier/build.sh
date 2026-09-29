#!/usr/bin/env bash
# 통신사 권한 PoC 앱 빌드(Gradle 없이): javac → d8 → aapt2 link → dex 추가 → zipalign → apksigner(동반 앱과 같은 디버그 키)
# 산출물: poc/carrier/build/nrc-poc.apk, 서명 인증서 SHA-256 출력
# Windows의 aapt2·zipalign은 한글 경로를 못 열어 영문 임시 폴더에서 빌드한다(app/build.sh와 같음).
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
if command -v cygpath >/dev/null 2>&1; then
  to_exe() { cygpath -u "$1"; }
  to_arg() { cygpath -m "$1"; }
  EXE=.exe
else
  to_exe() { printf '%s\n' "$1"; }
  to_arg() { printf '%s\n' "$1"; }
  EXE=
fi
HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="$(to_exe "${JAVA_HOME:?JAVA_HOME not set}")"
SDK="$(to_exe "${ANDROID_HOME:?ANDROID_HOME not set}")"
BT="$SDK/build-tools/${BUILD_TOOLS:-37.0.0}"
JAR="$(to_arg "$SDK/platforms/android-33/android.jar")"
KS="$HERE/../../app/build/debug.keystore"
[ -f "$KS" ] || { echo "no keystore: run bash app/build.sh first" >&2; exit 1; }
OUT="$HERE/build"
mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp "$HERE/AndroidManifest.xml" "$WORK/"
mkdir -p "$WORK/classes" "$WORK/dex"
W="$(to_arg "$WORK")"

SRC=()
while IFS= read -r f; do SRC+=("$(to_arg "$f")"); done < <(find "$HERE/src" -name '*.java')
"$JDK/bin/javac$EXE" -source 8 -target 8 -encoding UTF-8 -Xlint:-options -cp "$JAR" -d "$W/classes" "${SRC[@]}"
CLS=()
while IFS= read -r f; do CLS+=("$(to_arg "$f")"); done < <(find "$WORK/classes" -name '*.class')
"$JDK/bin/java$EXE" -cp "$(to_arg "$BT/lib/d8.jar")" com.android.tools.r8.D8 --min-api 31 --lib "$JAR" \
  --output "$W/dex" "${CLS[@]}"
"$BT/aapt2$EXE" link -o "$W/unsigned.apk" -I "$JAR" --manifest "$W/AndroidManifest.xml"
(cd "$WORK/dex" && "$JDK/bin/jar$EXE" uf "$W/unsigned.apk" classes.dex)
"$BT/zipalign$EXE" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
"$JDK/bin/java$EXE" -jar "$(to_arg "$BT/lib/apksigner.jar")" sign --ks "$(to_arg "$KS")" --ks-pass pass:android \
  --ks-key-alias nrcdebug --out "$W/signed.apk" "$W/aligned.apk"
cp "$WORK/signed.apk" "$OUT/nrc-poc.apk"
echo "built: $OUT/nrc-poc.apk"
"$JDK/bin/java$EXE" -jar "$(to_arg "$BT/lib/apksigner.jar")" verify --print-certs "$(to_arg "$OUT/nrc-poc.apk")" | grep -i "SHA-256"
