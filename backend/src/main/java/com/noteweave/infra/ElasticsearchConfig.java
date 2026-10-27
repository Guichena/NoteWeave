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
import org.springframework.core.env.Environment;
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
    public RestClient elasticsearchRestClient(NoteWeaveProperties properties, Environment environment) {
        NoteWeaveProperties.Elasticsearch cfg = properties.elasticsearch();
        URI uri = URI.create(cfg.scheme() + "://" + cfg.host() + ":" + cfg.port());
        log.info("Elasticsearch client connecting to {}://{}:{}", cfg.scheme(), cfg.host(), cfg.port());
        // 没有超时的 ES 客户端会把调用线程挂死：集群卡住（分片分配不了、磁盘水位超限）时，
        // 索引创建与写入会无限等待，请求线程连同事务一起挂住。这里给出有界超时，
        // 让问题快速变成一条明确的失败，而不是一整片静默卡死。
        int connectTimeoutMs = Math.max(100,
                environment.getProperty("noteweave.elasticsearch.connect-timeout-ms", Integer.class, 3000));
        int socketTimeoutMs = Math.max(100,
                environment.getProperty("noteweave.elasticsearch.socket-timeout-ms", Integer.class, 30000));
        var builder = RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
                .setRequestConfigCallback(config -> config
                        .setConnectTimeout(connectTimeoutMs)
                        .setSocketTimeout(socketTimeoutMs)
                        .setConnectionRequestTimeout(connectTimeoutMs));
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
