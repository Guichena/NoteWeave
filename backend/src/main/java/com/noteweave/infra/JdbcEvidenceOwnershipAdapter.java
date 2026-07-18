package com.noteweave.infra;

import com.noteweave.answer.strategy.EvidenceOwnershipPort;
import com.noteweave.answer.strategy.EvidenceOwnershipPort.EvidenceIdentity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcEvidenceOwnershipAdapter implements EvidenceOwnershipPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcEvidenceOwnershipAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Set<EvidenceIdentity> findCurrent(
            String workspaceId,
            List<EvidenceIdentity> identities
    ) {
        if (workspaceId == null || workspaceId.isBlank()
                || identities == null || identities.isEmpty()) {
            return Set.of();
        }
        Set<EvidenceIdentity> current = new LinkedHashSet<>();
        current.addAll(findCurrentPassages(workspaceId, identities));
        current.addAll(findCurrentKnowledgeVersions(workspaceId, identities));
        return Set.copyOf(current);
    }

    private List<EvidenceIdentity> findCurrentPassages(
            String workspaceId,
            List<EvidenceIdentity> identities
    ) {
        List<String> passageIds = identities.stream()
                .filter(identity -> "PASSAGE".equals(identity.kind()))
                .map(EvidenceIdentity::passageId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (passageIds.isEmpty()) {
            return List.of();
        }
        List<Object> parameters = parameters(workspaceId, passageIds);
        return jdbcTemplate.query("""
                select c.source_id, c.source_snapshot_id, c.id as passage_id
                from source_chunk c
                join source s
                  on s.id = c.source_id and s.workspace_id = c.workspace_id
                join source_snapshot ss
                  on ss.id = c.source_snapshot_id and ss.source_id = c.source_id
                where c.workspace_id = ? and c.id in (%s)
                  and s.status = 'READY'
                  and s.index_status = 'INDEXED'
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (
                      select max(current_ss.version_no)
                      from source_snapshot current_ss
                      where current_ss.source_id = s.id
                        and current_ss.index_status = 'INDEXED'
                  )
                  and c.projection_status = 'PROJECTED'
                """.formatted(placeholders(passageIds.size())), (rs, rowNum) -> new EvidenceIdentity(
                "PASSAGE",
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getString("passage_id"),
                "",
                ""
        ), parameters.toArray());
    }

    private List<EvidenceIdentity> findCurrentKnowledgeVersions(
            String workspaceId,
            List<EvidenceIdentity> identities
    ) {
        List<String> itemIds = identities.stream()
                .filter(identity -> "KNOWLEDGE_VERSION".equals(identity.kind()))
                .map(EvidenceIdentity::knowledgeItemId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (itemIds.isEmpty()) {
            return List.of();
        }
        List<Object> parameters = parameters(workspaceId, itemIds);
        return jdbcTemplate.query("""
                select i.id as item_id, v.id as version_id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.id in (%s)
                  and i.status = 'ACTIVE'
                """.formatted(placeholders(itemIds.size())), (rs, rowNum) -> new EvidenceIdentity(
                "KNOWLEDGE_VERSION",
                "",
                "",
                "",
                rs.getString("item_id"),
                rs.getString("version_id")
        ), parameters.toArray());
    }

    private List<Object> parameters(String workspaceId, List<String> ids) {
        List<Object> parameters = new ArrayList<>(ids.size() + 1);
        parameters.add(workspaceId);
        parameters.addAll(ids);
        return parameters;
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
