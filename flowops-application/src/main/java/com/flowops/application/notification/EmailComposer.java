package com.flowops.application.notification;

import com.flowops.application.config.FlowOpsUrls;
import com.flowops.domain.identity.User;
import com.flowops.domain.request.Request;
import com.flowops.domain.workflow.RequestType;
import com.flowops.domain.workflow.WorkflowStep;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Composes rich email content (subject + plain text + HTML) for workflow events.
 *
 * <p>Each event type gets a consistent layout with:
 * <ul>
 *   <li>Clear subject line</li>
 *   <li>Request context (type, ID, payload details)</li>
 *   <li>Action taken by whom</li>
 *   <li>Link back to the FlowOps UI</li>
 * </ul>
 *
 * <p>HTML is inline-styled (no external CSS) for maximum mail-client compatibility.
 */
@Component
public class EmailComposer {

    private final String baseUrl;
    private final String feBaseUrl;

    public EmailComposer(FlowOpsUrls urls) {
        this.baseUrl = urls.apiBaseUrl();
        this.feBaseUrl = stripTrailingSlash(urls.uiBaseUrl());
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Result of composing an email: subject, plain-text body, and HTML body.
     */
    public record ComposedEmail(String subject, String text, String html) {}

    // ── Event composers ─────────────────────────────────────────────────

    public ComposedEmail submitted(Request request) {
        String typeCode = request.getType().getCode();
        String subject = "Your " + typeCode + " request has been submitted";
        String link = baseUrl + "/requests/" + request.getId();

        String text = """
                Hi %s,

                Your request has been submitted and is now in review.

                  Request:   #%d — %s
                  Status:    IN_REVIEW

                You can track its progress here:
                %s

                — FlowOps
                """.formatted(
                displayName(request),
                request.getId(),
                describeRequest(request),
                link
        );

        String html = """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width:560px; margin:0 auto; padding:24px;">
                  <h2 style="margin:0 0 8px 0; font-size:20px; color:#1a1a1a;">Request submitted</h2>
                  <p style="margin:0 0 16px 0; color:#555;">Hi %s, your request has been submitted and is now in review.</p>
                  %s
                  <a href="%s" style="display:inline-block; margin-top:16px; padding:10px 24px; background:#2563eb; color:#fff; text-decoration:none; border-radius:6px; font-size:14px;">View request</a>
                  <p style="margin-top:24px; font-size:12px; color:#999;">— FlowOps Request &amp; Approval</p>
                </div>
                """.formatted(
                displayName(request),
                requestDetailTable(request, "IN_REVIEW", "#e0e7ff", "#3730a3"),
                link
        );

        return new ComposedEmail(subject, text, html);
    }

    public ComposedEmail approved(Request request, String approverUsername) {
        String subject = "Request #" + request.getId() + " has been approved";
        String link = baseUrl + "/requests/" + request.getId();

        String text = """
                Hi %s,

                Great news — your request has been fully approved!

                  Request:   #%d — %s
                  Approved by:  %s

                Next steps will be handled by the operations team.

                %s

                — FlowOps
                """.formatted(
                displayName(request),
                request.getId(),
                describeRequest(request),
                approverUsername,
                link
        );

        String html = """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width:560px; margin:0 auto; padding:24px;">
                  <div style="background:#f0fdf4; border:1px solid #bbf7d0; border-radius:8px; padding:16px; margin-bottom:16px;">
                    <h2 style="margin:0; font-size:18px; color:#166534;">&#10003; Request Approved</h2>
                  </div>
                  <p style="margin:0 0 16px 0; color:#555;">Hi %s, great news — your request has been fully approved!</p>
                  %s
                  <p style="margin:16px 0 0 0; font-size:14px; color:#555;">Approved by <strong>%s</strong>. Next steps will be handled by the operations team.</p>
                  <a href="%s" style="display:inline-block; margin-top:16px; padding:10px 24px; background:#16a34a; color:#fff; text-decoration:none; border-radius:6px; font-size:14px;">View request</a>
                  <p style="margin-top:24px; font-size:12px; color:#999;">— FlowOps Request &amp; Approval</p>
                </div>
                """.formatted(
                displayName(request),
                requestDetailTable(request, "APPROVED", "#dcfce7", "#166534"),
                approverUsername,
                link
        );

        return new ComposedEmail(subject, text, html);
    }

    public ComposedEmail rejected(Request request, String rejectorUsername, String justification) {
        String subject = "Request #" + request.getId() + " has been rejected";
        String link = baseUrl + "/requests/" + request.getId();

        String text = """
                Hi %s,

                We regret to inform you that your request has been rejected.

                  Request:   #%d — %s
                  Rejected by:  %s
                  Reason:     %s

                If you believe this is an error, please reach out to your manager.

                %s

                — FlowOps
                """.formatted(
                displayName(request),
                request.getId(),
                describeRequest(request),
                rejectorUsername,
                justification != null ? justification : "No reason provided",
                link
        );

        String html = """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width:560px; margin:0 auto; padding:24px;">
                  <div style="background:#fef2f2; border:1px solid #fecaca; border-radius:8px; padding:16px; margin-bottom:16px;">
                    <h2 style="margin:0; font-size:18px; color:#991b1b;">&#10007; Request Rejected</h2>
                  </div>
                  <p style="margin:0 0 16px 0; color:#555;">Hi %s, we regret to inform you that your request has been rejected.</p>
                  %s
                  <div style="background:#fff; border:1px solid #e5e7eb; border-radius:6px; padding:12px; margin:16px 0;">
                    <p style="margin:0; font-size:13px; color:#555;">Reason: %s</p>
                  </div>
                  <p style="margin:16px 0 0 0; font-size:14px; color:#555;">If you believe this is an error, please reach out to your manager.</p>
                  <a href="%s" style="display:inline-block; margin-top:16px; padding:10px 24px; background:#6b7280; color:#fff; text-decoration:none; border-radius:6px; font-size:14px;">View request</a>
                  <p style="margin-top:24px; font-size:12px; color:#999;">— FlowOps Request &amp; Approval</p>
                </div>
                """.formatted(
                displayName(request),
                requestDetailTable(request, "REJECTED", "#fee2e2", "#991b1b"),
                justification != null ? justification : "No reason provided",
                link
        );

        return new ComposedEmail(subject, text, html);
    }

    public ComposedEmail delegated(Request request, String fromUsername, String toUsername, String stepCode) {
        String subject = "Approval delegated to you — Request #" + request.getId();
        String link = baseUrl + "/requests/" + request.getId();

        String text = """
                Hi %s,

                %s has delegated their approval authority on request #%d to you.

                  Request:   #%d — %s
                  Step:      %s
                  Delegated by:  %s

                Please review and decide when ready.

                %s

                — FlowOps
                """.formatted(
                toUsername,
                fromUsername,
                request.getId(),
                request.getId(),
                describeRequest(request),
                stepCode,
                fromUsername,
                link
        );

        String html = """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width:560px; margin:0 auto; padding:24px;">
                  <div style="background:#eff6ff; border:1px solid #bfdbfe; border-radius:8px; padding:16px; margin-bottom:16px;">
                    <h2 style="margin:0; font-size:18px; color:#1e40af;">&#8594; Approval Delegated to You</h2>
                  </div>
                  <p style="margin:0 0 16px 0; color:#555;">Hi %s, <strong>%s</strong> has delegated their approval authority on this request to you.</p>
                  %s
                  <p style="margin:16px 0 0 0; font-size:14px; color:#555;">Step: <strong>%s</strong> — please review and decide when ready.</p>
                  <a href="%s" style="display:inline-block; margin-top:16px; padding:10px 24px; background:#2563eb; color:#fff; text-decoration:none; border-radius:6px; font-size:14px;">Review request</a>
                  <p style="margin-top:24px; font-size:12px; color:#999;">— FlowOps Request &amp; Approval</p>
                </div>
                """.formatted(
                toUsername,
                fromUsername,
                requestDetailTable(request, "IN_REVIEW", "#e0e7ff", "#3730a3"),
                stepCode,
                link
        );

        return new ComposedEmail(subject, text, html);
    }

    public ComposedEmail slaBreach(Request request, String stepCode, String message) {
        String subject = "[SLA] Request #" + request.getId() + " — step " + stepCode + " is breaching";
        String link = baseUrl + "/requests/" + request.getId();

        String text = """
                SLA Alert

                Request #%d — step %s is approaching or past its SLA deadline.

                %s

                %s

                — FlowOps
                """.formatted(request.getId(), stepCode, message, link);

        String html = """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width:560px; margin:0 auto; padding:24px;">
                  <div style="background:#fffbeb; border:1px solid #fde68a; border-radius:8px; padding:16px; margin-bottom:16px;">
                    <h2 style="margin:0; font-size:18px; color:#92400e;">&#9888; SLA Breach Alert</h2>
                  </div>
                  %s
                  <p style="margin:16px 0 0 0; font-size:14px; color:#555;">%s</p>
                  <a href="%s" style="display:inline-block; margin-top:16px; padding:10px 24px; background:#d97706; color:#fff; text-decoration:none; border-radius:6px; font-size:14px;">Review request</a>
                  <p style="margin-top:24px; font-size:12px; color:#999;">— FlowOps Request &amp; Approval</p>
                </div>
                """.formatted(
                requestDetailTable(request, "IN_REVIEW", "#fef3c7", "#92400e"),
                message,
                link
        );

        return new ComposedEmail(subject, text, html);
    }

    public ComposedEmail approverRequested(Request request, WorkflowStep step) {
        String typeCode = request.getType().getCode();
        String subject = "Request #" + request.getId() + " awaits your approval — " + typeCode;
        String stepCode = step != null ? step.getCode() : null;
        String link = feBaseUrl + "/requests/" + request.getId();

        String text = """
                Hello,

                A new request is waiting for your approval.

                  Request:      #%d — %s
                  Submitted by: %s
                  %s
                Please review it when ready:
                %s

                — FlowOps
                """.formatted(
                request.getId(),
                describeRequest(request),
                displayName(request),
                stepCode != null ? "Step:           " + stepCode + System.lineSeparator() : "",
                link
        );

        String html = """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width:560px; margin:0 auto; padding:24px;">
                  <div style="background:#eff6ff; border:1px solid #bfdbfe; border-radius:8px; padding:16px; margin-bottom:16px;">
                    <h2 style="margin:0; font-size:18px; color:#1e40af;">&#9203; Your Approval Is Needed</h2>
                  </div>
                  <p style="margin:0 0 16px 0; color:#555;">Hello, a new request from <strong>%s</strong> is waiting for your approval.</p>
                  %s
                  <p style="margin:16px 0 0 0; font-size:14px; color:#555;">Please review it when ready.</p>
                  <a href="%s" style="display:inline-block; margin-top:16px; padding:10px 24px; background:#2563eb; color:#fff; text-decoration:none; border-radius:6px; font-size:14px;">Review request</a>
                  <p style="margin-top:24px; font-size:12px; color:#999;">— FlowOps Request &amp; Approval</p>
                </div>
                """.formatted(
                escapeHtml(displayName(request)),
                requestDetailTable(request, "IN_REVIEW", "#e0e7ff", "#3730a3"),
                link
        );

        return new ComposedEmail(subject, text, html);
    }

    public ComposedEmail welcome(User user, String initialPassword) {
        String name = (user.getDisplayName() != null && !user.getDisplayName().isBlank())
                ? user.getDisplayName() : user.getUsername();
        String roles = user.roleCodes().stream()
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        String link = baseUrl + "/";

        String text = """
                Hi %s,

                Your FlowOps account has been created. Here are your sign-in details:

                  Username:      %s
                  Password:      %s
                  Your roles:    %s
                  Sign in at:    %s

                Please change your password after your first sign-in.

                — FlowOps Request & Approval
                """.formatted(name, user.getUsername(), initialPassword,
                roles.isEmpty() ? "(none)" : roles, link);

        String html = """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width:560px; margin:0 auto; padding:24px;">
                  <div style="background:#eff6ff; border:1px solid #bfdbfe; border-radius:8px; padding:16px; margin-bottom:16px;">
                    <h2 style="margin:0; font-size:18px; color:#1e40af;">&#128075; Welcome to FlowOps</h2>
                  </div>
                  <p style="margin:0 0 16px 0; color:#555;">Hi %s, your FlowOps account has been created. Here are your sign-in details:</p>
                  <table style="border-collapse:collapse; margin-bottom:16px; font-size:14px;">
                    <tr><td style="padding:6px 16px 6px 0; color:#777;">Username</td><td style="padding:6px 0;"><strong>%s</strong></td></tr>
                    <tr><td style="padding:6px 16px 6px 0; color:#777;">Password</td><td style="padding:6px 0;"><strong>%s</strong></td></tr>
                    <tr><td style="padding:6px 16px 6px 0; color:#777;">Your roles</td><td style="padding:6px 0;">%s</td></tr>
                  </table>
                  <a href="%s" style="display:inline-block; margin-top:8px; padding:10px 24px; background:#2563eb; color:#fff; text-decoration:none; border-radius:6px; font-size:14px;">Sign in to FlowOps</a>
                  <p style="margin-top:20px; font-size:13px; color:#92400e; background:#fffbeb; border:1px solid #fde68a; border-radius:6px; padding:10px;">
                    Please change your password after your first sign-in.
                  </p>
                  <p style="margin-top:24px; font-size:12px; color:#999;">— FlowOps Request &amp; Approval</p>
                </div>
                """.formatted(escapeHtml(name), escapeHtml(user.getUsername()),
                escapeHtml(initialPassword), escapeHtml(roles.isEmpty() ? "(none)" : roles),
                link);

        return new ComposedEmail("Your FlowOps account is ready — sign-in details", text, html);
    }

    /**
     * Compose a "reset your password" email. The {@code resetUrl} is the full
     * link to the front-end reset form (host + path + {@code ?token=...}),
     * built by the caller so this composer stays free of FE-host config.
     *
     * @param user       the account owner (for greeting)
     * @param resetUrl   full reset link to embed in the email
     * @param expiresAt  when the link stops working (shown to the user)
     */
    public ComposedEmail passwordReset(User user, String resetUrl, Instant expiresAt) {
        String name = (user.getDisplayName() != null && !user.getDisplayName().isBlank())
                ? user.getDisplayName() : user.getUsername();
        String expiry = DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneId.of("UTC"))
                .format(expiresAt)
                .replace("T", " ").replace("Z", " UTC");

        String text = """
                Hi %s,

                We received a request to reset the password for your FlowOps account.

                Open the secure link below to choose a new password:

                %s

                This link expires at %s. If you did not request a reset, you
                can safely ignore this email — your password will not change.

                — FlowOps Request & Approval
                """.formatted(name, resetUrl, expiry);

        String html = """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width:560px; margin:0 auto; padding:24px;">
                  <div style="background:#eff6ff; border:1px solid #bfdbfe; border-radius:8px; padding:16px; margin-bottom:16px;">
                    <h2 style="margin:0; font-size:18px; color:#1e40af;">&#128274; Reset your password</h2>
                  </div>
                  <p style="margin:0 0 16px 0; color:#555;">Hi %s, we received a request to reset the password for your FlowOps account.</p>
                  <p style="margin:0 0 16px 0; color:#555;">Open the secure link below to choose a new password:</p>
                  <a href="%s" style="display:inline-block; margin:0 0 16px 0; padding:12px 28px; background:#2563eb; color:#fff; text-decoration:none; border-radius:6px; font-size:14px;">Reset password</a>
                  <p style="margin:16px 0 0 0; font-size:13px; color:#6b7280;">This link expires at <strong>%s</strong>.</p>
                  <p style="margin:16px 0 0 0; font-size:13px; color:#6b7280;">If you did not request a reset, ignore this email — your password will not change.</p>
                  <p style="margin-top:24px; font-size:12px; color:#999;">— FlowOps Request &amp; Approval</p>
                </div>
                """.formatted(escapeHtml(name), escapeHtml(resetUrl), escapeHtml(expiry));

        return new ComposedEmail("Reset your FlowOps password", text, html);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private String displayName(Request request) {
        String name = request.getRequester().getDisplayName();
        if (name != null && !name.isBlank()) return name;
        return request.getRequester().getUsername();
    }

    private String describeRequest(Request request) {
        RequestType type = request.getType();
        Map<String, Object> payload = request.getPayload();
        StringBuilder sb = new StringBuilder(type.getName() != null ? type.getName() : type.getCode());

        if (payload != null && !payload.isEmpty()) {
            String summary = summarizePayload(payload);
            if (!summary.isEmpty()) sb.append(" (").append(summary).append(")");
        }
        return sb.toString();
    }

    private String summarizePayload(Map<String, Object> payload) {
        // Show the most informative fields, max 3
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (count >= 3) break;
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(e.getKey()).append(": ").append(e.getValue());
            count++;
        }
        return sb.toString();
    }

