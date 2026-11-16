package com.flowops.application.notification.outbox;

import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.application.notification.outbox.port.OutboxPayloadCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes outbox messages atomically with the caller's transaction (PLAN §8).
 *
 * <p><b>Idempotency</b>: if the same {@code idempotencyKey} already exists
 * (e.g. the business code re-runs within the same or a retried transaction),
 * the write is silently skipped — no duplicate notifications.
 *
 * <p>Payloads are built as {@link OutboxPayload} value objects; the JSON
 * storage shape is owned by the {@link OutboxPayloadCodec} adapter.
 *
 * <p>Usage from business code (e.g. the workflow use cases in {@code service.workflow}):
 * <pre>{@code
 * outbox.notifyApprover(requestId, approverId, "EXPENSE", "New expense submitted");
 * outbox.notifyRequester(requestId, requesterId, "APPROVED", "Your request was approved");
 * }</pre>
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxMessageRepository outbox;
    private final OutboxPayloadCodec codec;

    public OutboxService(OutboxMessageRepository outbox, OutboxPayloadCodec codec) {
        this.outbox = outbox;
        this.codec = codec;
    }

    /**
     * Enqueue a FEED notification for the given recipient.
     * Idempotent: repeated calls with the same key are no-ops.
     */
    public void notifyFeed(Long requestId, Long recipientId, String type,
                           String title, String body, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_NOTIFICATION, requestId, type, recipientId);
        enqueue(key, OutboxMessage.CHANNEL_FEED, recipientId,
                OutboxPayload.feed(requestId, type, title, body), correlationId);
    }

    /**
     * Enqueue an EMAIL notification for the given recipient.
     * The dispatcher resolves the email address at delivery time.
     */
    public void notifyEmail(Long requestId, Long recipientId, String type,
                            String subject, String body, String correlationId) {
        notifyEmail(requestId, recipientId, type, subject, body, null, correlationId);
    }

    /**
     * Enqueue an EMAIL notification with optional HTML body.
     */
    public void notifyEmail(Long requestId, Long recipientId, String type,
                            String subject, String body, String html, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_NOTIFICATION, requestId, type, recipientId);
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, recipientId,
                OutboxPayload.email(requestId, type, subject, body, html), correlationId);
    }

    /**
     * Enqueue a FEED notification scoped to a workflow step.
     * Key = (request, type, stepInstance, recipient): the same approver may be
     * notified once per step, while re-delivery of one step stays idempotent.
     */
    public void notifyStepFeed(Long requestId, Long stepInstanceId, Long recipientId,
                               String type, String title, String body, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_STEP_NOTIFICATION, requestId, type, stepInstanceId, recipientId);
        enqueue(key, OutboxMessage.CHANNEL_FEED, recipientId,
                OutboxPayload.feed(requestId, type, title, body), correlationId);
    }

    /**
     * Enqueue an EMAIL notification scoped to a workflow step (see {@link #notifyStepFeed}).
     */
    public void notifyStepEmail(Long requestId, Long stepInstanceId, Long recipientId,
                                String type, String subject, String body, String html, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_STEP_NOTIFICATION, requestId, type, stepInstanceId, recipientId);
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, recipientId,
                OutboxPayload.email(requestId, type, subject, body, html), correlationId);
    }

    /**
     * Enqueue a welcome email for a newly created user.
     * Idempotent per user: one welcome per account, ever.
     */
    public void welcomeEmail(Long userId, String subject, String body,
                             String html, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_USER_WELCOME, userId);
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, userId,
                new OutboxPayload(null, OutboxEvents.WELCOME, null, subject, body, html, null), correlationId);
    }

    /**
     * Enqueue a password-reset email for the given user.
     *
     * <p>Idempotent per token: the key embeds the token hash, so re-delivering
     * the same reset (e.g. after a dispatcher retry) does not duplicate, while
     * two distinct reset requests each produce their own message.
     */
    public void passwordResetEmail(Long userId, String tokenHash,
                                   String subject, String body,
                                   String html, String correlationId) {
        String key = String.format("user:%d:reset:%s", userId, tokenHash);
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, userId,
                new OutboxPayload(null, OutboxEvents.PASSWORD_RESET, null, subject, body, html, null), correlationId);
    }

    /**
     * Enqueue a SLA breach / escalation alert.
     */
    public void notifySlaBreach(Long requestId, Long recipientId,
                                String stepCode, String message, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_SLA_BREACH, requestId, recipientId);
        OutboxPayload payload =
                new OutboxPayload(requestId, OutboxEvents.SLA_BREACH, null, null, message, null, stepCode);
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, recipientId, payload, correlationId);
        // Also add to feed
        enqueue(key + ":feed", OutboxMessage.CHANNEL_FEED, recipientId, payload, correlationId);
    }

    // ── internal ─────────────────────────────────────────────────────────

    private void enqueue(String idempotencyKey, String channel, Long recipientId,
                         OutboxPayload payload, String correlationId) {
        OutboxMessage msg = new OutboxMessage(idempotencyKey, channel, recipientId,
                codec.encode(payload), correlationId);
        outbox.saveIfAbsent(msg).ifPresentOrElse(
                saved -> log.info("OUTBOX ENQUEUED: key={} channel={} recipient={}",
                        idempotencyKey, channel, recipientId),
                () -> log.debug("OUTBOX SKIP (duplicate): {}", idempotencyKey));
    }
}
