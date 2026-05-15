package com.noteweave.storage.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StoragePropertiesValidatorTest {

    @Test
    void devProfile_ShouldAllowDefaultMinioCredentials() {
        StorageProperties properties = storageProperties("noteweave", "noteweave-minio-secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        StoragePropertiesValidator validator = new StoragePropertiesValidator(properties, environment);
        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void prodProfile_ShouldRejectDefaultMinioCredentials() {
        StorageProperties properties = storageProperties("noteweave", "noteweave-minio-secret");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        StoragePropertiesValidator validator = new StoragePropertiesValidator(properties, environment);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Default MinIO credentials");
    }

    @Test
    void stagingProfile_ShouldRejectBlankMinioCredentials() {
        StorageProperties properties = storageProperties(" ", " ");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        StoragePropertiesValidator validator = new StoragePropertiesValidator(properties, environment);
        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be blank");
    }

    private StorageProperties storageProperties(String accessKey, String secretKey) {
        return new StorageProperties(
                new StorageProperties.Minio(
                        "http://localhost:9000",
                        accessKey,
                        secretKey,
                        "noteweave-dev",
                        "noteweave-test"
                ),
                new StorageProperties.Paths(
                        "target/noteweave-test",
                        "dev",
                        "test",
                        null
                )
        );
    }
}
