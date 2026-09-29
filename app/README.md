# 동반 앱 (nrc.companion)

지금은 **상단바 작동 표시용 작은 점 아이콘만** 담고 있다(실행 코드 없음, 약 9KB). 상단바는 아이콘을 "패키지 + 리소스 번호"로 불러오므로, 데몬이 이 패키지의 점을 지정한다(DESIGN §5.8·§5.11). 알림·빠른설정 타일·데몬 부재 감지는 이후 단계에서 이 앱에 더한다.

```bash
bash app/build.sh                 # → app/build/nrc-companion.apk, app/build/R.txt(점 아이콘 번호)
python pc/nrctl.py install        # 폰에 설치 → 다음 start부터 점 사용
```
- 점: `res/drawable/nrc_dot.xml`. 좁은 틀(12×24) 가운데 지름 7의 원만 그려, 나머지를 투명 여백으로 둬서 작게 보이게 한다. 상단바가 한 가지 색으로 칠하므로 색은 의미가 없다.
- 서명: 이 PC에서 만든 디버그 키(`app/build/debug.keystore`). 다른 PC에서 빌드하면 키가 달라 기존 설치 위에 덮어쓸 수 없다(먼저 제거).
- Windows의 aapt2·zipalign은 한글이 든 경로를 열지 못해 영문 임시 폴더에서 빌드한다.
