package com.noteweave.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** 校验 test-es profile 把副本数配成 0，单节点 ES 才不会出现未分配副本分片。 */
class TestEsReplicaProfileTest {

    @Test
    void testEsProfileDisablesReplicas() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("test-es", new ClassPathResource("application-test-es.yml"));
        assertThat(sources).isNotEmpty();
        assertThat(sources.get(0).getProperty("noteweave.elasticsearch.replicas")).isEqualTo(0);
    }
}
