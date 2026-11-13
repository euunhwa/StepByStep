# Gleo 저장 기록 연동 · 0.12.0

> 최신 화면은 [현재 UI 캡처](ui-screenshots-ko.md), 최신 시연은 [영상 안내](../media/README.md)에서 확인할 수 있습니다. 아래 화면·로그는 0.12.0 당시의 검증 근거로 보존합니다.

2026-11-07 · 앱0.12.0/code15. 점수·인식 규칙은 변경하지 않는다.

## 앱에서 사용하는 방법

1. 정차 후 `기록`에서 연습 하나를 열고 `저장 기록에 질문하기`로 이동한다.
2. `이 기록을 Gleo에 연결`을 켠다. 연결은 한 기록에만 적용되며 앱 재시작 후에도 유지된다.
3. Gleo에게 `이지카 최근 주차 결과 알려줘`, `이지카 주차 감점 이유 알려줘`, `이지카 이번 연습 요약해 줘`라고 질문한다.
4. 같은 화면의 스위치나 설정의 `Gleo 기록 연결 해제`에서 해제한다. 연결된 기록을 삭제하거나 시작 동의를 철회하면 연결도 해제된다.

연결을 켜면 음성 질문에 응답하기 위해 선택한 기록의 저장 점수·평가 근거·요약을 Gleo에 전달한다. 영상 파일, 원시 차량 신호, 사용자 제목은 전달하지 않는다. Gleo 자체의 음성·클라우드 처리 정책은 EasyCar의 로컬 저장 정책과 별개다. 앱 음성 끄기 설정은 Gleo callback에도 `mode=silent`로 반영한다.

## 실제 연결 코드

| 단계 | 구현 |
|---|---|
| 스키마 | [Pleos APK assets/schema.json](../app/src/pleos/assets/schema.json), `invokeWord=이지카`, 함수3개, `component=service` |
| Gleo 요청 | Pleos 전용 exported `GleoRecordReceiver`; 함수명과 `com.example.easycar.함수명` 두 action 형식 모두 수신 |
| 호출 권한 | Android가 Gleo 소유의 `ai.umos.gleo.permission.APP_ACTION` 서명 권한을 요구함. 일반 앱·shell 호출은 차단 |
| 파라미터 | 개별 String extras 또는 `ACTION_PARAM` JSON, 예시의 1회 문자열 인코딩 허용. 혼용·중복 키·다른 목적지·알 수 없는 필드는 거부 |
| 실제 관찰된 호환 | Gleo4.2.0.15는 action 앞에 앱 패키지를 붙이고 선택 항목을 빈 문자열로 보냄. 외부 어댑터에서 빈 선택값을 생략으로 처리하며 내부 ID 규격은 유지 |
| 조회 | 실제 Room 저장 기록의 읽기 전용 snapshot. 외부 sessionId는 사용자가 연결한 범위를 늘릴 수 없음 |
| 응답 직전 검사 | 연결·동의·UX 제한·코칭 진행·삭제·기록 변경을 다시 검사. 바뀐 기록의 지난 답변은 보내지 않음 |
| 콜백 | `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE`, extras `packageName`, `response`; 대상은 `ai.umos.ambientai`로 고정 |
| 수신자 제한 | callback 수신 앱에도 Gleo 서명 권한을 요구. Gleo 권한의 소유자·보호 수준·보유 여부 확인 후 전송 |
| 음성 | 정상 요청은 `mode=voice`; 앱 음성 끄기는 `silent`. EasyCar TTS로 같은 답변을 중복 발화하지 않음 |
| 제한 | 처리5초 제한(공식 Gleo7초 제한보다 짧게 설정), 초기 UX 확인1.8초, callback UTF-8 48KiB. 초과하면 실패 응답으로 전체 설명을 앱에서 확인하도록 안내 |

`service`는 Android Service를 실행한다는 의미가 아니라 공식 Gleo 명세의 `sendBroadcast` 방식이다. 일반 `demo` APK에는 Gleo schema와 exported receiver를 포함하지 않는다. 서명 권한 이름과 Gleo 패키지는 이번 에뮬레이터에서 확인한 값이다. 다른 OEM 빌드에서 식별자가 바뀌면 권한 제한을 풀지 말고 공식 계약을 확인해 어댑터를 갱신한다.

