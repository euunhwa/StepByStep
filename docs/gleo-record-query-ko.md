# 저장 기록 조회와 Gleo 연결 준비 · 0.11.0

> 최신 화면은 [현재 UI 캡처](ui-screenshots-ko.md), 최신 시연은 [영상 안내](../media/README.md)에서 확인할 수 있습니다. 아래 화면·로그는 0.11.0 당시의 검증 근거로 보존합니다.

> 0.13.2는 질문 화면의 기술 메타데이터·JSON 모의 호출 도구를 숨기고 화면/다시 듣기를 일상어로 정리했다. 아래0.11 개발 도구 설명은 당시 이력이다. 로컬 요청 규격과 저장 근거·외부 Gleo 프로토콜은 유지한다.

2026-11-07 · 앱0.11.0/code14 · 앱 내부 조회 규격 `schemaVersion=1`.

## 구현된 기능

실제 Room에 저장된 기록을 읽는 조회 포트와 한국어 설명, 로컬 모의 호출 도구를 구현했다. 현재 화면의 답변은 저장 근거를 조합한 결정적 문구다. LLM 응답이나 실제 Gleo 음성 인식 결과가 아니다. 실제 Gleo용 외부 수신기·콜백 전송·스키마 등록은 연결 전 단계다.

사용 경로: **기록 → 연습 기록 선택 → 저장 기록에 질문하기**.

| 기능 | 처리 |
| --- | --- |
| 최근 동작 | 선택한 연습 안에서 최신 결과를 반환한다. 동작 종류 필터를 지원한다. 최신 결과가 보류이면 보류 그대로 반환한다. |
| 감점 이유 | 최근 동작 또는 사용자가 고른 동작의 저장 점수·감점·관측값·기준값·근거 시각·지속시간을 설명한다. |
| 이번 연습 요약 | 평가·보류 개수, 비교 가능한 점수의 평균, 감점 없는 동작, 반복 감점과 복기 항목을 구성한다. |
| 답변 듣기 | 사용자가 누를 때 기존 기기 내 TTS로 조회 답변을 읽는다. Gleo를 호출하는 기능이 아니다. |
| 모의 호출 도구 | DEBUG 빌드에서 요청 JSON을 수정·실행하고 콜백 형식의 미리보기를 확인한다. 외부 전송은 없다. |

이번 연습 한 개를 대상으로 하며, 오늘의 모든 기록을 자동 수집하는 기능은 포함하지 않는다. 명시적 선택 범위가 없는 요청에는 먼저 연습을 선택하라는 상태를 반환한다.

## 내부 요청

```json
{
  "schemaVersion": 1,
  "requestId": "explain-1",
  "action": "EXPLAIN_RESULT",
  "parameters": {
    "sessionId": "fixture-session",
    "resultId": "fixture-tpark-85"
  }
}
```

위 식별자는 [합성 모의 호출 예제](../examples/gleo/mock-requests.json)의 값이다. 앱에서는 선택한 실제 기록의 ID로 요청을 만든다.

| action | 허용 parameter |
| --- | --- |
| `GET_LAST_RESULT` | 선택적 `sessionId`, `maneuverType` |
| `EXPLAIN_RESULT` | 선택적 `sessionId`, `resultId` |
| `SUMMARIZE_SESSION` | 선택적 `sessionId` |

`sessionId` 생략 시 호스트가 허용한 세션 중 createdAt이 가장 최근인 세션을 선택한다. 같은 시각은 ID로 안정적으로 정렬한다. 그 세션이 비어 있어도 이전의 좋은 기록으로 대체하지 않는다. 동작 순서는 해당 세션의 endMs/startMs/ID를 사용하며 다른 세션의 상대 시각을 직접 비교하지 않는다.

`resultId`를 생략하면 선택한 세션의 최신 동작을 사용한다. 결과 ID가 다른 세션에 있더라도 현재 선택 범위로 넘어가 조회하지 않는다.

요청은 최대16,384자다. 식별자는 ASCII 영숫자와 `. _ : -`를 포함한1~128자다. 중복 키·주석·뒤에 붙은 JSON·지원하지 않는 타입/버전·함수·필드·동작 종류를 거부한다. 점수 변경, 조회 범위 부여, 콜백 URL이나 패키지 지정 파라미터는 없다. 오류를 마지막 정상 응답으로 대체하지 않는다.

이 wrapper는 앱 내부 모의 호출 규격이다. 공식 Gleo Intent를 수신한다는 의미가 아니다.

## 응답과 설명 원칙

