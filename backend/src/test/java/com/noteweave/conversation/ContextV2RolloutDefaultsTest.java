package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import com.noteweave.security.WorkspaceAccessGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * Context V2 的产品默认值是开启：application.yml、docker-compose.yml 与 .env.example
 * 必须给出同样的默认值，代码里的 @Value 兜底也必须一致；否则关闭环境变量的部署会
 * 在不同入口表现出不同的上下文策略。
 */
class ContextV2RolloutDefaultsTest {

    private static final String WORKSPACE_ID = "workspace-defaults";

    @Test
    void applicationYamlTurnsContextV2OnByDefault() throws IOException {
        PropertySource<?> yaml = applicationYaml();

        assertThat(defaultOf(yaml, "noteweave.context.v2.shadow-enabled")).isEqualTo("true");
        assertThat(defaultOf(yaml, "noteweave.context.v2.active-enabled")).isEqualTo("true");
        assertThat(defaultOf(yaml, "noteweave.context.v2.default-mode")).isEqualTo("ACTIVE");
    }

    @Test
    void researchAndArtifactGatesKeepTheirOwnDefaults() throws IOException {
        PropertySource<?> yaml = applicationYaml();

        assertThat(defaultOf(yaml, "noteweave.context.v2.research-active-enabled")).isEqualTo("false");
        assertThat(defaultOf(yaml, "noteweave.context.v2.artifact-active-enabled")).isEqualTo("false");
    }

    @Test
    void deploymentDefaultsStayAlignedWithApplicationYaml() throws IOException {
        Path repositoryRoot = repositoryRoot();
        Assumptions.assumeTrue(repositoryRoot != null,
                "docker-compose.yml / .env.example 不在当前源码树中");

        String compose = Files.readString(repositoryRoot.resolve("docker-compose.yml"));
        assertThat(compose).contains(
                "NOTEWEAVE_CONTEXT_V2_SHADOW_ENABLED: ${NOTEWEAVE_CONTEXT_V2_SHADOW_ENABLED:-true}",
                "NOTEWEAVE_CONTEXT_V2_ACTIVE_ENABLED: ${NOTEWEAVE_CONTEXT_V2_ACTIVE_ENABLED:-true}",
                "NOTEWEAVE_CONTEXT_V2_DEFAULT_MODE: ${NOTEWEAVE_CONTEXT_V2_DEFAULT_MODE:-ACTIVE}");

        String envExample = Files.readString(repositoryRoot.resolve(".env.example"));
        assertThat(envExample).contains(
                "NOTEWEAVE_CONTEXT_V2_SHADOW_ENABLED=true",
                "NOTEWEAVE_CONTEXT_V2_ACTIVE_ENABLED=true",
                "NOTEWEAVE_CONTEXT_V2_DEFAULT_MODE=ACTIVE");
    }

    @Test
    void workspaceWithoutRolloutRowUsesActiveDefault() {
        ContextV2RolloutService rollout = new ContextV2RolloutService(
                jdbcReturningMode(null), mock(WorkspaceAccessGuard.class), true, true, "ACTIVE");

        assertThat(rollout.get(WORKSPACE_ID).mode()).isEqualTo("ACTIVE");
        assertThat(rollout.shadowEnabled(WORKSPACE_ID)).isTrue();
        assertThat(rollout.activeEnabled(WORKSPACE_ID)).isTrue();
    }

    @Test
    void invalidDefaultModeFallsBackToOff() {
        ContextV2RolloutService rollout = new ContextV2RolloutService(
                jdbcReturningMode(null), mock(WorkspaceAccessGuard.class), true, true, "TURBO");

        assertThat(rollout.get(WORKSPACE_ID).mode()).isEqualTo("OFF");
        assertThat(rollout.shadowEnabled(WORKSPACE_ID)).isFalse();
        assertThat(rollout.activeEnabled(WORKSPACE_ID)).isFalse();
    }

    @Test
    void activeModeStillRequiresTheGlobalActiveSwitch() {
        ContextV2RolloutService rollout = new ContextV2RolloutService(
                jdbcReturningMode(null), mock(WorkspaceAccessGuard.class), true, false, "ACTIVE");

        assertThatThrownBy(() -> rollout.set(WORKSPACE_ID, "ACTIVE"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("CONTEXT_V2_ROLLOUT_MODE_INVALID"));
        assertThat(rollout.activeEnabled(WORKSPACE_ID)).isFalse();
    }

    private static PropertySource<?> applicationYaml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();
        return sources.get(0);
    }

    /** 解析 {@code ${ENV_VAR:default}} 中的默认值，等价于环境变量未设置时的取值。 */
    private static String defaultOf(PropertySource<?> yaml, String key) {
        Object raw = yaml.getProperty(key);
        assertThat(raw).as(key).isNotNull();
        return new PropertyPlaceholderHelper("${", "}", ":", true)
                .replacePlaceholders(raw.toString(), placeholder -> null);
    }

    private static JdbcTemplate jdbcReturningMode(String mode) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any(), any()))
                .thenReturn(mode);
        return jdbc;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isRegularFile(current.resolve(".env.example"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
