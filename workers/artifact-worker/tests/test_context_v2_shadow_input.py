from app.models import ArtifactTaskInput


def test_worker_accepts_frozen_context_shadow_identity_without_using_it_as_generation_input():
    task = ArtifactTaskInput.model_validate({
        "task_id": "task-1", "workspace_id": "workspace-1", "target_id": "job-1",
        "control_pack": {"pack_type": "artifact", "target_key": "study_guide",
                         "task_neighborhood": "ARTIFACT_SKILL_STUDY_GUIDE"},
        "input_payload": {"skill_key": "study_guide", "user_requirement": "Original request"},
        "context_v2_shadow": {
            "snapshot_id": "shadow-1", "projection_sha256": "a" * 64,
            "compiler_version": "context-v2-test", "memory_revision_ids": ["revision-1"],
            "replay_availability": "FULL",
        },
    })

    assert task.input_payload.user_requirement == "Original request"
    assert task.context_v2_shadow.snapshot_id == "shadow-1"
    assert task.context_v2_shadow.memory_revision_ids == ["revision-1"]
