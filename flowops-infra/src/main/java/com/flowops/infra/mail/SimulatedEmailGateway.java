package com.flowops.infra.mail;
import com.flowops.domain.port.EmailGateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-transport email gateway: logs the email and supports failure injection
 * for retry/backoff tests (PLAN §8).
 *
 * <p>Default gateway ({@code flowops.email.gateway=simulated}) — tests rely on
 * it being present and on the failure-injection API.
 *
 * <p>In dev, use Mailpit instead: {@code FLOWOPS_EMAIL_GATEWAY=smtp}
 * (see {@code SmtpEmailGateway} + PLAN §11).
 */
@Component
@ConditionalOnProperty(prefix = "flowops.email", name = "gateway",
        havingValue = "simulated", matchIfMissing = true)
public class SimulatedEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedEmailGateway.class);

    private volatile boolean simulatedFailure = false;
    private volatile int failuresRemaining = 0;

    public SimulatedEmailGateway() {
        log.warn("EMAIL GATEWAY: SIMULATED (log-only, NO transport). Sends are recorded in "
                + "the outbox as SENT but nothing reaches Mailpit/SMTP/inbox. "
                + "To deliver real mail set FLOWOPS_EMAIL_GATEWAY=smtp (Mailpit at localhost:1025).");
    }

    @Override
    public void send(String to, String subject, String body) {
        if (simulatedFailure) {
            if (failuresRemaining > 0) {
                failuresRemaining--;
            }
            throw new EmailGatewayException("Simulated email failure (to=" + to + ")");
        }
        log.warn("EMAIL NOT DELIVERED (simulated gateway, log-only): to={} subject='{}' bodyLen={}",
                to, subject, body == null ? 0 : body.length());
    }

    @Override
    public void sendHtml(String to, String subject, String textBody, String htmlBody) {
        if (simulatedFailure) {
            if (failuresRemaining > 0) {
                failuresRemaining--;
            }
            throw new EmailGatewayException("Simulated email failure (to=" + to + ")");
        }
        log.warn("EMAIL NOT DELIVERED (simulated gateway, log-only): to={} subject='{}' textLen={} htmlLen={}",
                to, subject,
                textBody == null ? 0 : textBody.length(), htmlBody == null ? 0 : htmlBody.length());
    }

    /**
     * Enable simulated failures. With {@code count > 0} the next {@code count}
     * sends fail; with {@code count = 0} sends fail indefinitely until
     * {@link #clearSimulatedFailure()} is called.
     */
    public void setSimulatedFailure(boolean fail, int count) {
        this.simulatedFailure = fail;
        this.failuresRemaining = count;
        if (fail) {
            log.warn("EMAIL GATEWAY: simulated failure enabled ({} sends)", count);
        }
    }

    public void clearSimulatedFailure() {
        this.simulatedFailure = false;
        this.failuresRemaining = 0;
    }
}
