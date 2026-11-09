# 인식용 데이터와 판별 조건

## 0.13.2 이후 현재 적용 범위 · `recognition-document-1.0.2` (2026-11-09)

일반 코칭은 `recognition-document-1.0.2`를 사용한다. 1.0.1의 모든 문턱·보류 조건은 그대로이며 다음 두 가지만 바뀌었다. [실행 JSON](../../app/src/main/assets/datasets/document-v1/recognition.json), [구현](../../core/src/main/kotlin/com/easycar/core/DocumentCoachEngine.kt), [회귀 검사](../../core/src/test/kotlin/com/easycar/core/TurnPauseAndDepartureTest.kt).

- **감속으로 끊긴 회전 이어 붙이기 (`turnPauseMaxMs=10000`)**: 회전 중 속도가0.7m/s 아래로 떨어지거나 yaw가 문턱 아래로 내려가 회전 구간이 끝났을 때, 그 구간이 한 방향 부호만 가지고 ∣Δψ∣<60°(`turnMinDeg`)이면 버리지 않고 10초 동안 보관한다. 같은 부호의 새 yaw bump가 10초 안에 시작되면 두 구간을 하나의 회전으로 합쳐 다시 분류한다. 반대 부호이거나 10초가 지나면 이전처럼 기록 없이 버린다. 합친 구간 전체도 `turnTimeoutMs=30000` 안이어야 한다. 1.0.1에서는 보행자 양보처럼 회전 중 잠깐 감속하면 앞뒤 반쪽이 각각 60° 미만이 되어 회전 결과가 사라졌다. 이미60~120°/150~210°로 분류되는 회전은 기다리지 않고 바로 확정한다. 0.7m/s 미만 구간의 표본은 회전 구간에 넣지 않고 150ms 초과 간격은 적분에서 연결하지 않으므로, 감속·정차 중의 작은 회전량은 Δψ에 더해지지 않는다(합친 Δψ가 실제보다 약간 작을 수 있음). 10초는 공학 초기값이며 실차 검증이 필요하다. 같은 방향의 작은 곡선 두 개가10초 안에 이어지면 하나의 회전 후보가 될 수 있다(기존에도 큰 곡선과 교차로 회전은 구분하지 않는다).
- **출차 구간은 주차 기록에서 제외**: 안정된 P에서(N 경유 무시) R로 들어가 시작한 주차 구간이 D에서 10km/h를 넘어 끝나면, 주차 시도가 아니라 출차로 보고 `PARKING_UNKNOWN` 보류 기록을 남기지 않는다. D 주행 중 R로 들어간 주차 구간이 속도 이탈로 끝나는 경우와 5분 시간 초과는 이전처럼 보류 기록을 남긴다. P 정차·장기 정차로 끝나는 주차 구간은 출발 기어와 관계없이 이전과 같이 분류한다.

평가 JSON·프로필·배점은 바뀌지 않았다. 각 결과의 인식 근거(`recognition.source`)에 `recognition-document-1.0.2`가 기록되며 과거 기록은 다시 계산하지 않는다.

## 0.13.1 적용 범위

일반 코칭은 `recognition-document-1.0.1`을 유지한다. 기존10km/h 주차 상한에 `parkingSpeedEpsilonMps=0.000001`을 더해 Float 수신값10.000000476km/h가 상한 이탈로 판정되던 오류를 보정했다.10.01km/h는 계속 거부한다. 다른 yaw·주차 유형 문턱과 평가 JSON은 이전 동작을 유지한다.

사용자 요청으로 별도 주차 메뉴·명시적 녹화 세션·`parking-slot-final-1.0`을 현재 앱에서 제외했다. 실험 원본은 `experimental/parking-capture-v013`에 보관하며 APK/활성 인식에 포함하지 않는다. 기존 `parking-vision-2.0` 영상 복기의 측면 지지율0.42를 유지한다. 실제 위에서 보는 AVM은 미확보이며, 현대/Pleos의 승인된 탑뷰 입력과 보정 정보가 제공되면 재검토한다.

