# API·권한·가용성 조사

확인일: **2026-11-04, Asia/Seoul**. 공개 문서만으로 실차 지원을 가정하지 않았습니다. 문서 버전과 에뮬레이터 버전은 별도입니다.

## 환경·버전

- 설치된 `Pleos_Connect_v2.1.0` AVD, `ro.build.version.sdk=34`. 공식 [환경 구성](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)도 emulator 2.1.0 / API 34 / minSdk 28 이상을 명시합니다.
- [Vehicle 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro): SDK 2.3.2 / Service 2.3.21 기준 문서. Maven에서 2.3.2를 실제 해석·컴파일했습니다. 에뮬레이터가 이 서비스 버전과 같다는 주장은 하지 않습니다.
- [TextToSpeech 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro): 3.3.1, [FusedLocation 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro): 1.1.3. 두 SDK를 고정하여 컴파일했습니다.
- [NaviHelper 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro): 3.0.5. 이 앱에는 연결하지 않았습니다. 내비 예정 동작을 실제 수행 동작의 정답으로 사용하지 않습니다.
- Maven: https://nexus-playground.pleos.ai/repository/maven-releases/ . `ai.pleos.playground.security`도 TTS의 실제 전이 의존성이므로 저장소 필터에 포함했습니다.

## 신호·API

| 목적 | 확인한 API / 권한 | 실제 구현·제한 |
|---|---|---|
| 차량 속도 | `CarPropertyManager`, `VehiclePropertyIds.PERF_VEHICLE_SPEED`, `android.car.permission.CAR_SPEED` | 읽기 설정 확인 → 등록. m/s, 후진 음수. 런타임 권한 없으면 명시적 실패 |
| 선택 기어 | `GEAR_SELECTION`, `android.car.permission.CAR_POWERTRAIN` | `CURRENT_GEAR`는 실제 변속단일 수 있어 선택 기어와 구분. ON_CHANGE 콜백 외에 500ms 간격으로 현재 값을 읽고 원래 변경 시각과 수신 시각을 따로 보존. N=1/R=2/P=4/D=8 매핑 |
| 핸들 각도 | `Vehicle.getSteeringWheel()`, `checkSteeringWheelAngleCapabilities`, `getCurrentSteeringWheelAngle`, `registerSteeringWheelAngle`, 해제 함수 | AAR의 실제 함수·listener를 `javap`로 검증하고 컴파일. 읽기 `pleos.car.permission.CAR_DRIVE`. capability의 AccessType READ/READ_WRITE에서만 구독 |
| 위치·진행 방향 | `FusedLocation.initialize/isSupported/registerFusedLocationCallback/unregister.../release`, `FusedLocationListener` | 1.1.3의 nullable data·onError 모두 처리. `pleos.car.permission.FUSED_LOCATION`은 dangerous. 위치 자체가 없거나 confidence·시계 기준이 미검증이면 평가에 투입하지 않음 |
| Pleos 음성 | `TextToSpeech(context, Mode.ON_DEVICE)`, initialize listener, event listener, locale, speak, stop, release | `pleos.car.permission.TTS_SERVICE`. 네트워크·프로젝트 자격 증명이 필요한 HYBRID/SERVER를 선택하지 않음 |
| 주행 규제 | `CarUxRestrictionsManager.registerListener/currentCarUxRestrictions` | unknown 또는 DO/activeRestrictions가 있으면 최소 화면. 영상 제거 시 VideoView.stopPlayback. P만으로 허용하지 않음 |

