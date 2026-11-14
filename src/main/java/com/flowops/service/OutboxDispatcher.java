package com.flowops.service;

import com.flowops.domain.EmailLogEntry;
import com.flowops.domain.EmailLogEntryRepository;
import com.flowops.domain.Notification;
import com.flowops.domain.NotificationRepository;
import com.flowops.domain.OutboxMessage;
import com.flowops.domain.OutboxMessageRepository;
import com.flowops.domain.OutboxStatus;
import com.flowops.domain.User;
import com.flowops.domain.UserRepository;
import com.flowops.observability.FlowOpsSpans;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Outbox dispatcher (PLAN §8): polls the outbox table and delivers messages.
 *
 * <p><b>Concurrency-safe</b>: uses {@code FOR UPDATE SKIP LOCKED} so multiple
 * instances can poll concurrently without double-delivering.
 *
 * <p><b>Retry with exponential backoff</b>:
 * <ul>
 *   <li>Attempt 1 fail → next retry in 5s</li>
 *   <li>Attempt 2 fail → next retry in 10s</li>
 *   <li>Attempt 3 fail → next retry in 20s</li>
 *   <li>... up to max_retries (default 5) → DEAD_LETTER</li>
 * </ul>
 *
 * <p><b>Channels</b>:
 * <ul>
 *   <li>{@code EMAIL} → resolves recipient email, calls {@link EmailGateway#send}, stores in {@code email_log}</li>
 *   <li>{@code FEED} → inserts a {@link Notification} row</li>
 * </ul>
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int BATCH_SIZE = 50;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);

    private final OutboxMessageRepository outbox;
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final EmailGateway emailGateway;
    private final EmailLogEntryRepository emailLog;
    private final MeterRegistry metrics;
    private final FlowOpsSpans spans;

    public OutboxDispatcher(OutboxMessageRepository outbox,
                            NotificationRepository notifications,
                            UserRepository users,
                            EmailGateway emailGateway,
                            EmailLogEntryRepository emailLog,
                            MeterRegistry metrics,
                            FlowOpsSpans spans) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.users = users;
        this.emailGateway = emailGateway;
        this.emailLog = emailLog;
        this.metrics = metrics;
        this.spans = spans;
        Gauge.builder("flowops.outbox.pending", this,
                d -> d.outbox.countByStatus(OutboxStatus.PENDING) + d.outbox.countByStatus(OutboxStatus.FAILED))
                .description("Outbox messages awaiting delivery (PENDING + FAILED)")
                .register(metrics);
    }

    /**
     * When {@code false} (tests / maintenance), the background poller no-ops
     * but {@link #dispatch()} can still be invoked manually.
     */
    private volatile boolean running = true;

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Scheduled poller: runs every 2 seconds.
     */
    @Scheduled(fixedDelay = 2000)
    public void scheduledTick() {
        if (running) {
            dispatch();
        }
    }

    /**
     * Deliver a batch of due outbox messages.
     * Called by the scheduler (when running) or directly by tests.
     */
    @Transactional
    public void dispatch() {
        List<OutboxMessage> batch = outbox.claimBatch(Instant.now(), BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        log.info("OUTBOX DISPATCH: claiming {} messages", batch.size());

        Timer.builder("flowops.outbox.dispatch")
                .description("Time to deliver one outbox batch")
                .register(metrics)
                .record(() -> {
                    for (OutboxMessage msg : batch) {
                        deliver(msg);
                    }
                });
        outbox.saveAll(batch); // flush all status changes
    }

    /**
     * Deliver a single outbox message. Catches exceptions for retry logic.
     * One {@code outbox.deliver} span per message (iteration 8 tracing).
     */
    private void deliver(OutboxMessage msg) {
        Map<String, String> attrs = Map.of(
                "outbox.key", msg.getIdempotencyKey(),
                "channel", msg.getChannel(),
                "request.id", String.valueOf(msg.getRecipientId()));
        try {
            spans.run("outbox.deliver", attrs, () -> {
                switch (msg.getChannel()) {
                    case "EMAIL" -> deliverEmail(msg);
                    case "FEED" -> deliverFeed(msg);
                    default -> log.warn("OUTBOX UNKNOWN CHANNEL: {} (key={})", msg.getChannel(), msg.getIdempotencyKey());
                }
                msg.markSent();
                metrics.counter("flowops.outbox.message.sent", "channel", msg.getChannel()).increment();
                log.debug("OUTBOX SENT: key={}", msg.getIdempotencyKey());
                return null;
            });
        } catch (RuntimeException e) {
            long backoffSeconds = BASE_BACKOFF.toSeconds() * (1L << msg.getRetryCount());
            Instant nextRetry = Instant.now().plus(Duration.ofSeconds(backoffSeconds));
            msg.markFailed(e.getMessage(), nextRetry);
            metrics.counter("flowops.outbox.message.failed", "channel", msg.getChannel()).increment();
            if (msg.getStatus() == OutboxStatus.DEAD_LETTER) {
                metrics.counter("flowops.outbox.message.dead_letter", "channel", msg.getChannel()).increment();
            }
            log.warn("OUTBOX FAIL (retry={}/{}): key={} error={} nextRetry={}",
                    msg.getRetryCount(), msg.getMaxRetries(),
                    msg.getIdempotencyKey(), e.getMessage(), nextRetry);
        }
    }

    private void deliverEmail(OutboxMessage msg) {
        User recipient = users.findById(msg.getRecipientId())
                .orElseThrow(() -> new IllegalStateException("Recipient not found: " + msg.getRecipientId()));

        String to = recipient.getEmail();
        if (to == null || to.isBlank()) {
            // No email on file: the FEED channel (written for the same event)
            // already covers the user. Retrying would never succeed.
            log.warn("OUTBOX EMAIL SKIPPED (no email address): key={} recipient={}",
                    msg.getIdempotencyKey(), msg.getRecipientId());
            return; // caller marks SENT
        }

        Map<String, Object> payload = parsePayload(msg.getPayload());
        String subject = String.valueOf(payload.getOrDefault("subject", "FlowOps Notification"));
        String body = String.valueOf(payload.getOrDefault("body", ""));
        String html = payload.containsKey("html") ? String.valueOf(payload.get("html")) : null;

        if (html != null && !html.isBlank()) {
            emailGateway.sendHtml(to, subject, body, html);
        } else {
            emailGateway.send(to, subject, body);
        }
        // Audit the delivery (works for both SMTP and simulated gateways)
        emailLog.save(new EmailLogEntry(to, subject, body, msg.getId()));
    }

    private void deliverFeed(OutboxMessage msg) {
        Map<String, Object> payload = parsePayload(msg.getPayload());
        Notification notification = new Notification(
                msg.getRecipientId(),
                String.valueOf(payload.getOrDefault("type", "NOTIFY")),
                String.valueOf(payload.getOrDefault("title", "Notification")),
                String.valueOf(payload.getOrDefault("body", "")),
                payload.get("requestId") instanceof Number n ? n.longValue() : null
        );
        notifications.save(notification);
    }

    /**
     * Parse JSON payload without external dependencies.
     * Uses a simple regex-based extractor for the flat key-value maps we write.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        // Use Jackson (available via Spring Boot) — Boot 4.1 uses tools.jackson
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse outbox payload: {}", json, e);
            return Map.of();
        }
    }
}
