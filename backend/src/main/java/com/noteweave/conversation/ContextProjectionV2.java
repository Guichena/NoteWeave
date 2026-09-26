package com.noteweave.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Frozen conversation and approved Memory references. Source evidence is a separate trust domain. */
public record ContextProjectionV2(
        String schemaVersion,
        String compilerVersion,
        String workspaceId,
        String actorId,
        String conversationId,
        int cutoffSeq,
        String currentInput,
        List<RawMessage> rawTail,
        List<TopicSummary> topicSummaries,
        List<UserConstraint> constraints,
        List<MemoryRevision> memoryRevisions,
        List<Decision> decisions,
        int tokenBudget,
        int selectedTokens,
        List<String> degradationReasons,
        String replayAvailability
) {
    public ContextProjectionV2 {
        rawTail = List.copyOf(rawTail);
        topicSummaries = List.copyOf(topicSummaries);
        constraints = List.copyOf(constraints);
        memoryRevisions = List.copyOf(memoryRevisions);
        decisions = List.copyOf(decisions);
        degradationReasons = List.copyOf(degradationReasons);
        if (!"context-projection-v2".equals(schemaVersion) || blank(compilerVersion)
                || blank(workspaceId) || blank(actorId) || currentInput == null || cutoffSeq < 0
                || tokenBudget < 0 || selectedTokens < 0 || selectedTokens > tokenBudget
                || !("FULL".equals(replayAvailability) || "METADATA_ONLY".equals(replayAvailability))) {
            throw new IllegalArgumentException("invalid context projection envelope");
        }
        if (blank(conversationId) && (cutoffSeq != 0 || !rawTail.isEmpty()
                || !topicSummaries.isEmpty() || !constraints.isEmpty())) {
            throw new IllegalArgumentException("conversation references require a conversation identity");
        }
        int previousSeq = 0;
        Set<String> messageIds = new HashSet<>();
        for (RawMessage message : rawTail) {
            if (message.seq() <= previousSeq || message.seq() > cutoffSeq
                    || !messageIds.add(message.messageId())) {
                throw new IllegalArgumentException("raw tail must be ordered and bound to cutoff");
            }
            previousSeq = message.seq();
        }
        Set<String> revisionIds = new HashSet<>();
        for (TopicSummary summary : topicSummaries) {
            if (summary.startSeq() < 1 || summary.endSeq() > cutoffSeq
                    || !revisionIds.add(summary.revisionId())) {
                throw new IllegalArgumentException("topic summary must be frozen within cutoff");
            }
        }
        for (int index = 0; index < topicSummaries.size(); index++) {
            TopicSummary first = topicSummaries.get(index);
            for (int other = index + 1; other < topicSummaries.size(); other++) {
                TopicSummary second = topicSummaries.get(other);
                if (first.startSeq() <= second.endSeq() && second.startSeq() <= first.endSeq()) {
                    throw new IllegalArgumentException("selected topic summaries overlap");
                }
            }
        }
        for (UserConstraint constraint : constraints) {
            if (constraint.validFromSeq() > cutoffSeq) {
                throw new IllegalArgumentException("constraint is newer than the frozen cutoff");
            }
        }
        if ("METADATA_ONLY".equals(replayAvailability) && (!currentInput.isBlank()
                || rawTail.stream().anyMatch(message -> !message.text().isEmpty())
                || topicSummaries.stream().anyMatch(summary -> !summary.text().isEmpty())
                || constraints.stream().anyMatch(constraint -> !constraint.text().isEmpty())
                || memoryRevisions.stream().anyMatch(memory -> !memory.text().isEmpty()))) {
            throw new IllegalArgumentException("redacted projection cannot carry derived text");
        }
    }

    public record RawMessage(String messageId, int seq, String role, String text,
                             String contentSha256) {
        public RawMessage {
            if (blank(messageId) || seq < 1 || blank(role) || text == null
                    || !sha256(text).equals(contentSha256)) {
                throw new IllegalArgumentException("raw message reference or digest is invalid");
            }
        }
    }

    public record TopicSummary(String topicId, String segmentId, String revisionId,
                               int startSeq, int endSeq, String text, String contentSha256) {
        public TopicSummary {
            if (blank(topicId) || blank(segmentId) || blank(revisionId) || startSeq < 1
                    || endSeq < startSeq || text == null || !sha256(text).equals(contentSha256)) {
                throw new IllegalArgumentException("topic summary reference or digest is invalid");
            }
        }
    }

    public record UserConstraint(String constraintId, String sourceMessageId, String sourceRole,
                                 String kind, String scope, String text,
                                 int validFromSeq, Integer invalidAfterSeq, String status) {
        public UserConstraint {
            if (blank(constraintId) || blank(sourceMessageId) || !"USER".equals(sourceRole)
                    || blank(kind) || blank(scope)
                    || text == null || validFromSeq < 1
                    || (invalidAfterSeq != null && invalidAfterSeq < validFromSeq)
                    || !("ACTIVE".equals(status) || "REVOKED".equals(status)
                    || "UNRESOLVED".equals(status))) {
                throw new IllegalArgumentException("user constraint reference is invalid");
            }
        }
    }

    public record MemoryRevision(String memoryId, String revisionId, String text,
                                 String contentSha256) {
        public MemoryRevision {
            if (blank(memoryId) || blank(revisionId) || text == null
                    || !sha256(text).equals(contentSha256)) {
                throw new IllegalArgumentException("Memory revision reference or digest is invalid");
            }
        }
    }

    public record Decision(String refType, String refId, String action, String reason) {
        public Decision {
            if (!Set.of("MESSAGE", "TOPIC_SUMMARY", "USER_CONSTRAINT", "MEMORY_REVISION")
                    .contains(refType) || blank(refId) || !Set.of("INCLUDE", "EXCLUDE").contains(action)
                    || blank(reason)) {
                throw new IllegalArgumentException("context selection decision is invalid");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
