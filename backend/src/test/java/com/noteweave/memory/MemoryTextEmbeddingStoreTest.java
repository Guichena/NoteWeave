package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MemoryTextEmbeddingStoreTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:memory-embedding-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper();
        jdbcTemplate.execute("""
                create table memory_item(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    text_embedding longtext,
                    text_embedding_hash varchar(64)
                )
                """);
        jdbcTemplate.update("""
                insert into memory_item(id, workspace_id, text_embedding, text_embedding_hash)
                values ('item-1', 'workspace', null, null)
                """);
    }

    @Test
    void refreshWritesVectorAndSkipsUnchangedText() {
        AtomicInteger embeds = new AtomicInteger();
        MemoryTextEmbeddingStore store = new MemoryTextEmbeddingStore(
                jdbcTemplate, client(embeds), objectMapper);

        store.refresh("workspace", "item-1", " 保留页码 ");
        assertThat(embeds).hasValue(1);
        String hash = jdbcTemplate.queryForObject(
                "select text_embedding_hash from memory_item where id = 'item-1'", String.class);
        String json = jdbcTemplate.queryForObject(
                "select text_embedding from memory_item where id = 'item-1'", String.class);
        assertThat(hash).hasSize(64);
        store.refresh("workspace", "item-1", "保留页码");
        assertThat(embeds).hasValue(1);
        assertThat(store.vector("workspace", "item-1", "保留页码", json, hash))
                .containsExactly(0.5f, 0.25f);
        assertThat(embeds).hasValue(1);
    }

    @Test
    void disabledClientKeepsTheStoredVector() {
        MemoryTextEmbeddingStore store = new MemoryTextEmbeddingStore(
                jdbcTemplate, (EmbeddingClient) null, objectMapper);
        store.refresh("workspace", "item-1", "保留页码");
        assertThat(jdbcTemplate.queryForObject(
                "select text_embedding_hash from memory_item where id = 'item-1'", String.class)).isNull();
        assertThat(store.vector("workspace", "item-1", "保留页码", "[1.0, 0.0]", "stale"))
                .containsExactly(1f, 0f);
    }

    private EmbeddingClient client(AtomicInteger embeds) {
        return new EmbeddingClient() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public EmbeddingResult embedQuery(String text) {
                return new EmbeddingResult(List.of(List.of(1f, 0f)), "test", 2, 1);
            }

            @Override
            public EmbeddingResult embedDocuments(List<String> texts) {
                embeds.incrementAndGet();
                return new EmbeddingResult(List.of(List.of(0.5f, 0.25f)), "test", 2, 1);
            }
        };
    }
}
