package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 默认部署形态的端口暴露检查，不依赖 Spring 上下文。
 * <p>
 * OPS-02：{@code parse-worker} 曾经用 {@code "18103:8000"} 发布端口，等于把解析服务
 * 绑到所有网卡上；其余服务都走 {@code ${NOTEWEAVE_HOST_BIND_ADDRESS:-127.0.0.1}}，
 * 只有它漏了。这里把「端口发布必须带主机绑定地址」固化成部署检查，
 * 以后新增服务再漏写会在测试阶段失败。
 */
class ComposePortBindingDeploymentTest {

    /** 所有对外发布端口都必须显式声明主机绑定地址，默认回环。 */
    @Test
    void everyPublishedPortBindsToLoopbackByDefault() {
        List<String> published = publishedPorts(repositoryFile("docker-compose.yml"));

        assertThat(published).as("docker-compose.yml 应至少发布一个端口").isNotEmpty();
        assertThat(published).allSatisfy(entry -> assertThat(entry)
                .as("端口发布必须带主机绑定地址（默认 127.0.0.1），不能裸写 \"端口:端口\"：%s", entry)
                .startsWith("${NOTEWEAVE_HOST_BIND_ADDRESS:-127.0.0.1}:"));
    }

    /** 解析服务只需要被 backend 访问，宿主机端口不能暴露在所有网卡上。 */
    @Test
    void parseWorkerPortIsBoundToLoopback() {
        List<String> published = publishedPorts(repositoryFile("docker-compose.yml"));

        assertThat(published).contains("${NOTEWEAVE_HOST_BIND_ADDRESS:-127.0.0.1}:18103:8000");
        assertThat(published).doesNotContain("18103:8000");
    }

    /** 收集 compose 文件里所有 {@code ports:} 列表项，去掉引号。 */
    private static List<String> publishedPorts(Path composeFile) {
        try {
            List<String> ports = new ArrayList<>();
            boolean inPorts = false;
            for (String raw : Files.readAllLines(composeFile)) {
                String line = raw.trim();
                if (line.equals("ports:")) {
                    inPorts = true;
                    continue;
                }
                if (!inPorts || line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith("- ")) {
                    inPorts = false;
                    continue;
                }
                String value = line.substring(2).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                ports.add(value);
            }
            return ports;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 " + composeFile, exception);
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
