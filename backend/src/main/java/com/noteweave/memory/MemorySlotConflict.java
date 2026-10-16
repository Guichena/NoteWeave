package com.noteweave.memory;

import java.util.List;

/** 同一槽位上的确定性冲突判定。语义近邻不在这里处理。 */
public final class MemorySlotConflict {
    private MemorySlotConflict() {
    }

    public record Peer(
            String itemId,
            String displayText,
            String slot,
            String value,
            String polarity,
            boolean sameNeighborhood
    ) {
    }

    public static Outcome exact(MemorySlots.Assignment incoming, List<Peer> peers) {
        Peer equivalent = null;
        if (incoming != null && peers != null && !"unknown".equals(incoming.slot())) {
            for (Peer peer : peers) {
                if (peer == null || !peer.sameNeighborhood() || !incoming.slot().equals(peer.slot())) {
                    continue;
                }
                if (incoming.value().equals(peer.value()) && incoming.polarity().equals(peer.polarity())) {
                    equivalent = peer;
                    continue;
                }
                if (incoming.value().equals(peer.value())) {
                    return conflict("POLARITY", peer);
                }
                return conflict("SLOT_VALUE", peer);
            }
        }
        if (equivalent != null) {
            return new Outcome(
                    "NONE",
                    "EXISTING_EQUIVALENT",
                    "NOOP",
                    equivalent.itemId(),
                    equivalent.displayText(),
                    List.of());
        }
        return none();
    }

    private static Outcome conflict(String type, Peer peer) {
        return new Outcome(
                type,
                "CONFLICTING_ACTIVE_MEMORY",
                "SUPERSEDE",
                peer.itemId(),
                peer.displayText(),
                List.of());
    }

    public static Outcome none() {
        return new Outcome("NONE", "NO_CONFLICT", "ADD", "", "", List.of());
    }

    public static Outcome degraded(String reason) {
        return new Outcome("NONE", "NO_CONFLICT", "ADD", "", "", List.of(reason));
    }

    public static Outcome checkNotRun(String itemId, String text, String reason) {
        return new Outcome(
                "CHECK_NOT_RUN",
                "CONFLICT_CHECK_NOT_RUN",
                "REVIEW",
                itemId == null ? "" : itemId,
                text == null ? "" : text,
                List.of(reason));
    }

    public record Outcome(
            String conflictType,
            String legacyStatus,
            String action,
            String conflictingItemId,
            String conflictingText,
            List<String> degradationReasons
    ) {
        public boolean blocking() {
            return "CONFLICTING_ACTIVE_MEMORY".equals(legacyStatus)
                    || "CONFLICT_CHECK_NOT_RUN".equals(legacyStatus);
        }
    }
}
