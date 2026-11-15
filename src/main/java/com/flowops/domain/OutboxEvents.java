package com.flowops.domain;

/**
 * Outbox notification event types (the {@code type} field of an outbox payload).
 *
 * <p>Each user-facing notification pair has a feed variant (short, imperative
 * title for the in-app notification center) and an email variant (full
 * sentence subject). Both carry the same semantic event:
 *
 * <ul>
 *   <li>{@link #NOTIFY_SUBMIT} / {@link #SUBMITTED} — request entered review</li>
 *   <li>{@link #NOTIFY_DELEGATE} / {@link #DELEGATED} — step delegated to another approver</li>
 *   <li>{@link #NOTIFY_REJECT} / {@link #REJECTED} — request rejected</li>
 *   <li>{@link #NOTIFY_APPROVE} / {@link #APPROVED} — request approved</li>
 *   <li>{@link #SLA_BREACH} — SLA threshold exceeded for a pending step</li>
 * </ul>
 */
public final class OutboxEvents {

    private OutboxEvents() {}

    public static final String SUBMITTED       = "SUBMITTED";
    public static final String NOTIFY_SUBMIT   = "NOTIFY_SUBMIT";

    public static final String DELEGATED       = "DELEGATED";
    public static final String NOTIFY_DELEGATE = "NOTIFY_DELEGATE";

    public static final String REJECTED        = "REJECTED";
    public static final String NOTIFY_REJECT   = "NOTIFY_REJECT";

    public static final String APPROVED        = "APPROVED";
    public static final String NOTIFY_APPROVE  = "NOTIFY_APPROVE";

    public static final String SLA_BREACH      = "SLA_BREACH";

    public static final String WELCOME         = "WELCOME";
}
