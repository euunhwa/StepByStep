# 설치용 Pleos 릴리스 APK · 0.13.2

> 소스는 0.13.3(기록 DB v2, 인식 1.0.2)입니다. 이 APK는 0.13.2 빌드이며 전용 서명키로 다시 빌드·서명해야 0.13.3 APK가 됩니다.

[EasyCar_Pleos_release.apk](EasyCar_Pleos_release.apk): Pleos용, 앱 ID `com.example.easycar`. 제출 패키지에는 일반 Android Phone용 APK를 포함하지 않습니다.

디버그가 비활성인 release 빌드를 전용 키로 서명했습니다. [SHA256SUMS](SHA256SUMS)와 [메타데이터](release-metadata.json)에 파일 해시·버전·인증서 해시를 기록했습니다. 개인 키와 암호는 포함하지 않습니다. `.idsig`는 일반 설치에는 필요하지 않은 서명 보조 파일입니다.

설치 후 홈 우측 **데모**에서 7개 합성 동작을 시연할 수 있습니다. 8088 회전 재현 모델은 개발용 Debug에만 있습니다. 실제 차량 신호 연결 범위·현재 한계는 [루트 README](../README.md), 설치·서명·업데이트는 [저장소 인계 안내](../docs/repository-handoff-ko.md)를 참고하세요.

기존 디버그 앱은 서명이 다르므로 기록이 있는 앱을 삭제하지 말고 별도 검증 장치에서 설치하세요.
