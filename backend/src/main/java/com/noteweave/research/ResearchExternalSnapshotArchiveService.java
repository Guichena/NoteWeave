package com.noteweave.research;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the immutable, server-owned copy of external text before it may be
 * referenced by an atomic Research Agent completion.
 */
@Service
public class ResearchExternalSnapshotArchiveService {

    private static final int MAX_CONTENT_CHARS = 131_072;
    private final JdbcTemplate jdbcTemplate;
    private final ResearchAgentPermitService permitService;

    public ResearchExternalSnapshotArchiveService(
            JdbcTemplate jdbcTemplate,
            ResearchAgentPermitService permitService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.permitService = permitService;
    }

    @Transactional
    public ArchiveReceipt archive(ArchiveCommand command) {
        validate(command);
        // REQUIRED propagation keeps the task/run locks acquired by the permit
        // check until this immutable archive row has been committed.
        permitService.requirePermit(new ResearchAgentPermitService.PermitCommand(
                command.taskId(), command.workerInstanceId(), command.leaseEpoch(), command.fencingToken(), "archive"));

        String contentSha256 = sha256Hex(command.contentText());
        String snapshotKey = "research/external/" + command.taskId() + "/" + contentSha256;
        ArchiveRow existing = jdbcTemplate.query("""
                select id, source_title, source_url, source_domain, provider, adapter, snapshot_key,
                       content_text, content_sha256, archive_status
                from research_external_snapshot
                where research_agent_task_id = ? and window_id = ? and source_id = ?
                for update
                """, rs -> rs.next() ? new ArchiveRow(
                rs.getString("id"), rs.getString("source_title"), rs.getString("source_url"),
                rs.getString("source_domain"), rs.getString("provider"), rs.getString("adapter"),
                rs.getString("snapshot_key"), rs.getString("content_text"), rs.getString("content_sha256"),
                rs.getString("archive_status")) : null,
                command.taskId(), command.windowId(), command.sourceId());
        if (existing != null) {
            if (!sameArchive(existing, command, contentSha256, snapshotKey)) throw conflict();
            return new ArchiveReceipt(existing.id(), command.taskId(), command.windowId(), command.sourceId(),
                    "EXTERNAL_ARCHIVED", existing.snapshotKey(), existing.contentSha256(), true);
        }

        String archiveId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_external_snapshot(
                    id, research_run_id, research_agent_task_id, window_id, source_id, source_title,
                    source_url, source_domain, provider, adapter, snapshot_key, content_text,
                    content_sha256, archive_status
                ) select ?, research_run_id, id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ARCHIVED'
                  from research_agent_task where id = ?
                """, archiveId, command.windowId(), command.sourceId(), command.sourceTitle(), command.sourceUrl(),
                sourceDomain(command.sourceUrl()), command.provider(), command.adapter(), snapshotKey,
                command.contentText(), contentSha256, command.taskId());
        return new ArchiveReceipt(archiveId, command.taskId(), command.windowId(), command.sourceId(),
                "EXTERNAL_ARCHIVED", snapshotKey, contentSha256, false);
    }

    private boolean sameArchive(ArchiveRow existing, ArchiveCommand command, String digest, String snapshotKey) {
        return "ARCHIVED".equals(existing.archiveStatus())
                && existing.sourceTitle().equals(command.sourceTitle())
                && existing.sourceUrl().equals(command.sourceUrl())
                && existing.sourceDomain().equals(sourceDomain(command.sourceUrl()))
                && existing.provider().equals(command.provider())
                && existing.adapter().equals(command.adapter())
                && existing.snapshotKey().equals(snapshotKey)
                && existing.contentText().equals(command.contentText())
                && existing.contentSha256().equalsIgnoreCase(digest);
    }

    private void validate(ArchiveCommand command) {
        if (command == null || invalid(command.taskId(), 160) || invalid(command.workerInstanceId(), 160)
                || command.leaseEpoch() < 1 || command.fencingToken() < 1
                || invalid(command.windowId(), 64) || invalid(command.sourceId(), 64)
                || invalid(command.sourceTitle(), 300) || invalid(command.sourceUrl(), 2048)
                || invalid(command.provider(), 64) || invalid(command.adapter(), 64)
                || invalid(command.contentText(), MAX_CONTENT_CHARS)) {
            throw new BusinessException("RESEARCH_AGENT_EXTERNAL_ARCHIVE_INVALID",
                    "External snapshot archive request is invalid");
        }
        try {
            URI uri = URI.create(command.sourceUrl());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw invalidUrl();
            }
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }
    }

    private boolean invalid(String value, int maxChars) {
        return value == null || value.isBlank() || value.length() > maxChars;
    }

    private String sourceDomain(String sourceUrl) {
        return URI.create(sourceUrl).getHost().toLowerCase(java.util.Locale.ROOT);
    }

    private String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BusinessException invalidUrl() {
        return new BusinessException("RESEARCH_AGENT_EXTERNAL_ARCHIVE_INVALID",
                "External snapshot URL must be an absolute HTTP(S) URL without credentials");
    }

    private BusinessException conflict() {
        return new BusinessException("RESEARCH_AGENT_EXTERNAL_ARCHIVE_CONFLICT",
                "External snapshot identity already belongs to different immutable content");
    }

    public record ArchiveCommand(
            String taskId,
            String workerInstanceId,
            int leaseEpoch,
            long fencingToken,
            String windowId,
            String sourceId,
            String sourceTitle,
            String sourceUrl,
            String provider,
            String adapter,
            String contentText
    ) { }

    public record ArchiveReceipt(
            String archiveId,
            String taskId,
            String windowId,
            String sourceId,
            String snapshotStatus,
            String snapshotKey,
            String contentSha256,
            boolean idempotentReplay
    ) { }

    private record ArchiveRow(
            String id,
            String sourceTitle,
            String sourceUrl,
            String sourceDomain,
            String provider,
            String adapter,
            String snapshotKey,
            String contentText,
            String contentSha256,
            String archiveStatus
    ) { }
}
