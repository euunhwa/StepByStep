# 새 저장소 제출·협업 준비 · 0.13.2

2026-11-08 기준. 이 저장소는 검증된 EasyCar 0.13.2 파일로 시작한 `euunhwa/DriveCoach`입니다. 기존 저장소의 Git 이력을 가져오지 않고 initial commit으로 시작합니다. 이 문서는 릴리스 서명·검증과 이후 다른 저장소로 옮길 때 사용할 사본 생성 안내입니다.

## 포함한 자료

최신 `app/`·`core/` 소스와 전체 자산·테스트, Gradle Wrapper, 데이터 출처·기준·이력, 과거 검증 근거, 재현 예제·도구, 현재 빌드에서 제외한 주차 실험 원본을 포함합니다. 테스트가 참조하는 과거 자산과 `docs/validation`의 fixture도 보존합니다.

`apk/`에는 별도 전용키로 서명한 Pleos 릴리스가 있습니다. `.idsig`는 선택적인 증분 설치용 서명 보조 파일이며 일반 APK 설치에는 필수가 아닙니다. `media/`의 영상은 **0.9.3 당시 시연**이며 최신 화면 영상이 아닙니다. 개별 촬영 원본은 포함하지 않습니다.

이전 `.git` 이력·remote, `local.properties`, IDE 개인 설정, Gradle/Kotlin 캐시, `build/`, 사용자 앱 DB, 개인 서명키와 암호는 제외합니다. 과거 검증 로그·메타데이터의 실행 당시 절대 경로는 근거 보존을 위해 남깁니다. 이는 새 환경에서 사용할 설정값이 아닙니다.

`FILE_MANIFEST.json`은 준비한 사본의 파일 크기와 SHA256 목록이며, `SHA256SUMS`로 무결성을 확인할 수 있습니다. 이후 파일을 수정하면 해당 파일의 해시는 달라집니다. macOS에서는 최상위에서 `shasum -a 256 -c SHA256SUMS`, Linux에서는 `sha256sum -c SHA256SUMS`로 확인할 수 있습니다.

추후 다시 사본을 만들 때는 `python3 tools/prepare_repository.py --output <새-폴더> --zip <새-ZIP>`을 사용합니다. 기존 출력은 덮어쓰지 않습니다. 이 도구는 Git 등록 여부와 관계없이 현재 파일을 복사하므로 미커밋 작업도 포함합니다.

## 새 저장소에서 시작

1. ZIP을 풀고 최상위 `README.md`가 있는 폴더를 엽니다.
2. JDK17·Android SDK를 준비합니다. `local.properties.example`을 `local.properties`로 복사하고 본인의 SDK 경로를 넣습니다.
3. 에뮬레이터를 종료한 상태에서 루트 README의 빌드·단위 검사를 실행합니다. 최초 의존성 다운로드에는 네트워크가 필요합니다.
4. 빌드를 마친 뒤 에뮬레이터 한 대를 CPU2/RAM3072MB로 실행합니다. 에뮬레이터가 메모리를 자동으로 높이는 환경에서는 실제 게스트 메모리도 확인합니다.
5. 테스트용 장치에 APK를 설치하고 홈 → 데모를 확인합니다. 실제 차량 연결·권한과 합성 데모를 구분합니다.

새 원격 저장소를 정한 뒤, 이 사본에서 다음 순서로 시작할 수 있습니다. 아래는 안내이며 아직 실행하지 않았습니다.

```sh
git init -b main
git add .
git diff --cached --stat
git commit -m "Prepare EasyCar 0.13.2 submission"
# 실제 새 저장소 주소로 변경
git remote add origin <새-저장소-URL>
git push -u origin main
```

파일을 먼저 검토하고 본인 저장소 공개 범위와 협업자를 설정합니다. 기존 프로젝트에는 배포 라이선스가 없으므로 이 사본에 임의의 오픈소스 라이선스를 추가하지 않았습니다. 포함된 외부 샘플의 출처·라이선스는 해당 폴더에 보존합니다.

## 릴리스 실행과 개발 입력 구분

