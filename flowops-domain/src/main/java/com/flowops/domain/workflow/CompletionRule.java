package com.flowops.domain.workflow;

/**
 * Step completion rules (PLAN §6.2).
 * ANY_OF needs one recorded slot; ALL_OF needs every slot frozen at activation;
 * K_OF_N needs k distinct recorded slots. Legacy ALL_OF instances retain their numeric threshold.
 */
public enum CompletionRule {
    ANY_OF,
    ALL_OF,
    K_OF_N
}
