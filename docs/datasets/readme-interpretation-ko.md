# 자료별 README·공식 소개 풀이

확인일: 2026-11-06 / Asia·Seoul. 앞서 제시한 Pleos SDK, AAOS, Geotab, Zenodo, DLP가 대상이다. README가 있는 DLP는 해당 문서를, 나머지는 공식 소개/API 설명을 읽고 핵심을 한국어로 풀었다. 원문 전체 번역이 아닌 요약이다. ‘EasyCar 해석’은 문서에서 내린 적용 판단이다.

## Pleos Vehicle SDK

[공식 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)

차량 서비스에 연결해 Brake·SteeringWheel 등의 기능을 사용하는 구조다. Vehicle 객체에서 도메인을 얻고, 지원 여부를 확인한 다음 현재값 조회나 변경 구독을 한다. 응답은 비동기이고 사용 종료 때 연결을 해제한다. 가이드는 SDK2.3.2/Service2.3.21 기준이다.

EasyCar 해석: 차량 신호 수집 연결부에 해당한다. capability는 기능의 지원 범위이며 운전 능력 점수가 아니다. 브레이크 상태 조회가 있다고 해서 잘못된 제동인지 판단하는 정답 자료를 제공한다는 뜻은 아니다. API가 존재하는 것과 실제 기기에서 권한·값·시각이 검증된 것을 구분한다.

## Android Automotive Car API

[공식 차량 속성 설명](https://developer.android.com/reference/android/car/VehiclePropertyIds)

CarPropertyManager로 속성 ID에 해당하는 값을 읽는다. 속도는 m/s이며 이동 방향에 따라 부호가 달라진다. GEAR_SELECTION은 선택한 기어, CURRENT_GEAR는 변속기의 현재 기어여서 D에서도1단/2단 등이 될 수 있다. PERF_STEERING_ANGLE은 핸들이 아닌 앞바퀴 모델 각도다.

EasyCar 해석: R→D 셀렉터 전환은 GEAR_SELECTION으로 관측한다. 속도만 보고 기어를 만들어 넣지 않는다. 앞바퀴 각도를 기존 핸들45° 문턱에 대입하면 안 된다. 관측 입력에서 동작과 평가값을 계산하는 로직은 앱이 별도로 구현해야 한다.

## Geotab

[ExceptionEvent](https://developers.geotab.com/myGeotab/apiReference/objects/ExceptionEvent/), [Scorecard 공식 안내](https://support.geotab.com/help/mygeotab/reports/safety-reports/driver-safety-scorecard)

ExceptionEvent는 규칙 위반 이벤트의 시작/끝·기간·운전자·위반 규칙 등을 기록한다. Scorecard는 급가속·급제동·급회전·안전벨트·과속 두 항목의 위반 빈도를 가중 집계해0~100점으로 보여주는 보고서다. 가중치와 분류 경계는 수정할 수 있다.

EasyCar 해석: 상황별 규칙을 만들고 가중 집계하는 설계를 참고할 수 있다. 일정 기간의 운전자 집계와 동작1회 종료 직후의 평가를 구분해야 한다. 열람한 안내만으로 모범 조작 시계열이나 강사 정답 점수가 확보된 것은 아니다.

## Zenodo

[공식 REST API 소개](https://developers.zenodo.org/)

연구 결과를 등록·공개하는 기능, 공개 기록을 검색하는 기능, 파일을 주고받는 기능이 구분된다. Records는 게시된 자료를 찾는 경로다. 문서의 업로드 예제는 연구 결과를 출판하는 사용법이다.

EasyCar 해석: 자료를 찾아 확보하는 수단이다. Zenodo를 쓴다고 운전 분석이나 점수 계산이 실행되지는 않는다. 실제로 선택한 데이터셋의 변수·정답 라벨·버전·이용 조건을 확인해야 한다. 이번에는 파일 업로드·출판·데이터 학습을 하지 않았다.

## DLP 데이터 README

[Dryad 공개 페이지의 README](https://datadryad.org/dataset/doi:10.5061/dryad.tht76hf5b)

드론으로 촬영한 주차장 행동을 JSON으로 정리한 자료다. 차량 내부 센서 로그와 구분한다.

| 용어 | 뜻 |
|---|---|
| Agent | 추적 대상 객체 하나 |
| Instance | 해당 객체의 한 시점 위치·방향·속도·가속 |
| Frame | 같은 시점에 보이는 객체 묶음 |
| Obstacle | 해당 녹화 내내 움직이지 않은 차량 |
| Scene | 연속 녹화 구간 |

좌표는 주차장 로컬 좌표로 변환돼 있다. 궤적 연구에는 JSON을 사용할 수 있고 원영상은 별도 요청 대상이다.

EasyCar 해석: ‘ground truth’는 문맥상 영상/객체 분석용 정답 주석으로 읽어야 하며 강사 운전 점수로 간주하면 안 된다. 한 Scene을 동작1회로 취급하지 않는다. 실제 핸들·기어·제동 원인과 모범 점수는 공개 README에서 확인되지 않았다. 판단 기준으로 쓰려면 적절성을 검토한 표본/라벨이 추가로 필요하다.

개별 README 다운로드는403이었으나 공개 레코드에 표시된 README 본문을 읽었다. 대용량 원자료의 실제 스키마/내용을 검수한 상태는 아니다.

## DLP Python 도구 README

[저자 저장소 README](https://github.com/MPC-Berkeley/dlp-dataset/blob/main/README.md)

가상환경에서 도구를 설치하고 JSON 파일을 data 폴더에 둔 뒤 Python의 Dataset 객체로 읽는 절차다. tutorial은 구조/조회법, visualization은 궤적을 그리는 방법을 설명한다.

EasyCar 해석: 이름의 API는 데이터를 다루는 Python 인터페이스다. 온라인 운전 채점 서비스가 아니다. 설치만으로 데이터가 확보되거나 점수 모델이 학습되지 않는다. 이번에는 안내만 해석했고 도구 설치를 실행하지 않았다.

관련 검토는 [자료 후보 분석](../evaluation-candidates-2026-11-06.md), 현재 적용 상태는 [인식용](recognition-ko.md)과 [평가용](evaluation-ko.md)을 따른다. 앱 코드·기준·버전은 이번 문서 해석 작업으로 바꾸지 않았다.
