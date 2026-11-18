package com.flowops.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record RecoverRequest(@NotNull @Positive Long stepInstanceId,
                              @NotNull @PositiveOrZero Long expectedVersion,
                              @NotBlank @Size(max=1000) String reason,
                              Boolean refreshPool,
                              @Size(max=100) List<@NotNull @Valid Assignment> assignments) {
    public record Assignment(UUID predecessorId, @NotNull @Positive Long userId) {}
}
