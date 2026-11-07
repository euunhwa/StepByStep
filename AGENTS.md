# EasyCar 데이터셋 문서 유지

- 인식용 데이터/규칙은 `docs/datasets/recognition-ko.md`, 평가용 데이터/기준은 `docs/datasets/evaluation-ko.md`에서 계속 관리한다. 두 범주와 학습/검증/단순 조사 용도를 구분한다.
- DetectionConfig, Telemetry, CoachEngine, ParkingClassifier의 문턱/단위/시작·종료·보류 조건을 바꾸면 인식 문서를 같은 작업에서 갱신한다.
- 평가 참조 JSON, PolicyScorer, 프로필, 배점/가중치/노출시간을 바꾸면 평가 문서의 실제 수치·수식·버전·근거·한계를 갱신한다. 결과 의미가 달라지면 프로필/정책 버전을 올리고 과거 기록을 몰래 다시 채점하지 않는다.
- 변경일·이전/새 값·이유·실행한 검사와 미검증 항목은 `docs/datasets/CHANGELOG-ko.md`에 추가한다. 자료 확보 상태와 경로도 갱신한다. 실측/합성/임의/전문가 검증을 혼동하지 않는다.
- 임의 평가셋의 개선 필요 공지와 필수 신호 누락 보류를 유지한다. MD 설명을 실행 설정인 것처럼 표시하지 말고 실제 코드/JSON 원본을 연결한다.
- 저부하 검증을 위해 Gradle 빌드와 Pleos 에뮬레이터를 동시에 실행하지 않는다. 에뮬레이터가 필요하면 한 대를 CPU2코어/RAM3GB로 제한한다.
