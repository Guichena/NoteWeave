package com.noteweave.infra;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.noteweave.config.NoteWeaveProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 客户端 + 索引管理。
 * <p>
 * 对接 docker-compose 中的 Elasticsearch 服务（端口 9200）。
 * 索引命名规则：{@code <prefix>_<workspaceId>}，便于工作台级隔离。
 */
@Configuration
@ConditionalOnProperty(name = "noteweave.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(NoteWeaveProperties properties) {
        NoteWeaveProperties.Elasticsearch cfg = properties.elasticsearch();
        URI uri = URI.create(cfg.scheme() + "://" + cfg.host() + ":" + cfg.port());
        log.info("Elasticsearch client connecting to {}://{}:{}", cfg.scheme(), cfg.host(), cfg.port());
        var builder = RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()));
        if (cfg.username() != null && !cfg.username().isBlank()) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(cfg.username(), cfg.password()));
            builder.setHttpClientConfigCallback(client -> client.setDefaultCredentialsProvider(credentials));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
