from __future__ import annotations

import subprocess
from pathlib import Path

import pytest
from PIL import Image

from app.video_frame_capture import capture_local_video, sample_times


def test_sampler_preserves_opening_and_near_end_under_frame_cap() -> None:
    assert sample_times(60_000) == (0, 30_000, 59_500)
    times = sample_times(600_000, interval_ms=1_000, max_frames=16)
    assert len(times) == 16
    assert times[0] == 0
    assert times[-1] == 599_500


def test_capture_records_actual_pts_dedupes_pixels_and_keeps_last_frame(tmp_path: Path) -> None:
    video = tmp_path / "video.mp4"
    video.write_bytes(b"synthetic test stream")
    colors = ["red", "red", "blue"]
    pts = ["0", "30.040", "59.480"]
    calls: list[list[str]] = []

    def fake_run(command, **kwargs):
        index = len(calls)
        calls.append(command)
        Image.new("RGB", (2, 2), colors[index]).save(command[-1], format="PNG")
        return subprocess.CompletedProcess(command, 0, "", f"[Parsed_showinfo_0] pts_time:{pts[index]}")

    result = capture_local_video(
        video_path=video, output_dir=tmp_path / "frames", sandbox_root=tmp_path,
        part=2, duration_ms=60_000, ffmpeg_path="ffmpeg-test", run=fake_run,
    )

    assert [frame.at_ms for frame in result.frames] == [0, 30_040, 59_480]
    assert [frame.part for frame in result.frames] == [2, 2, 2]
    assert result.frames[1].dedupe_of == result.frames[0].frame_id
    assert result.frames[1].file_id == result.frames[0].file_id
    assert result.frames[2].dedupe_of == ""
    assert result.frames[2].path.is_file()
    assert not (tmp_path / "frames" / "frame-2-001.png").exists()
    assert result.missing_requested_ms == ()
    assert all("-i" in command and str(video) in command for command in calls)


def test_capture_rejects_external_paths_and_records_missing_frames(tmp_path: Path) -> None:
    sandbox = tmp_path / "sandbox"
    sandbox.mkdir()
    outside = tmp_path / "outside.mp4"
    outside.write_bytes(b"synthetic test stream")
    with pytest.raises(ValueError, match="in-sandbox"):
        capture_local_video(
            video_path=outside, output_dir=sandbox / "frames", sandbox_root=sandbox,
            part=1, duration_ms=2_000, ffmpeg_path="ffmpeg-test",
        )

    video = sandbox / "video.mp4"
    video.write_bytes(b"synthetic test stream")

    def no_frame(command, **kwargs):
        return subprocess.CompletedProcess(command, 0, "", "")

    result = capture_local_video(
        video_path=video, output_dir=sandbox / "frames", sandbox_root=sandbox,
        part=1, duration_ms=2_000, ffmpeg_path="ffmpeg-test", run=no_frame,
    )
    assert result.frames == ()
    assert result.missing_requested_ms == (0, 1500)
