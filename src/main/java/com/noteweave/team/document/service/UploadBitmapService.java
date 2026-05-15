package com.noteweave.team.document.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UploadBitmapService {

    private final StringRedisTemplate stringRedisTemplate;

    public boolean isChunkUploaded(Long uploadId, int chunkIndex) {
        Boolean value = stringRedisTemplate.opsForValue().getBit(bitmapKey(uploadId), chunkIndex);
        return Boolean.TRUE.equals(value);
    }

    public void markChunkUploaded(Long uploadId, int chunkIndex, Duration ttl) {
        String key = bitmapKey(uploadId);
        stringRedisTemplate.opsForValue().setBit(key, chunkIndex, true);
        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            stringRedisTemplate.expire(key, ttl);
        }
    }

    public List<Integer> getUploadedChunks(Long uploadId, int totalChunks) {
        List<Integer> uploaded = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (isChunkUploaded(uploadId, i)) {
                uploaded.add(i);
            }
        }
        return uploaded;
    }

    public void clear(Long uploadId) {
        stringRedisTemplate.delete(bitmapKey(uploadId));
    }

    private String bitmapKey(Long uploadId) {
        return "upload:" + uploadId;
    }
}
