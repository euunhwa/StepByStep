#!/usr/bin/env python3
"""Copy the current working tree into a clean folder for another repository.

Uncommitted and untracked files are included. Local caches, build outputs,
Git history, and private settings/signing material are excluded.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import zipfile


SOURCE_ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_DIRECTORIES = {
    '.git', '.gradle', '.kotlin', '.idea', 'build', '__pycache__', '.private-signing',
}
GENERATED_FILES = {'FILE_MANIFEST.json', 'SHA256SUMS'}
PRIVATE_SUFFIXES = {'.pyc', '.jks', '.keystore', '.p12', '.pfx'}


class PreparationError(RuntimeError):
    pass


def excluded_file(name: str) -> bool:
    lower = name.lower()
    return (
        lower in {'local.properties', '.ds_store', '.env'}
        or (lower.startswith('.env.') and lower != '.env.example')
        or Path(lower).suffix in PRIVATE_SUFFIXES
    )


def source_files(source: Path) -> list[Path]:
    """Collect from disk rather than the Git index; never follow symlinks."""
    result: list[Path] = []
    for directory, directories, files in os.walk(source, followlinks=False):
        parent = Path(directory)
        for name in directories + files:
            if (parent / name).is_symlink():
                raise PreparationError(f'Symlinks are not supported: {(parent / name).relative_to(source)}')
        directories[:] = sorted(name for name in directories if name not in EXCLUDED_DIRECTORIES)
        for name in sorted(files):
            path = parent / name
            if excluded_file(name) or (parent == source and name in GENERATED_FILES):
                continue
            if not stat.S_ISREG(path.stat().st_mode):
                raise PreparationError(f'Only regular files can be copied: {path.relative_to(source)}')
            if any(character in path.relative_to(source).as_posix() for character in '\r\n'):
                raise PreparationError(f'Line breaks in filenames are not supported: {path.relative_to(source)!s}')
            result.append(path)
    return sorted(result, key=lambda path: path.relative_to(source).as_posix())


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def version_name(source: Path) -> str:
    text = (source / 'app/build.gradle.kts').read_text(encoding='utf-8')
    match = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.MULTILINE)
    if not match:
        raise PreparationError('Cannot read versionName from app/build.gradle.kts')
    return match.group(1)


def output_path(value: Path, source: Path, label: str) -> Path:
    if value.is_symlink():
        raise PreparationError(f'{label} cannot be a symlink: {value}')
    resolved = value.expanduser().resolve()
    if resolved == source or resolved.is_relative_to(source):
        raise PreparationError(f'{label} must be outside the source project')
    return resolved


def verify_output(output: Path) -> list[Path]:
    files: list[Path] = []
    for directory, directories, names in os.walk(output, followlinks=False):
        parent = Path(directory)
        for name in directories + names:
            path = parent / name
            if path.is_symlink():
                raise PreparationError(f'Unexpected symlink in output: {path.relative_to(output)}')
        for name in directories:
            if name in EXCLUDED_DIRECTORIES:
                raise PreparationError(f'Excluded directory reached output: {(parent / name).relative_to(output)}')
        for name in names:
            path = parent / name
            if excluded_file(name) or not path.is_file():
                raise PreparationError(f'Excluded or nonregular file reached output: {path.relative_to(output)}')
            files.append(path)
    return sorted(files, key=lambda path: path.relative_to(output).as_posix())


def prepare_repository(source: Path, output: Path, archive: Path | None = None) -> dict:
    source = source.resolve()
    output = output_path(output, source, 'Output folder')
    if source.is_relative_to(output):
        raise PreparationError('Output folder cannot contain the source project')
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise PreparationError(f'Output must be a new or empty folder: {output}')
    if archive is not None:
        archive = output_path(archive, source, 'ZIP path')
        if archive.exists():
            raise PreparationError(f'ZIP path already exists: {archive}')
        if archive == output or archive.is_relative_to(output):
            raise PreparationError('ZIP path must be outside the output folder')
    files = source_files(source)
    source_version = version_name(source)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise PreparationError(f'Output folder is no longer empty: {output}')
    for path in files:
        destination = output / path.relative_to(source)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists() or destination.is_symlink():
            raise PreparationError(f'Refusing to overwrite: {destination}')
        shutil.copy2(path, destination)
    copied = verify_output(output)
    records = [
        {'path': path.relative_to(output).as_posix(), 'bytes': path.stat().st_size, 'sha256': sha256(path)}
        for path in copied
    ]
    manifest = {
        'schemaVersion': 1,
        'sourceVersionName': source_version,
        'createdUTC': datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z'),
        'files': records,
    }
    manifest_path = output / 'FILE_MANIFEST.json'
    with manifest_path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    # The manifest describes the copied payload; checksums also cover the manifest.
    checksum_records = records + [{
        'path': manifest_path.name, 'sha256': sha256(manifest_path),
    }]
    with (output / 'SHA256SUMS').open('x', encoding='utf-8', newline='\n') as stream:
        for record in sorted(checksum_records, key=lambda item: item['path']):
            stream.write(f'{record["sha256"]}  {record["path"]}\n')
    final_files = verify_output(output)
    if len(final_files) != len(records) + 2:
        raise PreparationError('Output contents changed during preparation')
    if archive is not None:
        archive.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED,
                             compresslevel=9, strict_timestamps=False) as bundle:
            bundle.write(output, output.name + '/')
            for path in final_files:
                # ZipFile.write preserves each file's Unix mode, including gradlew.
                bundle.write(path, f'{output.name}/{path.relative_to(output).as_posix()}')
        with zipfile.ZipFile(archive) as bundle:
            corrupt = bundle.testzip()
            if corrupt is not None:
                raise PreparationError(f'ZIP integrity check failed: {corrupt}')
    return {
        'sourceVersionName': source_version,
        'payloadFiles': len(records),
        'payloadBytes': sum(record['bytes'] for record in records),
        'output': str(output),
        'zip': str(archive) if archive else None,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True,
                        help='New or empty folder outside this source project.')
    parser.add_argument('--zip', type=Path, dest='archive',
                        help='Optional new ZIP path outside the source and output folders.')
    args = parser.parse_args()
    try:
        result = prepare_repository(SOURCE_ROOT, args.output, args.archive)
    except (OSError, PreparationError, zipfile.BadZipFile) as exc:
        print(f'Preparation failed: {exc}', file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
