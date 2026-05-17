package com.noteweave.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.memory.model.MemoryItem;
import com.noteweave.memory.model.SpaceMemory;
import com.noteweave.memory.repository.SpaceMemoryRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SpaceMemoryService {

    private final SpaceMemoryRepository spaceMemoryRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SpaceMemory getOrCreate(Long userId, Long spaceId) {
        return spaceMemoryRepository.findByUserIdAndSpaceId(userId, spaceId).orElseGet(() -> {
            SpaceMemory memory = new SpaceMemory();
            memory.setUserId(userId);
            memory.setSpaceId(spaceId);
            return spaceMemoryRepository.save(memory);
        });
    }

    @Transactional
    public void refreshFromItems(Long userId, Long spaceId, List<MemoryItem> items) {
        SpaceMemory memory = getOrCreate(userId, spaceId);
        memory.setTopic(items.isEmpty() ? null : items.get(0).getTopic());
        memory.setSummary(items.stream().map(MemoryItem::getSummary).limit(3).reduce((a, b) -> a + "\n" + b).orElse(null));
        memory.setConversationPatternsJson(writeJson(items.stream()
                .map(item -> java.util.Map.of("topic", item.getTopic(), "summary", item.getSummary()))
                .toList()));
        memory.setExpiresAt(LocalDateTime.now().plusDays(30));
        spaceMemoryRepository.save(memory);
    }

    @Transactional(readOnly = true)
    public SpaceMemory get(Long userId, Long spaceId) {
        return spaceMemoryRepository.findByUserIdAndSpaceId(userId, spaceId).orElse(null);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }
}
