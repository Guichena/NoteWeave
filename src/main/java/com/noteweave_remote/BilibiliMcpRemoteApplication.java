package com.noteweave_remote;

import com.noteweave.common.error.GlobalExceptionHandler;
import com.noteweave.personal.source.fetch.HttpUrlFetchTransport;
import com.noteweave.personal.source.fetch.SafeUrlContentFetcher;
import com.noteweave.studio.remote.BilibiliMcpRemoteController;
import com.noteweave.studio.service.BilibiliMcpCoreService;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootApplication(
        scanBasePackageClasses = {
                BilibiliMcpRemoteController.class,
                GlobalExceptionHandler.class,
                SafeUrlContentFetcher.class,
                HttpUrlFetchTransport.class
        },
        exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                RedisAutoConfiguration.class,
                KafkaAutoConfiguration.class,
                SecurityAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@Import(BilibiliMcpCoreService.class)
public class BilibiliMcpRemoteApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BilibiliMcpRemoteApplication.class);
        application.setDefaultProperties(Map.of(
                "noteweave.studio.mcp.remote-server.enabled", "true"
        ));
        application.run(args);
    }
}
