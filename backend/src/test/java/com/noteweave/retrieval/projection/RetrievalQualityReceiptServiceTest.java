package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.noteweave.security.AuditActorProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RetrievalQualityReceiptServiceTest {
    @Test
    void rejectsAClaimThatDoesNotContainTheCompleteAblationMatrix() {
        RetrievalQualityReceiptService service = new RetrievalQualityReceiptService(
                mock(JdbcTemplate.class), mock(AuditActorProvider.class));
        var request = new RetrievalQualityReceiptService.RecordRequest(
                "qa-weknora-hybrid-v1", "qa-weknora-gold-v1", "report-v1",
                "sha256:" + "a".repeat(64), true, 20, 0, 0, 0, 0,
                List.of(new RetrievalQualityReceiptService.AblationResult(
                        "keyword-only", 0.8, 0.8, 0.8, 1.0, 0, 1000)));

        assertThatThrownBy(() -> service.record("workspace", request))
                .hasMessageContaining("ablation matrix");
    }
}
