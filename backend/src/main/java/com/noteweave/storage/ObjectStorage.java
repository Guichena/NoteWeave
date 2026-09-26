package com.noteweave.storage;

import java.nio.file.Path;

/** Application-facing object storage port. */
public interface ObjectStorage {

    Path write(String bucket, String objectKey, byte[] content);

    byte[] read(String bucket, String objectKey);

    boolean exists(String bucket, String objectKey);

    void delete(String bucket, String objectKey);

    String backendName();
}
