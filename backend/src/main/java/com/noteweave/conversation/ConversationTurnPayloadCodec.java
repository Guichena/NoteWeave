package com.noteweave.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Owns immutable turn input/receipt serialization and frozen-input validation. */
final class ConversationTurnPayloadCodec {

    private final ObjectMapper objectMapper;

    ConversationTurnPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String requestHash(SubmitTurnCommand command) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("operation", "NEW_TURN");
        canonical.put("content", command.content());
        canonical.put("requested_turn_mode", command.requestedTurnMode());
        EffectiveRetrievalConfig retrievalConfig = command.effectiveRetrievalConfig();
        canonical.put("retrieval_config", retrievalConfig);
        canonical.put("expected_history_head_message_id", command.expectedHistoryHeadMessageId());
        try {
            byte[] payload = objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot hash turn submission", ex);
        }
    }

    String preparationJson(SubmitTurnCommand command, String activeHeadMessageId, int lockVersion) {
        Map<String, Object> preparation = new LinkedHashMap<>();
        preparation.put("content", command.content());
        preparation.put("requested_turn_mode", command.requestedTurnMode());
        EffectiveRetrievalConfig retrievalConfig = command.effectiveRetrievalConfig();
        preparation.put("source_scope", retrievalConfig.sourceScope());
        preparation.put("retrieval_strategy", retrievalConfig.strategy());
        preparation.put("retrieval_channels", retrievalConfig.channels());
        preparation.put("grounding_refs", retrievalConfig.groundingRefs());
        preparation.put("history_head_message_id", activeHeadMessageId);
        preparation.put("conversation_lock_version", lockVersion);
        preparation.put("expected_history_head_message_id", command.expectedHistoryHeadMessageId());
        try {
            return objectMapper.writeValueAsString(preparation);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot persist frozen turn preparation", ex);
        }
    }

    String writeReceipt(TurnReceipt receipt) {
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot persist turn receipt", ex);
        }
    }

    TurnReceipt readReceipt(String receiptJson) {
        try {
            return objectMapper.readValue(receiptJson, TurnReceipt.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot read persisted turn receipt", ex);
        }
    }

    SubmitTurnCommand readFrozenCommand(String conversationId, String preparationJson) {
        try {
            Map<String, Object> frozen = objectMapper.readValue(
                    preparationJson, new TypeReference<Map<String, Object>>() { });
            if (frozen == null) {
                throw invalidFrozenCommand();
            }
            String content = requiredFrozenText(frozen, "content");
            String requestedTurnMode = requiredFrozenText(frozen, "requested_turn_mode");
            List<String> sourceScope = requiredFrozenStringList(frozen, "source_scope");
            return new SubmitTurnCommand(
                    conversationId,
                    content,
                    requestedTurnMode,
                    "recovery:" + conversationId,
                    sourceScope,
                    optionalFrozenText(frozen, "expected_history_head_message_id"),
                    optionalFrozenText(frozen, "retrieval_strategy"),
                    optionalFrozenStringList(frozen, "retrieval_channels"),
                    optionalFrozenStringList(frozen, "grounding_refs")
            );
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw invalidFrozenCommand();
        }
    }

    private String requiredFrozenText(Map<String, Object> frozen, String key) {
        Object value = frozen.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalidFrozenCommand();
        }
        return text;
    }

    private String optionalFrozenText(Map<String, Object> frozen, String key) {
        if (!frozen.containsKey(key) || frozen.get(key) == null) {
            return null;
        }
        Object value = frozen.get(key);
        if (!(value instanceof String)) {
            throw invalidFrozenCommand();
        }
        return (String) value;
    }

    private List<String> requiredFrozenStringList(Map<String, Object> frozen, String key) {
        if (!frozen.containsKey(key)) {
            throw invalidFrozenCommand();
        }
        return frozenStringList(frozen.get(key));
    }

    private List<String> optionalFrozenStringList(Map<String, Object> frozen, String key) {
        if (!frozen.containsKey(key) || frozen.get(key) == null) {
            return List.of();
        }
        return frozenStringList(frozen.get(key));
    }

    private List<String> frozenStringList(Object value) {
        if (!(value instanceof List<?> values)) {
            throw invalidFrozenCommand();
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalidFrozenCommand();
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private BusinessException invalidFrozenCommand() {
        return new BusinessException(
                "TURN_RECOVERY_INPUT_INVALID", "Frozen turn preparation is invalid", HttpStatus.CONFLICT);
    }
}
