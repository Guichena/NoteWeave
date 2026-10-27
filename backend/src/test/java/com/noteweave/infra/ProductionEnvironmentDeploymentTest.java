package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SEC-04：默认部署必须触发加固检查，不依赖部署者记得设环境变量。
 * <p>
 * 此前 {@code docker-compose.yml} 把 {@code NOTEWEAVE_ENVIRONMENT} 默认成 {@code development}，
 * 于是 {@link com.noteweave.config.ProductionConfigurationGuard} 的加固检查在默认部署里全部跳过：
 * 开发默认口令、开启的本地降级开关都能被带到共享环境里。
 * 现在 compose 默认 {@code staging}（同样属于共享部署），本地开发必须在 {@code .env.example}
 * 那样显式声明 {@code development}。
 */
class ProductionEnvironmentDeploymentTest {

    @Test
    void composeDefaultsToSharedDeploymentSoHardeningChecksRun() {
        List<String> lines = environmentLines(repositoryFile("docker-compose.yml"));

        assertThat(lines).as("compose 里每个后端服务都应声明环境").isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line)
                .as("默认部署必须落在共享环境（staging），不能默认 development：%s", line)
                .isEqualTo("NOTEWEAVE_ENVIRONMENT: ${NOTEWEAVE_ENVIRONMENT:-staging}"));
    }

    @Test
    void envExampleOptsIntoDevelopmentExplicitly() {
        List<String> lines = environmentLines(repositoryFile(".env.example"));

        assertThat(lines).containsExactly("NOTEWEAVE_ENVIRONMENT=development");
    }

    private static List<String> environmentLines(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith("NOTEWEAVE_ENVIRONMENT"))
                    .toList();
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
