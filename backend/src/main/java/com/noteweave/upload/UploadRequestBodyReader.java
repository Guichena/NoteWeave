package com.noteweave.upload;

import com.noteweave.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class UploadRequestBodyReader {

    private UploadRequestBodyReader() {
    }

    static byte[] read(HttpServletRequest request) {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > UploadSecurityPolicy.MAX_CHUNK_SIZE) {
            throw tooLarge();
        }
        int initialCapacity = declaredLength > 0
                ? (int) Math.min(declaredLength, UploadSecurityPolicy.MAX_CHUNK_SIZE)
                : 8 * 1024;
        try (InputStream input = request.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > UploadSecurityPolicy.MAX_CHUNK_SIZE - total) {
                    throw tooLarge();
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException("UPLOAD_CHUNK_READ_FAILED", "无法读取上传分片");
        }
    }

    private static BusinessException tooLarge() {
        return new BusinessException("UPLOAD_CHUNK_TOO_LARGE", "上传分片超过允许大小");
    }
}
