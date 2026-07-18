# Phase 6 Spec: Events, Evaluation, Deletion Propagation and Legacy Cleanup

## Goal

Finish the migration by making canonical state observable, proving safety boundaries end to end, propagating deletion through every frozen input, and removing production dependencies on legacy Memory/context/Research paths.

## Public seams

```text
GET /api/v2/workspaces/{workspaceId}/conversations/{conversationId}/events
GET /api/v2/workspaces/{workspaceId}/runs/{executionKind}/{runId}/input-snapshot
GET /internal/worker/artifact-tasks/{taskId}/input
GET /api/v2/workspaces/{workspaceId}/memory/shadow-recall
GET /api/v2/workspaces/{workspaceId}/memory/review
```

Inspector/demo seams introduced by this phase:

```text
GET /api/v2/workspaces/{workspaceId}/context-inspector/runs/{executionKind}/{runId}
GET /api/v2/workspaces/{workspaceId}/memory/inspector
GET /api/v2/workspaces/{workspaceId}/run-replay/{executionKind}/{runId}
```

## Invariants

- Research progress projected to a conversation is ordered, workspace-scoped and cannot create a second local message path.
- RAG/Web/report text never writes preference or convention Memory without trusted user/project provenance.
- Deleting Source, Report, Message, Summary or Memory revision removes frozen body content and downgrades every affected replay to `METADATA_ONLY`.
- Cross-workspace access to snapshots, inspectors, events and replay returns resource-not-found/forbidden without leaking identity.
- Context Inspector explains selected summary/message/memory/source/upstream revision identities without mutating runtime state.
- Memory Inspector reads the canonical Item/Revision lifecycle.
- Run Replay uses the frozen snapshot only; it never recompiles current context.
- Production Answer/Research/Artifact reads no legacy Memory tables after cutover.
- Legacy Research result/message routes are rejected or removed after canonical atomic completion is the only writer.

## TDD slices

1. Deleting an Artifact-selected Source redacts its frozen source body and marks the Artifact snapshot `METADATA_ONLY`.
2. Deleting a Research Report revision propagates to downstream Artifact snapshots through typed upstream refs.
3. Research progress appears on Conversation SSE exactly once and in task-event order.
4. Cross-workspace inspector, replay and worker-input requests cannot reveal IDs or payloads.
5. Web/Source/Report prompt-injection observations cannot create canonical Memory revisions.
6. Context Inspector, Memory Inspector and Run Replay expose the canonical identities selected at execution time.
7. Architecture tests prohibit production prompt compilation from `memory_object/memory_version` and prohibit new Research-local conversation writes.
8. Remove legacy writers/routes after parity tests prove canonical behavior.
9. Run focused E2E, deletion, replay, authorization, poisoning and performance gates.

## Definition of done

Canonical events, snapshots and inspectors are the only supported observability/replay path; deletion and authorization are proven across Answer, Research and Artifact; production code no longer depends on legacy Memory write/read or duplicate Research message assembly.
