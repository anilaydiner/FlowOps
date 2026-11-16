package com.flowops.application.notification.outbox;

/**
 * Typed payload of an outbox message (PLAN §8).
 *
 * <p>Replaces the former flat key/value JSON contract: the writer
 * (notification services) and the reader (outbox dispatch) both work
 * against this value object, and the on-disk JSON shape is owned by the
 * {@link com.flowops.application.notification.outbox.port.OutboxPayloadCodec OutboxPayloadCodec}
 * adapter — application code does not depend on a JSON library.
 *
 * <p>Not every field applies to every channel: FEED messages use
 * type/title/body, EMAIL messages use type/subject/body (html optional),
 * SLA-breach messages add step. Inapplicable fields are {@code null};
 * the canonical constructor normalizes {@code body} (never null) and
 * {@code html} (null when blank).
 */
public record OutboxPayload(Long requestId,
                            String type,
                            String title,
                            String subject,
                            String body,
                            String html,
                            String step) {

    public OutboxPayload {
        body = body != null ? body : "";
        html = (html != null && !html.isBlank()) ? html : null;
    }

    /** FEED notification payload. */
    public static OutboxPayload feed(Long requestId, String type, String title, String body) {
        return new OutboxPayload(requestId, type, title, null, body, null, null);
    }

    /** EMAIL notification payload (html optional). */
    public static OutboxPayload email(Long requestId, String type,
                                      String subject, String body, String html) {
        return new OutboxPayload(requestId, type, null, subject, body, html, null);
    }
}
