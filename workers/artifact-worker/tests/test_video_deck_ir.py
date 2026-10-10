from __future__ import annotations

import hashlib
import shutil
from io import BytesIO

import pytest
from PIL import Image
from reportlab.pdfgen import canvas

from app.video_deck_ir import VideoDeckIRV1, build_video_deck_ir
from app.video_deck_render import (
    _body_font_size, _title_font_size, render_original_video_deck,
    verify_original_video_deck,
)
from app.video_deck_preview import rasterize_deck_pdf
from app.video_deck_runtime import run_video_deck_task, render_video_deck_markdown
from app.candidate_file_manifest import build_video_deck_required_files
from app.artifact_skill_catalog import CATALOG_DIGEST
from app.models import ArtifactTaskInput
from app.callback import JavaArtifactCallbackClient, run_artifact_task_with_callbacks
from app.video_knowledge_plan import VideoKnowledgePlanV1, build_local_evidence_plan
from app.video_material_bundle import VideoMaterialBundleV1


def _frozen_source(frame_bytes: bytes | None = None) -> tuple[VideoMaterialBundleV1, VideoKnowledgePlanV1]:
    image_digest = hashlib.sha256(frame_bytes).hexdigest() if frame_bytes else "b" * 64
    bundle = VideoMaterialBundleV1.model_validate({
        "bundle_id": "deck-bundle", "bundle_version": 1,
        "workspace_id": "workspace-1", "bvid": "BV1234567890", "part": 2,
        "duration_ms": 10_000, "input_digest": "a" * 64,
        "subtitle_source": "MANUAL", "transcript_original": "cache consistency",
        "transcript_corrected": "cache consistency",
        "transcript_segments": [{"segment_id": "s1", "part": 2, "start_ms": 1000,
                                 "end_ms": 4000, "original_text": "cache consistency",
                                 "corrected_text": "cache consistency"}],
        "files": [{"file_id": "file-1", "role": "VIDEO_FRAME", "media_type": "image/png",
                   "size_bytes": len(frame_bytes) if frame_bytes else 100,
                   "checksum_sha256": image_digest}],
        "frames": [{"frame_id": "f1", "part": 2, "at_ms": 2500,
                    "file_id": "file-1", "checksum_sha256": image_digest}],
        "knowledge_nodes": [{"node_id": "n1", "title": "Frame evidence",
                             "start_ms": 1000, "end_ms": 4000,
                             "transcript_segment_ids": ["s1"], "frame_ids": ["f1"]}],
        "coverage_gaps": [],
    })
    plan = VideoKnowledgePlanV1.model_validate({
        "bundle_content_digest": bundle.content_digest(), "bvid": bundle.bvid,
        "part": bundle.part, "duration_ms": bundle.duration_ms,
        "nodes": [
            {"node_id": "root", "kind": "TOPIC", "title": "Cache", "start_ms": 0,
             "end_ms": 10_000},
            {"node_id": "concept", "parent_id": "root", "kind": "CONCEPT",
             "title": "Consistency", "start_ms": 1000, "end_ms": 4000,
             "transcript_segment_ids": ["s1"], "frame_ids": ["f1"],
             "terms": ["cache"],
             "claims": [{"text": "cache consistency", "status": "EXTRACTED",
                         "evidence_refs": ["segment:s1"]}]},
        ],
    })
    return bundle, plan


def test_slide_order_and_picture_are_bound_to_frozen_evidence() -> None:
    bundle, plan = _frozen_source()
    deck = build_video_deck_ir(bundle, plan)
    assert deck.slides[0].sequence_no == 1
    assert deck.slides[0].frame_id == "f1"
    assert deck.slides[0].file_id == "file-1"
    assert deck.slides[0].image_checksum_sha256 == "b" * 64
    assert deck.slides[0].claims == ["cache consistency"]
    assert deck.slides[0].evidence_refs == ["segment:s1"]
    deck.verify_against(bundle, plan)
    assert deck.content_digest == build_video_deck_ir(bundle, plan).content_digest