아래는 일반 코칭·기존 영상 복기의 기준 이력이다. 위1.0.1 속도 수치 오차 보정이 현재 적용된다.

앱0.10.0의 주차 **영상 복기**는 별도 알고리즘 `parking-vision-2.0`을 사용한다. 흰색/노란색의 국소 대비, 선 지지, 제공된 차량 외곽, 여러 프레임의 일관성을 검사한다. 이는 아래 차량 신호 기반 동작 분류에 공간 정답을 제공하지 않으며 인식 JSON·yaw 문턱·주차 유형 분류는 그대로다. 영상 문턱·수식·합성 자료·보류 조건 전체는 [영상 분석 개선](../parking-video-analysis-ko.md)에 관리한다.

2026-11-07 · 앱0.9.3 · `recognition-document-1.0` 유지. [실행 JSON](../../app/src/main/assets/datasets/document-v1/recognition.json), [구현](../../core/src/main/kotlin/com/easycar/core/DocumentCoachEngine.kt).

## 어떤 자료를 쓰는가

첨부 원문의 규칙을 JSON으로 옮겼다. 새 `recognition-validation.json`은 정상 일곱 동작, yaw 없음, yaw 단위 오류, 수신시각 추정 yaw의 4개 합성 사례이며 각 사례의 정답은 프레임 밖 `expectedCompletedTypes`에 둔다. 엔진은 정답·사용자의 동작 선택·주차 공간 라벨을 읽지 않는다. 별도 `auto-drive.json`은 우측 상단 데모에서만 사용한다. 파일별 해시는 `manifest.json`에 기록한다. 실제 실차 인식 학습/정확도 측정 자료는 아직 없다.

0.9.3의8088 촬영 입력은 번들 데모 재생과 별개다. 사용자가 승인한 에뮬레이터 전용 보완으로, Virtual Console이 DDS에 전달하고 차량 서비스에서 관측한 속도·기어·핸들값을 사용한다. yaw만 아래에 명시한 모델로 계산한다. 이 입력은 실제 도로 주행이나 차량 yaw 센서 실측 데이터가 아니다.

## 신호와 전처리

- 속도 m/s, 기어 P/R/N/D, 운전자 핸들 각도 degree:steering-wheel. 속도/기어 필수. 앞바퀴 각도를 핸들 각도로 바꾸지 않는다.
- yaw는 `rad/s:vehicle-z-left-positive`만 허용한다. 좌회전 +, 우회전 −. VALID 품질·MEASUREMENT 시각·150ms 이내인 차량 좌표계 입력만 통과한다. 실차에서는 도/초 또는 기기 gyro, GNSS heading, 수신시각 추정으로 대체하지 않는다. DEBUG 에뮬레이터의 명시적 모델 입력 예외는 아래와 같다.
- 입력 프레임 시각 기준 100ms 이상 간격으로 선택해 명목 10Hz로 정렬한다. 인식 엔진은 값 보간이나 누락 신호 합성을 하지 않는다. 실차 yaw를 임의로 생성하지 않으며, 에뮬레이터 어댑터에서 계산한 yaw에는 모델 출처와 계산 시각을 별도로 남긴다. 역순/중복 프레임은 무시한다. 속도·핸들은 3점 중앙값, yaw는 후방 200ms 이동평균이다. 필터 지연은 명목10Hz에서 약0.1~0.2초다.
- 속도·기어 신선도750ms는 유지한다. 기어·핸들의 current read는 실차에서 명목500ms, 모델을 사용하는 DEBUG 에뮬레이터에서 명목200ms 간격이다. 에뮬레이터의 조회 빈도를 높여 수신 시각이 오래되는 것을 줄였으며750ms 허용값을 넓히지 않았다. 1초 초과 입력 공백/속도·기어 미수신 시 후보를 중단한다. yaw 적분·연속 초과시간은150ms를 넘는 간격을 연결하지 않는다. yaw가 끊기면500ms의 안정된 yaw 복구 전에는 새 회전을 시작하지 않아 유턴의 남은 반쪽을 좌회전으로 오인하지 않는다. 150ms는10Hz 수신 지터 허용값이며 표본 정확도를 보장하는 규격이 아니다.
- 속도 차분은 D/R 같은 기어 내 이동속력 |v| 기준이다. 기어 변경 후300ms는 미분 준비 구간으로 제외한다. 실제 값이 없는 구간을0으로 채우지 않는다.

