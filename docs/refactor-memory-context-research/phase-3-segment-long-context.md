# Phase 3 Spec: Segment Long Context

## Goal

Replace the implicit fixed recent-message window with a versioned conversation projection:

```text
READY SegmentSummary revisions + contiguous recent raw-message tail + current user message
```

The projection is selected at submission time and recorded in `RunInputSnapshot`. It is never reconstructed from the current conversation state on retry or replay.

## Public seams

```text
POST /api/v2/conversations/{conversationId}/messages
GET  /api/v2/workspaces/{workspaceId}/runs/{executionKind}/{runId}/input-snapshot
GET  /api/v2/conversations/{conversationId}/messages
DELETE /api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}
GET  /api/v2/workspaces/{workspaceId}/conversations/{conversationId}/segment-summary-builds
POST /internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}/promote
DELETE /internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}
```

The Snapshot response is the observation boundary for the compiler result. Phase 3 adds these fields to its `snapshot` payload:

```text
segment_summary_refs[]
  segment_id, summary_revision_id, covered_start_seq, covered_end_seq, content_hash

recent_message_refs[]
  message_id, message_seq, role, content_hash
```

The conversation-message endpoint remains the boundary for verifying that raw-tail references point to current-path messages only.
Message deletion is a soft, body-erasing operation: it is the boundary for observing redaction and replay availability
after a referenced message has been removed.
The internal promotion seam is the worker completion boundary; it accepts an already-generated summary and applies the Segment version fence before making it visible.
The paired internal deletion seam erases a revision body, marks it ineligible for future compilation, and redacts
Snapshots that had selected it.
The summary-build list is the public observation boundary for asynchronous construction. Each item exposes
the immutable segment range, building revision, and its Task state, but never the frozen message bodies.
Builds begin only when the active prefix has at least three messages; their coverage is chosen so the next
submission's raw tail begins immediately after the prefix.

## Invariants

- A Segment belongs to exactly one conversation and covers a contiguous, inclusive message sequence range.
- A SegmentSummary revision is append-only. Only `READY` revisions are eligible for compilation.
- The compiler must choose one active path only: `PENDING`, `FAILED`, `CANCELLED`, and `SUPERSEDED` messages never enter summary or tail.
- The selected summary coverage and raw tail have no gap and no overlap:
  `max(covered_end_seq) + 1 = min(recent_tail.message_seq)` when both are present.
- If no eligible `READY` summary exists, compilation falls back to a bounded raw tail. It does not invent a summary or read a future message.
- A summary build may create `BUILDING` revisions, but promotion to `READY` uses a Segment version/CAS check. A stale builder cannot supersede newer conversation state.
- Deleting a referenced message or summary makes replay `METADATA_ONLY` or `UNAVAILABLE`; it never substitutes new content.

## TDD scenarios

### SG-01 Bounded raw-tail baseline

For a conversation with more than the configured tail limit and no ready summaries, submitting a new Answer records only the contiguous most-recent active messages in `recent_message_refs`; `segment_summary_refs` is empty. A repeated submission with the same client request id returns the same Run and the same projection.

### SG-02 Ready summary replaces older raw messages

Given a `READY` summary that covers the early active range, a new Run Snapshot references that immutable revision and begins its raw tail at `covered_end_seq + 1`. It must not include any covered message again as raw content.

### SG-03 BUILDING summary is ignored

A `BUILDING` revision is never selected. Submission falls back to the bounded raw tail until a correctly fenced promotion makes a `READY` revision visible.

### SG-04 Stale completion cannot promote

If a summary worker begins from Segment version N and the segment changes before it finishes, its promotion is rejected. The next Snapshot keeps the last READY revision or falls back to raw tail.

### SG-05 Deletion propagation

Deleting a message referenced by a Snapshot lowers replay availability and removes body access; later snapshots do not rebuild the old projection using replacement messages.

## First vertical slice

Deliver SG-01 only:

1. Add immutable `conversation_segment` and `segment_summary_revision` storage.
2. Compile a bounded, active-path raw tail for Answer and Research submission.
3. Store the selected raw refs in `RunInputSnapshot.snapshot_json`.
4. Assert the projection only through submit/message/snapshot HTTP seams.

Summary generation, ready-revision selection, and async/CAS promotion follow in separate red-green slices after the baseline is stable.

## Definition of done

- SG-01 through SG-05 pass through the stated public seams.
- The active-path and gap/overlap invariants are enforced in code and database constraints where possible.
- Snapshot selection is retry-safe, versioned, and deletion-aware.
- Existing Answer, Research, SSE, and Evidence flows remain compatible.
