package com.flowops.domain;

public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED,
    DEAD_LETTER
}