## DEBUG 에뮬레이터의 회전 모델 예외

[PleosBridge](../../app/src/pleos/java/com/example/easycar/PleosBridge.kt)는 `BuildConfig.DEBUG && emulated`인 경우에만 [EmulatorDynamics](../../core/src/main/kotlin/com/easycar/core/EmulatorDynamics.kt)를 활성화한다. 사용자 승인 범위는8088 연동과 촬영을 위한 에뮬레이터 시뮬레이션 보완이다. 실제 기기와 release 빌드는 이 모델로 yaw를 채우지 않는다.

`yaw = signedV / 2.7 × tan((steeringWheelDeg / 15) × π / 180)`을 사용한다. 축거2.7m와 조향비15는 촬영용 시뮬레이션 가정이며 실차 보정값이 아니다. `steeringWheelDeg`는 SDK에서 관측한 핸들값이며 양수를 전진 좌회전으로 정의한다. 이번 설치 환경의8088 제어 부호는 SDK 관측과 반대여서 촬영 드라이버는 `consoleSteeringDeg = −theorySteeringDeg`로 전송한다. 이 변환은 입력 제어 쪽에서 수행하며 모델의 좌표계나 실제 앱 판정명을 뒤집지 않는다. `signedV`는 D에서 `|v|`, R에서 `−|v|`다. P/N 정차는0이며 P/N 이동이나 방향 미확인은 보류한다. 속도·기어·핸들 중 하나라도 없거나 오래됐거나 단위/품질이 맞지 않으면 yaw는null이다.

출처는 `EMULATOR_BICYCLE_MODEL_NOT_SENSOR`다. 새 yaw의 VALID/MEASUREMENT와 현재 시각은 **이번 시뮬레이션 단계의 계산 시각**을 뜻하며 차량 센서 실측을 뜻하지 않는다. 원래 속도·기어·핸들 신호의 품질과 측정/수신시각 표기는 그대로 둔다. 모델 사용 사실은 연결 상태·회전 표시·촬영 입력 출처에서 드러낸다. 이 예외는 기기 gyro나 불확실한 GNSS heading을 실차 yaw로 쓰는 허용이 아니다.

## 시작·종료·분류

