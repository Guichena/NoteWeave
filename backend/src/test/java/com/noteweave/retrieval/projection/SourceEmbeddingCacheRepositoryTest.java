package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.projection.SourceEmbeddingCacheRepository.CacheKey;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SourceEmbeddingCacheRepositoryTest {
    private JdbcTemplate jdbc;
    private SourceEmbeddingCacheRepository repository;

    @BeforeEach
    void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:vector-cache-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table workspace(id varchar(36) primary key)");
        jdbc.update("insert into workspace(id) values ('workspace-a'), ('workspace-b')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V147__source_embedding_cache.sql")).execute(ds);
        repository = new SourceEmbeddingCacheRepository(jdbc, new ObjectMapper());
    }

    @Test
    void reuseRequiresWorkspaceVersionsAndBothRealInputHashes() {
        CacheKey key = key("workspace-a", "ctx-v1");
        repository.put(key, "full-a", "effective-a", "model", 2, List.of(1f, 2f));

        assertThat(repository.find(key, "full-a", "effective-a", "model", 2)).contains(List.of(1f, 2f));
        assertThat(repository.find(key("workspace-b", "ctx-v1"), "full-a", "effective-a", "model", 2)).isEmpty();
        assertThat(repository.find(key("workspace-a", "ctx-v2"), "full-a", "effective-a", "model", 2)).isEmpty();
        assertThat(repository.find(key, "full-b", "effective-a", "model", 2)).isEmpty();
        assertThat(repository.find(key, "full-a", "effective-b", "model", 2)).isEmpty();
        assertThat(repository.find(key, "full-a", "effective-a", "another-model", 2)).isEmpty();
        assertThat(repository.find(key, "full-a", "effective-a", "model", 3)).isEmpty();
    }

    @Test
    void sameOriginalTextUnderDifferentDocumentContextCannotReuseOldVector() {
        CacheKey key = key("workspace-a", "ctx-v1");
        repository.put(key, "doc-one", "truncated-same", "model", 2, List.of(1f, 2f));
        repository.put(key, "doc-two", "truncated-same", "model", 2, List.of(3f, 4f));

        assertThat(repository.find(key, "doc-one", "truncated-same", "model", 2)).isEmpty();
        assertThat(repository.find(key, "doc-two", "truncated-same", "model", 2)).contains(List.of(3f, 4f));
        assertThat(jdbc.queryForObject("select count(*) from source_embedding_cache", Integer.class)).isEqualTo(1);
    }

    @Test
    void corruptedNonFiniteOrWrongShapeCacheIsAMiss() {
        CacheKey key = key("workspace-a", "ctx-v1");
        repository.put(key, "full", "effective", "model", 2, List.of(1f, 2f));
        for (String invalid : List.of("[1]", "[1,\"2\"]", "[1,1e100]", "[1,null]", "{}", "not-json")) {
            jdbc.update("update source_embedding_cache set vector_json = ?", invalid);
            assertThat(repository.find(key, "full", "effective", "model", 2)).isEmpty();
        }
    }

    @Test
    void rejectsInvalidProviderVectorsInsteadOfPersistingThem() {
        CacheKey key = key("workspace-a", "ctx-v1");
        assertThatThrownBy(() -> repository.put(key, "full", "effective", "model", 2, List.of(Float.NaN, 1f)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.put(key, "full", "effective", "model", 2, List.of(1f)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from source_embedding_cache", Integer.class)).isZero();
    }

    private CacheKey key(String workspace, String context) {
        return new CacheKey(workspace, "embedding-v1", "chunker-v1", context, "same-content");
    }
}
