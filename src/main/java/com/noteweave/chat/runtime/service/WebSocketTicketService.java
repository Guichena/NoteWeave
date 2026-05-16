package com.noteweave.chat.runtime.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebSocketTicketService {

    private static final Duration TICKET_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate stringRedisTemplate;

    public String createTicket(Long userId) {
        String ticket = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(key(ticket), String.valueOf(userId), TICKET_TTL);
        return ticket;
    }

    public Long consumeTicket(String ticket) {
        String value = stringRedisTemplate.opsForValue().getAndDelete(key(ticket));
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.WS_TICKET_INVALID);
        }
        return Long.valueOf(value);
    }

    private String key(String ticket) {
        return "chat:ws-ticket:" + ticket;
    }
}
