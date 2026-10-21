package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.PathResource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 默认部署形态下的 Kafka 静态消费成员配置测试，不依赖 Spring 上下文。
 * <p>
 * M3-M09：{@code docker-compose.yml} 曾经用 {@code ${NOTEWEAVE_KAFKA_INSTANCE_ID:-noteweave-v2-backend}}
 * 给所有副本注入了同一个静态成员 ID，多个后端实例会互相顶替、反复 rebalance。
 * 现在默认注入空值（{@code ${NOTEWEAVE_KAFKA_INSTANCE_ID-}}），空值在
 * {@link KafkaConfig#staticMemberIdOrNull(String)} 中被识别为“不启用静态成员”。
 */
class KafkaInstanceIdDeploymentTest {

    /** 解析 application.yml 里真实的 instance-id 占位符，避免测试与配置文件漂移。 */
    @Test
    void emptyEnvironmentValueDisablesStaticMembersInsteadOfFallingBackToHostname() {
        String placeholder = configuredInstanceIdPlaceholder();

        assertThat(resolve(placeholder, Map.of("NOTEWEAVE_KAFKA_INSTANCE_ID", "", "HOSTNAME", "host-a"))).isBlank();
        assertThat(resolve(placeholder, Map.of("NOTEWEAVE_KAFKA_INSTANCE_ID", "deploy-a", "HOSTNAME", "host-a")))
                .isEqualTo("deploy-a");
        assertThat(resolve(placeholder, Map.of("HOSTNAME", "host-a"))).isEqualTo("host-a");
    }

    /** 默认部署（未设置该变量）解析出的空实例 ID 不写入 group.instance.id，成员 ID 由 broker 分配。 */
    @Test
    void defaultDeploymentResolvesToNoStaticGroupInstanceId() {
        String resolved = resolve(configuredInstanceIdPlaceholder(),
                Map.of("NOTEWEAVE_KAFKA_INSTANCE_ID", "", "HOSTNAME", "host-a"));
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "instanceId", resolved);

        assertThat(config.staticMemberIdOrNull("source-parse")).isNull();
        assertThat(config.staticMemberIdOrNull("source-chunk")).isNull();
        assertThat(config.staticMemberIdOrNull("source-embed")).isNull();
        assertThat(config.staticMemberIdOrNull("source-index")).isNull();
    }

    /** compose 只能把变量原样透传：不能用 {@code :-} 缺省值，否则空值又会被替换成共享静态 ID。 */
    @Test
    void composeFileDoesNotInjectSharedStaticInstanceId() {
        String line = singleLineStartingWith(repositoryFile("docker-compose.yml"), "NOTEWEAVE_KAFKA_INSTANCE_ID:");

        assertThat(line).isEqualTo("NOTEWEAVE_KAFKA_INSTANCE_ID: ${NOTEWEAVE_KAFKA_INSTANCE_ID-}");
        assertThat(line).doesNotContain(":-").doesNotContain("noteweave-v2-backend");
    }

    /** 示例环境文件默认留空，用户照抄也不会把多个副本配成同一个静态成员。 */
    @Test
    void envExampleShipsEmptyInstanceId() {
        String line = singleLineStartingWith(repositoryFile(".env.example"), "NOTEWEAVE_KAFKA_INSTANCE_ID=");

        assertThat(line.substring("NOTEWEAVE_KAFKA_INSTANCE_ID=".length())).isBlank();
    }

    private static String configuredInstanceIdPlaceholder() {
        Path applicationYml = repositoryFile("backend/src/main/resources/application.yml");
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application", new PathResource(applicationYml));
            for (PropertySource<?> source : sources) {
                Object value = source.getProperty("noteweave.kafka.instance-id");
                if (value != null) {
                    return value.toString();
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法解析 " + applicationYml, exception);
        }
        throw new IllegalStateException("application.yml 缺少 noteweave.kafka.instance-id");
    }

    /** 用真实的占位符解析规则（null 才用缺省值，空字符串原样保留）模拟环境变量注入。 */
    private static String resolve(String placeholder, Map<String, Object> environment) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("test-env", environment));
        return new PropertySourcesPropertyResolver(sources).resolvePlaceholders(placeholder);
    }

    private static String singleLineStartingWith(Path file, String prefix) {
        try {
            List<String> matches = Files.readAllLines(file).stream()
                    .filter(line -> line.trim().startsWith(prefix))
                    .map(String::trim)
                    .toList();
            assertThat(matches).as("%s 中 %s 应只出现一次", file, prefix).hasSize(1);
            return matches.get(0);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 " + file, exception);
        }
    }

    /** 从当前工作目录向上找仓库根，兼容 {@code cd backend && mvn test} 与根目录运行。 */
    private static Path repositoryFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到仓库文件：" + relativePath);
    }
}
