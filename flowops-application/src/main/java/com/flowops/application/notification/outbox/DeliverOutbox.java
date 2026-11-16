package com.flowops.application.notification.outbox;

import com.flowops.application.notification.model.EmailLogEntry;
import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.application.notification.outbox.port.OutboxObservability;
import com.flowops.application.notification.outbox.port.OutboxPayloadCodec;
import com.flowops.application.notification.port.EmailGateway;
import com.flowops.application.notification.port.EmailLogEntryRepository;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.notification.Notification;
import com.flowops.domain.notification.port.NotificationRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: deliver a batch of due outbox messages (PLAN §8).
 *
 * <p>Claims a batch ({@code FOR UPDATE SKIP LOCKED}, so multiple instances
 * can poll concurrently without double-delivery) and routes each message by
 * channel:
 * <ul>
 *   <li><b>EMAIL</b> — resolves the recipient's address, sends via
 *       {@link EmailGateway}, and audits the delivery in {@code email_log}.
 *       Recipients with no email address on file are skipped (the matching
 *       FEED message already covers them).</li>
 *   <li><b>FEED</b> — inserts a {@link Notification} row.</li>
 * </ul>
 *
 * <p>Failures are recorded with exponential backoff
 * ({@link OutboxMessage#retryBackoff()}: 5s × 2^n) up to the retry budget,
 * after which the message is dead-lettered.
 */
@Service
public class DeliverOutbox {

    private static final Logger log = LoggerFactory.getLogger(DeliverOutbox.class);

    private final OutboxMessageRepository outbox;
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final EmailGateway emailGateway;
    private final EmailLogEntryRepository emailLog;
    private final OutboxPayloadCodec codec;
    private final OutboxObservability observability;

    public DeliverOutbox(OutboxMessageRepository outbox,
                         NotificationRepository notifications,
                         UserRepository users,
                         EmailGateway emailGateway,
                         EmailLogEntryRepository emailLog,
                         OutboxPayloadCodec codec,
                         OutboxObservability observability) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.users = users;
        this.emailGateway = emailGateway;
        this.emailLog = emailLog;
        this.codec = codec;
        this.observability = observability;
    }

    @Transactional
    public void dispatch() {
        List<OutboxMessage> batch = outbox.claimBatch(Instant.now(), OutboxMessageRepository.DEFAULT_BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        log.info("OUTBOX DISPATCH: claiming {} messages", batch.size());

        observability.dispatch(() -> {
            for (OutboxMessage msg : batch) {
                deliver(msg);
            }
        });
        outbox.saveAll(batch); // flush all status changes
    }

    /**
     * Deliver a single outbox message. Catches exceptions for retry logic.
     */
    private void deliver(OutboxMessage msg) {
        try {
            observability.runDelivery(msg, () -> {
                switch (msg.getChannel()) {
                    case OutboxMessage.CHANNEL_EMAIL -> deliverEmail(msg);
                    case OutboxMessage.CHANNEL_FEED -> deliverFeed(msg);
                    default -> log.warn("OUTBOX UNKNOWN CHANNEL: {} (key={})", msg.getChannel(), msg.getIdempotencyKey());
                }
                msg.markSent();
                observability.recordSent(msg);
                log.debug("OUTBOX SENT: key={}", msg.getIdempotencyKey());
                return null;
            });
        } catch (RuntimeException e) {
            Instant nextRetry = Instant.now().plus(msg.retryBackoff());
            msg.markFailed(e.getMessage(), nextRetry);
            observability.recordFailed(msg);
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

        OutboxPayload payload = codec.decode(msg.getPayload());
        String subject = payload.subject() != null ? payload.subject() : "FlowOps Notification";
        String body = payload.body() != null ? payload.body() : "";
        String html = payload.html();

        if (html != null) {
            emailGateway.sendHtml(to, subject, body, html);
        } else {
            emailGateway.send(to, subject, body);
        }
        // Audit the delivery (works for both SMTP and simulated gateways)
        emailLog.save(new EmailLogEntry(to, subject, body, msg.getId()));
    }

    private void deliverFeed(OutboxMessage msg) {
        OutboxPayload payload = codec.decode(msg.getPayload());
        Notification notification = new Notification(
                msg.getRecipientId(),
                payload.type() != null ? payload.type() : "NOTIFY",
                payload.title() != null ? payload.title() : "Notification",
                payload.body() != null ? payload.body() : "",
                payload.requestId()
        );
        notifications.save(notification);
    }
}
