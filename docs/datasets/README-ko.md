# 데이터셋 관리

2026-11-07 · 앱0.13.2 · 인식 recognition-document-1.0.1 / 평가 evaluation-document-1.0 / 프로필 document-derived-1.0.

| 종류 | 활성 실행 설정 | 관리 문서 | 검증 데이터 |
|---|---|---|---|
| 인식용 | `app/src/main/assets/datasets/document-v1/recognition.json` | [인식](recognition-ko.md) | `recognition-validation.json`: 정답 분리 4개 합성 재생 사례 |
| 평가용 | `app/src/main/assets/datasets/document-v1/evaluation.json` | [평가](evaluation-ko.md) | `evaluation-validation.json`: 21개 임의 경계값 벡터 |

사용자 첨부 MD는 원시 주행 로그가 아닌 문헌·추론 명세다. [원문 사본](sources/driving-recognition-evaluation-original-ko.md), SHA256은 두 실행 JSON에 기록했다. [적용/보류 매핑](../dataset-implementation-v9-ko.md), [출처 대조](source-review-v9-ko.md), [변경 이력](CHANGELOG-ko.md), [검증](../verification-ko.md)을 함께 관리한다.

활성 경로는 두 JSON을 쌍으로 읽는다. 누락/오타/검증 상태/원문 해시 불일치 시 시작에 실패하며 이전 임의 참조셋으로 대체하지 않는다. 기존 임의 참조 0.2·프로필0.3·정책0.4와 45종 파형은 과거 기록/회귀 테스트용으로 보존한다. [0.8 설명 보관](archive-v8/README-ko.md).

활성 기준의 학습·통계 보정에 사용한 외부 원시 주행 데이터셋은 없고 전문가 라벨링·실차 안전성 검증도 수행하지 않았다. 과거 comma2k19의4개 NPY 형식 조사 표본은 보존만 하며 학습·평가 점수 정답으로 사용하지 않는다. 합성 예제는 구현 검증용이며 모범운전 모집단이나 안전성의 증거가 아니다.

[루트 README의 출처·용도 구분](../../README.md#10-데이터와-기록-관리)과 [개선 계획·기준 변경 조건](../../README.md#12-현재-한계와-후속-개발)을 함께 확인한다. 향후 기준·신호·배점을 바꾸면 인식/평가 문서와 변경 이력을 같은 작업에서 갱신한다. 원본·기존 정답·과거 점수를 보존하고 조정에 사용하지 않은 독립 자료로 변경 전후를 검증한다.
