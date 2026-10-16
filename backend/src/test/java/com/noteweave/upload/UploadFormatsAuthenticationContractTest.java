package com.noteweave.upload;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.noteweave.security.TokenHasher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "noteweave.security.local-user-fallback=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadFormatsAuthenticationContractTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void authenticatedFormatsRequestDoesNotResolveFormatsAsAnUploadId() throws Exception {
        String token = "upload-formats-" + UUID.randomUUID();
        jdbc.update("""
                insert into user_session(id, user_id, session_token, token_hash, status)
                values (?, 'local-user', '', ?, 'ACTIVE')
                """, UUID.randomUUID().toString(), TokenHasher.sha256(token));
        mvc.perform(get("/api/v2/uploads/formats").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void formatsRequestWithoutAuthenticationReturns401() throws Exception {
        mvc.perform(get("/api/v2/uploads/formats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
}
