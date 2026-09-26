package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MinioObjectStorageTest {

    @Test
    void shouldOnlyClassifyObjectMissingCodesAsAbsent() {
        assertThat(MinioObjectStorage.isObjectNotFoundCode("NoSuchKey")).isTrue();
        assertThat(MinioObjectStorage.isObjectNotFoundCode("NoSuchObject")).isTrue();
        assertThat(MinioObjectStorage.isObjectNotFoundCode("NoSuchBucket")).isFalse();
        assertThat(MinioObjectStorage.isObjectNotFoundCode("AccessDenied")).isFalse();
        assertThat(MinioObjectStorage.isObjectNotFoundCode(null)).isFalse();
    }
}
