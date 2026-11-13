# EasyCar 0.4.0 · 작은 코칭 창 검증

> 최신 화면은 [현재 UI 캡처](ui-screenshots-ko.md), 최신 시연은 [영상 안내](../media/README.md)에서 확인할 수 있습니다. 아래 화면·로그는 0.4.0 당시의 검증 근거로 보존합니다.

확인일 2026-11-06 / Asia·Seoul. 사용자 허용 이후 Pleos Connect v2.1.0 에뮬레이터 한 대에서 실제 검증했다. CPU2코어·RAM3GB로 제한했고 빌드와 에뮬레이터를 동시에 실행하지 않았다. 검사 후 시연 코칭과 에뮬레이터를 종료했다.

## 실제 지원 범위

이 에뮬레이터의 `android.software.picture_in_picture`와 freeform feature는 모두 false다. **Pleos2.1.0에서 자동 떠있는 PiP 창을 지원한다고 주장하지 않는다.** 지원 기능이 있는 기기를 위한 PiP 진입·기능 확인·실패 보류 코드는 컴파일했다. PiP 실제 성공·이동·크기 변경은 이 기기에서 검증할 수 없었다.

Pleos 시스템의 **오른쪽 가장자리 점 세 개 핸들을 왼쪽으로 드래그**하면 EasyCar와 내비를 나란히 배치할 수 있음을 확인했다. 앱 영역이 약841×1210dp가 되었고, 코칭 중에는 메뉴·그래프를 숨긴 미니 화면으로 바뀐다. 창 크기는 시스템이 관리하며 앱이 비공개 API로 바꾸지 않는다.

실제 버튼으로 코칭 시작 후 미니 창에 출발 완료와 전체 조언·점수 보류가 나타나는 장면을 [스크린샷](screenshots/pleos-mini-feedback-v4.png)으로 확인했다. 실제 코칭 세션의 합성 입력 화면이며 UI 목업이 아니다.

## 검사 결과

| 검사 | 결과 |
|---|---|
| 새 JVM 표시 정책 시험 | 4개 통과: 즉시 게시·9초 만료·음성 콜백으로 오래된 카드 부활 금지·보류 점수·정지/종료 상태 |
| 앱 JVM 전체 | 5개 통과 (기존 예제1개 포함) |
| Pleos 계측 | **3개 통과, 89.781초** |
| 다른 앱으로 이동한 뒤 한 세션 일곱 동작 | 출발/좌회전/우회전/유턴/정지/T자/평행 순서로 모두 기록·화면 피드백 데이터 게시, 시제품 총점 보류 유지 |
| 배경 상태 한국어 음성 | 좌회전 실제 TTS onStart/onDone 및 SPOKEN 확인. 화면 게시 후3531ms 발화 시작. 우선 오디오/간격 등의 조건에 따라 지연 가능 |
| 미니 카드 크기 | 240×180,180×100,840×1000dp에서 제목·본문 경계가 창 안에 들어옴. PiP 안에 직접 터치 버튼을 넣지 않음 |
| 실제 Pleos 분할 창 | 내비와 동시에 표시·코칭 종료 버튼·미니 결과 카드 확인 |
| 일반 Android/Pleos APK | 빌드 성공 |
| Lint demo/pleos | 오류0, 경고41/44 |

코어의 인식·평가 정책은 바꾸지 않았다. 이전53개 시험 및 관련 제한은 [v3 기록](verification-v3-ko.md)을 보존한다. 계측3개는 합성 주행·에뮬레이터 검증이며 실차 정확도나 운전 안전 인증 결과가 아니다. 원인/신호/전문가 기준이 부족하면 보류하는 정책도 유지한다.

## 증거

- `validation/pleos-mini-instrumentation-v4.log`: 3개 계측 결과 원본.
- `validation/mini-background-v4.json`: 같은 세션의 일곱 동작·게시/발화 상태.
- `validation/mini-voice-v4.json`: 배경 상태에서 실제 SPOKEN·발화 지연 기록.
- `validation/mini-device-capabilities-v4.json`: 실제 기기 기능·분할 경계.
- `validation/app-v4-TEST-*.xml`: JVM 표시 정책 원본.
- `validation/build-lint-mini-v4.log`, `validation/lint-v4-*.sarif`: 빌드·정적 검사.
- `validation/target-build-v4.log`, `validation/apk-sha256-v4.json`: 지정 프로젝트 최종 빌드·APK 해시.

알림에 최근 결과와 종료/돌아가기 동작을 연결했다. **알림 표시 권한을 허용한 경우에 표시된다.** 현재 에뮬레이터 프로필은 알림 권한 미허용이므로 실제 알림 UI 표시는 검증하지 않았다. 권한이 없어도 코칭/음성/미니 창을 차단하지 않는다. SYSTEM_ALERT_WINDOW 등의 다른 앱 위에 표시 권한은 추가하지 않았다.
