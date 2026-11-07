> 이전 버전 구조 기록입니다. 현재 0.5.0의 입력·점수·데이터셋은 [데이터셋 사용 매핑](dataset-implementation-v5-ko.md)과 README를 기준으로 확인하세요.

# 작은 코칭 창 · EasyCar 0.4.0

코칭 시작을 한 번 누르면 지원 기기에서 자동으로 시스템 PiP 창으로 전환한다. 앱 전체 화면을 띄워두지 않아도 ForegroundService가 같은 세션의 관측·평가·음성을 계속한다.

- 평소: EasyCar / 자동 코칭 중. 새 동작이 끝나면 결과 제목·조언·점수 또는 보류를9초 동안 표시한다. 음성 상태 콜백이 갱신되어도 오래된 카드 표시 시간을 연장하지 않는다.
- 작은 창은 시스템이 배치·이동·확대·닫기를 관리한다. PiP 컨트롤의 코칭 종료는 서비스를 종료한다. 작은 창을 닫는 것만으로 코칭을 종료하지 않으며 알림에서 다시 열거나 종료할 수 있다.
- 전체 화면의 작은 창 버튼, 코칭 중 뒤로 가기, 지원 기기의 다른 앱 이동에서도 작은 창을 사용할 수 있다. 설정의 시작하면 작은 창으로를 끄면 자동 전환을 해제한다.
- PiP 진입 실패·기능 미지원은 이유를 표시하고 기존 코칭을 계속한다. 임의로 세션을 재시작하거나 신호 공급원을 바꾸지 않는다.
- 알림은 표시 권한을 허용한 경우 최근 피드백을 갱신한다. 기존 낮은 중요도 채널을 사용하며 별도의 강제 팝업·SYSTEM_ALERT_WINDOW 권한을 추가하지 않는다.

## Pleos 분할 창

Pleos의 공식 창 정책은 Connect-S에서1:1, Connect-W에서2:1 또는1:2의 두 앱 표시를 안내한다. 크기·위치는 시스템 창 버튼/바/제스처가 관리한다. 실제2.1.0 에뮬레이터에서는 오른쪽 가장자리 점 세 개 핸들을 왼쪽으로 드래그해 내비와 나란히 배치됨을 확인했다. 탭만으로는 전환되지 않았다. 앱이 강제로 네비 영역 크기를 바꾸는 비공개 API는 사용하지 않는다. PiP는 PackageManager.FEATURE_PICTURE_IN_PICTURE 확인 및 실제 진입 성공을 모두 요구하며 Pleos2.1.0 전체에 무조건 지원된다고 가정하지 않는다.

MainActivity의 가로 고정을 제거하고 resizeableActivity 및 창 크기 변경 처리를 추가했다. 작은 분할 영역(가로900dp 미만)에는 탐색 메뉴·그래프·영상 없이 같은 미니 코칭 화면을 표시한다. 창 전환으로 Activity가 멈춰도 서비스가 유지되고, Activity 재생성/전체 화면 복귀 때 같은 세션을 자동으로 다시 축소하는 반복을 막는다.

## 근거

- Pleos Window Policy: https://document.pleos.ai/en/docs/connect/guide/docs-design/getting-started/window
- Pleos Display Layout: https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout
- Android PiP API·기능 확인·재생성·표시 컨트롤: https://developer.android.com/develop/ui/views/picture-in-picture
- AAOS에서는 기능을 확인해 대응: https://developer.android.com/training/cars/parked/automotive-os

기존 자동 인식 및 평가 초안의 보류 정책은 유지한다. 작은 창은 새 안전 기준이나 확인되지 않은 신호를 추가하지 않는다. 실제 실행 결과·지원 여부는 verification-ko.md에 기록한다.
