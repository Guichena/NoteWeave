package com.noteweave.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * TEST-01：test-es profile 的默认端点必须指向隔离测试实例。
 *
 * <p>这里断言的是 YAML 里的原始占位符，而不是解析后的值——解析结果取决于跑测试的机器，
 * 断言原始默认值才能在任何环境下锁住「默认不连业务集群」这条约束。</p>
 */
class TestEsProfileEndpointTest {

    @Test
    void testEsProfileDefaultsToTheIsolatedInstanceWithoutCredentials() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("test-es", new ClassPathResource("application-test-es.yml"));
        assertThat(sources).isNotEmpty();
        PropertySource<?> source = sources.get(0);

        assertThat(source.getProperty("noteweave.elasticsearch.enabled")).isEqualTo(true);
        assertThat(source.getProperty("noteweave.elasticsearch.host")).isEqualTo("${NOTEWEAVE_ES_HOST:127.0.0.1}");
        // 业务集群默认端口是 9200，这里断言默认段是隔离实例的 19200
        assertThat(source.getProperty("noteweave.elasticsearch.port")).isEqualTo("${NOTEWEAVE_ES_PORT:19200}");
        // 空默认用户名让客户端不发鉴权头：隔离实例没开鉴权，发凭据只会被拒
        assertThat(source.getProperty("noteweave.elasticsearch.username")).isEqualTo("${NOTEWEAVE_ES_USERNAME:}");
        assertThat(source.getProperty("noteweave.elasticsearch.password")).isEqualTo("${NOTEWEAVE_ES_PASSWORD:}");
        // 测试集群卡住时必须尽快失败：套件不能因为一个无响应的索引创建挂住整轮
        assertThat(source.getProperty("noteweave.elasticsearch.connect-timeout-ms")).isEqualTo(2000);
        assertThat(source.getProperty("noteweave.elasticsearch.socket-timeout-ms")).isEqualTo(15000);
    }
}