응답은 requestId/action/status/answer/warnings와 선택된 session, result 또는 summary를 가진다. `transport=LOCAL_MOCK`, `externallyDispatched=false`를 명시한다. JSON은 null을 보존해 미평가 점수가0점으로 바뀌지 않게 한다.

동작 근거에는 저장된 점수 종류·프로필·평가 정책 버전·입력 출처·기어 전환 횟수·감점 내역·보류 사유·미평가 항목을 포함한다. confidence0.75를 인식 정확도75%로 설명하지 않는다. 원시 차량 프레임·위치 신호·개인 영상 경로·전체 Room payload는 응답에 넣지 않는다.

- **기존 점수 보존:** 새 채점기를 호출하지 않는다. 실제 저장된 점수와 측정값을 반환한다.
- **과거 버전:** 현재 평가 버전과 다르면 과거 결과임을 표시한다. 이전 형식의 정책 버전/점수 종류가 없으면 없는 상태로 남긴다.
- **보류·미완료:** 해당 상태와 근거를 그대로 반환한다. 계산 중 남은 감점 내역을 확정 감점으로 설명하지 않는다.
- **감점 합계:** 모든 저장 감점의 합과 `100−저장 점수`로 확인한 적용 감점을 따로 제공한다. 불일치하면 해당 버전의 상한·근거 확인을 안내하며 점수를 수정하지 않는다.
- **주차 설명:** `evaluation-document-1.0`의 저장 근거가 일치할 때 전후진4회·허용1회·초과3회·15점 감점처럼 설명한다. 다른 정책을 새 선형 식으로 재해석하지 않는다.
- **미관측 항목:** 안전 맥락·주차 완성도 등 passed=null인 항목은 미평가로 보존한다.
- **출력 길이:** 감점/미평가 근거는 각각20개, 요약 개선 그룹은10개까지 반환하고 생략 개수를 표시한다. 감점 총합은 생략 전의 전체 내역으로 계산한다.

요약에서 평가된 동작은 COMPLETED·점수 존재·보류 이유 없음 조건을 모두 충족해야 한다. 보류된 동작의 잠정 감점은 반복 감점 그룹에 넣지 않는다. 모든 동작이 평가되었고 같은 프로필/정책 버전일 때만 기존 방식의 정수 산술평균을 표시한다. 버전이 섞이면 평균을 보류하고 개선 그룹도 버전과 단위·기준별로 나눈다. 그룹의 recordedPoints는 저장된 개별 항목 합이며 세션 전체의 적용 감점으로 해석하지 않는다.

## 조회 범위와 저장 일관성

조회 범위는 JSON이 아닌 호스트의 `RecordQueryAccess`로 받는다. 화면에서는 사용자가 연 세션 하나만 허용한다. 동의가 없거나 정차 UX 상태가 확인되지 않으면 기록 소스를 읽지 않는다. 매 요청마다 저장소의 최신 snapshot을 읽으며 삭제된 기록을 응답 캐시에서 되살리지 않는다. 없는 ID와 범위 밖 ID는 같은 결과로 처리한다.

조회 포트에는 save/delete가 없다. 화면은 기록이 바뀌거나 삭제되면 지난 응답을 지우고, 주행 제한 중에는 조회 화면을 숨긴다. 현재 이 범위 검사는 앱 내부 경계이며 외부 Gleo 호출자 신원이나 OEM 서명 권한 검증을 대신하지 않는다.

구현: [조회 엔진](../core/src/main/kotlin/com/easycar/core/RecordQueries.kt), [규격·콜백 미리보기](../core/src/main/kotlin/com/easycar/core/RecordQueryProtocol.kt), [저장소 어댑터](../app/src/main/java/com/example/easycar/data/LocalRecordQueryGateway.kt), [조회 화면](../app/src/main/java/com/example/easycar/ui/RecordQueryScreen.kt).

## Gleo에 연결할 때 사용할 초안

[App Schema 초안](../examples/gleo/app-schema.draft.json)은 세 개의 function, 대문자 이름, 영어 설명과 제한된 parameter 타입을 사용한다. 현재 앱 버전0.11.0을 명시했다. 로컬 검사는 구현한 함수/파라미터와의 일치 여부를 확인하며 Gleo 서버의 등록 수락을 증명하지 않는다. 작성 기준은 [공식 App Schema 가이드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)다.

콜백 미리보기는 functionName/status/message/mode/data를 구성한다. data는 필요한 조회 응답 한 개를 담은 **JSON 객체 배열의 문자열**이다. status는 정상 조회·보류·검색 결과 없음에서 success, 세션 선택 필요에서 asking, 요청/조건/저장 형식 오류에서 fail이다. mode는 silent이며 실제 발송이나 자동 음성은 없다. [공식 Callback 가이드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)의 필드 형식을 참고했다.