## 함수와 저장 결과 보존

| 함수 | 선택 파라미터 | 의미 |
|---|---|---|
| `GET_LAST_RESULT` | sessionId, maneuverType | 선택된 기록의 최신 동작. `PARKING`은 T자·평행·유형 미확정 주차를 모두 포함 |
| `EXPLAIN_RESULT` | sessionId, resultId, maneuverType | 특정 저장 동작 또는 선택 종류의 최신 동작의 실제 점수·감점 설명 |
| `SUMMARIZE_SESSION` | sessionId | 관측된 장점·반복 감점·보류·복기 항목. 다른 평가 버전을 섞어 평균 내지 않음 |

보류·미완료·과거 버전·관측 누락은 저장된 상태 그대로 반환한다. 최신 결과가 보류됐다고 과거의 좋은 결과로 대체하지 않는다. `PARKING`도 최신 유형 미확정 주차의 보류를 그대로 반환한다. 재채점, 점수 변경, 영상·위치·원시 신호 전송 경로는 없다. 내부 모의 호출 도구는 계속 앱 내부에서만 실행된다.

## 등록과 배포

[공식 전달 절차](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)에 따라 `assets/schema.json`을 APK에 포함했다. Gleo의 스키마 읽기, Android 전달, 실제 음성 요청의 처리는 각각 따로 검증한다. 개발 에뮬레이터의 동작이 Pleos App Market 정식 심사·승인을 의미하지 않는다. 승인 상태를 임의로 완료 처리하거나 Gleo 내부 데이터베이스·서명 권한을 우회하지 않는다.

실제 요청·콜백 규격의 근거는 [App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability), [Intent/Callback 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples), [Callback Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)다. 실제 관찰된 접두어·빈 선택값 차이는 위 표에 명시했다.

## 개발 검증과 재현

Gradle과 에뮬레이터를 동시에 실행하지 않는다. 먼저 에뮬레이터를 종료하고 다음을 실행한다.

```sh
./gradlew :core:test :app:testPleosDebugUnitTest :app:assemblePleosDebug :app:assemblePleosDebugAndroidTest :app:lintPleosDebug :app:assembleDemoDebug
```

에뮬레이터 한 대(CPU2코어/RAM3072MB)를 시작하고 두 Pleos APK를 `adb install -r`로 설치한 뒤 관련 장치18개만 명시해 실행한다. 전체 자동 수집에는 콘솔 촬영용 수동 테스트도 포함되므로 다음의 class 목록을 사용한다.

```sh
adb shell am instrument --user 10 -w -e class com.example.easycar.GleoTransportTest,com.example.easycar.RecordQueryStorageTest,com.example.easycar.RecordQueryScreenTest,com.example.easycar.RecordQueryFlowTest,com.example.easycar.ParkingVideoAnalysisTest,com.example.easycar.ParkingVideoGalleryTest,com.example.easycar.ParkingVideoReviewTest,com.example.easycar.PersistenceAndVideoTest com.example.easycar.test/androidx.test.runner.AndroidJUnitRunner
```

일반 전송 검사는 명시적인 합성 기록을 Room에 저장한 다음 같은 UID의 Android broadcast로 수신기를 검사한다. 실제 Gleo에 callback을 보내지만 그 자체가 음성 요청의 성공은 아니다. 대기 중인 Gleo는 요청 없이 도착한 callback을 거부할 수 있다. `SENT`는 Android 전송 성공을 뜻하며 음성 발화나 Gleo의 응답 수용 확인이 아니다.

