package com.noteweave.storage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Application-facing object storage port. */
public interface ObjectStorage {

    Path write(String bucket, String objectKey, byte[] content);

    byte[] read(String bucket, String objectKey);

    boolean exists(String bucket, String objectKey);

    void delete(String bucket, String objectKey);

    /** Bounded inventory for reconciliation of a controlled object-key prefix. */
    List<StoredObject> list(String bucket, String prefix, String afterKey, int limit);

    record StoredObject(String key, Instant lastModified) {}

    String backendName();
}
