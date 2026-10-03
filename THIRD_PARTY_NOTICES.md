# 제3자 구성요소 고지 (Third-Party Notices)

이 저장소는 아래 제3자 소프트웨어를 포함하거나(벤더링), 그 소스에서 옮긴(이식한) 코드를 담고 있습니다.
각 구성요소의 원저작권과 라이선스를 아래에 고지합니다. Apache License 2.0 전문은
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) 에 있습니다.

---

## 1. Shizuku API (shizuku-api, shizuku-aidl, shizuku-shared, shizuku-provider)

- 포함 위치: `poc/shizuku/libs/shizuku-api.jar`, `shizuku-aidl.jar`, `shizuku-shared.jar`, `shizuku-provider.jar`
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

- 포함 위치: `poc/shizuku/libs/annotation.jar`
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

## 4. BoringSSL — SPAKE2 (이식 출처)

- 영향 받은 파일: `phoneapp/src/nrc/controller/Spake2.java` 는 BoringSSL 의
  `crypto/curve25519/spake25519` (SPAKE2 over edwards25519) 구현을 Java 로 옮긴(이식한) 것입니다.
  Android `adb` 페어링이 쓰는 그 구현과 같은 상수(N·M 시드, password scalar hack)를 따릅니다.
- 프로젝트: BoringSSL — https://boringssl.googlesource.com/boringssl/ (미러: https://github.com/google/boringssl)
- 원저작권: Copyright (c) 2016, Google Inc. / The BoringSSL Authors
- 라이선스: 이식 당시 원본 파일은 아래 ISC 계열 고지로 배포되었고, 이후 BoringSSL 프로젝트는
  동일 파일을 Apache License 2.0 으로 재라이선스했습니다. 어느 쪽으로 보더라도 본 이식본은
  원 고지를 보존하는 조건으로 사용됩니다(Apache-2.0 전문: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)).

이식 당시(ISC 계열) 원본 헤더:

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

현재 BoringSSL 원본 헤더(재라이선스 후):

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