실제 음성 경로는 opt-in `GleoVoiceProbe`로 별도 검사한다. 임시 합성 기록을 연결한 후, SDK의 공식 `EmulatorController/injectAudio`에 macOS Yuna로 만든 시험 문장만 전달한다. 호스트 마이크는 켜지 않는다. 검사 종료 후 임시 기록·연결·음성 설정을 복원한다. [마이크 주입 도구](../tools/gleo_microphone_probe.py)는 SDK의 `emulator_controller.proto`로 생성한 Python 모듈, `grpcio`가 필요하다. 에뮬레이터 discovery ini의 정상 인증 토큰을 사용하며 토큰은 출력·저장소에 포함하지 않는다. [Android 에뮬레이터 gRPC 안내](https://developer.android.com/studio/run/emulator-console)를 참조한다.

검증 결과·APK 해시·실제 음성 경로의 성공 여부는 [0.12 검증 요약](validation/gleo-integration-summary-v012.json)에 기록한다. 단순 모의 전송의 성공을 실제 음성 인식·답변 성공으로 합산하지 않는다.

## 최종 확인 결과

| 검사 | 결과 | 근거 |
|---|---|---|
| 코어 / 앱 JVM | 164개 / 14개 통과, 실패·오류·스킵0 | [빌드 로그](validation/build-gleo-integration-v012.log), [집계](validation/gleo-integration-summary-v012.json) |
| Pleos 장치 회귀 | 18개 통과, 28.782초 | [장치 로그](validation/device-gleo-integration-v012.log) |
| Android Lint | 오류0, 전체 프로젝트 경고36; Gleo 소스 경고0 | 검증 요약 및 로컬 `app/build/reports/lint-results-pleosDebug.html` |
| APK | Pleos/장치 검사/Demo 빌드 성공. Pleos 스키마 원본 일치. Demo 스키마·수신기 미포함 | 검증 요약 |
| 수신·권한·전송 | bare/qualified action, 개별/JSON extras, 빈 선택값, 악성 필드, 비허가 shell 호출 차단, 삭제 시 연결 해제 확인 | [전송 근거](validation/gleo-transport-v012.json), [Android 전달 이력](validation/gleo-android-delivery-v012.log) |
| 실제 음성 결과 조회 | 합성 질문을 실제 Gleo가 인식→GET_LAST_RESULT 선택→실제 수신기/Room 조회→Thinking 상태에서 callback 수용→85점·전환4회·감점15점 TTS 문장 생성 | [음성 경로 로그](validation/gleo-real-voice-v012.log), [원본 보존](validation/gleo-voice-probe-v012.json) |
| 실제 음성 요약 | SUMMARIZE_SESSION 선택→callback 수용→총3개/평가2개/보류1개 및 미평가 한계를 포함한 TTS 문장 생성 | [요약 검사](validation/gleo-voice-summary-probe-v012.json) |
| 실제 감점 질문 | Gleo가 이전 대화 근거로 설명했고, 재조회 질문에서는 GET_LAST_RESULT를 호출해85점/15점 근거를 다시 받음. EXPLAIN_RESULT 자체의 실제 음성 선택은 관찰되지 않았으며 해당 함수의 Android 수신·조회·콜백은 장치 검사에서 검증 | [음성 경로 로그](validation/gleo-real-voice-v012.log), [재조회 검사](validation/gleo-voice-refresh-probe-v012.json) |
| 앱 화면 | 실제 기록 선택→Gleo 연결 켜기/끄기→85점 감점 설명; 로컬 조회·보류·영상 복기 회귀 통과 | [실제 화면](validation/gleo-query-flow-v012.png), 장치 로그 |

최초 합성 음성 관찰에서 Gleo가 실제로 package-prefixed action을 만들었으나 앱의 bare action 필터와 일치하지 않는 것을 발견했다. 양쪽 필터와 빈 선택값 처리를 보완한 뒤 실제 음성 흐름과 전체 관련 회귀 검사를 재실행했다. 처음 장치 전체 자동 수집에 포함된 콘솔 촬영용 수동 테스트는 종료하고 명시적인 관련18개로 다시 실행했다. 해당 수동 수집을 통과 검사 수에 합산하지 않았다.

네 번의 합성 질문에 대한 Gleo의 문장 생성은 관찰 결과이며 모든 자연어 표현에서 동일한 함수 선택·문장을 보장하지 않는다. Gleo가 이미 전달받은 답변을 대화 맥락에서 재사용할 수 있다. EasyCar 연결 해제는 이후 조회를 차단하며 이미 전달된 Gleo 대화 내용을 삭제하는 동작은 아니다. 실제 사용자 음성·실차 마이크/스피커·주행 오디오 환경·정식 마켓 승인은 검증하지 않았다. 관찰된 TTS 요청 문장을 실제 실차 스피커의 측정 결과로 표시하지 않는다.
