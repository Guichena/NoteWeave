# Phase 4 Spec: Canonical MemoryRuntime

## Goal

Converge existing Memory Object/Version data behind one runtime. Answer, Research and Artifact emit observations only; they do not write canonical memory directly.

## Public seams

```text
GET  /api/v2/workspaces/{workspaceId}/memory/shadow-recall
GET  /api/v2/workspaces/{workspaceId}/memory/review
POST /api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review
```

The shadow-recall response is the initial observation boundary: it returns the legacy pack and runtime pack separately, without changing prompt compilation.

## Invariants

- Only USER and WORKSPACE scope are valid; workspace rows never cross workspace boundaries.
- A scope-aware slot has one Item and at most one ACTIVE Revision.
- Observation IDs are idempotent; untrusted Source/Web/Report text cannot create preference or convention memory.
- Stale, rejected, deleted and superseded revisions are never recalled.
- Shadow recall is read-only and cannot change the currently compiled prompt.

## TDD slices

1. Shadow recall returns an empty runtime pack for an empty workspace while preserving the legacy pack unchanged.
2. Active workspace constraints appear in the runtime pack; stale or proposed revisions do not.
3. Reviewer acceptance activates one revision and supersedes the former active revision atomically.
4. Repeated observation IDs create no duplicate revision or event.

## Definition of done

Runtime recall, observation, review and user commands use one canonical lifecycle; Inspector, snapshots and deletion propagation expose the selected revision identities.
