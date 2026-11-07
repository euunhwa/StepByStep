#!/usr/bin/env python3
"""환경변수의 암호로 APK를 서명하고 검증합니다. 키는 생성하지 않습니다."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def build_tools_version(path):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(.*)", path.name)
    if not match:
        return (0, 0, 0, False, path.name)
    return (*map(int, match.group(1, 2, 3)), not match.group(4), match.group(4))


def find_apksigner(explicit):
    if explicit:
        candidate = explicit.expanduser()
        if not candidate.is_file():
            raise ValueError("--apksigner에 지정한 실행 파일을 찾을 수 없습니다.")
        return candidate.resolve()
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(variable)
        if not sdk:
            continue
        build_tools = Path(sdk).expanduser() / "build-tools"
        if not build_tools.is_dir():
            continue
        for version in sorted(build_tools.iterdir(), key=build_tools_version, reverse=True):
            candidate = version / ("apksigner.bat" if os.name == "nt" else "apksigner")
            if candidate.is_file():
                return candidate.resolve()
    raise ValueError("ANDROID_HOME/ANDROID_SDK_ROOT를 설정하거나 --apksigner를 지정하세요.")


def publish_without_overwrite(staged, output):
    """같은 파일시스템의 완성 파일만 게시하고, 이번에 만든 링크만 롤백합니다."""
    pairs = [(staged, output)]
    staged_idsig = staged.with_name(staged.name + ".idsig")
    if staged_idsig.is_file():
        pairs.append((staged_idsig, output.with_name(output.name + ".idsig")))
    published = []
    try:
        for source, destination in pairs:
            os.link(source, destination)
            published.append((source, destination))
    except BaseException:
        for source, destination in reversed(published):
            try:
                if source.samefile(destination):
                    destination.unlink()
            except OSError:
                pass
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="서명되지 않은 APK를 서명·검증합니다. 기존 출력은 덮어쓰지 않습니다.",
        epilog="암호: EASYCAR_KEYSTORE_PASSWORD 필수, EASYCAR_KEY_PASSWORD 생략 시 같은 암호 사용.",
    )
    parser.add_argument("--input", required=True, type=Path, help="서명 전 APK")
    parser.add_argument("--output", required=True, type=Path, help="새로 저장할 서명 APK")
    parser.add_argument("--keystore", required=True, type=Path, help="기존 서명 키 저장소")
    parser.add_argument("--alias", required=True, help="키 별칭")
    parser.add_argument("--apksigner", type=Path, help="SDK build-tools의 apksigner 경로")
    args = parser.parse_args(argv)

    try:
        source = args.input.expanduser().resolve()
        keystore = args.keystore.expanduser().resolve()
        output = args.output.expanduser().absolute()
        idsig = output.with_name(output.name + ".idsig")
        if not source.is_file():
            raise ValueError("입력 APK를 찾을 수 없습니다.")
        if not keystore.is_file():
            raise ValueError("키 저장소를 찾을 수 없습니다.")
        if os.path.lexists(output) or os.path.lexists(idsig):
            raise ValueError("출력 APK 또는 .idsig가 이미 있습니다. 새 출력 이름을 지정하세요.")
        if not args.alias.strip():
            raise ValueError("키 별칭이 비어 있습니다.")
        env = os.environ.copy()
        if not env.get("EASYCAR_KEYSTORE_PASSWORD"):
            raise ValueError("EASYCAR_KEYSTORE_PASSWORD 환경변수를 설정하세요.")
        env["EASYCAR_KEY_PASSWORD"] = (
            env.get("EASYCAR_KEY_PASSWORD") or env["EASYCAR_KEYSTORE_PASSWORD"]
        )
        apksigner = find_apksigner(args.apksigner)
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".easycar-sign-", dir=output.parent) as temporary:
            staged = Path(temporary) / output.name
            # 암호 값과 키 경로가 오류 로그에 노출되지 않도록 도구 출력을 보관합니다.
            subprocess.run(
                [str(apksigner), "sign", "--ks", str(keystore), "--ks-key-alias", args.alias,
                 "--ks-pass", "env:EASYCAR_KEYSTORE_PASSWORD",
                 "--key-pass", "env:EASYCAR_KEY_PASSWORD",
                 "--out", str(staged), str(source)],
                env=env, check=True, capture_output=True, text=True,
            )
            verification = subprocess.run(
                [str(apksigner), "verify", "--verbose", "--print-certs", str(staged)],
                env=env, check=True, capture_output=True, text=True,
            )
            publish_without_overwrite(staged, output)
            print(verification.stdout, end="")
            if verification.stderr:
                print(verification.stderr, end="", file=sys.stderr)
        print(f"서명·검증 완료: {output.name}")
        return 0
    except ValueError as error:
        print(f"오류: {error}", file=sys.stderr)
    except subprocess.CalledProcessError as error:
        step = "서명" if "sign" in error.cmd else "검증"
        print(f"오류: APK {step}에 실패했습니다. 키·별칭·암호와 입력 APK를 확인하세요.", file=sys.stderr)
    except OSError:
        print("오류: 파일 접근 또는 apksigner 실행에 실패했습니다. 경로·권한·SDK/JDK를 확인하세요.", file=sys.stderr)
    except KeyboardInterrupt:
        print("서명을 취소했습니다.", file=sys.stderr)
        return 130
    return 1


if __name__ == "__main__":
    sys.exit(main())
