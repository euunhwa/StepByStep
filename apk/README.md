# 설치용 Pleos 릴리스 APK · 0.13.3

> 현재 소스 0.13.3(`versionCode 19`, 기록 DB v2, 인식 1.0.3)에서 빌드한 차근차근 (StepByStep) Pleos 서명 APK입니다. 앱 이름 변경과 주차 궤적 재생을 포함합니다. 기존 다운로드 링크를 유지하기 위해 파일명은 `EasyCar_Pleos_release.apk`를 사용합니다.

[EasyCar_Pleos_release.apk](EasyCar_Pleos_release.apk): Pleos용, 앱 ID `com.example.easycar`. 제출 패키지에는 일반 Android Phone용 APK를 포함하지 않습니다.

디버그가 비활성인 release 빌드를 전용 키로 서명했습니다. [SHA256SUMS](SHA256SUMS)와 [메타데이터](release-metadata.json)에 파일 해시·버전·인증서 해시를 기록했습니다. 개인 키와 암호는 포함하지 않습니다. `.idsig`는 일반 설치에는 필요하지 않은 서명 보조 파일입니다.

2026-11-14 기존 릴리스와 동일한 인증서 및 v3/v4 서명을 확인했습니다. 분리된 Pleos 에뮬레이터에서 기존 0.13.2로부터의 업데이트, 7개 동작 데모, T자 주차 궤적 재생과 앱 재시작 후 기록 보존을 확인했습니다. [0.13.3 릴리스 검증 기록](../docs/validation/release-v0133-20261114.json)

설치 후 홈의 **데모**에서 7개 합성 동작을, **T자 데모**와 **평행 데모**에서 주차 궤적 재생을 시연할 수 있습니다. 8088 회전 재현 모델은 개발용 Debug에만 있습니다. 실제 차량 신호 연결 범위·현재 한계는 [루트 README](../README.md), 설치·서명·업데이트는 [저장소 인계 안내](../docs/repository-handoff-ko.md)를 참고하세요. 인계 안내의 0.13.2 설치·계측 결과는 이전 APK의 검증 기록입니다.

기존 디버그 앱은 서명이 다르므로 기록이 있는 앱을 삭제하지 말고 별도 검증 장치에서 설치하세요.
