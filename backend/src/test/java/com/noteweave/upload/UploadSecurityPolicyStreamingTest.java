package com.noteweave.upload;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadSecurityPolicyStreamingTest {

    private final UploadSecurityPolicy policy = new UploadSecurityPolicy();

    @TempDir
    Path directory;

    @Test
    void multiByteCharacterSplitAcrossReadBlocksIsStillValidUtf8() throws Exception {
        // 64KB 块边界正好落在一个三字节汉字中间
        byte[] content = ("a".repeat(64 * 1024 - 1) + "缓存一致性").getBytes(StandardCharsets.UTF_8);
        Path file = write("boundary.md", content);

        assertThatCode(() -> policy.validateMergedFile("text/markdown", content.length, file)).doesNotThrowAnyException();
    }

    @Test
    void invalidUtf8NulBytesSizeMismatchAndBadPdfHeaderAreRejected() throws Exception {
        byte[] invalid = {'o', 'k', (byte) 0xC3, (byte) 0x28};
        assertCode(write("invalid.md", invalid), "text/markdown", invalid.length, "UPLOAD_TEXT_ENCODING_INVALID");
        byte[] nul = {'a', 0, 'b'};
        assertCode(write("nul.txt", nul), "text/plain", nul.length, "UPLOAD_BINARY_CONTENT_REJECTED");
        byte[] text = "hello".getBytes(StandardCharsets.UTF_8);
        assertCode(write("size.md", text), "text/markdown", text.length + 1, "UPLOAD_FILE_SIZE_MISMATCH");
        byte[] fakePdf = "not a pdf".getBytes(StandardCharsets.UTF_8);
        assertCode(write("fake.pdf", fakePdf), "application/pdf", fakePdf.length, "UPLOAD_PDF_SIGNATURE_INVALID");
        byte[] truncated = "尾部".getBytes(StandardCharsets.UTF_8);
        byte[] cut = java.util.Arrays.copyOf(truncated, truncated.length - 1);
        assertCode(write("cut.md", cut), "text/markdown", cut.length, "UPLOAD_TEXT_ENCODING_INVALID");
    }

    @Test
    void pdfOnlyNeedsItsSignature() throws Exception {
        byte[] pdf = "%PDF-1.7 binary \u0000 content".getBytes(StandardCharsets.ISO_8859_1);
        Path file = write("doc.pdf", pdf);

        assertThatCode(() -> policy.validateMergedFile("application/pdf", pdf.length, file)).doesNotThrowAnyException();
    }

    @Test
    void mediaFilesAreCheckedByTheirSignatureOnly() throws Exception {
        byte[] mp3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 1, 0x7f};
        Path audio = write("meeting.mp3", mp3);
        assertThatCode(() -> policy.validateMergedFile("audio/mpeg", mp3.length, audio)).doesNotThrowAnyException();
        byte[] m4a = {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0};
        Path aac = write("interview.m4a", m4a);
        assertThatCode(() -> policy.validateMergedFile("audio/mp4", m4a.length, aac)).doesNotThrowAnyException();
        byte[] fake = "not really audio".getBytes(StandardCharsets.UTF_8);
        assertCode(write("fake.mp3", fake), "audio/mpeg", fake.length, "UPLOAD_MEDIA_SIGNATURE_INVALID");
    }

    @Test
    void mediaUploadsAreAcceptedWhenTheMimeMatchesTheExtension() {
        assertThatCode(() -> policy.validateMetadata(new CreateUploadRequest(
                "组会录音.mp3", 1024L, "audio/mpeg", 1024, 1))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateMetadata(new CreateUploadRequest(
                "组会录音.mp3", 1024L, "video/mp4", 1024, 1)))
                .isInstanceOf(BusinessException.class);
    }

    private void assertCode(Path file, String mime, long declaredSize, String code) {
        assertThatThrownBy(() -> policy.validateMergedFile(mime, declaredSize, file))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.code()).isEqualTo(code));
    }

    private Path write(String name, byte[] content) throws Exception {
        Path file = directory.resolve(name);
        Files.write(file, content);
        return file;
    }
}