    /**
     * Build a simple HTML table of request details.
     */
    private String requestDetailTable(Request request, String status, String badgeBg, String badgeColor) {
        RequestType type = request.getType();
        String typeName = type.getName() != null ? type.getName() : type.getCode();
        Map<String, Object> payload = request.getPayload();

        StringBuilder rows = new StringBuilder();
        rows.append("<tr><td style='padding:8px 12px; border-bottom:1px solid #f3f4f6; font-size:13px; color:#999;'>Request ID</td>")
            .append("<td style='padding:8px 12px; border-bottom:1px solid #f3f4f6; font-size:13px; color:#1a1a1a; font-weight:600;'>#").append(request.getId()).append("</td></tr>");
        rows.append("<tr><td style='padding:8px 12px; border-bottom:1px solid #f3f4f6; font-size:13px; color:#999;'>Type</td>")
            .append("<td style='padding:8px 12px; border-bottom:1px solid #f3f4f6; font-size:13px; color:#1a1a1a;'>").append(typeName).append("</td></tr>");

        if (payload != null) {
            for (Map.Entry<String, Object> e : payload.entrySet()) {
                rows.append("<tr><td style='padding:8px 12px; border-bottom:1px solid #f3f4f6; font-size:13px; color:#999;'>").append(escapeHtml(e.getKey())).append("</td>")
                    .append("<td style='padding:8px 12px; border-bottom:1px solid #f3f4f6; font-size:13px; color:#1a1a1a;'>").append(escapeHtml(String.valueOf(e.getValue()))).append("</td></tr>");
            }
        }

        rows.append("<tr><td style='padding:8px 12px; font-size:13px; color:#999;'>Status</td>")
            .append("<td style='padding:8px 12px; font-size:13px;'>")
            .append("<span style='display:inline-block; padding:2px 10px; border-radius:12px; background:").append(badgeBg).append("; color:").append(badgeColor).append("; font-size:12px; font-weight:600;'>").append(status).append("</span>")
            .append("</td></tr>");

        return """
                <table style="width:100%%; border-collapse:collapse; border:1px solid #e5e7eb; border-radius:8px; overflow:hidden; margin:16px 0;">
                  %s
                </table>
                """.formatted(rows);
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