공식 가이드는 App Schema 등록과 Action/Callback 상호작용을 설명한다. 실제 연결에는 호출자 식별·권한·전달 형식·콜백 수명주기·오디오 정책 확인이 남아 있다. [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts), [코드 예시](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples), [스키마 전달 안내](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)를 함께 확인해야 한다. 개별 Intent extras 설명과 ACTION_PARAM 예시의 전달 형식은 실제 환경에서 대조한 뒤 어댑터에 적용한다.

현재는 Gleo Action intent-filter, 외부 공개 조회 receiver/service, 외부 callback broadcast, 네트워크 전송을 추가하지 않았다. 등록 전 초안을 실제 연동 완료로 표시하지 않는다.

## 로컬 재현과 검증

에뮬레이터를 종료한 상태에서 JDK17로 실행한다.

```sh
./gradlew --offline :core:test :core:recordQuerySmoke :app:testPleosDebugUnitTest :app:assemblePleosDebug :app:assemblePleosDebugAndroidTest
```

`--offline`은 기존 의존성 캐시가 있는 경우에 사용한다. smoke는 실제 저장소를 읽지 않고, 활성 채점기로 만든 명시적 합성 fixture를 사용한다. [세 가지 모의 호출 결과](validation/gleo-local-smoke-v011.json)에 요청·도메인 응답·콜백 미리보기를 저장한다. 실제 앱의 조회는 이 fixture를 자동 주입하지 않고 사용자가 선택한 Room 기록을 읽는다.

단위 검사는 새27개 포함 코어158개와 새4개 포함 앱9개가 통과했다. 85점 설명, 최신 보류, 범위 밖/삭제 ID, 동의·UX 제한, 세션/상대 시각 정렬, 과거/누락 버전, 감점 상한/불일치, 혼합 버전 요약, 중복·비정상 JSON, 메타데이터만 반환하는 경계를 포함한다. 최종 장치 검사도 통과했다.

| 범위 | 결과 | 근거 |
| --- | --- | --- |
| 코어 JVM | 158개 통과, 실패/오류/스킵0 | [검증 요약](validation/gleo-query-summary-v011.json), [빌드 로그](validation/build-gleo-query-v011.log) |
| 앱 JVM | 9개 통과, 실패/오류/스킵0 | [저장소 경계 검사 XML](validation/gleo-v011-TEST-com.example.easycar.LocalRecordQueryGatewayTest.xml) |
| 로컬 모의 호출 | 3개 함수 성공 | [합성 모의 결과](validation/gleo-local-smoke-v011.json) |
| Pleos 장치 | 새 기록 조회5개 + 기존 주차/저장9개 =14개 통과, 25.422초 | [장치 로그](validation/device-gleo-query-v011.log) |
| Room 기록 보존 | 저장된85점·전환4회·감점15점 조회, 원본 JSON 유지 | [실제 저장소 조회 근거](validation/gleo-query-storage-v011.json) |
| 앱 전체 흐름 | 기록 선택→조회 화면→최신 보류→대상 주차85점 설명, 원본 유지 | [흐름 확인](validation/gleo-query-flow-v011.json), [실제 앱 화면](validation/gleo-query-flow-v011.png) |
| 크기·오류·UX | 1280dp/실제700dp 폭, 잘못된 모의 JSON, 기록 변경 후 응답 제거, 주행 제한 화면 | [넓은 화면](validation/gleo-query-wide-v011.png), [좁은 화면](validation/gleo-query-narrow-v011.png) |

장치에서는 임시 Room 데이터베이스와 명시적인 합성 세션을 사용했다. 실제 사용자 기록을 테스트 정답으로 변경하거나 합성 세션을 Gleo 음성 응답으로 표시하지 않았다. 검사 기록·임시 DB를 정리하고 사용자 설정을 복원했다. 좁은 화면은 Surface의 최소 제약을 전달하지 않는 컨테이너로 실제700dp 폭임을 단언했다. 구성요소 제약 검사이며 새 OS 분할 창 드래그 검증은 아니다.

Gradle 빌드와 에뮬레이터를 동시에 실행하지 않았다. Pleos Connect v2.1.0 한 대, CPU2코어/RAM3072MB에서 검사했고 기존 앱 데이터를 유지하며0.11.0/code14 설치를 확인했다. 종료 후 앱과8088 포워딩을 복원했다.

실제 Gleo 음성 인식·LLM 최종 답변·OEM 수신/전송·서명 권한·실차 오디오 검증은 이번 범위에 포함하지 않는다. 평가/인식 JSON·프로필·감점과 기존 기록의 채점 결과는 바꾸지 않았다.
