package com.flowops.domain;

/**
 * JSON payload keys of outbox messages.
 *
 * <p>The writer ({@link OutboxService}) and the reader
 * ({@code infra.worker.OutboxDispatcher}) must agree on exactly these keys —
 * they share this class as the single source of truth.
 */
public final class OutboxPayload {

    private OutboxPayload() {}

    public static final String REQUEST_ID = "requestId";
    public static final String TYPE       = "type";
    public static final String TITLE      = "title";
    public static final String BODY       = "body";
    public static final String SUBJECT    = "subject";
    public static final String HTML       = "html";
    public static final String STEP       = "step";
}
