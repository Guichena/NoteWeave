package com.noteweave.upload;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UploadSecurityPolicyTest {

    private final UploadSecurityPolicy policy = new UploadSecurityPolicy();

    @Test
    void metadataMustMatchSafeTextExtensionAndMime() {
        assertThatThrownBy(() -> policy.validateMetadata(new CreateUploadRequest(
                "payload.exe", 10, "application/octet-stream", 10, 1)))
                .hasMessageContaining("仅支持");
        assertThatThrownBy(() -> policy.validateMetadata(new CreateUploadRequest(
                "../payload.md", 10, "text/markdown", 10, 1)))
                .hasMessageContaining("文件名");
    }

    @Test
    void mergedContentMustBeExactUtf8Text() {
        assertThatThrownBy(() -> policy.validateMergedContent("text/markdown", 2, new byte[]{(byte) 0xc3, 0x28}))
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> policy.validateMergedContent("text/markdown", 1, new byte[]{0}))
                .hasMessageContaining("NUL");
    }

    @Test
    void pdfMetadataAndSignatureShouldBeAccepted() {
        assertThatCode(() -> policy.validateMetadata(new CreateUploadRequest(
                "research.pdf", 8, "application/pdf", 8, 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validateMergedContent(
                "application/pdf", 8, "%PDF-1.7".getBytes()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateMergedContent(
                "application/pdf", 8, "not-a-pd".getBytes()))
                .hasMessageContaining("PDF 文件头");
    }
}
