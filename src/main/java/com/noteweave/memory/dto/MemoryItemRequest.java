package com.noteweave.memory.dto;

import com.noteweave.memory.model.MemoryType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MemoryItemRequest {

    @NotNull
    private MemoryType memoryType;

    @NotBlank
    private String topic;

    @NotBlank
    private String summary;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private BigDecimal importanceScore = BigDecimal.valueOf(0.5d);

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private BigDecimal confidenceScore = BigDecimal.valueOf(1.0d);

    private Boolean pin = Boolean.FALSE;

    private LocalDateTime expiresAt;
}
