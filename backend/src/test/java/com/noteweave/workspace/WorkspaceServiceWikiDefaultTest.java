package com.noteweave.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class WorkspaceServiceWikiDefaultTest {

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void workspaceCreatedByWorkspaceServiceEnablesWiki() {
        WorkspaceResponse created = workspaceService.createWorkspace(
                new CreateWorkspaceRequest("wiki default", "new workspaces enable wiki"));

        Boolean wikiEnabled = jdbcTemplate.queryForObject(
                "select wiki_enabled from workspace where id = ?",
                Boolean.class,
                created.workspaceId());

        assertThat(wikiEnabled).isTrue();
        assertThat(workspaceService.isWikiEnabled(created.workspaceId())).isTrue();
    }
}
