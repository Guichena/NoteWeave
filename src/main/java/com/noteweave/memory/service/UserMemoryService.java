package com.noteweave.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.memory.model.MemoryItem;
import com.noteweave.memory.model.UserMemory;
import com.noteweave.memory.repository.UserMemoryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserMemoryService {

    private final UserMemoryRepository userMemoryRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public UserMemory getOrCreate(Long userId) {
        return userMemoryRepository.findByUserId(userId).orElseGet(() -> {
            UserMemory memory = new UserMemory();
            memory.setUserId(userId);
            memory.setMemoryWriteEnabled(true);
            return userMemoryRepository.save(memory);
        });
    }

    @Transactional(readOnly = true)
    public boolean isWriteEnabled(Long userId) {
        return userMemoryRepository.findByUserId(userId)
                .map(UserMemory::isMemoryWriteEnabled)
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public UserMemory get(Long userId) {
        return userMemoryRepository.findByUserId(userId).orElse(null);
    }

    @Transactional
    public UserMemory setWriteEnabled(Long userId, boolean enabled) {
        UserMemory memory = getOrCreate(userId);
        memory.setMemoryWriteEnabled(enabled);
        return userMemoryRepository.save(memory);
    }

    @Transactional
    public void refreshFromItems(Long userId, List<MemoryItem> items) {
        UserMemory memory = getOrCreate(userId);
        memory.setSummary(items.stream().map(MemoryItem::getSummary).limit(3).reduce((a, b) -> a + "\n" + b).orElse(null));
        Map<String, String> preferences = new LinkedHashMap<>();
        for (MemoryItem item : items) {
            preferences.put(item.getTopic(), item.getSummary());
        }
        memory.setPreferencesJson(writeJson(preferences));
        userMemoryRepository.save(memory);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
