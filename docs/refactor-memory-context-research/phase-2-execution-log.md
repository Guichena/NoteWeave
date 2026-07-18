# Phase 2 Execution Log

## 2026-07-18 — SR-06 Research Final Evidence Manifest

Public seams under test:

```text
POST /internal/worker/tasks/{taskId}/complete
GET  /api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/evidence
```

RED: the evidence GET endpoint returned HTTP 500 because no immutable research evidence manifest or endpoint existed.

GREEN: migration `V076` adds a one-per-run manifest, ordered evidence items, and a report-content hash. The ordinary `WorkerTaskCallback` completion path writes the manifest inside the report-completion transaction. The read endpoint returns the original citation id, source reference, excerpt, and SHA-256 content hash.

Regression: `completedResearchExposesAnImmutableFinalEvidenceManifest` submits a Deep Research run, completes it with an explicit citation, then sends a different late completion callback. The terminal acknowledgement is returned, and the public evidence endpoint still returns exactly the original evidence item. Surefire result: 1 test, 0 failures, 0 errors.

## 2026-07-18 — SR-05 Research source-deletion propagation

Public seams under test:

```text
DELETE /api/v2/workspaces/{workspaceId}/sources/{sourceId}
GET    /api/v2/workspaces/{workspaceId}/runs/RESEARCH/{runId}/input-snapshot
GET    /api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/evidence
```

RED: a deleted source left the associated Research Snapshot at `FULL` replay availability.

GREEN: `RunReplayRedactionService` now redacts matching research evidence excerpts and content hashes, then changes matching Research snapshots from `FULL` to `METADATA_ONLY`. The source id remains for audit purposes. `deletingResearchEvidenceSourceRedactsTheManifestAndDowngradesReplay` passes (1 test, 0 failures, 0 errors).

## 2026-07-18 — SR-06 INCREMENTAL_V1 finalization

Public seam under test:

```text
GET /api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/evidence
```

RED: an `INCREMENTAL_V1` finalizer completed the report, but the evidence API returned `RESEARCH_EVIDENCE_MANIFEST_NOT_FOUND`.

GREEN: `ResearchAgentIncrementalFinalizationService` now persists the same V076 manifest inside its finalization transaction. It derives ordered immutable entries only from `research_cell_evidence` rows joined to `source_evidence`; the plain JSON evidence-reference list is never treated as excerpt content. `finalizedIncrementalResearchExposesCitationGatedEvidenceManifest` passes, and the complete finalizer regression is 6 tests, 0 failures, 0 errors.

## 2026-07-18 — SR-06 recovery for pre-V076 incremental runs

Recovery decision: an idempotent incremental finalizer replay backfills a missing V076 manifest from the still citation-gated ledger, without changing the completed report, task, or artifact.

RED: after simulating a completed pre-V076 run by deleting its manifest, finalizer replay left the evidence API at `RESEARCH_EVIDENCE_MANIFEST_NOT_FOUND`.

GREEN: the completed finalizer branch now calls the manifest writer, which is a no-op when the immutable header already exists. `idempotentIncrementalFinalizerBackfillsAMissingEvidenceManifest` passes (1 test, 0 failures, 0 errors).

## Phase 2 exit status

SR-01 through SR-06 are covered for Answer, ordinary Research worker callbacks, and citation-gated incremental finalization. Snapshot and Manifest deletion propagation is covered through the public APIs. Phase 2 is ready for final regression and review; the next implementation phase is the context/memory model described in Phase 3.
