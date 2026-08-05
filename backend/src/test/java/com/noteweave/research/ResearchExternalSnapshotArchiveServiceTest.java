package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResearchExternalSnapshotArchiveServiceTest {

    private final ResearchExternalSnapshotArchiveService service =
            new ResearchExternalSnapshotArchiveService(null, null);

    @Test
    void shouldRejectAlternateLoopbackIpv4LiteralForms() {
        assertThat(service.nonPublicLiteralHost("2130706433")).isTrue();
        assertThat(service.nonPublicLiteralHost("0177.0.0.1")).isTrue();
        assertThat(service.nonPublicLiteralHost("0x7f.1")).isTrue();
        assertThat(service.nonPublicLiteralHost("127.0.0.1")).isTrue();
    }

    @Test
    void shouldRejectMalformedNumericLiteralsFailClosed() {
        assertThat(service.nonPublicLiteralHost("4294967296")).isTrue();
        assertThat(service.nonPublicLiteralHost("999.1.1.1")).isTrue();
        assertThat(service.nonPublicLiteralHost("08.0.0.1")).isTrue();
    }

    @Test
    void shouldAllowPublicLiteralsAndLeaveHostnamesForWorkerValidation() {
        assertThat(service.nonPublicLiteralHost("8.8.8.8")).isFalse();
        assertThat(service.nonPublicLiteralHost("134744072")).isFalse();
        assertThat(service.nonPublicLiteralHost("example.com")).isFalse();
    }
}