def test_forged_picture_reference_is_rejected_even_with_new_digest() -> None:
    bundle, plan = _frozen_source()
    deck = build_video_deck_ir(bundle, plan)
    forged = deck.model_copy(deep=True)
    forged.slides[0].file_id = "other-file"
    forged.content_digest = forged.digest()
    forged = VideoDeckIRV1.model_validate(forged.model_dump(mode="json"))
    with pytest.raises(ValueError, match="does not match frozen"):
        forged.verify_against(bundle, plan)


def test_evidence_window_only_plan_cannot_publish_learning_deck() -> None:
    bundle, _ = _frozen_source()
    with pytest.raises(ValueError, match="verified semantic claims"):
        build_video_deck_ir(bundle, build_local_evidence_plan(bundle))


@pytest.mark.parametrize("language,label", [
    ("zh-CN", "- 原画面:"), ("en", "- Original frame:"),
    ("zh-EN", "- 原画面 / Original frame:"),
])
def test_deck_template_labels_follow_frozen_language(language, label) -> None:
    bundle, plan = _frozen_source()
    ir = build_video_deck_ir(bundle, plan, language)
    assert ir.language == language
    assert label in render_video_deck_markdown(ir)
    with pytest.raises(ValueError, match="unsupported video deck language"):
        build_video_deck_ir(bundle, plan, "fr")


def test_original_image_deck_opens_and_preserves_complete_frame(
        tmp_path, monkeypatch) -> None:
    output = BytesIO()
    Image.new("RGB", (640, 360), "navy").save(output, format="PNG")
    frame_bytes = output.getvalue()
    bundle, plan = _frozen_source(frame_bytes)
    ir = build_video_deck_ir(bundle, plan)
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))

    path = render_original_video_deck(
        "task-deck-1", ir, bundle, plan, lambda _: frame_bytes)
    assert path.is_file()
    assert path.read_bytes().startswith(b"PK")
    verify_original_video_deck(path, ir)
    with pytest.raises(ValueError, match="manifest"):
        render_original_video_deck("task-deck-2", ir, bundle, plan, lambda _: b"wrong")


def test_fixed_template_reduces_long_title_and_rejects_unreadable_density() -> None:
    assert _title_font_size("Consistency and synchronization across cache replicas") == 24
    with pytest.raises(ValueError, match="title exceeds"):
        _title_font_size("长" * 41)
    with pytest.raises(ValueError, match="claims exceed"):
        _body_font_size(["证" * 320])


