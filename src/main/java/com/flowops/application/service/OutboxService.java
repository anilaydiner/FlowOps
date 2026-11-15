package com.flowops.application.service;

import com.flowops.domain.OutboxEvents;
import com.flowops.domain.OutboxMessage;
import com.flowops.domain.port.OutboxMessageRepository;
import com.flowops.domain.OutboxPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Writes outbox messages atomically with the caller's transaction (PLAN §8).
 *
 * <p><b>Idempotency</b>: if the same {@code idempotencyKey} already exists
 * (e.g. the business code re-runs within the same or a retried transaction),
 * the write is silently skipped — no duplicate notifications.
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

    public OutboxService(OutboxMessageRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * Enqueue a FEED notification for the given recipient.
     * Idempotent: repeated calls with the same key are no-ops.
     */
    public void notifyFeed(Long requestId, Long recipientId, String type,
                           String title, String body, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_NOTIFICATION, requestId, type, recipientId);
        String payload = toJson(Map.of(
                OutboxPayload.REQUEST_ID, requestId,
                OutboxPayload.TYPE, type,
                OutboxPayload.TITLE, title,
                OutboxPayload.BODY, body == null ? "" : body
        ));
        enqueue(key, OutboxMessage.CHANNEL_FEED, recipientId, payload, correlationId);
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
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put(OutboxPayload.REQUEST_ID, requestId);
        fields.put(OutboxPayload.TYPE, type);
        fields.put(OutboxPayload.SUBJECT, subject);
        fields.put(OutboxPayload.BODY, body == null ? "" : body);
        if (html != null && !html.isBlank()) {
            fields.put(OutboxPayload.HTML, html);
        }
        String payload = toJson(fields);
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, recipientId, payload, correlationId);
    }

    /**
     * Enqueue a FEED notification scoped to a workflow step.
     * Key = (request, type, stepInstance, recipient): the same approver may be
     * notified once per step, while re-delivery of one step stays idempotent.
     */
    public void notifyStepFeed(Long requestId, Long stepInstanceId, Long recipientId,
                               String type, String title, String body, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_STEP_NOTIFICATION, requestId, type, stepInstanceId, recipientId);
        String payload = toJson(Map.of(
                OutboxPayload.REQUEST_ID, requestId,
                OutboxPayload.TYPE, type,
                OutboxPayload.TITLE, title,
                OutboxPayload.BODY, body == null ? "" : body
        ));
        enqueue(key, OutboxMessage.CHANNEL_FEED, recipientId, payload, correlationId);
    }

    /**
     * Enqueue an EMAIL notification scoped to a workflow step (see {@link #notifyStepFeed}).
     */
    public void notifyStepEmail(Long requestId, Long stepInstanceId, Long recipientId,
                                String type, String subject, String body, String html, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_STEP_NOTIFICATION, requestId, type, stepInstanceId, recipientId);
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put(OutboxPayload.REQUEST_ID, requestId);
        fields.put(OutboxPayload.TYPE, type);
        fields.put(OutboxPayload.SUBJECT, subject);
        fields.put(OutboxPayload.BODY, body == null ? "" : body);
        if (html != null && !html.isBlank()) {
            fields.put(OutboxPayload.HTML, html);
        }
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, recipientId, toJson(fields), correlationId);
    }

    /**
     * Enqueue a welcome email for a newly created user.
     * Idempotent per user: one welcome per account, ever.
     */
    public void welcomeEmail(Long userId, String subject, String body,
                             String html, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_USER_WELCOME, userId);
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put(OutboxPayload.TYPE, OutboxEvents.WELCOME);
        fields.put(OutboxPayload.SUBJECT, subject);
        fields.put(OutboxPayload.BODY, body == null ? "" : body);
        if (html != null && !html.isBlank()) {
            fields.put(OutboxPayload.HTML, html);
        }
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, userId, toJson(fields), correlationId);
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
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put(OutboxPayload.TYPE, OutboxEvents.PASSWORD_RESET);
        fields.put(OutboxPayload.SUBJECT, subject);
        fields.put(OutboxPayload.BODY, body == null ? "" : body);
        if (html != null && !html.isBlank()) {
            fields.put(OutboxPayload.HTML, html);
        }
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, userId, toJson(fields), correlationId);
    }

    /**
     * Enqueue a SLA breach / escalation alert.
     */
    public void notifySlaBreach(Long requestId, Long recipientId,
                                String stepCode, String message, String correlationId) {
        String key = String.format(OutboxMessage.KEY_FORMAT_SLA_BREACH, requestId, recipientId);
        String payload = toJson(Map.of(
                OutboxPayload.REQUEST_ID, requestId,
                OutboxPayload.TYPE, OutboxEvents.SLA_BREACH,
                OutboxPayload.STEP, stepCode,
                OutboxPayload.BODY, message
        ));
        enqueue(key, OutboxMessage.CHANNEL_EMAIL, recipientId, payload, correlationId);
        // Also add to feed
        enqueue(key + ":feed", OutboxMessage.CHANNEL_FEED, recipientId, payload, correlationId);
    }

    // ── internal ─────────────────────────────────────────────────────────

    private void enqueue(String idempotencyKey, String channel, Long recipientId,
                         String payload, String correlationId) {
        OutboxMessage msg = new OutboxMessage(idempotencyKey, channel, recipientId,
                payload, correlationId);
        outbox.saveIfAbsent(msg).ifPresentOrElse(
                saved -> log.info("OUTBOX ENQUEUED: key={} channel={} recipient={}",
                        idempotencyKey, channel, recipientId),
                () -> log.debug("OUTBOX SKIP (duplicate): {}", idempotencyKey));
    }

    /**
     * Minimal JSON serialization without external deps (avoids Jackson version issues).
     */
    static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(v.toString())).append('"');
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
