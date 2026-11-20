package com.flowops.domain.request;

/**
 * Business result, retained when an executed or rejected request is administratively
 * closed.
 */
public enum RequestOutcome {

    EXECUTED, REJECTED, CANCELLED, EXPIRED

}
