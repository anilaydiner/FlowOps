package com.flowops.mail;

import com.flowops.application.notification.port.EmailGateway;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Real SMTP delivery (PLAN §8). In dev this points at
 * <a href="https://github.com/axllent/mailpit">Mailpit</a> (localhost:1025,
 * UI on :8025); in production it would point at SES/SendGrid/SMTP relay.
 *
 * <p>Selected via {@code flowops.email.gateway=smtp}. Delivery failures throw
 * {@link EmailGatewayException} so the outbox retry/backoff machinery kicks in.
 *
 * <p>Builds its own {@link JavaMailSenderImpl} from {@code spring.mail.*}
 * properties — no reliance on Boot's mail auto-configuration (Boot 4
 * modularizes autoconfig modules; explicit wiring keeps this predictable).
 */
@Component
@ConditionalOnProperty(prefix = "flowops.email", name = "gateway", havingValue = "smtp")
public class SmtpEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailGateway.class);

    private final JavaMailSenderImpl sender;
    private final String from;

    public SmtpEmailGateway(
            @Value("${spring.mail.host:localhost}") String host,
            @Value("${spring.mail.port:1025}") int port,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password,
            @Value("${flowops.email.from:flowops@localhost}") String from) {
        this.from = from;
        this.sender = new JavaMailSenderImpl();
        this.sender.setHost(host);
        this.sender.setPort(port);
        if (username != null && !username.isBlank()) {
            this.sender.setUsername(username);
        }
        if (password != null && !password.isBlank()) {
            this.sender.setPassword(password);
        }
        Properties p = new Properties();
        p.put("mail.smtp.auth", username != null && !username.isBlank() ? "true" : "false");
        p.put("mail.smtp.connectiontimeout", "3000");
        p.put("mail.smtp.timeout", "3000");
        p.put("mail.smtp.writetimeout", "3000");
        this.sender.setJavaMailProperties(p);
        log.info("EMAIL GATEWAY: SMTP enabled host={}:{} from={}", host, port, from);
    }

    @Override
    public void send(String to, String subject, String body) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
            log.info("EMAIL SENT (smtp): to={} subject='{}' via {}:{}", to, subject,
                    sender.getHost(), sender.getPort());
        } catch (Exception e) {
            throw new EmailGatewayException(
                    "SMTP send failed (to=" + to + "): " + e.getMessage(), e);
        }
    }

    @Override
    public void sendHtml(String to, String subject, String textBody, String htmlBody) {
        try {
            MimeMessage message = sender.createMimeMessage();
            // multipart = true: text/plain + text/html
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(textBody, htmlBody);
            sender.send(message);
            log.info("EMAIL SENT (smtp/html): to={} subject='{}' via {}:{}", to, subject,
                    sender.getHost(), sender.getPort());
        } catch (Exception e) {
            throw new EmailGatewayException(
                    "SMTP HTML send failed (to=" + to + "): " + e.getMessage(), e);
        }
    }
}
