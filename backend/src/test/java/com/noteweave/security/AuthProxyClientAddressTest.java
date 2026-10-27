package com.noteweave.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * SEC-02：部署在 nginx 后面时，所有请求的 socket 对端都是代理机。
 * 打开 forward-headers-strategy 后限流必须按 X-Forwarded-For 里的真实客户端分别计数，
 * 攻击者只能锁住自己，不能把同一台代理后面的其他用户一起锁死。
 */
@SpringBootTest(properties = {
        "server.forward-headers-strategy=framework",
        "noteweave.security.login-rate-limit.ip-max-failures=3",
        "noteweave.security.login-rate-limit.login-max-failures=100",
        "noteweave.security.login-rate-limit.backoff-starts-at=100"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthProxyClientAddressTest {

    private static final String ATTACKER_IP = "203.0.113.10";
    private static final String BYSTANDER_IP = "203.0.113.20";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void forwardedClientAddressSeparatesIpBucketsBehindTheProxy() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            login("intruder-" + UUID.randomUUID(), ATTACKER_IP)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_INVALID"));
        }

        login("intruder-final", ATTACKER_IP)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_RATE_LIMITED"));

        // socket 对端相同（都是 MockMvc 的默认地址），只有 X-Forwarded-For 不同：
        // 另一个客户端不应被攻击者的失败次数牵连
        login("bystander", BYSTANDER_IP)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_INVALID"));
    }

    private ResultActions login(String login, String forwardedFor) throws Exception {
        return mockMvc.perform(post("/api/v2/auth/login")
                .header("X-Forwarded-For", forwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "login", login,
                        "password", "Definitely-Wrong-Password"))));
    }
}
