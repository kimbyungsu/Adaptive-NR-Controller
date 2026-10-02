#!/usr/bin/env bash
# 제품 앱 빌드(Gradle 없이): aapt2 compile/link(R.java 생성) → javac → d8 → dex 추가 → zipalign → apksigner
# 산출물: phoneapp/build/nrc-controller.apk, 서명 인증서 SHA-256 출력
# 서명 키: app/build/debug.keystore(이 PC 전용 개발 키, 저장소 밖). 배포 전에는 별도 배포 키가 필요하다
#   (통신사 설정 덧붙임 항목이 인증서 해시에 묶이므로 키를 바꾸면 PC 설치를 다시 해야 한다).
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
# 서명 키: 기본은 개발 키(app/build/debug.keystore). 배포 키로 서명하려면 환경변수로 고른다(기본 동작은 그대로):
#   RELEASE_KEYSTORE=<경로> RELEASE_ALIAS=<별칭> RELEASE_STOREPASS=<암호> [RELEASE_KEYPASS=<키암호>] bash phoneapp/build.sh
# 배포 키는 저장소 밖에 둔다(.gitignore의 *.jks/*.keystore). 키를 바꾸면 인증서 해시가 달라져 폰 재등록이 필요하다.
if [ -n "${RELEASE_KEYSTORE:-}" ]; then
  KS="$RELEASE_KEYSTORE"
  KS_ALIAS="${RELEASE_ALIAS:?RELEASE_ALIAS not set}"
  KS_STOREPASS="${RELEASE_STOREPASS:?RELEASE_STOREPASS not set}"
  KS_KEYPASS="${RELEASE_KEYPASS:-$KS_STOREPASS}"
  SIGN_KIND="release"
else
  KS="$HERE/../app/build/debug.keystore"
  KS_ALIAS="nrcdebug"
  KS_STOREPASS="android"
  KS_KEYPASS="android"
  SIGN_KIND="debug"
fi
[ -f "$KS" ] || { echo "no keystore: $KS (개발 키는 bash app/build.sh 먼저)" >&2; exit 1; }
OUT="$HERE/build"
mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/res" "$HERE/AndroidManifest.xml" "$WORK/"
[ -d "$HERE/assets" ] && cp -r "$HERE/assets" "$WORK/"
ASSETS=()
[ -d "$WORK/assets" ] && ASSETS=(-A "$(to_arg "$WORK/assets")")
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"
W="$(to_arg "$WORK")"

"$BT/aapt2$EXE" compile --dir "$W/res" -o "$W/res.zip"
"$BT/aapt2$EXE" link -o "$W/unsigned.apk" -I "$JAR" --manifest "$W/AndroidManifest.xml" \
  --java "$W/gen" "${ASSETS[@]}" "$W/res.zip"

SRC=()
while IFS= read -r f; do SRC+=("$(to_arg "$f")"); done < <(find "$HERE/src" "$WORK/gen" -name '*.java')
"$JDK/bin/javac$EXE" -source 8 -target 8 -encoding UTF-8 -Xlint:-options -cp "$JAR" -d "$W/classes" "${SRC[@]}"
CLS=()
while IFS= read -r f; do CLS+=("$(to_arg "$f")"); done < <(find "$WORK/classes" -name '*.class')
"$JDK/bin/java$EXE" -cp "$(to_arg "$BT/lib/d8.jar")" com.android.tools.r8.D8 --min-api 31 --lib "$JAR" \
  --output "$W/dex" "${CLS[@]}"
(cd "$WORK/dex" && "$JDK/bin/jar$EXE" uf "$W/unsigned.apk" classes.dex)
"$BT/zipalign$EXE" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
"$JDK/bin/java$EXE" -jar "$(to_arg "$BT/lib/apksigner.jar")" sign --ks "$(to_arg "$KS")" --ks-pass "pass:$KS_STOREPASS" \
  --key-pass "pass:$KS_KEYPASS" --ks-key-alias "$KS_ALIAS" --out "$W/signed.apk" "$W/aligned.apk"
cp "$WORK/signed.apk" "$OUT/nrc-controller.apk"
echo "built: $OUT/nrc-controller.apk (sign=$SIGN_KIND)"
"$JDK/bin/java$EXE" -jar "$(to_arg "$BT/lib/apksigner.jar")" verify --print-certs "$(to_arg "$OUT/nrc-controller.apk")" | grep -i "SHA-256"