| 경로 | 시작/분류 | 종료/보류 |
|---|---|---|
| 정차·출발 | 속도≤0.15m/s, 최근4초 모두 정차·표준편차≤0.05m/s이면 idle. 이후 D에서≥0.7m/s면 출발 | 출발 시작4초 또는 감속 전환. 일반 출발도 평가하며 급출발 여부를 별도로 기록 |
| 급출발 | 구간 시작≤5km/h, 가속≥10/3.6 m/s²가 연속600ms | `harshStart` 관측 플래그, 운전자 과실 판정 아님 |
| 주행 중 급가속 | 속도≥6km/h에서≥8/3.6 m/s²가 연속600ms | 마지막 초과 이후3초 병합, ACCELERATION. 출발 이벤트와 중복 억제 |
| 정지·급감속 | ≥6km/h 관측 후 감속>0.3m/s²면 후보. 14/3.6 m/s² 연속600ms면 급감속 | 4초 정차 확인으로 STOP. 정차 없이 감속 종료 후2초면 급감속만 DECELERATION. 종료속도≤5km/h의 급감속은 급정지 플래그 |
| 좌·우회전 | yaw bump 절댓값≥0.05rad/s, peak≥0.07, 길이≥1.5초. 사다리꼴 적분 Δψ가60~120°면 부호에 따라 좌/우 | yaw가 문턱 아래500ms면 종료. 30초 내 종료 미확인/신호 공백은 중단. 작은 bump·반대 부호 혼합은 회전 확정 안 함 |
| 유턴 | 같은 bump에서 ∣Δψ∣150~210° | 넓힌 범위는 추론값. 고속 유턴 표시는≥25km/h·≤6초로 따로 기록 |
| 급회전 | 회전 bump 전체 속도≥30km/h·길이≤3초 | 좌/우 분류와 별도의 `rapidTurn` 플래그 |
| 주차 | ≤10km/h, 안정 R단500ms에 진입. 최대12초 저속 전진 준비 구간과 이후 모든 R/D를 누적 | P+정차500ms, 검증된 시동OFF, 또는15초 장기 정차. 5분/속도 이탈은 중단. 완료 시 이전 주차 버퍼 제거 |
| T자 후보 | 전진+후진 순 ∣Δψ∣70~110°와 후진 중 한 방향 조향 지속 | full-lock 보정이 없으므로 공간에 대한 확정이 아닌 운동 패턴 추정 |
| 평행 후보 | 순 ∣Δψ∣<20°, 후진 중 조향+/−와 yaw+/−가 각각600ms 이상 | 순간 부호 잡음으로 S자를 만들지 않음. 근거 부족이면 PARKING_UNKNOWN |

주차 완료 후 D에서 이동500ms로 다시 준비한다. R→D는500ms 안정 후 한 번 세며 N 경유를 포함한다. P/신호 공백은 기어 연결을 끊는다. 주차 진입 시 미완료 저속 출발/감속 후보를 주차 준비 구간에 흡수하며 이미 완료한 동작은 보존한다. 주차 진행 중 D 이동을 새로운 출발/정지로 중복 보고하지 않는다. 주차 전후 회전/속도 후보는 각 경로에서 관리하지만 임의의 공간 라벨로 재분류하지 않는다.

0.9.3은 정차 창에서4초 경계 바로 이전 샘플 한 개를 보존하도록 수정했다. 이전 구현은4초를 넘는 샘플을 먼저 버리고 정확히4초가 남아야 idle로 판정해,101ms 같은 polling 간격에서 출발 준비를 놓쳤다. 현재는4초 이상 관측된 창으로 판정하며 정차0.15m/s·표준편차0.05m/s·`idleWindowMs=4000` 수치는 그대로다. 신호 공백은 이전 정차 이력을 지우므로 짧은 두 정차를 합쳐4초로 만들지 않는다.

차선변경 쌍+bump 간격3초·횡변위3.65m, 도로 교차로와 큰 곡선의 구분, full-lock/차량 길이별 거리 판정은 위치·도로폭·차종 보정이 없어 보류한다. 90° 곡선도 회전 운동 후보가 될 수 있으므로 도로 상황의 의미적 인식 정확도를 주장하지 않는다. 필터·수신 간격 때문에 경계 부근 분류에는 오차가 있으며 자체 실측 ROC 검증이 필요하다.

## 실행 수치 전체

키의 단위는 접미사(Ms=ms, Mps=m/s, Mps2=m/s², Kph=km/h, Deg=degree, Radps=rad/s). 비율은0~1이다. 표준편차·잡음·시간창·버퍼·신선도는 공학 초기값, 유턴/주차 구간은 문서의 추론값이다. 출처가 있는 택시/스마트폰 연구값도 Pleos 승용차의 검증 기준은 아니다.

