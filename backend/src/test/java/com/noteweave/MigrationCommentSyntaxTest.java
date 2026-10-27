package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MySQL 只把「-- 加空白」当成行注释；写成 {@code --（} 时整行会被当成 SQL 送进解析器，
 * 迁移直接失败。H2 的 MySQL 模式接受这种写法，所以单测跑全量迁移也不会报错——
 * 直到真的在 MySQL 上发布才暴露（V209 就是这么被发现的，见进度文档 §2.57）。
 */
class MigrationCommentSyntaxTest {

    @Test
    void migrationCommentLinesMustHaveWhitespaceAfterDashes() throws Exception {
        Path migrationDirectory = Path.of("src/main/resources/db/migration");
        List<String> offenders = new ArrayList<>();
        try (var files = Files.list(migrationDirectory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index).stripLeading();
                    if (!line.startsWith("--")) {
                        continue;
                    }
                    // 只校验行首注释：行内出现 -- 可能是字符串字面量的一部分，不做判断
                    if (line.length() > 2 && !Character.isWhitespace(line.charAt(2))) {
                        offenders.add(file.getFileName() + ":" + (index + 1) + ": " + line);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("迁移里的行注释必须写成 `-- 内容`，`--` 后缺少空白在 MySQL 上会导致迁移失败")
                .isEmpty();
    }
}
