# Gleo 연결 계약 · 앱0.12.0

선택한 실제 저장 기록의 조회·감점 설명·요약을 Gleo Action 수신과 실제 callback broadcast에 연결했다. [사용법·권한·요청·응답·검증](gleo-integration-ko.md)을 현재 기준 문서로 관리한다. [0.11 내부 조회 문서](gleo-record-query-ko.md)는 당시 구현의 이력이다.

| 사용자 요청 | 함수 | 선택 입력 |
|---|---|---|
| 최근 주차·유턴 등 결과 | GET_LAST_RESULT | sessionId, maneuverType (`PARKING`은 주차 전체) |
| 주차 등 감점 이유 | EXPLAIN_RESULT | sessionId, resultId, maneuverType |
| 연결한 연습 요약 | SUMMARIZE_SESSION | sessionId |

실행 함수명은 EasyCar의 앱 함수다. 원본은 [APK 스키마](../app/src/pleos/assets/schema.json)에 있다. 사용자가 앱에서 연결한 한 세션만 조회하며 다른 ID를 전달해도 범위가 늘어나지 않는다. 기록이 삭제·변경되거나 연결이 철회되면 지난 응답을 전송하지 않는다. 과거 기록을 재채점하지 않는다.

Pleos 수신기는 Gleo 소유 서명 권한으로 보호한다. 공식 예시의 bare action과 실제 Gleo4.2.0.15의 package-prefixed action을 지원한다. 실제 관찰된 빈 선택값은 생략으로 처리한다. 콜백은 공식 action/extras로 Gleo 패키지에만 보내고 Gleo 권한을 가진 수신자로 제한한다.

개발 환경에서의 실제 동작과 정식 앱 심사는 별개다. 배포 시 [공식 App Schema 전달·검토 절차](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)를 진행하고, 대상 OEM 빌드의 패키지·서명 권한·음성 처리 동작을 확인한다. 이번에 확인한 사실과 미검증 사항은 현재 검증 요약에서 구분한다.
