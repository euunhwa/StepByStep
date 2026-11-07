# 실행 검증 기록 · EasyCar v0.2.0

확인일: **2026-11-05, Asia/Seoul**. 사용자 지정 디렉터리 `/Users/genius/AndroidStudioProjects/EasyCar`에서 생성한 APK를 설치해 검사했습니다. 합성 데모/에뮬레이터의 구현 검증이며 실차 평가 정확도나 차량 앱 배포 승인이 아닙니다.

## 최종 결과

| 검사 | 결과 |
|---|---|
| 순수 Kotlin 코어 | **30개 통과**, 오류/실패 0 |
| Pleos Connect v2.1.0 / API 34 계측 | **10개 통과**, 221.749초 |
| 일반 Android API 37 계측 | **10개 통과**, 229.174초 |
| demo/pleos APK 및 계측 APK | 지정한 최종 프로젝트에서 빌드 성공 |
| demo/pleos Lint | 오류 0, 경고 41/44개 |
| Virtual Console localhost | 기존 8088 포워딩 확인, GET / 응답 200 |

JDK17 / Gradle9.6.0 / AGP9.5.0 / Kotlin2.2.10, minSdk28 / targetSdk34 / compileSdk37.0입니다. Pleos는 가로 차량 패널, 일반 Android는 2400×1080 작은 가로 화면에서 확인했습니다. 에뮬레이터 부팅/메모리로 나타난 시스템 응답 지연 창은 재시작·RAM3GB로 환경을 정리한 후 검사했습니다.

## 핵심: 세션 종료 전 실제 화면과 자동 음성

일곱 유형 각각에 대해 기록이 ACTIVE이고 파형 끝 전일 때 카드가 나타나는지 검사했습니다. 조언 문장 전체가 주행 화면 영역 안에 들어오는지도 검사합니다. 실제 TTS onStart/onDone 콜백을 확인했으며 최종 일곱 안내 모두 SPOKEN입니다. 무음에서도 카드가 유지되고, 마지막 음성 대기 중 종료를 눌러도 완료한 결과/요약이 보존됩니다.

아래 단위는 ms입니다. 카드 값은 감지 콜백 후 카드 **데이터 발행**까지, 음성 값은 카드 발행 후 **실제 TTS onStart**까지입니다. 화면 패널의 광학 지연이나 사람이 청취한 스피커 음질 측정은 아닙니다.

| 유형 | Pleos 카드 | Pleos 음성 시작 | Android 카드 | Android 음성 시작 |
|---|---:|---:|---:|---:|
| 좌회전 | 0 | 459 | 0 | 755 |
| 우회전 | 1 | 431 | 0 | 534 |
| 유턴 | 0 | 361 | 1 | 1366 |
| 출발 | 4 | 654 | 1 | 1011 |
| 정지 | 0 | 462 | 9 | 858 |
| T자 주차 | 105 | 518 | 97 | 599 |
| 평행주차 | 466 | 778 | 146 | 720 |

원본 수치: `validation/pleos-immediate-v2.json`, `validation/android-immediate-v2.json`. 당시 카드 스크린샷: `screenshots/pleos-immediate-left-v2.png`, `screenshots/android-immediate-left-v2.png`.

## 함께 검증한 범위

