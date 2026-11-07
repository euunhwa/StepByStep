# localhost 차량 상태 조작

2026-11-07 · 앱0.9.3. Pleos Connect v2.1.0 이상 Virtual Console을 사용합니다. EasyCar 자체가 차량을 조향·가속·제동하는 기능은 없습니다. 아래는 사용자 승인을 받아 보완한 에뮬레이터 촬영 입력이며 실차 주행이 아닙니다.

## 연결과 실제 입력 확인

```sh
/Users/genius/Library/Android/sdk/platform-tools/adb devices
/Users/genius/Library/Android/sdk/platform-tools/adb -s <Pleos-serial> forward tcp:8088 tcp:8088
```

PC 브라우저에서 `http://127.0.0.1:8088/`을 엽니다. 여러 에뮬레이터가 있으면 반드시 Pleos의 serial을 지정합니다. 포트가 사용 중이면 호스트 쪽만 `tcp:18088`로 바꾸고 브라우저에서 18088로 접속할 수 있습니다.

EasyCar 설정에서 **차량 읽기 권한 요청**을 누르고 허용한 뒤 **연결 다시 확인**으로 속도·기어·핸들 상태를 확인합니다. 홈에서 **코칭 시작**을 한 번 누릅니다. 기본 입력은 차량 서비스로 고정되어 있습니다. 내장 데모를 선택하면 번들 파형을 재생하므로 localhost 값은 해당 데모 입력을 바꾸지 않습니다.

촬영 장면마다 새 라이브 세션을 시작합니다. 촬영 도구는 runtime의 `state.motion`이 존재하고 현재 세션의 `frames`가 실제로 수신된 것을 확인한 뒤 준비 완료로 처리합니다. 그 다음 각 프로필의 초기 정차/직진 구간부터 재생합니다. 출발 장면에는 D/0km/h10초, 회전·유턴·정지에는 D/11km/h 직진3초, 주차에는 P/0km/h5초의 초기 구간을 둡니다.

Virtual Console의 Emulation Controls → **Motion**에는 Gear Selection(P/R/N/D), Steering Angle(핸들 −540~540도), Brake Pedal 등이 있습니다. ADAS의 **Cruise Target Speed는 크루즈 목표값**이며 앱의 실제 `PERF_VEHICLE_SPEED`로 단정할 수 없습니다. 실제 속도 신호가 바뀌는지는 EasyCar 설정의 속도 관측값으로 확인합니다.

이번에 확인한 설치 서버의 `/vehicle/controls`는 속도 정수 km/h와 핸들 정수 degree를 받습니다. 소수값은 `invalid_speed`/`invalid_steering`으로 거절됐습니다. 기어 변경은 별도 요청으로 보내고 현재 목표 속도를 다시 설정합니다. 이 로컬 동작 확인을 근거로 촬영 드라이버를 구성했으며 존재를 확인하지 않은 제어 API는 사용하지 않습니다.

이 서버는 활성 브라우저 세션이 있어야 DDS 전달이 진행됩니다. 브라우저를 열어 두거나, 같은 client UUID로 `POST /browser/session?client=<UUID>` heartbeat를 유지하는 촬영 드라이버를 사용합니다. 제어 요청에 HTTP 성공이 돌아왔다는 사실만으로 차량 서비스 전달이나 앱 인식 성공을 단정하지 않습니다. 앱에서 실제 속도·기어·핸들 관측값과 완료 기록을 확인합니다.

## 0.9.3 회전 모델의 범위

속도·기어·핸들은8088 → DDS → 에뮬레이터 차량 서비스 → 앱 어댑터를 거칩니다. [PleosBridge](../app/src/pleos/java/com/example/easycar/PleosBridge.kt)는 `BuildConfig.DEBUG && emulated`에서만 [EmulatorDynamics](../core/src/main/kotlin/com/easycar/core/EmulatorDynamics.kt)로 yaw를 보완합니다. 식은 `signedV / 2.7 × tan((핸들각 / 15) × π / 180)`이며 축거2.7m·조향비15는 시뮬레이션 가정입니다. D는 전진+, R은 후진−이고 모델이 받는 양의 SDK 핸들값은 전진 좌회전입니다.

