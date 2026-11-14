package com.flowops.service;

/**
 * Abstraction over the email delivery mechanism (PLAN §8).
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code SmtpEmailGateway} — real SMTP (Mailpit in dev, SES/SendGrid in prod),
 *       selected via {@code flowops.email.gateway=smtp}</li>
 *   <li>{@code SimulatedEmailGateway} — no transport; logs + supports failure
 *       injection, selected via {@code flowops.email.gateway=simulated}
 *       (the default, and what tests run)</li>
 * </ul>
 *
 * <p>Both implementations are audited into {@code email_log} by the
 * {@code OutboxDispatcher} after a successful {@link #send} call.
 */
public interface EmailGateway {

    /**
     * Send a plain-text email.
     *
     * @param to      recipient address
     * @param subject subject line
     * @param body    body (plain text)
     * @throws EmailGatewayException on delivery failure (triggers outbox retry)
     */
    void send(String to, String subject, String body);

    /**
     * Send an HTML email with a plain-text fallback.
     * Default implementation falls back to {@link #send} with the text body.
     */
    default void sendHtml(String to, String subject, String textBody, String htmlBody) {
        send(to, subject, textBody);
    }

    class EmailGatewayException extends RuntimeException {
        public EmailGatewayException(String message) { super(message); }
        public EmailGatewayException(String message, Throwable cause) { super(message, cause); }
    }
}
