package com.flowops.domain;

/**
 * Execution action codes triggered when a request is APPROVED.
 *
 * <p>Action codes are configured per {@link RequestType} (the
 * {@code execution_actions} column) and dispatched to an
 * {@code ActionHandler} by the {@code JobWorker}. The built-in actions are:
 *
 * <ul>
 *   <li>{@link #PROVISIONING} — IT access provisioning (IT_ACCESS)</li>
 *   <li>{@link #PURCHASE_TICKET} — procurement ticket (EQUIPMENT)</li>
 *   <li>{@link #ACCOUNTING_EXPORT} — accounting export (EXPENSE)</li>
 * </ul>
 */
public final class JobActions {

    private JobActions() {}

    public static final String PROVISIONING      = "PROVISIONING";
    public static final String PURCHASE_TICKET   = "PURCHASE_TICKET";
    public static final String ACCOUNTING_EXPORT = "ACCOUNTING_EXPORT";
}
