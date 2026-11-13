# 앱0.9.0 검증 기록

> 최신 화면은 [현재 UI 캡처](ui-screenshots-ko.md), 최신 시연은 [영상 안내](../media/README.md)에서 확인할 수 있습니다. 아래 화면·로그는 0.9.0 당시의 검증 근거로 보존합니다.

후속0.9.1 점수별 음성 변경은 [음성 안내 변경·검증](voice-feedback-ko.md)에 별도로 기록했다. 아래는0.9.0 인식·평가 기준 교체 당시의 검증 기록이다.

0.9.2 데모의 음성 완료 대기는 [데모 순서 변경·검증](demo-speech-sequence-ko.md)에 기록했다.

2026-11-06 · 대상 Pleos Connect v2.1.0 · 프로필 document-derived-1.0 · 인식 recognition-document-1.0 · 평가 evaluation-document-1.0.

## 빌드와 자동검사

에뮬레이터를 종료한 상태에서 JDK17, CPU2코어 제한, Gradle 작업자1개, offline 모드로 실행했다. `:core:exportDocumentFixtures :core:test :app:testPleosDebugUnitTest :app:assemblePleosDebug :app:assemblePleosDebugAndroidTest` 성공. 최종 빌드23초.

- 코어88개(기존71 + 새 데이터셋17), 앱 단위검사5개 모두 통과. 실패/오류/스킵0.
- 새 검사: 원문SHA/manifest, 엄격한 기준 쌍/오타/전문가 상태 거부, 기존 기준 우회 차단, yaw 부호·단위·신선도·수신시각 추정 거부,100Hz→10Hz 정렬,500ms 기어 조회, 일반/짧은 bump/고속 회전,600ms 초과 경계,21개 평가 벡터, 전진+후진 T자, 이전 주차 버퍼 초기화, 유턴 중 yaw 복구 오분류 방지, 주차 준비 중복 제거, 실서비스 기록 복사 재생.
- 새 합성 일곱 동작896프레임/89.5초를 출발·좌·우·유턴·정지·T자·평행 순으로 완료. 관측 조작 점수 모두100. 안전성100점이나 전문가 정답 재현이 아니며 의도적으로 경계 안에 만든 검증 입력이다.
- 이전 localhost 차량 서비스269프레임은 원본을 수정하지 않고 새 엔진으로 재생했다. 신선도300ms 시도는500ms 기어 읽기와 충돌해 반복 보류를 만들었고750ms로 수정했다. 최종 재생은 신호 공백 보류가 없으며, 마지막 STOP은 새4초 정차 창 전에 로그가 끝나 미완료로 보류했다. 기존 사용자 세션을 다시 채점하지 않았다.

[빌드 로그](validation/build-v9.log), 코어/앱 XML은 `validation/core-v9-TEST-*.xml`, `app-v9-TEST-*.xml`. [합성 재생 결과](validation/document-v9-replay.json), [실서비스 과거 기록 재생](validation/document-v9-real-recording-replay.json).

## 장치 검증

기존 데이터를 지우지 않고 `adb install -r`로 최종 APK를 업데이트했다. versionCode9 / versionName0.9.0 확인. 최종 계측 검사4개,132.116초, 모두 통과했다.

1. SubmissionDemoTest: 실제 우측 상단 데모 버튼 → 새 합성896프레임 → 일곱 동작 완료/피드백7개 → Room 저장. 모든 결과 새 프로필·정책과 PROVISIONAL 표시, 입력 출처 SYNTHETIC_DOCUMENT_VALIDATION 확인.
2. FeedbackExpiryTest: 코칭 유지 상태에서 완료 카드가 5046ms 뒤 숨겨지고 기록 유지, 다음 좌회전에서 다시 표시.
3. MiniCoachLayoutTest: 최소/작은 창 크기의 실제 구성요소 배치 경계 확인.
4. FeedbackScoreUiTest:0/79/80/90/99/100의 빨강/주황/초록과 짧은 원인,1280×800·1280×360·840×1000dp 레이아웃 검사.

[장치 로그](validation/device-v9-final.log), [저장된 제출 데모](validation/submission-demo-v9-session.json), [5초 복귀 증거](validation/feedback-v9-expiry.json), [완료 화면](validation/feedback-v9-visible.png), [대기 화면](validation/feedback-v9-waiting.png).

마지막으로 앱 홈을 열고 TCP8088 포워딩을 복원했다. http://127.0.0.1:8088/ 응답200. 이 URL은 차량 서비스 조작 화면이며 앱의 합성 데모와 구분한다. 현재 에뮬레이터의 [CarPropertyService 진단](validation/car-properties-v9.txt)에서 yaw 이름 매치는 없었다.

최종 APK SHA256: `80ed0c13416122bf56e9086361cc8cf6af939e7c245fc3cde2c311ab7ee2240e`.

## 범위와 한계

- 장치 검증은 Pleos 에뮬레이터에서의 동작이며 실차 도로 검증이 아니다. Gradle과 에뮬레이터는 동시에 실행하지 않았다. 에뮬레이터는 한 대 CPU2/RAM3072MB.
- UI의 작은 창 검사는 실제 Compose 구성요소를 여러 크기에 배치한 계측 검사다. 이번에 OS 점 세 개 핸들 드래그를 다시 수행한 것은 아니다.
- 데모/화면 검사는 무음으로 실행한다. 음성 큐·중단·만료·중복 방지는 코어 검사로 확인했고 이번에 실제 음성의 가청 품질을 측정하지 않았다.
- 현재 브리지는 yaw·횡가속·시동 공급자를 구현하지 않았다. Vehicle2.3.2 AAR 클래스 목록과 CarPropertyService 진단에서 yaw 이름의 제공자를 확인하지 못했지만 모든 차종/벤더 속성의 불가능을 증명한 것은 아니다. 기기 gyro와 불확실한 GNSS heading으로 대체하지 않는다.
- 회전/주차 유형은 운동 패턴 추정이다. 원시 HDD/CAN-DSAD/UAH 등을 확보해 학습·보정·정확도를 측정하지 않았다. 표준/법령·사고 맥락·full-lock/차량 치수 보정·도로/공간 완성도·UBI 점수는 검증 전 보류.
- 새 결과 confidence0.75는 검증 전 후보의 고정 표시값이며 통계적으로 보정된 정확도/확률이 아니다. 속도·기어 충족률은 measurements.baseCoverage에 별도로 저장한다. 실제 수집 라벨을 통한 신뢰도 보정이 필요하다.
- 차분 jerk는 문헌의 전체 Frenet jerk와 같지 않다. v×yaw 횡가속은 경사/미끄러짐 미보정 추정이다. 연속600ms/필터 등 공학 선택을 포함하므로 문헌 표를 완전히 재현한 검증으로 표시하지 않는다.

[어떤 자료를 어디에 썼는지](dataset-implementation-v9-ko.md), [인식](datasets/recognition-ko.md), [평가](datasets/evaluation-ko.md), [출처 대조](datasets/source-review-v9-ko.md), [이전0.8 검증](verification-v8-ko.md).
