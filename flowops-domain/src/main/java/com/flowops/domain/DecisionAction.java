package com.flowops.domain;

/**
 * Decision actions recorded on a step instance.
 * DELEGATE/OVERRIDE arrive in later iterations (5/7); the schema supports them now.
 */
public enum DecisionAction {
    APPROVE,
    REJECT,
    DELEGATE,
    OVERRIDE
}
