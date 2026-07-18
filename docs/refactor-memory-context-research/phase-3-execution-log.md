# Phase 3 Execution Log

## 2026-07-18 — SG-01 bounded active raw tail

Public seams:

```text
POST /api/v2/conversations/{conversationId}/messages
GET  /api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot
```

RED: the Snapshot contained no segment or raw-tail projection fields.

GREEN: `V077` introduces append-only Segment and Summary Revision storage. `ConversationContextProjectionService` stores the ordered eight-message maximum `CURRENT` tail in the immutable Snapshot, excluding the new pending assistant placeholder. A completed Answer assistant is promoted to `CURRENT` only after its content is persisted, so it becomes eligible for later projections. `inputSnapshotUsesABoundedContiguousActiveRawTail` passes.

## 2026-07-18 — SG-02 READY summary selection

RED: a prepared `READY` Summary Revision was ignored and the Snapshot kept an empty summary list.

GREEN: the projection selects a ready prefix revision only when its remaining contiguous raw tail fits the bounded budget. The Snapshot carries the Segment/Revision/hash reference and starts raw references at the following sequence, without overlap. `readySegmentSummaryReplacesItsCoveredMessagesInTheSnapshot` passes.

The actual Answer compiler now uses the same frozen projection rather than its former independent `limit 12` query. It receives the bounded raw-message content and, when selected, the READY Summary text; Snapshot references and prompt context therefore share one selection rule.

## Next slice

## 2026-07-18 — SG-04 fenced summary promotion

Worker seam:

```text
POST /internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}/promote
```

RED: the promotion route did not exist and fell through to static-resource handling.

GREEN: promotion locks the Segment and Revision in one transaction. A mismatch between `source_segment_version` and Segment `lock_version` marks the BUILDING Revision `STALE` and returns `SEGMENT_SUMMARY_PROMOTION_STALE` (409); matching versions write the summary/hash and promote it to `READY`. The next submitted Run sees only a successfully promoted revision. Both `staleSummaryPromotionCannotMakeItsRevisionVisibleToANewRun` and `currentSummaryPromotionMakesTheRevisionVisibleToANewRun` pass.

## 2026-07-18 — SG-03 asynchronous active-prefix builds

Public seams:

```text
GET /api/v2/workspaces/{workspaceId}/conversations/{conversationId}/segment-summary-builds
POST /internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}/promote
```

RED: no build-status endpoint existed after a completed Answer, and fenced promotion left its associated Task `PENDING`.

GREEN: after a completed Answer grows the active path beyond the raw tail, the module creates one immutable
prefix Segment, a `BUILDING` Summary Revision, a `CONVERSATION_SUMMARY` Task, and a `READY` outbox message on
`noteweave.conversation.summary`. The payload freezes ordered message ids, sequence numbers, roles, bodies, and
hashes. It starts at a three-message minimum and covers enough of the prefix for the *next* submission's tail to
remain exactly contiguous. The generic leased outbox dispatcher now owns the new topic.

The status endpoint exposes identifiers, coverage, revision state, and Task state without exposing frozen bodies.
A successful fenced promotion completes the linked task in the same transaction. `finalizedAnswerQueuesOneAsyncSummaryBuildForTheOldActivePrefix` and `promotedAutomaticallyQueuedSummaryIsSelectedByTheNextSnapshot` pass.

## Next slice

Add active-path invalidation/deletion propagation for already-recorded Snapshot references, then extend automatic
summary build triggering to every conversation completion path (including completed Research reports).

## 2026-07-18 — SG-05 deletion propagation and all completion paths

RED: a completed Research report did not queue the next prefix build; neither message nor Summary Revision had
a deletion boundary that could downgrade an already-recorded replay.

GREEN: a Research report queues a build only after its report card has been projected to the active conversation.
`DELETE /api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}` is a soft,
body-erasing deletion: it marks affected snapshots `METADATA_ONLY`, invalidates overlapping Segment revisions,
and excludes the message from later projection. The paired internal revision deletion endpoint erases the Summary
body, marks it `DELETED`, and downgrades snapshots that referenced it. `V078` makes `DELETED` a valid revision state.

`completedResearchReportQueuesTheNextActivePrefixSummaryBuild`,
`deletingAReferencedMessageRedactsItsBodyAndDowngradesReplay`, and
`deletingAReadySummaryRedactsEarlierReplayAndExcludesItFromNewSnapshots` pass. Full
`ConversationTurnModuleContractTest`: 32/32 passing.
