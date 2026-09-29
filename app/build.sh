#!/usr/bin/env bash
# 동반 앱 빌드(리소스만, Gradle 없이): aapt2 compile/link → zipalign → apksigner(디버그 키)
# 산출물: app/build/nrc-companion.apk, app/build/R.txt(점 아이콘 리소스 번호)
# 필요: JAVA_HOME(JDK 17), ANDROID_HOME(platforms/android-33, build-tools/37.0.0)
# Windows의 aapt2·zipalign은 한글이 든 경로를 열지 못한다(이 프로젝트 폴더에서 실측) → 영문 임시 폴더에서 빌드하고 결과만 가져온다.
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
OUT="$HERE/build"
mkdir -p "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/res" "$HERE/AndroidManifest.xml" "$WORK/"
W="$(to_arg "$WORK")"

"$BT/aapt2$EXE" compile --dir "$W/res" -o "$W/res.zip"
"$BT/aapt2$EXE" link -o "$W/unsigned.apk" -I "$(to_arg "$SDK/platforms/android-33/android.jar")" \
  --manifest "$W/AndroidManifest.xml" --output-text-symbols "$W/R.txt" "$W/res.zip"
"$BT/zipalign$EXE" -f 4 "$W/unsigned.apk" "$W/aligned.apk"

# 서명: 이 PC 전용 디버그 키(없으면 만든다). 저장소 밖으로 내보내지 않는다.
KS="$OUT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool$EXE" -genkeypair -keystore "$(to_arg "$KS")" -storepass android -keypass android \
    -alias nrcdebug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NR Controller Debug" >/dev/null
fi
"$JDK/bin/java$EXE" -jar "$(to_arg "$BT/lib/apksigner.jar")" sign --ks "$(to_arg "$KS")" --ks-pass pass:android \
  --ks-key-alias nrcdebug --out "$W/signed.apk" "$W/aligned.apk"
cp "$WORK/signed.apk" "$OUT/nrc-companion.apk"
cp "$WORK/R.txt" "$OUT/R.txt"
echo "built: $OUT/nrc-companion.apk"
grep nrc_dot "$OUT/R.txt"