- 전체 주행 한 세션에서 출발 → 좌회전 → 우회전 → 유턴 → 정지 인식. 정답 라벨 없이 동일 엔진의 42종 파형을 재생합니다.
- R→D 가중 감점 0/2/6/12/20/30, 중립 경유·기어 흔들림·반복 콜백, 영상 없어도 확정 횟수 유지, 필수 근거 없으면 종합점수 보류.
- 이탈 크기/길이, 감점 상한·합계, 단계별 실제 근거, 진입 전 감속을 회전 중 감속으로 오인하지 않음, 신호 공백·지연·역순·잡음과 낮은 heading 처리.
- 출발/정지는 핸들 없이 관측. 핸들 수신 시각으로 센서 조향 미분을 꾸미지 않음. 정차한 P 상태의 돌아간 핸들을 회전 중으로 오인해 음성을 막지 않음.
- 실제 MP4 디코딩 → 실제 픽셀 선 검출·회귀 → 각도/침범·가림 보류 → 재생/오버레이. 영상 탐색 완료를 기다린 뒤 재생합니다.
- 탑뷰 보정 JSON·수치/범위·종횡비, 축소 픽셀과 차량 외곽 비례 보정, 다중 칸 미선택 보류와 사용자 선택 후 실제 두 선 분석.
- Room 저장/재시작/중단 복원/영상 삭제, 늦은 저장 콜백이 삭제한 기록을 복원하지 않음. Activity를 내리거나 재생성해도 같은 세션과 단일 결과 유지.
- 기본 6초 음성 간격/무음/중복/만료/포커스 거부/회전·후진 억제. 실제 Android 한국어 TTS와 Pleos ON_DEVICE TTS의 준비·시작·완료.

## 검증 자료와 재현

최종 로그는 `validation/build-lint-core-v2.log`, `validation/pleos-instrumentation-v2.log`, `validation/android-instrumentation-v2.log`, `validation/core-v2-TEST-*.xml`입니다. `scenario-audit-v2.log`에는 42종의 세션 종료 전 이벤트 시각이 있습니다. APK 위치와 해시는 `validation/apk-sha256.txt`에 기록합니다. 이름에 v2가 없는 기존 로그는 이전 버전의 기록입니다.

```sh
./gradlew :core:test :core:auditScenarios :app:assembleDemoDebug :app:assemblePleosDebug :app:lintDemoDebug :app:lintPleosDebug
./gradlew :app:assembleDemoDebugAndroidTest :app:assemblePleosDebugAndroidTest
adb -s <serial> shell am instrument -w <applicationId>.test/androidx.test.runner.AndroidJUnitRunner
```

## 수행하지 못한 검증·외부 조건

- 실제 차종/코스의 전문가 기준, 핸들 부호·센서 측정 시각, FusedLocation timestampMillis 시계/신뢰도 의미와 장기 신호 품질. 실제 heading은 검증 전 평가 보류이며 합성으로 대체하지 않습니다.
- OEM 안전 경고·실제 내비·통화의 동시 통합 시험, Android 밖의 경고 오디오 경로, 스피커 음질·음량의 사람 청취 시험. 공개 Android 재생 상태/실제 포커스로 제공하는 경로와 구분합니다.
- 실제 내장 어라운드뷰 승인/녹화/종료 전 프레임 보존, 렌즈·스티칭·실차 거리 보정 정확도. 사용자 sidecar의 실측 정확도와 모든 외부 파일 형식별 기기 호환성은 별도입니다.
- Gleo 앱 스키마 등록/action/callback/권한, 배포 카테고리/DO/specialUse 심사, 부팅 자동 시작/상시 실행과 실제 내비 전면의 장기 성능 시험.
- 실제 CarUxRestrictions 변화의 통합 시험은 대기입니다. 정차 UX 수신과 보수적 잠금·합성 제한 시나리오는 검증했지만, 공식 예시 `hmg_car_service`는 해당 이미지에 없어 실제 규제 변경 검증으로 소개하지 않습니다.

Lint의 기존 API34 대상/최신 dependency 추천/미사용 리소스 경고가 남습니다. Pleos TTS 전이 JNA x86_64 라이브러리의 16KB 정렬 경고도 남으며, 현재 검증은 API34 Pleos arm64입니다. 외부 게시·스토어 제출·실차 제어 시험을 하지 않았습니다.


최종 전체 검사 후 서비스 검사를 한 번씩 다시 실행하여 시험용 무음을 해제하고 앱을 기본 홈으로 다시 열었습니다. 추가 검사도 두 기기에서 통과했습니다. 설치된 APK와 전달 파일의 SHA-256 일치는 `validation/installed-apk-v2.txt`에 기록했으며, 이 정리 과정에서 앱 APK는 변경하지 않았습니다.
