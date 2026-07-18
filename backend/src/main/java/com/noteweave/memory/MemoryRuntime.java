package com.noteweave.memory;

/**
 * Canonical boundary for memory recall.  Callers obtain a pack and never
 * mutate memory as a side effect of recall.
 */
public interface MemoryRuntime {

    MemoryRuntimePack recall(MemoryRuntimeQuery query);

    MemoryObservationResult observe(ExecutionObservation observation);
}
