# 점수별 음성 안내 · 0.9.1

변경일: 2026-11-07. versionCode 10 / versionName 0.9.1.

자동 코칭과 기록 상세 화면의 ‘코칭 듣기’에서 `동작명 N점 입니다. + 조언`을 읽는다. 점수/색상은 기존 기준 그대로이며 화면과 음성이 `FeedbackBand`를 공유한다.

| 점수 | 음성 예시 |
| --- | --- |
| 100 · 초록 | 좌회전 100점 입니다. 잘하고 있습니다. |
| 80~99 · 주황 | 유턴 80점 입니다. 너무 빠른 속도로 회전했어요. 유턴 시에는 조금 더 천천히 회전하는 게 좋겠어요. |
| 0~79 · 빨강 | 우회전 60점 입니다. 너무 빠른 운전은 위험할 수 있습니다. 속도를 줄이세요. |

예시의 80/60점은 안내 형식 설명용이다. 실제 음성은 계산된 점수와 가장 큰 감점 원인을 사용한다. 횡가속·급가감속·핸들·주차 시간·전후진 반복·정렬에도 각각 다른 문구가 있다. 점수가 낮다는 이유만으로 과속을 단정하지 않는다. 주차 시간이 길다는 이유로 서두르도록 안내하지 않는다.

100점의 칭찬은 여섯 문장을 무작위 순서로 섞어 순환하며, 모두 사용하기 전에는 반복하지 않는다. 같은 런타임의 다음 세션과 ‘코칭 듣기’도 같은 순환을 사용한다. 오디오 준비 재시도나 변화 없는 보정에서는 이미 고른 문장을 유지한다.

상세 근거 `ManeuverResult.coaching`과 화면용 `FeedbackReceipt.text`는 보존하고 음성 전용 `voiceText`를 추가했다. 과거 기록에 필드가 없으면 기존 문장을 사용한다. 보류 점수에는 기존 보류 설명을 읽는다. 발화 전 보정은 새 점수를 반영하고, 이미 시작한 발화는 다시 재생하지 않는다. 5초 카드 만료·대기 큐·우선 오디오 중단·무음 설정은 기존 동작을 유지한다.

원본: [문구·색상 구간](../core/src/main/kotlin/com/easycar/core/FeedbackSpeech.kt), [게시·보정](../core/src/main/kotlin/com/easycar/core/ImmediateFeedback.kt), [음성 출력](../app/src/main/java/com/example/easycar/runtime/DriveRuntime.kt).

## 검증

에뮬레이터를 종료하고 JDK17, CPU2코어, Gradle 작업자1개, offline 모드에서 다음을 실행했다.

```sh
./gradlew --offline :core:test :app:testPleosDebugUnitTest :app:assemblePleosDebug :app:assemblePleosDebugAndroidTest
```

- 코어99개·앱 단위5개 모두 통과. 실패/오류/스킵0. 새 음성 검사11개는 점수 경계, 세 가지 요청 예시, 칭찬 순환, 모든 동작명/점수, 원인별 문구, 보류, 오디오 재시도, 보정, 중복 게시, 새/구 JSON 호환을 확인한다.
- Pleos APK와 계측 APK 빌드 성공. [빌드 로그](validation/build-voice-feedback-20261107.log).
- CPU2코어/RAM3072MB로 Pleos 에뮬레이터 한 대를 재실행하고 `adb install -r`로 업데이트했다. 설치 버전0.9.1/code10 확인. 기존 앱 데이터를 지우지 않았다.
- 장치 검사2개 통과(115.734초): 실제 우측 상단 데모로 일곱 동작을 완료·저장했고, Pleos 음성 엔진의 준비됨·시험 발화 수락·발화 시작/완료를 확인했다. [장치 로그](validation/device-voice-feedback-20261107.log), [음성 엔진 이벤트](validation/speech-engine-20261107.log).
- 활성 사용자10의 저장 기록에서 일곱 `voiceText`에 실제 동작명과100점이 포함되고, 처음 여섯 칭찬이 모두 다르며 인접 문장이 반복하지 않음을 확인했다. 데모는 무음으로 실행했고 별도의 짧은 음성 엔진 시험은 실제 발화했다. [저장 기록](validation/voice-feedback-device-session-20261107.json), [확인 요약](validation/voice-feedback-device-summary-20261107.json).

APK SHA256: `dceeea9bf98d8e722b289432c21f5feae203e1d1d72dd6a72d215883a1721944`.

음성 문구 변경이며 인식/평가 기준·JSON·프로필 버전과 과거 점수를 변경하지 않았다. 합성 데이터·에뮬레이터 검증이며 실제 도로 검증이나 가청 품질의 청취 평가는 아니다. 기존 인식·평가 검증 범위는 [0.9.0 검증 기록](verification-ko.md)에 있다.
