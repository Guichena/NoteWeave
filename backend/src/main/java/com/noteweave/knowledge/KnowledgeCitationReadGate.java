package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/** Rechecks persisted citation origins before exposing a Knowledge version. */
@Component
public class KnowledgeCitationReadGate {
    private final JdbcTemplate jdbc;
    private final ResearchGeneratedSourceReadGate generatedSourceGate;

    public KnowledgeCitationReadGate(JdbcTemplate jdbc,
                                     ResearchGeneratedSourceReadGate generatedSourceGate) {
        this.jdbc = jdbc;
        this.generatedSourceGate = generatedSourceGate;
    }

    public void requireReadable(String workspaceId, String versionId) {
        if (!readableVersionIds(workspaceId, List.of(versionId)).contains(versionId)) {
            throw new BusinessException("KNOWLEDGE_SOURCE_REVOKED",
                    "知识版本引用的资料已撤销", HttpStatus.CONFLICT);
        }
    }

    public void requireCitationIdsReadable(String workspaceId, List<String> citationIds) {
        List<String> ids = citationIds.stream().distinct().toList();
        if (ids.isEmpty()) return;
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        List<String> sourceIds = jdbc.queryForList("""
                select source_id from citation
                where workspace_id = ? and id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                String.class, parameters.toArray());
        if (sourceIds.size() != ids.size()
                || !generatedSourceGate.readableSourceIds(workspaceId, sourceIds)
                        .containsAll(sourceIds)) {
            throw new BusinessException("KNOWLEDGE_CITATION_REVOKED",
                    "知识引用的资料已撤销或不属于当前工作台", HttpStatus.CONFLICT);
        }
    }

    public Set<String> readableVersionIds(String workspaceId, List<String> versionIds) {
        List<String> ids = versionIds.stream().distinct().toList();
        if (ids.isEmpty()) return Set.of();
        Map<String, Set<String>> sourcesByVersion = new LinkedHashMap<>();
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        List<String> ownedIds = jdbc.queryForList("""
                select v.id from knowledge_version v
                join knowledge_item i on i.id = v.item_id
                where i.workspace_id = ? and v.id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                String.class, parameters.toArray());
        jdbc.query("""
                select kvc.knowledge_version_id, c.source_id
                from knowledge_version_citation kvc
                join knowledge_version v on v.id = kvc.knowledge_version_id
                join knowledge_item i on i.id = v.item_id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and kvc.knowledge_version_id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (RowCallbackHandler) rs ->
                        sourcesByVersion.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashSet<>())
                                .add(rs.getString(2)), parameters.toArray());
        List<String> sourceIds = sourcesByVersion.values().stream().flatMap(Set::stream)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        Set<String> readableSources = generatedSourceGate.readableSourceIds(workspaceId, sourceIds);
        LinkedHashSet<String> readableVersions = new LinkedHashSet<>();
        for (String versionId : ownedIds) {
            if (readableSources.containsAll(sourcesByVersion.getOrDefault(versionId, Set.of()))) {
                readableVersions.add(versionId);
            }
        }
        return Set.copyOf(readableVersions);
    }

    public Set<String> readableWikiItemIds(String workspaceId, List<String> itemIds) {
        List<String> ids = itemIds.stream().filter(id -> id != null && !id.isBlank())
                .distinct().toList();
        if (ids.isEmpty()) return Set.of();
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        Map<String, String> versionsByItem = new LinkedHashMap<>();
        jdbc.query("""
                select id, latest_version_id from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and status = 'ACTIVE'
                  and id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (RowCallbackHandler) rs -> versionsByItem.put(rs.getString(1), rs.getString(2)),
                parameters.toArray());
        Set<String> readableVersions = readableVersionIds(workspaceId,
                versionsByItem.values().stream().filter(id -> id != null && !id.isBlank()).toList());
        LinkedHashSet<String> readableItems = new LinkedHashSet<>();
        versionsByItem.forEach((itemId, versionId) -> {
            if (readableVersions.contains(versionId)) readableItems.add(itemId);
        });
        return Set.copyOf(readableItems);
    }
}
