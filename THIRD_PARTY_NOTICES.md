# 제3자 구성요소 고지 (Third-Party Notices)

이 저장소는 아래 제3자 소프트웨어를 포함하거나(벤더링된 바이너리), 그 소스에서 옮긴(이식한) 코드를 담고 있습니다.
각 구성요소의 원저작권과 라이선스를 아래에 고지합니다. Apache License 2.0 전문은
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) 에 있습니다.

구성요소 구분:
- **벤더링 바이너리**(`poc/shizuku/libs/*.jar`, `phoneapp/libs/*.jar`): §1 Shizuku, §2 AndroidHiddenApiBypass(PoC만), §3 AndroidX Annotation
- 제품 앱(phoneapp) 설치 파일에는 §1 Shizuku API 클래스가 들어가고, 이 고지문과 Apache-2.0 전문이 앱 안(assets)에 함께 담긴다(빌드가 복사).
- **소스 이식**(phoneapp 내 Java로 옮긴 코드): §4 AOSP adb·libcrypto_utils, §5 BoringSSL SPAKE2
- **공개 표준의 독자 구현**(제3자 코드 아님, 참고용 기재): §6

---

## 1. Shizuku API (shizuku-api, shizuku-aidl, shizuku-shared, shizuku-provider)

- 포함 위치: `poc/shizuku/libs/` 및 `phoneapp/libs/`의 `shizuku-api.jar`, `shizuku-aidl.jar`, `shizuku-shared.jar`, `shizuku-provider.jar`
  (제품 앱은 이 클래스를 설치 파일에 포함해 길 2 Shizuku 방식에 쓴다)
- 프로젝트: RikkaApps/Shizuku-API — https://github.com/RikkaApps/Shizuku-API
- 라이선스: MIT License

```
MIT License

Copyright (c) 2021 RikkaW

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. AndroidHiddenApiBypass

- 포함 위치: `poc/shizuku/libs/hiddenapibypass.jar` (org.lsposed.hiddenapibypass)
- 프로젝트: LSPosed/AndroidHiddenApiBypass — https://github.com/LSPosed/AndroidHiddenApiBypass
- 라이선스: Apache License, Version 2.0 (전문: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt))

```
Copyright (C) 2021-2025 LSPosed

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 3. AndroidX Annotation (androidx.annotation)

- 포함 위치: `poc/shizuku/libs/annotation.jar`, `phoneapp/libs/annotation.jar` (컴파일에만 쓰고 설치 파일에는 넣지 않음)
- 프로젝트: The Android Open Source Project — https://developer.android.com/jetpack/androidx/releases/annotation
- 라이선스: Apache License, Version 2.0 (전문: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt))

```
Copyright (C) The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 4. AOSP — Android Debug Bridge(adb) 및 libcrypto_utils (소스 이식)

phoneapp 의 무선 디버깅 페어링/접속 코드 일부는 AOSP `packages/modules/adb` 와
`system/core/libcrypto_utils` 의 구현을 Java 로 옮긴(이식한) 것입니다.

- 영향 받은 파일(이 저장소):
  - `phoneapp/src/nrc/controller/AdbPair.java` — adb 페어링 클라이언트
  - `phoneapp/src/nrc/controller/PairCrypto.java` — 페어링 암호(AES-128-GCM, 패킷/HKDF)
  - `phoneapp/src/nrc/controller/AdbExec.java` — adb 전송 프로토콜(CNXN/STLS/OPEN/WRTE/…)
  - `phoneapp/src/nrc/controller/AdbKey.java` — adb 공개키 포맷(android_pubkey)·RSA 키 처리
- 이식 출처(AOSP 원본):
  - `packages/modules/adb/adb.cpp` — Copyright (C) 2007 The Android Open Source Project
  - `packages/modules/adb/pairing_connection/pairing_connection.cpp` — Copyright (C) 2020 The Android Open Source Project
  - `packages/modules/adb/pairing_auth/aes_128_gcm.cpp` — Copyright (C) 2020 The Android Open Source Project
  - `packages/modules/adb/crypto/rsa_2048_key.cpp` — Copyright (C) 2019 The Android Open Source Project
  - `system/core/libcrypto_utils/android_pubkey.c` — Copyright (C) The Android Open Source Project
  - 원본: https://android.googlesource.com/platform/packages/modules/adb/ ,
    https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcrypto_utils/
- 라이선스: Apache License, Version 2.0 (전문: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt))

```
Copyright (C) 2007-2020 The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 5. BoringSSL — SPAKE2 (소스 이식)

- 영향 받은 파일: `phoneapp/src/nrc/controller/Spake2.java` 는 BoringSSL 의
  `crypto/curve25519/spake25519` (SPAKE2 over edwards25519) 구현을 Java 로 옮긴(이식한) 것입니다.
  Android `adb` 페어링이 쓰는 그 구현과 같은 상수(N·M 시드, password scalar hack)를 따릅니다.
- 프로젝트: BoringSSL — https://boringssl.googlesource.com/boringssl/ (미러: https://github.com/google/boringssl)
- 원저작권: Copyright (c) 2016, Google Inc. / Copyright 2016 The BoringSSL Authors
- 라이선스에 관한 사실: BoringSSL 의 이 파일은 **과거 판본에서는 아래 ISC 계열 고지**로,
  **현재 판본에서는 Apache License 2.0** 으로 배포됩니다(프로젝트가 재라이선스함). 본 이식본이 참고한
  정확한 상류 판본(커밋)은 기록돼 있지 않으므로, 어느 판본을 기준으로 보더라도 원 고지를 보존하도록
  두 고지를 모두 싣습니다(Apache-2.0 전문: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)).
  참고 판본 예: ISC 판본 `boringssl cdccbe1 crypto/curve25519/spake25519.c`,
  현재 판본 `google/boringssl main crypto/curve25519/spake25519.cc`.

과거 판본(ISC 계열) 고지:

```
Copyright (c) 2016, Google Inc.

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION
OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

현재 판본(재라이선스 후, Apache-2.0) 고지:

```
Copyright 2016 The BoringSSL Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 6. 공개 표준의 독자 구현 (참고 — 제3자 코드 아님)

아래 파일은 제3자 소스 코드를 옮긴 것이 아니라 **공개 표준/사양을 보고 직접 구현**한 것입니다.
저작권 고지 의무 대상은 아니며, 출처 표준만 참고로 밝힙니다.

- `phoneapp/src/nrc/controller/Ed25519.java` — IETF **RFC 8032 §5.1**(edwards25519 점 연산)의 식을 직접 구현.
  주석의 "BoringSSL `x25519_sc_reduce` 와 같은 값"은 결과값 교차 확인용 언급일 뿐, 코드 이식이 아닙니다.
