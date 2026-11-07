#!/usr/bin/env python3
"""Verify eight real app recordings, then edit/concatenate/amplify them.

This file only runs when invoked by the parent after all scene captures finish.
It never changes scene originals, sessions, verification files, or app source.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any

DEFAULT_ROOT = Path(__file__).resolve().parents[2] / 'recordings-20261107'
LABELS = {
    'START': '출발', 'LEFT': '좌회전', 'RIGHT': '우회전', 'U_TURN': '유턴',
    'STOP': '정지', 'T_PARK': 'T자 주차', 'PARALLEL_PARK': '평행주차',
}
PARKING = {'T_PARK', 'PARALLEL_PARK'}
REQUIRED_SCORES = {100, 90, 85, 60}
CROP = (0, 26, 1870, 1052)


class RenderError(RuntimeError):
    pass


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError) as exc:
        raise RenderError(f'Cannot read complete JSON: {path}: {exc}') from exc


def finite(value: Any, name: str) -> float:
    if isinstance(value, bool):
        raise RenderError(f'{name} must be a finite number')
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise RenderError(f'{name} must be a finite number') from exc
    if not math.isfinite(number):
        raise RenderError(f'{name} must be finite')
    return number


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def save_json(path: Path, data: Any) -> None:
    if path.exists():
        raise RenderError(f'Refusing to overwrite existing output: {path}')
    temporary = path.with_name(path.name + '.tmp')
    if temporary.exists():
        raise RenderError(f'Refusing to overwrite temporary output: {temporary}')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def scene_metadata(root: Path) -> list[dict[str, Any]]:
    scene_root = root / 'scenes'
    if not scene_root.is_dir():
        raise RenderError(f'Scene directory is missing: {scene_root}')
    found: dict[int, Path] = {}
    for folder in scene_root.iterdir():
        if not folder.is_dir():
            continue
        match = re.match(r'^(\d{2})[-_]', folder.name)
        if not match:
            continue
        index = int(match.group(1))
        if index in found:
            raise RenderError(f'Duplicate scene number {index:02d}')
        found[index] = folder
    if set(found) != set(range(1, 9)):
        raise RenderError(f'Exactly scenes01 through08 are required; found {sorted(found)}. Wait until capture finishes.')
    result: list[dict[str, Any]] = []
    for index in range(1, 9):
        folder = found[index]
        original = folder / 'original.mp4'
        if not original.is_file() or original.stat().st_size == 0:
            raise RenderError(f'Original MP4 is missing/empty: {original}')
        verification = read_json(folder / 'verification.json')
        if verification.get('exactMatch') is not True or verification.get('error') is not None:
            raise RenderError(f'App verification did not pass: {folder / "verification.json"}')
        actual, expected = verification.get('actual'), verification.get('expected')
        if actual != expected or not isinstance(actual, list) or len(actual) != 1:
            raise RenderError(f'Expected exactly one matching real app result in {folder.name}')
        session = read_json(folder / 'session.json')
        session_id = session.get('id')
        source = str(session.get('source', ''))
        if session.get('demoCaseId') is not None or '8088' not in source or '모델' not in source:
            raise RenderError(f'Scene must use the8088 model source and no built-in demoCaseId: {folder.name}')
        results = session.get('results', [])
        if not session_id or not isinstance(results, list):
            raise RenderError(f'Invalid app session in {folder.name}')
        pairs = [[item.get('type'), item.get('score')] for item in results]
        if pairs != actual:
            raise RenderError(f'Saved app session differs from verification in {folder.name}: {pairs} versus {actual}')
        maneuver, score = actual[0]
        if maneuver not in LABELS or not isinstance(score, int) or not 0 <= score <= 100:
            raise RenderError(f'Invalid maneuver/score in {folder.name}')
        result_id = results[0].get('id')
        feedbacks = session.get('feedbacks', [])
        if not result_id or not isinstance(feedbacks, list) or len(feedbacks) != 1:
            raise RenderError(f'Exactly one target narration is required in saved app session: {folder.name}')
        feedback = feedbacks[0]
        if feedback.get('resultId') != result_id or feedback.get('type') != maneuver or feedback.get('score') != score or feedback.get('voiceState') != 'SPOKEN':
            raise RenderError(f'Target narration type/score/resultId must match and finish SPOKEN: {folder.name}')
        result.append({
            'index': index, 'scene': folder.name, 'folder': folder, 'original': original,
            'sessionId': session_id, 'resultId': result_id, 'type': maneuver, 'score': score,
            'title': f'{index:02d} {LABELS[maneuver]}', 'exactMatch': True,
            'source': session.get('source'), 'profileVersion': session.get('profileVersion'),
            'sourceSHA256': sha256(original),
        })
    types = {item['type'] for item in result}
    scores = {item['score'] for item in result}
    if types != set(LABELS):
        raise RenderError(f'All seven requested maneuver types must be present; found {sorted(types)}')
    if not REQUIRED_SCORES.issubset(scores):
        raise RenderError(f'100/90/85/60 must all appear in actual app results; found {sorted(scores)}')
    return result


def completed_host_time(scene: dict[str, Any]) -> float | None:
    path = scene['folder'] / 'observed-feedback.jsonl'
    if not path.is_file():
        return None
    matches: list[float] = []
    with path.open(encoding='utf-8') as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except ValueError as exc:
                raise RenderError(f'Incomplete observation JSONL at {path}:{number}') from exc
            state = row.get('state') or {}
            observed_session = state.get('id')
            if not observed_session and isinstance(state.get('session'), dict):
                observed_session = state['session'].get('id')
            if observed_session != scene['sessionId']:
                continue  # Previous-session snapshots must never trim the current scene.
            if any(item.get('resultId') == scene['resultId'] and item.get('voiceState') == 'SPOKEN'
                   for item in state.get('feedbacks', [])):
                matches.append(finite(row.get('hostTime'), f'{path}:{number} hostTime'))
    return min(matches) if matches else None


def select_end(scene: dict[str, Any], original_duration: float) -> dict[str, Any]:
    timing_path = scene['folder'] / 'recording-timing.json'
    if timing_path.is_file():
        timing = read_json(timing_path)
        origin = finite(timing.get('hostEpochAtVideoStart'), str(timing_path))
        completed = completed_host_time(scene)
        if completed is not None:
            spoken_offset = completed - origin
            if spoken_offset < 0 or spoken_offset > original_duration + 0.1:
                raise RenderError(f'SPOKEN timestamp is outside the original recording: {scene["scene"]}: {spoken_offset}')
            desired = spoken_offset + 1.3
            return {'endSeconds': min(desired, original_duration), 'endSource': 'same-session-result-first-SPOKEN-plus1.3s',
                    'spokenAtOriginalSeconds': spoken_offset, 'hostEpochAtVideoStart': origin,
                    'firstSpokenHostTime': completed, 'requestedEndSeconds': desired}
    # Conservative, explicitly requested fallback for the first two captures.
    if scene['index'] == 1 and scene['type'] == 'START':
        return {'endSeconds': min(22.0, original_duration), 'endSource': 'requested-start-fixed22s-no-timing'}
    if scene['index'] == 2 and scene['type'] == 'LEFT':
        return {'endSeconds': min(20.0, original_duration), 'endSource': 'requested-left-fixed20s-no-timing'}
    phases = read_json(scene['folder'] / 'phase-times.json')
    if not isinstance(phases, list):
        raise RenderError(f'Invalid phase-times array for {scene["scene"]}')
    ends = [finite(item.get('videoStartSeconds'), 'phase end videoStartSeconds')
            for item in phases if item.get('name') == 'end']
    if not ends:
        raise RenderError(f'No SPOKEN timestamp or phase end for {scene["scene"]}')
    end = max(ends)
    return {'endSeconds': min(end, original_duration), 'endSource': 'phase-times-end-videoStartSeconds',
            'requestedEndSeconds': end}


class MediaRunner:
    def __init__(self, tool: Path, logs: Path | None):
        self.tool = tool
        self.logs = logs

    def run(self, arguments: list[str], name: str, timeout: int = 600) -> dict[str, Any]:
        command = [str(self.tool), *arguments]
        print(f'{name}: {arguments[0]}', file=sys.stderr, flush=True)
        completed = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
        if self.logs is not None:
            save_json(self.logs / f'{name}-command.json', {'argv': command, 'returncode': completed.returncode})
            (self.logs / f'{name}-stderr.log').write_text(completed.stderr, encoding='utf-8')
            (self.logs / f'{name}-stdout.json').write_text(completed.stdout, encoding='utf-8')
        if completed.returncode:
            raise RenderError(f'{name} failed ({completed.returncode}): {completed.stderr.strip()}')
        try:
            parsed = json.loads(completed.stdout)
        except ValueError as exc:
            raise RenderError(f'{name} did not return valid JSON: {completed.stdout[:200]}') from exc
        if not isinstance(parsed, dict):
            raise RenderError(f'{name} returned a non-object result')
        return parsed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recording-root', type=Path, default=DEFAULT_ROOT,
                        help='Defaults to recordings-20261107/ in the repository root.')
    parser.add_argument('--output-dir', type=Path, help='Defaults to the recording root; originals always stay in scenes/.')
    parser.add_argument('--media-tool', type=Path, default=Path('/tmp/easycar-media-tool'))
    parser.add_argument('--plan-only', action='store_true', help='Verify all eight scenes and probe media, then print the plan without editing.')
    args = parser.parse_args()
    root = args.recording_root.resolve()
    output = (args.output_dir or root).resolve()
    tool = args.media_tool.resolve()
    if not tool.is_file() or not os.access(tool, os.X_OK):
        raise RenderError(f'Compiled media helper is unavailable: {tool}')
    scenes = scene_metadata(root)  # Every exactMatch passes before any media tool runs.
    quiet = output / 'masterquiet.mp4'
    final = output / 'EasyCar-8088-demo.mp4'
    chapters_file = output / 'chapters.json'
    plan_file = output / 'render-plan.json'
    edited = output / 'edited'
    logs = output / 'render-logs'
    reserved = [quiet, final, chapters_file, plan_file, *[edited / f'{item["index"]:02d}.mp4' for item in scenes]]
    if not args.plan_only:
        existing = [str(path) for path in reserved if path.exists()]
        if existing:
            raise RenderError('Refusing to overwrite generated output; use a fresh --output-dir: ' + ', '.join(existing))
        if logs.exists():
            raise RenderError(f'Use a fresh output directory; render logs already exist: {logs}')
        output.mkdir(parents=True, exist_ok=True)
        edited.mkdir(parents=True, exist_ok=True)
        logs.mkdir()
    runner = MediaRunner(tool, None if args.plan_only else logs)
    plan: list[dict[str, Any]] = []
    for scene in scenes:
        media = runner.run(['probe', str(scene['original'])], f'{scene["index"]:02d}-source-probe')
        source_duration = finite(media.get('durationSeconds'), 'original duration')
        if int(media.get('audioTracks', 0)) != 1:
            raise RenderError(f'Exactly one captured app audio track is required: {scene["scene"]}')
        if finite(media.get('orientedWidth'), 'source width') < CROP[0] + CROP[2] or finite(media.get('orientedHeight'), 'source height') < CROP[1] + CROP[3]:
            raise RenderError(f'Given Qt-chrome crop does not fit source dimensions: {scene["scene"]}')
        start = 10.0 if scene['type'] == 'START' else 6.0 if scene['type'] in PARKING else 2.0
        ending = select_end(scene, source_duration)
        end = ending['endSeconds']
        if end <= start + 3.0:
            raise RenderError(f'Clip would be too short or lose its narration: {scene["scene"]}: {start}..{end}')
        item = {key: value for key, value in scene.items() if key not in {'folder', 'original'}}
        item.update({'original': str(scene['original']), 'originalDurationSeconds': source_duration,
                     'cutStartSeconds': start, **ending, 'durationSeconds': end-start,
                     'crop': list(CROP), 'titleDurationSeconds': 3,
                     'edited': str(edited / f'{scene["index"]:02d}.mp4')})
        plan.append(item)
    document = {'schemaVersion': 1, 'createdUTC': datetime.now(timezone.utc).isoformat(),
                'recordingRoot': str(root), 'allEightAppExactMatches': True,
                'allSevenManeuverTypes': sorted({item['type'] for item in plan}),
                'actualScores': sorted({item['score'] for item in plan}, reverse=True),
                'crop': list(CROP), 'requestedGainDB': 18, 'peakLimit': 0.85,
                'quietMaster': str(quiet), 'finalMaster': str(final), 'scenes': plan}
    if args.plan_only:
        print(json.dumps(document, ensure_ascii=False, indent=2))
        return
    save_json(plan_file, document)
    chapters: list[dict[str, Any]] = []
    cursor = 0.0
    for item in plan:
        number = f'{item["index"]:02d}'
        runner.run(['edit', '--input', item['original'], '--output', item['edited'],
                    '--start', str(item['cutStartSeconds']), '--duration', str(item['durationSeconds']),
                    '--crop', ','.join(map(str, CROP)), '--title', item['title'],
                    '--title-duration', '3', '--fps', '30'], f'{number}-edit')
        probe = runner.run(['probe', item['edited']], f'{number}-edited-probe')
        length = finite(probe.get('durationSeconds'), 'edited duration')
        if int(probe.get('orientedWidth', 0)) != CROP[2] or int(probe.get('orientedHeight', 0)) != CROP[3] or int(probe.get('audioTracks', 0)) != 1:
            raise RenderError(f'Edited clip lost expected picture/audio geometry: {number}')
        chapters.append({**item, 'masterStartSeconds': cursor, 'masterEndSeconds': cursor + length,
                         'editedDurationSeconds': length, 'editedSHA256': sha256(Path(item['edited']))})
        cursor += length
    runner.run(['concat', '--output', str(quiet), *[item['edited'] for item in plan], '--passthrough'], 'masterquiet-concat')
    gain = runner.run(['gain', '--input', str(quiet), '--output', str(final), '--gain-db', '18', '--peak-limit', '0.85'], 'final-gain18')
    media = runner.run(['probe', str(final), '--audio-stats'], 'final-probe-with-audio-stats')
    audio = media.get('audioLevels') or {}
    actual_gain = finite(gain.get('effectiveGainDB'), 'effective gain')
    if not 16 <= actual_gain <= 18.01:
        raise RenderError(f'Peak protection reduced voice gain outside16~18dB target: {actual_gain}')
    if audio.get('nonSilent') is not True or not 0 < finite(audio.get('peak'), 'final peak') < 0.9:
        raise RenderError('Final master is silent or exceeds required audio headroom')
    if int(media.get('audioTracks', 0)) != 1 or abs(finite(media.get('durationSeconds'), 'final duration') - cursor) > 0.15:
        raise RenderError('Final master audio track count/duration differs from the verified chapter timeline')
    for item in plan:
        if sha256(Path(item['original'])) != item['sourceSHA256']:
            raise RenderError(f'Original recording changed during rendering: {item["original"]}')
    save_json(chapters_file, {**document, 'scenes': chapters, 'gainReport': gain, 'finalMediaProbe': media,
                             'finalSHA256': sha256(final), 'originalRecordingsUnchanged': True,
                             'finalDurationSeconds': cursor, 'chapterTitleOverlaysContainTypesOnly': True})
    print(json.dumps({'finalMaster': str(final), 'chapters': str(chapters_file), 'durationSeconds': cursor,
                      'actualScores': document['actualScores'], 'effectiveGainDB': actual_gain,
                      'peak': audio['peak'], 'rms': audio.get('rms'), 'originalsUnchanged': True}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (RenderError, subprocess.SubprocessError, OSError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        sys.exit(1)
