package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;

/** Verifies that a persisted checkpoint reference still resolves to the immutable bytes it names. */
final class ResearchCheckpointIntegrity {

    private ResearchCheckpointIntegrity() {
    }

    static void verify(ResearchCheckpointRecord record, byte[] payload) {
        if (payload == null
                || payload.length != record.contentSize()
                || record.payloadSha256() == null
                || !record.payloadSha256().equalsIgnoreCase(sha256(payload))) {
            throw new BusinessException(
                    "RESEARCH_CHECKPOINT_CORRUPTED",
                    "Research checkpoint payload does not match its persisted size and digest",
                    HttpStatus.CONFLICT
            );
        }
    }

    static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
