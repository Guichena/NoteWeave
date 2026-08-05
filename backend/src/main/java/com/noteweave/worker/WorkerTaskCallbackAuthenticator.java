package com.noteweave.worker;

import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class WorkerTaskCallbackAuthenticator {

    static final String HEADER_NAME = "X-NoteWeave-Task-Callback-Token";

    private final JdbcTemplate jdbcTemplate;
    private final String artifactSecret;

    public WorkerTaskCallbackAuthenticator(
            JdbcTemplate jdbcTemplate,
            @Value("${noteweave.worker.artifact-callback-secret:}") String artifactSecret
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.artifactSecret = normalize(artifactSecret);
    }

    public void authenticate(String taskId, String suppliedToken) {
        String taskType = jdbcTemplate.query(
                "select task_type from task where id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                taskId
        );
        if (taskType == null) {
            throw new BusinessException("TASK_NOT_FOUND", "Task does not exist", HttpStatus.NOT_FOUND);
        }
        String secret = switch (taskType) {
            case "ARTIFACT_JOB" -> artifactSecret;
            default -> throw new BusinessException(
                    "WORKER_CALLBACK_TASK_TYPE_INVALID",
                    "Task is not owned by an external worker callback",
                    HttpStatus.CONFLICT
            );
        };
        if (secret.isBlank()) {
            throw new BusinessException(
                    "WORKER_CALLBACK_AUTH_NOT_CONFIGURED",
                    "Worker callback secret is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        String expected = tokenFor(secret, taskType, taskId);
        if (suppliedToken == null || suppliedToken.isBlank()
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(
                    "WORKER_CALLBACK_AUTH_INVALID",
                    "Worker callback token does not own this task",
                    HttpStatus.UNAUTHORIZED
            );
        }
    }

    static String tokenFor(String secret, String taskType, String taskId) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    ("noteweave-worker-callback:v1:" + taskType + ":" + taskId)
                            .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