[AAOS 속성 공식 정의](https://developer.android.com/reference/android/car/VehiclePropertyIds), [SDK API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api), [가용성](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/api-compatibility-availability)이 근거입니다. 속도·기어는 AAOS 표준을 우선합니다. `PERF_STEERING_ANGLE`은 앞바퀴 모델 각도이므로 핸들 각도로 대체하지 않았습니다.

핸들 callback은 **센서 측정 시각을 제공하지 않습니다.** 수신 시각과 그 한계를 source에 보존하고 ESTIMATED_TIME / RECEIPT_ESTIMATE로 분류합니다. 후보 감지에는 사용할 수 있지만 측정 시각 기반 조향 미분과 종합 조향 평가는 보류합니다. FusedLocation의 `timestampMillis` 시계 기준·confidence 의미도 OEM 검증 전에는 신뢰할 수 있는 공통 시간축으로 바꾸지 않습니다. 원시 값은 출처에 남기며 실제 평가를 보류합니다. 동작 중 데이터가 사라져도 합성 데이터로 바뀌지 않습니다.

에뮬레이터에서 `android.car`를 required=true로 선언하면 INSTALL_FAILED_MISSING_SHARED_LIBRARY가 발생했습니다. 기존 AAOS API는 해당 이미지에서 사용 가능하므로 optional 선언과 자동차 feature 확인을 사용하며, 실제 `Car` 연결·UX 구독 성공을 별도로 확인했습니다. 일반 Android 데모 소스는 Car 클래스를 참조하지 않습니다.

## 오디오·카메라·수명주기

- [오디오 정책](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development): navigation guidance 속성 + transient MAY_DUCK 요청, 손실 시 즉시 stop/abandon. 공개 Android `AudioManager.getActivePlaybackConfigurations()`와 통화 모드를 함께 확인합니다. 다른 재생이 없고 실제 포커스가 승인될 때 라이브 안내도 요청합니다. 확인 실패는 unknown으로 보류합니다. 시작/완료는 실제 TTS 이벤트로 구분하며 단순 요청 수락을 완료로 세지 않습니다. Android 밖의 OEM 경고 경로와 실제 내비·통화 동시 통합 시험은 별도 검증 대상입니다.
- [주행 안전](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline), [주행 규제 구현](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide): 최소 화면 정책. DO 승인·차량 카테고리 심사를 받았다는 표시는 하지 않습니다.
- [하드웨어 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture): 내장 어라운드뷰의 일반 앱 입력·녹화 허용을 확인하지 못했습니다. CAMERA 권한·스크린 캡처·비공개 HAL을 추가하지 않았습니다.
- [Foreground service 종류](https://developer.android.com/develop/background-work/services/fgs/service-types): 사용자 시작의 분석을 specialUse로 선언하고 사유를 manifest에 적었습니다. 알림·종료 동작, START_NOT_STICKY, 중단 체크포인트를 구현했습니다. **배포 시 specialUse의 플랫폼/스토어 심사가 필요하며 상시·자동 부팅 실행을 보장하지 않습니다.** 재연결은 기존 subscription을 해제한 뒤 사용자 재시도로 수행합니다.
- [FusedLocation 권한](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions): Android 일반 위치 SDK의 백그라운드 보장을 Pleos에 적용하지 않습니다. 라이브 위치의 백그라운드 지속성은 실차 검증 대기입니다.
- [Gleo](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts): App Schema 등록과 action/callback 연결이 필요합니다. 등록·권한을 확보하지 않았으므로 가짜 대화·임의 API를 구현하지 않았습니다.

SDK의 전이 manifest에는 INTERNET 및 SDK binding 권한이 포함됩니다. 앱에는 외부 전송 코드가 없고 TTS는 ON_DEVICE입니다. 데모 빌드에는 OEM 의존성이 없으며 일반 Android 음성도 설치된 오프라인 한국어 voice만 선택합니다.

실제 발화 시험으로 `Locale.KOREAN`(ko)과 미완료 스트림은 사용하면 안 된다는 것을 확인했습니다. [Set locale](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/set-locale)의 `Locale.KOREA`(ko-KR), [Start and stop](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop)의 완성 문장 `speak(text, isFinal=true)`를 적용했습니다. 초기화 성공만으로 음성 출력 검증을 완료 처리하지 않습니다.


## 2026-11-05 즉시 피드백 보완

[공개 Android 재생 상태 API](https://developer.android.com/reference/android/media/AudioManager#getActivePlaybackConfigurations())를 사용합니다. 자기 음성은 navigation guidance 속성으로 출력하며 추가/다른 재생·통화·포커스 상실 시 중단합니다. 커스텀 알림 접근 권한이나 비공개 오디오 API를 추가하지 않았습니다. 최소 주행 Activity에는 `distractionOptimized` 메타데이터를 선언했으며 복기·영상·설정은 여전히 UX 상태로 제한합니다. 이 선언을 OEM 승인이나 규제 변화의 통합 검증으로 소개하지 않습니다.
