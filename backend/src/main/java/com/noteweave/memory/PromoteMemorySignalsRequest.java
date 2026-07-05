package com.noteweave.memory;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PromoteMemorySignalsRequest(@NotEmpty List<String> signalIds) {
}
