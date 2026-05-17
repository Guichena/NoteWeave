package com.noteweave.memory.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.memory.dto.MemoryItemRequest;
import com.noteweave.memory.model.MemoryItem;
import com.noteweave.memory.model.MemoryType;
import com.noteweave.memory.repository.MemoryItemRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemoryItemService {

    private final MemoryItemRepository memoryItemRepository;

    @Transactional
    public MemoryItem upsert(
            Long userId,
            Long spaceId,
            MemoryType memoryType,
            String topic,
            String summary,
            String sourceType,
            Long sourceId,
            BigDecimal importanceScore,
            BigDecimal confidenceScore,
            boolean pin,
            LocalDateTime expiresAt
    ) {
        MemoryItem memoryItem = memoryItemRepository.findCandidates(userId, spaceId, memoryType, topic).stream()
                .findFirst()
                .orElseGet(MemoryItem::new);
        if (memoryItem.getId() != null
                && memoryItem.getConfidenceScore() != null
                && memoryItem.getConfidenceScore().compareTo(confidenceScore) > 0
                && memoryType == MemoryType.USER_PREFERENCE) {
            return memoryItem;
        }
        memoryItem.setUserId(userId);
        memoryItem.setSpaceId(spaceId);
        memoryItem.setMemoryType(memoryType);
        memoryItem.setTopic(topic);
        memoryItem.setSummary(summary);
        memoryItem.setSourceType(sourceType);
        memoryItem.setSourceId(sourceId);
        memoryItem.setImportanceScore(clamp(importanceScore));
        memoryItem.setConfidenceScore(clamp(confidenceScore));
        memoryItem.setPin(pin);
        memoryItem.setExpiresAt(expiresAt);
        memoryItem.setDeletedAt(null);
        return memoryItemRepository.save(memoryItem);
    }

    @Transactional
    public MemoryItem upsertManual(Long userId, Long spaceId, MemoryItemRequest request) {
        return upsert(
                userId,
                spaceId,
                request.getMemoryType(),
                request.getTopic().trim(),
                request.getSummary().trim(),
                "MANUAL",
                null,
                request.getImportanceScore(),
                request.getConfidenceScore(),
                Boolean.TRUE.equals(request.getPin()),
                request.getExpiresAt()
        );
    }

    @Transactional(readOnly = true)
    public List<MemoryItem> listActive(Long userId, Long spaceId) {
        LocalDateTime now = LocalDateTime.now();
        if (spaceId == null) {
            return memoryItemRepository.findActiveUserLevel(userId, now);
        }
        return memoryItemRepository.findActiveByUserIdAndSpaceId(userId, spaceId, now);
    }

    @Transactional(readOnly = true)
    public List<MemoryItem> listContextEligible(Long userId, Long spaceId) {
        LocalDateTime now = LocalDateTime.now();
        if (spaceId == null) {
            return memoryItemRepository.findContextEligibleUserLevel(userId, now);
        }
        return memoryItemRepository.findContextEligibleByUserIdAndSpaceId(userId, spaceId, now);
    }

    @Transactional
    public void delete(Long userId, Long spaceId, Long memoryItemId) {
        MemoryItem memoryItem = (spaceId == null
                ? memoryItemRepository.findByIdAndUserIdAndSpaceIdIsNull(memoryItemId, userId)
                : memoryItemRepository.findByIdAndUserIdAndSpaceId(memoryItemId, userId, spaceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "memory item not found"));
        memoryItem.setDeletedAt(LocalDateTime.now());
        memoryItemRepository.save(memoryItem);
    }

    @Transactional
    public void expireDueItems(java.time.Instant now) {
        LocalDateTime time = LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC);
        for (MemoryItem item : memoryItemRepository.findExpiredUnpinned(time)) {
            item.setDeletedAt(time);
        }
    }

    private BigDecimal clamp(BigDecimal value) {
        if (value == null) {
            return BigDecimal.valueOf(0.5d);
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }
}
