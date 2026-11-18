package com.flowops.domain.request;

import java.util.UUID;

/** Null predecessor adds a new slot; otherwise replaces the current owner of an unvoted slot. */
public record RecoveryAssignment(UUID predecessorId, Long userId) {}