def test_two_page_deck_preserves_landscape_and_portrait_frames(
        tmp_path, monkeypatch) -> None:
    first_output = BytesIO()
    second_output = BytesIO()
    Image.new("RGB", (1280, 720), "navy").save(first_output, format="PNG")
    Image.new("RGB", (720, 1280), "forestgreen").save(second_output, format="PNG")
    frames = {"file-1": first_output.getvalue(), "file-2": second_output.getvalue()}
    bundle, plan = _frozen_source(frames["file-1"])
    bundle_data = bundle.model_dump(mode="json")
    bundle_data["files"].append({
        "file_id": "file-2", "role": "VIDEO_FRAME", "media_type": "image/png",
        "size_bytes": len(frames["file-2"]),
        "checksum_sha256": hashlib.sha256(frames["file-2"]).hexdigest(),
    })
    bundle_data["frames"].append({
        "frame_id": "f2", "part": 2, "at_ms": 6500, "file_id": "file-2",
        "checksum_sha256": hashlib.sha256(frames["file-2"]).hexdigest(),
    })
    bundle_data["transcript_original"] = "cache consistency\nread repair"
    bundle_data["transcript_corrected"] = "cache consistency\nread repair"
    bundle_data["transcript_segments"].append({
        "segment_id": "s2", "part": 2, "start_ms": 5000, "end_ms": 8000,
        "original_text": "read repair", "corrected_text": "read repair",
    })
    bundle_data["knowledge_nodes"].append({
        "node_id": "n2", "title": "Repair evidence", "start_ms": 5000,
        "end_ms": 8000, "transcript_segment_ids": ["s2"], "frame_ids": ["f2"],
    })
    multi_bundle = VideoMaterialBundleV1.model_validate(bundle_data)
    plan_data = plan.model_dump(mode="json")
    plan_data["bundle_content_digest"] = multi_bundle.content_digest()
    plan_data["nodes"].append({
        "node_id": "repair", "parent_id": "root", "kind": "CONCEPT",
        "title": "Read repair", "start_ms": 5000, "end_ms": 8000,
        "transcript_segment_ids": ["s2"], "frame_ids": ["f2"],
        "terms": ["repair"],
        "claims": [{"text": "read repair", "status": "EXTRACTED",
                    "evidence_refs": ["segment:s2"]}],
    })
    multi_plan = VideoKnowledgePlanV1.model_validate(plan_data)
    ir = build_video_deck_ir(multi_bundle, multi_plan)
    assert [(slide.sequence_no, slide.frame_id) for slide in ir.slides] == [
        (1, "f1"), (2, "f2")]
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    deck = render_original_video_deck("two-page", ir, multi_bundle, multi_plan,
                                      frames.__getitem__)
    verify_original_video_deck(deck, ir)
    previews = []
    for index in (1, 2):
        preview = deck.parent / f"slide-{index:03d}.png"
        Image.new("RGB", (1600, 900), "white").save(preview)
        previews.append(preview)
    manifest = build_video_deck_required_files(
        render_video_deck_markdown(ir), deck, previews)
    assert [(file["role"], file["sequence_no"]) for file in manifest] == [
        ("PRIMARY_MARKDOWN", 0), ("PRIMARY_PPTX", 0),
        ("SLIDE_PREVIEW", 1), ("SLIDE_PREVIEW", 2)]


@pytest.mark.skipif(not shutil.which("pdfinfo") or not shutil.which("pdftoppm"),
                    reason="Poppler preview tools are unavailable")
def test_deck_pdf_rasterizer_checks_every_page(tmp_path) -> None:
    bundle, plan = _frozen_source()
    ir = build_video_deck_ir(bundle, plan)
    pdf = tmp_path / "deck.pdf"
    document = canvas.Canvas(str(pdf), pagesize=(960, 540))
    document.drawString(40, 480, "Consistency")
    document.drawString(40, 420, "cache consistency")
    document.showPage()
    document.save()
    previews = rasterize_deck_pdf(pdf, ir, tmp_path / "previews")
    assert len(previews) == 1
    with Image.open(previews[0]) as image:
        assert image.width >= 1000 and image.height >= 500

    two_pages = tmp_path / "extra.pdf"
    document = canvas.Canvas(str(two_pages), pagesize=(960, 540))
    document.showPage()
    document.showPage()
    document.save()
    with pytest.raises(ValueError, match="page count"):
        rasterize_deck_pdf(two_pages, ir, tmp_path / "other-previews")

    missing_claim = tmp_path / "missing-claim.pdf"
    document = canvas.Canvas(str(missing_claim), pagesize=(960, 540))
    document.drawString(40, 480, "Consistency")
    document.showPage()
    document.save()
    with pytest.raises(ValueError, match="lost rendered title or claim"):
        rasterize_deck_pdf(missing_claim, ir, tmp_path / "missing-preview")


