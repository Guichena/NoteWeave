# Phase 5 Spec: Artifact Explicit Input

## Goal

Artifact jobs consume only inputs explicitly selected by the caller. Job retries and regenerations reuse an immutable input snapshot; they never discover newer workspace Sources or a newer Research report implicitly.

## Public seams

```text
POST /api/v2/workspaces/{workspaceId}/artifact-jobs
GET  /internal/worker/artifact-tasks/{taskId}/input
```

Creation request additions:

```json
{
  "source_scope_source_ids": ["source-id"],
  "upstream_refs": [
    {
      "ref_type": "RESEARCH_REPORT",
      "ref_id": "research-run-id",
      "revision_id": "source-snapshot-id"
    }
  ]
}
```

Both arrays are explicit and may be empty. Missing arrays are normalized to empty arrays for JSON compatibility; missing never means all workspace Sources.

## Invariants

- Artifact creation never loads the latest or all READY Sources implicitly.
- Every requested Source belongs to the job workspace and is READY at capture time.
- The snapshot records a concrete Source Snapshot identity and the worker reads the frozen snapshot, not the mutable Source row.
- Upstream refs are typed and revision-specific. A Research Report ref must identify the exact report revision represented by a Source Snapshot.
- A retry or regeneration reuses the original Artifact RunInputSnapshot unless the user creates a new job with changed explicit inputs.
- Cross-workspace Source and upstream references fail before task dispatch.
- Deleting a selected Source or upstream revision makes replay metadata-only and prevents body replay.

## TDD slices

1. A job created without `source_scope_source_ids` receives an empty worker Source scope even when READY Sources exist.
2. A job receives only explicitly selected Source IDs; a later Source cannot enter worker input.
3. The worker input remains byte-for-byte tied to the captured Source Snapshot after the Source receives a newer snapshot.
4. An explicit Research Report upstream ref resolves only when its revision belongs to the requested Research Run and workspace.
5. Regeneration reuses the same Artifact RunInputSnapshot identity and upstream revisions.
6. Source/report deletion downgrades replay and removes frozen body content.

## Definition of done

Artifact job creation, worker input, regeneration and Research-to-Artifact handoff use one immutable Artifact RunInputSnapshot. No Artifact code path performs an implicit workspace-wide Source lookup.
