package com.noteweave.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadDocumentFormatPolicyTest {
    @TempDir Path root;
    private final UploadSecurityPolicy policy = new UploadSecurityPolicy();

    private Path archive(String name, Map<String, String> entries) throws Exception {
        Path file = root.resolve(name);
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void officeArchivesMustMatchTheirDeclaredFormatInsteadOfOnlyHavingZipMagic() throws Exception {
        Path word = archive("document.docx", Map.of("[Content_Types].xml", "<Types/>", "word/document.xml", "<document/>"));
        String wordMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        assertThatCode(() -> policy.validateMergedFile(wordMime, Files.size(word), word)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateMergedFile("application/vnd.openxmlformats-officedocument.presentationml.presentation", Files.size(word), word))
                .isInstanceOf(BusinessException.class);
        Path fake = archive("fake.docx", Map.of("arbitrary.txt", "not a document"));
        assertThatThrownBy(() -> policy.validateMergedFile(wordMime, Files.size(fake), fake)).isInstanceOf(BusinessException.class);
    }

    @Test
    void epubRequiresTypeAndContainerAndArchiveTraversalIsRejected() throws Exception {
        Path epub = archive("book.epub", Map.of("mimetype", "application/epub+zip", "META-INF/container.xml", "<container/>"));
        assertThatCode(() -> policy.validateMergedFile("application/epub+zip", Files.size(epub), epub)).doesNotThrowAnyException();
        Path unsafe = archive("unsafe.epub", Map.of("mimetype", "application/epub+zip", "META-INF/container.xml", "<container/>", "../escape", "bad"));
        assertThatThrownBy(() -> policy.validateMergedFile("application/epub+zip", Files.size(unsafe), unsafe)).isInstanceOf(BusinessException.class);
    }

    @Test
    void htmlAndRtfRequireTextHeadersAndHashesMustBeLowercaseHex() {
        byte[] html = "<!doctype html><html><body>内容</body></html>".getBytes(StandardCharsets.UTF_8);
        byte[] rtf = "{\\rtf1 sample}".getBytes(StandardCharsets.UTF_8);
        assertThatCode(() -> policy.validateMergedContent("text/html", html.length, html)).doesNotThrowAnyException();
        assertThatCode(() -> policy.validateMergedContent("application/rtf", rtf.length, rtf)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateMergedContent("application/rtf", html.length, html)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateMetadata(new CreateUploadRequest("file.txt", 1, "text/plain", 1, 1, "A".repeat(64))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.validateMetadata(new CreateUploadRequest("file.txt", 1, "text/plain", 1, 1, "0".repeat(63))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void formatsListUsesSameWhitelistAndRetainsMediaAndOriginalConstructors() {
        assertThat(policy.formats()).extracting(UploadFormatResponse::extension)
                .contains("docx", "pptx", "xlsx", "html", "epub", "rtf", "pdf", "mp3", "mp4");
        for (UploadFormatResponse format : policy.formats()) {
            assertThatCode(() -> policy.validateMetadata(new CreateUploadRequest("sample." + format.extension(), 1, format.mimeType(), 1, 1)))
                    .doesNotThrowAnyException();
        }
        assertThat(new CreateUploadRequest("file.txt", 1, "text/plain", 1, 1).sha256()).isNull();
        assertThat(new CreateUploadResponse("id", "UPLOADING", 1, 1).instant()).isFalse();
    }
}