def test_frozen_deck_candidate_and_required_file_manifest(tmp_path, monkeypatch) -> None:
    output = BytesIO()
    Image.new("RGB", (640, 360), "navy").save(output, format="PNG")
    frame_bytes = output.getvalue()
    bundle, plan = _frozen_source(frame_bytes)
    task = ArtifactTaskInput.model_validate({
        "task_id": "task-deck", "workspace_id": bundle.workspace_id,
        "target_id": "job-deck", "input_snapshot_id": "snapshot-deck",
        "catalog_digest": CATALOG_DIGEST,
        "control_pack": {"pack_type": "ARTIFACT", "target_key": "video_learning_deck",
                         "task_neighborhood": "ARTIFACT"},
        "input_payload": {"skill_key": "video_learning_deck", "inputs": {
            "url": "https://www.bilibili.com/video/BV1234567890",
            "video_material_bundle_id": "bundle-row-1"}},
        "frozen_video_material": bundle.model_dump(mode="json"),
        "frozen_video_knowledge_plan": plan.model_dump(mode="json"),
    })
    _, result = run_video_deck_task(task)
    assert result.result_payload["content_ir"]["artifact_type"] == "SLIDE_DECK"
    assert result.result_payload["export_trace"]["status"] == "PENDING"
    ir = VideoDeckIRV1.model_validate(result.result_payload["video_deck_ir"])
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    pptx = render_original_video_deck(task.task_id, ir, bundle, plan,
                                      lambda _: frame_bytes)
    preview = pptx.parent / "slide-001.png"
    Image.new("RGB", (1600, 900), "white").save(preview)
    files = build_video_deck_required_files(
        result.result_payload["markdown"], pptx, [preview])
    assert [item["role"] for item in files] == [
        "PRIMARY_MARKDOWN", "PRIMARY_PPTX", "SLIDE_PREVIEW"]
    assert files[1]["checksum_sha256"] == hashlib.sha256(pptx.read_bytes()).hexdigest()
    with pytest.raises(ValueError, match="path or order"):
        build_video_deck_required_files(result.result_payload["markdown"], pptx,
                                        [pptx.parent / "slide-002.png"])


def test_callback_attaches_complete_deck_files_from_frozen_host_material(
        tmp_path, monkeypatch) -> None:
    output = BytesIO()
    Image.new("RGB", (640, 360), "navy").save(output, format="PNG")
    frame_bytes = output.getvalue()
    bundle, plan = _frozen_source(frame_bytes)
    task = ArtifactTaskInput.model_validate({
        "task_id": "task-deck-callback", "workspace_id": bundle.workspace_id,
        "target_id": "job-deck", "input_snapshot_id": "snapshot-deck",
        "catalog_digest": CATALOG_DIGEST,
        "control_pack": {"pack_type": "ARTIFACT", "target_key": "video_learning_deck",
                         "task_neighborhood": "ARTIFACT"},
        "input_payload": {"skill_key": "video_learning_deck", "inputs": {
            "url": "https://www.bilibili.com/video/BV1234567890?p=2",
            "video_material_bundle_id": "bundle-row-1"}},
    })

    class Host(JavaArtifactCallbackClient):
        def __init__(self):
            super().__init__("http://host.local")
            self.completed = []

        def fetch_task_input(self, task_id):
            return task

        def fetch_video_material(self, task_id, bundle_row_id=""):
            return bundle

        def fetch_video_knowledge_plan(self, task_id, bundle_row_id, *, referenced=False):
            assert referenced
            return plan

        def fetch_video_material_files(self, task_id, material, bundle_row_id=""):
            assert material == bundle and bundle_row_id == "bundle-row-1"
            return {"file-1": frame_bytes}

        def send_progress(self, task_id, event):
            pass

        def send_complete(self, task_id, result):
            self.completed.append(result)

    def preview_stub(pptx, ir):
        path = pptx.parent / "slide-001.png"
        Image.new("RGB", (1600, 900), "white").save(path)
        return [path]

    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    monkeypatch.setattr("app.callback.render_original_video_deck_previews", preview_stub)
    host = Host()
    response = run_artifact_task_with_callbacks(task.task_id, host)
    assert response.status == "COMPLETED"
    assert len(host.completed) == 1
    payload = host.completed[0].result_payload
    assert payload["export_trace"]["preview_count"] == 1
    assert [file["role"] for file in payload["candidate"]["required_files"]] == [
        "PRIMARY_MARKDOWN", "PRIMARY_PPTX", "SLIDE_PREVIEW"]
    assert payload["candidate"]["video_material"]["content_digest"] == \
        bundle.content_digest()
