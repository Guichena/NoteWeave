package com.noteweave;

import com.noteweave.worker.ArtifactOutboxPublisher;
import com.noteweave.artifact.ArtifactWorkerExportClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@TestConfiguration
class TestResearchOutboxPublisherConfig {

    @Bean
    @Primary
    ArtifactWorkerExportClient artifactWorkerExportClient() {
        java.util.concurrent.ConcurrentHashMap<String, byte[]> exports = new java.util.concurrent.ConcurrentHashMap<>();
        return (taskId, fileName) -> {
            if (fileName.startsWith("missing-")) {
                throw new com.noteweave.common.BusinessException(
                        "ARTIFACT_EXPORT_FETCH_FAILED", "worker export unavailable",
                        org.springframework.http.HttpStatus.BAD_GATEWAY);
            }
            return exports.computeIfAbsent(taskId + ":" + fileName, key -> {
                if (fileName.endsWith(".png")) {
                    try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                                2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
                        javax.imageio.ImageIO.write(image, "png", output);
                        return output.toByteArray();
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException("test PNG generation failed", ex);
                    }
                }
                try (org.apache.pdfbox.pdmodel.PDDocument document =
                             new org.apache.pdfbox.pdmodel.PDDocument();
                     java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                    document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
                    document.save(output);
                    return output.toByteArray();
                } catch (java.io.IOException ex) {
                    throw new IllegalStateException("test PDF generation failed", ex);
                }
            });
        };
    }

    @Bean
    OncePerRequestFilter artifactWorkerCallbackHeaders(JdbcTemplate jdbcTemplate) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain
            ) throws ServletException, IOException {
                String prefix = "/internal/worker/tasks/";
                if (!request.getRequestURI().startsWith(prefix)) {
                    filterChain.doFilter(request, response);
                    return;
                }
                String remainder = request.getRequestURI().substring(prefix.length());
                String taskId = remainder.contains("/")
                        ? remainder.substring(0, remainder.indexOf('/'))
                        : remainder;
                String suppliedDeliveryToken = request.getHeader("X-NoteWeave-Outbox-Delivery-Token");
                String deliveryToken = suppliedDeliveryToken == null
                        ? claimArtifactDelivery(jdbcTemplate, taskId)
                        : suppliedDeliveryToken;
                if (deliveryToken.isBlank()) {
                    filterChain.doFilter(request, response);
                    return;
                }
                String callbackToken = taskCallbackToken(taskId);
                HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
                    @Override
                    public String getHeader(String name) {
                        if ("X-NoteWeave-Outbox-Delivery-Token".equalsIgnoreCase(name)) {
                            return deliveryToken;
                        }
                        if ("X-NoteWeave-Task-Callback-Token".equalsIgnoreCase(name)
                                && super.getHeader(name) == null) {
                            return callbackToken;
                        }
                        return super.getHeader(name);
                    }

                    @Override
                    public Enumeration<String> getHeaders(String name) {
                        String value = getHeader(name);
                        return value == null
                                ? Collections.emptyEnumeration()
                                : Collections.enumeration(List.of(value));
                    }

                    @Override
                    public Enumeration<String> getHeaderNames() {
                        LinkedHashSet<String> names = new LinkedHashSet<>();
                        Enumeration<String> existing = super.getHeaderNames();
                        while (existing.hasMoreElements()) {
                            names.add(existing.nextElement());
                        }
                        names.add("X-NoteWeave-Outbox-Delivery-Token");
                        if (super.getHeader("X-NoteWeave-Task-Callback-Token") == null) {
                            names.add("X-NoteWeave-Task-Callback-Token");
                        }
                        return Collections.enumeration(names);
                    }
                };
                filterChain.doFilter(wrapped, response);
            }
        };
    }

    private static String claimArtifactDelivery(JdbcTemplate jdbcTemplate, String taskId) {
        List<String> existing = jdbcTemplate.queryForList("""
                select lease_owner from task_outbox
                where task_id = ? and topic = 'noteweave.artifact.job'
                  and status in ('PROCESSING', 'SENT') and lease_owner is not null
                order by created_at desc, id desc limit 1
                """, String.class, taskId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String token = "test-delivery:" + java.util.UUID.randomUUID();
        int claimed = jdbcTemplate.update("""
                update task_outbox
                set status = 'PROCESSING', claimed_at = current_timestamp,
                    lease_owner = ?, lease_until = ?, attempt_count = attempt_count + 1
                where task_id = ? and topic = 'noteweave.artifact.job' and status = 'READY'
                """,
                token,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(4200)),
                taskId
        );
        return claimed == 1 ? token : "";
    }

    private static String taskCallbackToken(String taskId) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    "test-artifact-callback-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            byte[] digest = mac.doFinal(
                    ("noteweave-worker-callback:v1:ARTIFACT_JOB:" + taskId)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Bean
    @Primary
    RecordingArtifactOutboxPublisher recordingArtifactOutboxPublisher() {
        return new RecordingArtifactOutboxPublisher();
    }

    record PublishedMessage(String topic, String messageKey, String payloadJson, String deliveryToken) {
    }

    static class RecordingArtifactOutboxPublisher implements ArtifactOutboxPublisher {

        private final List<PublishedMessage> messages = new ArrayList<>();
        private boolean failNextPublish;

        @Override
        public void publish(String topic, String messageKey, String payloadJson, String deliveryToken) {
            if (failNextPublish) {
                failNextPublish = false;
                throw new IllegalStateException("simulated artifact worker outage");
            }
            messages.add(new PublishedMessage(topic, messageKey, payloadJson, deliveryToken));
        }

        List<PublishedMessage> messages() {
            return messages;
        }

        void reset() {
            messages.clear();
            failNextPublish = false;
        }

        void failNextPublish() {
            failNextPublish = true;
        }
    }
}