- Pleos Release: `apk/EasyCar_Pleos_release.apk`, 앱 ID `com.example.easycar`.
- Pleos 앱 안에서 홈의 내장 합성 데모를 제공합니다. 별도의 Android Phone 앱은 제출하지 않습니다. 일반 Android용 `demo` flavor는 기존 개발 옵션으로 소스에만 보존합니다.
- **8088 조작에서 회전율을 계산하는 보조 모델은 Pleos Debug 에뮬레이터 전용**입니다. Release는 모델을 사용하지 않으며 현재 에뮬레이터의 7개 동작 시연은 내장 데모를 사용합니다.
- 실제 탑뷰 AVM 입력·자동 녹화·최종 주차 배치 통합 채점은 현재 미연결입니다. 현대/Pleos의 승인된 입력·권한·보정·시각 정보가 제공되면 세 메뉴 안에서 재검토합니다.
- 실제 Gleo 음성→앱 콜백→점수 답변은 기존 개발 빌드에서 검증했습니다. 이번 릴리스에서 Gleo 음성 전체 경로를 새로 검증한 것으로 표시하지 않습니다. 정식 배포 승인·실차/OEM 검증도 별도입니다.

기존 디버그 앱과 준비된 릴리스 APK는 서명이 다릅니다. 기록이 있는 기존 앱을 삭제하지 말고 별도 검증 장치를 사용하세요. 이후 동일 앱 업데이트에는 준비한 릴리스와 같은 키가 필요합니다.

## 다음 릴리스 서명

개인 키와 암호는 Git/ZIP에 포함하지 않습니다. 기존 키는 로컬 프로젝트의 Git 제외 디렉터리에 따로 보관했습니다. 협업자에게 키를 전달할 필요가 있으면 저장소 밖의 적절한 비밀 전달 수단을 사용합니다.

```sh
# 에뮬레이터 종료 후 서명 전 APK 생성
./gradlew :app:assemblePleosRelease

# 암호는 셸 기록에 직접 쓰지 않고 환경변수로 전달합니다(Bash/Zsh).
read -rs EASYCAR_KEYSTORE_PASSWORD
export EASYCAR_KEYSTORE_PASSWORD
export EASYCAR_KEY_PASSWORD="$EASYCAR_KEYSTORE_PASSWORD"
export ANDROID_HOME=/absolute/path/to/Android/sdk

python3 tools/sign_release.py \
  --input app/build/outputs/apk/pleos/release/app-pleos-release-unsigned.apk \
  --output apk/EasyCar_Pleos_release-next.apk \
  --keystore /private/path/to/easycar-submission.jks \
  --alias easycar-submission

unset EASYCAR_KEYSTORE_PASSWORD EASYCAR_KEY_PASSWORD
```

키 암호가 다르면 `EASYCAR_KEY_PASSWORD`를 별도로 입력합니다. 도구는 SDK의 `apksigner`를 찾아 서명하고 인증서를 검증하며 기존 출력 파일은 덮어쓰지 않습니다. Windows에서도 Python 도구를 사용할 수 있지만 환경변수 입력 구문은 해당 셸에 맞춰야 합니다.

## 자료 해석

인식용·평가용 자료는 사용자 연구 노트에서 옮긴 문헌/추론 규칙과 합성 검증 사례입니다. 실차 학습 데이터나 전문가 정답 점수로 표시하지 않습니다. 출처·미확보 항목·검증 목적은 루트 README 10절, 개선 계획은 12절에 정리했습니다. 인식·평가 수치와 기존 저장 점수는 이번 제출 준비에서 바꾸지 않았습니다.

## 이번 준비에서 확인한 결과

- 깨끗한 별도 사본에서 코어165개·앱21개 단위 검사 모두 통과했습니다. 최종 사본의 소스·자산·빌드 설정은 검사한 사본과 같습니다. 다른 PC의 최초 의존성 다운로드는 이번 오프라인 검사에 포함하지 않았습니다.
- 전용키로 서명한 Pleos Release를 분리 AVD에 설치하고 기록 조회·원래85점 보존·쉬운 설명·세 메뉴와 7개 합성 데모 결과를 실제 계측으로 확인했습니다(2개 검사 통과).
- 현재 문구 정리의 기존 디버그 장치19개 결과와 이번 릴리스2개 결과는 별도로 표시합니다.
- 개인 키·암호와 Phone용 APK를 제외하고 준비했습니다. 기존 저장소의 이력·remote는 이 initial commit에 포함하지 않습니다.

세부 로그·APK 해시·검증 한계는 [제출 준비 검증](validation/submission-ready-v0132.json)에 기록했습니다.
