package com.flowops.domain;

/**
 * Step completion rules (PLAN §6.2).
 * {@code required_approvals} carries the parameter:
 * ANY_OF(1) — one approval suffices; ALL_OF(n) — n distinct approvals; K_OF_N(k) — k of n.
 */
public enum CompletionRule {
    ANY_OF,
    ALL_OF,
    K_OF_N
}