이번 설치 환경에서는8088 제어의 핸들 부호가 SDK 관측과 반대였습니다. 프로필은 이론상 좌회전+를 유지하고 촬영 드라이버가 `consoleSteeringDeg = −theorySteeringDeg`로 전송해 SDK 입력 좌표계에 맞춥니다. 앱이 저장한 실제 동작명·점수를 바꾸는 처리는 하지 않습니다. 기어/핸들 current read는 모델 활성 DEBUG 에뮬레이터에서 명목200ms, 실차에서 명목500ms입니다. 신선도 허용값750ms는 그대로 유지합니다.

yaw 출처는 `EMULATOR_BICYCLE_MODEL_NOT_SENSOR`이며 새 시각은 모델 계산 단계의 시각입니다. 실제 차량 yaw 센서나 차종 보정 결과로 표시하지 않습니다. 입력 누락·지연·잘못된 단위에는 모델 yaw도null입니다. 실제 기기와 release 빌드에는 이 보완이 적용되지 않습니다. 검증된 실차 yaw 공급자가 없는 상태에서 기기 gyro·불확실한 GNSS heading으로 대체하지 않는 제한을 유지합니다.

## 인식과 촬영용 점수

출발은 속도≤0.15m/s의4초 정차·표준편차≤0.05m/s 뒤 D에서≥0.7m/s 이동해야 합니다.0.9.3은4초 경계 직전 샘플을 보존해101ms 같은 polling 간격에서도 정차 창을 확인하도록 수정했으며, 이 수치는 변경하지 않았습니다. 정지는4초 정차로 확인합니다.

회전·유턴은 yaw의 지속·각도 적분 조건을 충족해야 합니다. 주차는 전진 준비와 후진의 yaw·핸들 패턴으로 T자/평행 후보를 나누며, 공간에 대한 주차 성공을 판정하지 않습니다. 주차 횟수는 안정된 R→D만 셉니다. 각 기어를 최소500ms 유지하며 R→N→D도 한 번입니다. D→R은 추가하지 않습니다. P+정차500ms 또는15초 연속 정차 등으로 완료하며, 긴 반복 교정 중에는 저속 이동을 포함해야15초 정차로 먼저 끝나지 않습니다. 실제 공간·정렬·연석 등 미관측 항목은 계속 별도 보류입니다.

촬영 입력은 일곱 유형과 T자 주차 추가 장면의8장면이며 각각 새 라이브 세션으로 분리합니다. 아래는 입력 설계의 오프라인 예상값이며 실제 촬영 결과 확인과 구분합니다.

| 순서 | 장면 | 예상 점수·색상 |
|---|---|---|
| 1~5 | 출발·좌회전·우회전·유턴·정지 | 각각100·파랑 |
| 6 | T자 주차, R→D9회 | 60·빨강 |
| 7 | 평행주차, R→D4회 | 90·주황 |
| 8 | T자 주차, R→D4회 | 85·주황 |

점수는 [기존 평가식](datasets/evaluation-ko.md)으로 계산하며 앱에 고정 점수나 동작 정답을 주입하지 않습니다. 프로필·평가 JSON·과거 점수는 변경하지 않았습니다. 원본 번들 데모를 쓰려면 우측 상단 **데모**를 선택하지만, 이번8088 촬영은 **코칭 시작**의 차량 서비스 입력을 사용합니다.

실제 차량 서비스 관측은 음성 때문에 입력 수집을 멈추지 않습니다. 촬영 드라이버가 각 동작 뒤 직진 또는 P정차 구간을 유지해 음성이 다음 회전/후진으로 중단되지 않도록 합니다. T자60 뒤에는 P정차18초, 평행90 뒤에는12초, 마지막 T자85 뒤에는18초를 포함했습니다. 화면 카드의5초 수명과 실제 발화 완료는 별개입니다.

코어114개·앱 단위5개 검사와 APK 빌드는 통과했습니다. 최종8장면의 실제 세션 점수, 화면의 파랑/주황/빨강 갱신, 발화 완료 상태와 영상·녹음 검수는 [8088 촬영 검증](console-capture-ko.md)에 별도로 기록합니다. 이 문서의 오프라인 예상값을 전체 촬영 성공으로 간주하지 않습니다.

공식 근거: [Virtual Console 셋업](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator), [기능/범위 사용 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide). 확인일 2026-11-05.