| JSON 키 | 현재 값 |
|---|---|
| `maxSignalAgeMs` | 750 |
| `maxSampleGapMs` | 150 |
| `gearStableMs` | 500 |
| `stoppedMps` | 0.15 |
| `idleWindowMs` | 4000 |
| `idleStdMaxMps` | 0.05 |
| `movingMps` | 0.7 |
| `preRollMs` | 12000 |
| `startWindowMs` | 4000 |
| `stopWindowMs` | 20000 |
| `stopCandidateMps2` | 0.3 |
| `stopArmMps` | 1.6666666666666665 |
| `yawMeanWindowMs` | 200 |
| `yawBumpRadps` | 0.05 |
| `yawPeakRadps` | 0.07 |
| `yawMinimumMs` | 1500 |
| `yawQuietMs` | 500 |
| `turnPauseMaxMs` | 10000 (1.0.2 추가) |
| `turnTimeoutMs` | 30000 |
| `turnMinDeg` | 60.0 |
| `turnMaxDeg` | 120.0 |
| `uTurnMinDeg` | 150.0 |
| `uTurnMaxDeg` | 210.0 |
| `harshRunMs` | 600 |
| `harshStartMaxKph` | 5.0 |
| `harshStartMps2` | 2.7777777777777777 |
| `harshAccelerationMinKph` | 6.0 |
| `harshAccelerationMps2` | 2.2222222222222223 |
| `harshDecelerationMps2` | 3.888888888888889 |
| `harshStopMaxKph` | 5.0 |
| `rapidTurnMinKph` | 30.0 |
| `rapidTurnMaxMs` | 3000 |
| `rapidUTurnMinKph` | 25.0 |
| `rapidUTurnMaxMs` | 6000 |
| `parkingMaxMps` | 2.7777777777777777 |
| `parkingSpeedEpsilonMps` | 0.000001 (Float 표현 오차 허용) |
| `parkingTMinDeg` | 70.0 |
| `parkingTMaxDeg` | 110.0 |
| `parkingParallelMaxDeg` | 20.0 |
| `steeringNoiseDeg` | 20.0 |
| `parkingLongStopMs` | 15000 |
| `parkingTimeoutMs` | 300000 |
| `minimumCoverage` | 0.95 |
| `resamplePeriodMs` | 100 |
| `derivativeWarmupMs` | 300 |
| `accelerationMergeMs` | 3000 |
| `decelerationMergeMs` | 2000 |
| `stopPreRollMs` | 1000 |
| `maxFrameGapMs` | 1000 |

음성 안내도 새 yaw 문턱을 사용해 회전 중 발화를 보류한다. 핸들이 중앙이어도 관측 yaw가0.05rad/s 이상이면 회전으로 취급한다. 화면 피드백은 음성 대기와 독립적이며5초 뒤 숨긴다.

## 현재 Pleos 연결의 한계

연동은 AAOS 속도·GEAR_SELECTION과 Pleos 핸들각을 읽는다. Vehicle2.3.2 AAR의 클래스 목록에서 yaw/gyro/angular 이름의 API를 찾지 못했고, 실제 차량 브리지에는 검증된 차량 yaw·횡가속·시동 제공자가 없다. DEBUG 에뮬레이터만 위 모델을 명시적으로 사용한다. SDK 전체/모든 차종에서 영구적으로 불가능하다는 뜻이 아니다. 실차 신호 추가 시 차량 좌표계·부호·단위·실측 시각·권한·지원 여부를 실제 공급자에서 검증해야 한다. 현재 FusedLocation heading은 LOW_CONFIDENCE이므로 회전 인식에 사용하지 않는다.

0.9.3 코어114개·앱 단위5개 검사와 빌드는 통과했다. 촬영은 장면마다 새 라이브 세션을 사용하고 `state.motion`이 존재하며 세션에 실제 `frames`가 들어온 뒤 초기 정차/직진 준비 구간을 시작한다. 최종8장면의 실제 기록·화면 갱신·음성 완료 검수는 [8088 촬영 검증](../console-capture-ko.md)에 별도로 기록한다. 오프라인 입력 검증을 촬영 성공이나 실차 정확도 검증으로 표현하지 않는다.

[검증](../verification-ko.md): 합성 검증과 실제 서비스 기록 재생을 구분한다. 기존 comma2k19 형식 조사 자료와 과거45종 파형은 새 기준의 학습/튜닝/평가셋으로 사용하지 않는다.
