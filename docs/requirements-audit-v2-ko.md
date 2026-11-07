> v2 기록 문서입니다. 현재 0.3.0의 자동 인식·보류 정책은 [자동 코칭 구조](automatic-coaching-v3-ko.md)를 기준으로 확인하세요.

# 원문 요구사항 재검증 · v0.2.0

검토 기준: 보존한 `requirements-ko.md`와 사용자의 후속 요청. **지정한 동작 종료 직후 화면·음성 피드백**을 우선순위로 적용했습니다. 앱명/대상 경로는 사용자 요청대로 EasyCar입니다. 주차 R→D 가중 감점은 유지합니다.

## 확인한 기존 부족과 보완

- 기본 화면이 T자 주차이고 다른 동작은 선택 창에 숨겨져 있었습니다. 기본을 전체 주행으로 바꾸고 일곱 유형을 홈에서 직접 선택하게 했습니다.
- 화면 문장이 음성 출력 성공에 종속됐습니다. 화면 발행·음성 큐·저장을 분리했습니다.
- 30초 음성 간격과 선행 출발 안내가 선택한 회전 피드백을 밀어냈습니다. 기본 간격을 6초로 설정 가능하게 바꾸고 선택한 유형의 안내를 우선합니다.
- 주차가 세션 종료에서만 결과를 냈습니다. P + 정차 유지 시 바로 완료 이벤트를 냅니다.
- 모든 동작이 핸들 입력을 요구했습니다. 출발·정지는 속도·기어만으로 관측하도록 분리했습니다.
- 실차 음성의 오디오 상태가 항상 unknown이었습니다. 공개 재생 상태·통화 모드·실제 포커스·TTS 콜백을 연결했습니다.
- 회전 단계별 관측값과 그래프가 부족했습니다. 진입/회전/탈출 기준과 감점 없는 측정 항목까지 저장·표시합니다.
- 사용자 영상은 재생만 가능했습니다. 탑뷰 보정 JSON, 명시적 시간 매핑, 실제 픽셀 재분석과 여러 칸의 사용자 선택을 추가했습니다.

## 범위별 상태

| 원문 범위 | 구현·검증 위치 | 현재 상태 |
|---|---|---|
| Kotlin/Compose/ViewModel/Flow, Room/DataStore, 오프라인 | app/main, core, demo/pleos 변형 | 구현·빌드·계측 |
| OEM 없는 일반 Android 데모 | demo flavor, OEM 없는 의존성 | 빌드 및 일반 Android 계측 |
| 측정/수신 시각·단위·출처·품질·공백·미분 | Models, Telemetry | 코어 자동 검증 |
| JSON 기준·차종/코스·임시 수치·검증 상태 | demo-v2, ScoringConfig, ReferenceProfile | 합성 기준 구현·검증. 전문가 기준은 외부 자료 대기 |
| 회전/유턴·출발/정지·주차 상태 머신 | CoachEngine | 일곱 유형과 전체 주행 자동 검증 |
| 곡선·S자·신뢰도 부족·급감속 맥락 | DemoCatalog, EngineTest | 확정/과실 단정 방지 검증 |
| 단계별 평가·이탈 크기/시간·상한/반올림 | DeterministicScorer, MetricAssessment | 실제 파형·합계 자동 검증 |
| R→D 0~5회·중립 경유·기어 흔들림 | GearTransitionCounter, countPenalty | 0/2/6/12/20/30 유지·자동 검증 |
| 동작 직후 시각/음성/저장 | ImmediateFeedback, DriveRuntime | 코어·실제 화면/TTS 계측 |
| 무음·지연·중복·만료·우선 오디오 | ImmediateFeedback, AudioAvailability, SpeechOutput | 상태/억제 구현. OEM 동시 오디오 통합 시험 별도 |
| 영상 파일/합성 MP4 실제 선 검출·재생/오버레이 | LocalVideoInput, ParkingLineAnalyzer, VideoReview | 실제 MP4 계측·픽셀 분석·UI 검증 |
| 보정 없는 영상/다중 칸/신뢰도/미터 조건 | VideoCalibrationDocument, forDimensions, 선택 UI | 코어 검증. 실제 카메라 보정 정확도 별도 |
| 신호-영상 동기화 | VideoSync | 명시 범위만 매핑. 없으면 연계 보류 |
| 화면 구성·제한·빈 기록/오류·동의·삭제/보관 | Compose, CarUxRestrictions, AppRepository | 구현·저장/재시작/영상 계측 |
| 화면 전환/Activity 재생성 중 지속 | 사용자 시작 specialUse FGS | 동일 sessionId·단일 결과 계측 |
| 성장 비교·근거 있는 한국어 설명 | comparableGrowth, KoreanCoach | 같은 유형/차종/코스/버전, 합성 한계 표시 |
| Vehicle/AAOS/FusedLocation/TTS 실제 API | pleos flavor, API 근거 문서 | 컴파일·연결·TTS 완료 확인. 실차 품질 검증 별도 |
| 내비 예정 동작/실제 수행 구분 | 실제 센서 감지, 내비 미연결 | 예정 동작을 정답으로 사용하지 않음 |
| 실제 카메라/녹화·마지막 프레임 보존 | VideoInput 경계 | 공식 입력·녹화 허용 대기. 녹화 성공을 만들지 않음 |
| Gleo 질의 | gleo-contract-ko.md | 스키마/등록/권한 대기. 가짜 API/대화 없음 |
| 실차 평가 정확도·배포 심사·자동 부팅/상시 | expert-validation, API 근거 | 실제 기기·외부 승인 필요 / 자동 시작 미지원 |

## 전달 시 판정

실행 가능한 합성 데모와 승인된 파일 분석 경로를 완성하고 실제 에뮬레이터에서 검증합니다. 전체 실차 기획 완료로 표현하지 않습니다. FusedLocation confidence/시계 기준, 실제 차종·코스, 핸들 측정 시각, OEM 경고·내비 오디오, 카메라 접근/보정, Gleo 등록은 코드만으로 확보할 수 없는 외부 조건입니다. 실행한 검사·로그·스크린샷과 미실행 항목은 `verification-ko.md`에서 구분합니다.
