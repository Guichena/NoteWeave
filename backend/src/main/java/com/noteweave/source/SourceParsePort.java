package com.noteweave.source;

import com.noteweave.common.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public interface SourceParsePort {

    void parseAndIndex(String workspaceId, String sourceId, String snapshotId, byte[] bytes);

    /** 旧实现兼容字节入口，真实解析服务覆盖此方法并流式读取原件。 */
    default void parseAndIndex(String workspaceId, String sourceId, String snapshotId, Path file) {
        try {
            parseAndIndex(workspaceId, sourceId, snapshotId, Files.readAllBytes(file));
        } catch (IOException exception) {
            throw new BusinessException("SOURCE_DOCUMENT_READ_FAILED", "资料原件读取失败");
        }
    }

    void parseAndIndexAsync(String workspaceId, String sourceId, String snapshotId);
}
