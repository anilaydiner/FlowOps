package com.flowops.service;

import com.flowops.domain.OutboxMessage;
import com.flowops.domain.OutboxMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Writes outbox messages atomically with the caller's transaction (PLAN §8).
 *
 * <p><b>Idempotency</b>: if the same {@code idempotencyKey} already exists
 * (e.g. the business code re-runs within the same or a retried transaction),
 * the write is silently skipped — no duplicate notifications.
 *
 * <p>Usage from business code (e.g. {@code WorkflowEngine}):
 * <pre>{@code
 * outbox.notifyApprover(requestId, approverId, "EXPENSE", "New expense submitted");
 * outbox.notifyRequester(requestId, requesterId, "APPROVED", "Your request was approved");
 * }</pre>
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_FEED = "FEED";

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
        String key = String.format("req:%d:%s:%d", requestId, type, recipientId);
        String payload = toJson(Map.of(
                "requestId", requestId,
                "type", type,
                "title", title,
                "body", body == null ? "" : body
        ));
        enqueue(key, CHANNEL_FEED, recipientId, payload, correlationId);
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
        String key = String.format("req:%d:%s:%d", requestId, type, recipientId);
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("requestId", requestId);
        fields.put("type", type);
        fields.put("subject", subject);
        fields.put("body", body == null ? "" : body);
        if (html != null && !html.isBlank()) {
            fields.put("html", html);
        }
        String payload = toJson(fields);
        enqueue(key, CHANNEL_EMAIL, recipientId, payload, correlationId);
    }

    /**
     * Enqueue a SLA breach / escalation alert.
     */
    public void notifySlaBreach(Long requestId, Long recipientId,
                                String stepCode, String message, String correlationId) {
        String key = String.format("req:%d:sla:%d", requestId, recipientId);
        String payload = toJson(Map.of(
                "requestId", requestId,
                "type", "SLA_BREACH",
                "step", stepCode,
                "body", message
        ));
        enqueue(key, CHANNEL_EMAIL, recipientId, payload, correlationId);
        // Also add to feed
        enqueue(key + ":feed", CHANNEL_FEED, recipientId, payload, correlationId);
    }

    // ── internal ─────────────────────────────────────────────────────────

    private void enqueue(String idempotencyKey, String channel, Long recipientId,
                         String payload, String correlationId) {
        // Idempotency check: skip if already enqueued
        if (outbox.findByIdempotencyKey(idempotencyKey).isPresent()) {
            log.debug("OUTBOX SKIP (idempotent): {}", idempotencyKey);
            return;
        }

        OutboxMessage msg = new OutboxMessage(idempotencyKey, channel, recipientId,
                payload, correlationId);

        try {
            outbox.save(msg);
            log.info("OUTBOX ENQUEUED: key={} channel={} recipient={}",
                    idempotencyKey, channel, recipientId);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert with same key — safe to ignore
            log.debug("OUTBOX SKIP (concurrent duplicate): {}", idempotencyKey);
        }
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
