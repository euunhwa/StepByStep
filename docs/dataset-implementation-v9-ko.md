# 앱0.9 데이터셋 전면 교체 매핑

2026-11-06. 첨부 MD의 원문과 해시는 [데이터셋 관리](datasets/README-ko.md)에서 관리한다.

| 종류/자료 | 어느 구현에서 사용하는가 | 결과 의미 |
|---|---|---|
| 인식 규칙 `datasets/document-v1/recognition.json` | DocumentDataset → CoachEngine → DocumentCoachEngine. 속도·yaw 적분·R 세그먼트·시작/종료 | 자동 기동 운동 패턴 후보. ML 학습 없음 |
| 인식 검증 `recognition-validation.json` | DocumentDatasetTest. 4개 사례의 프레임을 재생하고 외부 정답과 비교 | 합성 구현 검증, 도로 정확도 아님 |
| 평가 기준 `evaluation.json` | DocumentScorer. 가감속/jerk/횡가속 3단 경계, 주차 횟수/시간 | 전문가 미검증 관측 조작 점수 |
| 평가 검증 `evaluation-validation.json` | DocumentDatasetTest. 21개 임의 지표 벡터의 기대 배점 대조 | 경계·계산 검증, 모범 운전 데이터 아님 |
| 제출 데모 `auto-drive.json` | 우측 상단 데모 → DriveRuntime → 같은 새 엔진 | 명시적 합성 일곱 동작. 코칭 시작에는 주입하지 않음 |
| 원문·출처 조사 | JSON 수치 근거와 적용/보류 결정 | 출처/추론/공학 설정 구분. 공개 데이터 확보·학습으로 표시하지 않음 |
| localhost269프레임 기록 | 새로운 정책으로 복사 재생해 별도 검증 파일 생성 | 이전 저장 기록은 변경하지 않음. 실차 자료 아님 |

앱은 `profiles/document-derived-v1.json`과 새 인식/평가 쌍을 기본값으로 로드한다. 기존 `arbitrary-reference-v1.json`, `evaluation-policy-prototype.json`, `arbitrary-prototype-v1.json`은 새 코칭의 실행 경로에서 제외했다. 보존한 구 기준을 새 데이터가 없을 때 대체값으로 사용하지 않는다.

가감속 인식과 평가는 구분한다. 가령≥3.888…m/s²의 감속600ms는 급감속 인식, >3.5는 위험 수준 조작 초안 감점이다. 문헌·추론 값의 배점은 안전성 인증이나 면허시험 평가가 아니다. 차량 yaw가 없는 실제 연결에서는 회전·주차 유형을 보류하며 화면/설정에 제한을 남긴다. 안전 맥락·UBI·유턴 반경·정렬 완성도는 [보류 항목](datasets/evaluation-ko.md)에 명시했다.

기존 화면의100/80~99/0~79 색상과5초 후 대기 복귀, 홈·기록·설정/분할 모드는 유지했다. 데이터셋 고지는 문헌 초안/임의 배점/합성 검증/개선 필요로 바꾸었다. 과거 세션을 지우거나 다시 점수화하지 않는다.

재생성: `JAVA_HOME=... ./gradlew :core:exportDocumentFixtures`.
검증: `:core:test :app:testPleosDebugUnitTest :app:assemblePleosDebug :app:assemblePleosDebugAndroidTest`.
에뮬레이터와 Gradle은 동시에 실행하지 않는다. [실행 결과](verification-ko.md).
