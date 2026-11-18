package com.flowops;

import com.flowops.application.notification.model.EmailLogEntry;
import com.flowops.application.notification.outbox.OutboxMessage;
import com.flowops.application.notification.outbox.OutboxService;
import com.flowops.application.notification.outbox.OutboxStatus;
import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.application.notification.port.EmailLogEntryRepository;
import com.flowops.domain.identity.Role;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.port.RoleRepository;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.notification.Notification;
import com.flowops.domain.notification.port.NotificationRepository;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.mail.SimulatedEmailGateway;
import com.flowops.worker.JobWorker;
import com.flowops.worker.OutboxDispatcher;
import com.flowops.worker.SlaScanner;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Iteration 6 tests: Transactional Outbox + Notifications (PLAN §8).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxNotificationsTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("flowops")
                .withUsername("flowops")
                .withPassword("flowops");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired OutboxMessageRepository outboxRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired EmailLogEntryRepository emailLogRepo;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired com.flowops.worker.JobWorker jobWorker;
    @Autowired com.flowops.worker.SlaScanner slaScanner;
    @Autowired OutboxService outbox;
    @Autowired SimulatedEmailGateway emailGateway;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired DepartmentRepository departments;
    @Autowired PasswordEncoder encoder;

    // ── Setup: create test users ─────────────────────────────────────────

    private String bobToken;
    private String approverToken;

    @BeforeEach
    void setupUsers() throws Exception {
        // Create bob (MEMBER) if not exists
        if (users.findByUsername("bob").isEmpty()) {
            Set<Role> memberRoles = Set.of(roles.findByCode("MEMBER").orElseThrow());
            User bob = User.newUser("bob", "Bob Builder", "bob@example.com",
                    departments.findByCode("FINANCE").orElse(null), memberRoles, encoder.encode("bob123"));
            users.save(bob);
        }

        // Outbox delivery tests use the workflow's assigned ADMIN role.
        // Scope and participation denial are tested separately.
        // Create approver if not exists
        if (users.findByUsername("approver1").isEmpty()) {
            Set<Role> fmRoles = Set.of(roles.findByCode("ADMIN").orElseThrow());
            User approver = User.newUser("approver1", "Approver One", "approver1@example.com",
                    departments.findByCode("FINANCE").orElse(null), fmRoles, encoder.encode("pass123456"));
            users.save(approver);
        }

        bobToken = userToken("bob", "bob123");
        approverToken = userToken("approver1", "pass123456");

        // Deterministic delivery: tests trigger dispatcher.dispatch() manually;
        // the background poller would otherwise race with the assertions.
        dispatcher.setRunning(false);
        jobWorker.setRunning(false);
        slaScanner.setRunning(false);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String userToken(String username, String password) throws Exception {
        var resp = mvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int idx = resp.indexOf("\"token\":\"");
        int start = idx + "\"token\":\"".length();
        int end = resp.indexOf("\"", start);
        return resp.substring(start, end);
    }

    private String aliceToken() throws Exception { return userToken("alice", "alice123"); }
    private String adminToken() throws Exception { return userToken("admin", "admin123"); }

    private long createAndSubmit(String requesterToken, String typeCode, String payload) throws Exception {
        var resp = mvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + requesterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeCode\":\"" + typeCode + "\",\"payload\":" + payload + "}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        int idIdx = resp.indexOf("\"id\":");
        long id = Long.parseLong(resp.substring(idIdx + 5).split("[,}\\s]")[0].trim());

        mvc.perform(post("/api/v1/requests/" + id + "/submit")
                        .header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().is2xxSuccessful());
        return id;
    }

    private void approve(Long requestId, String approverToken) throws Exception {
        mvc.perform(post("/api/v1/requests/" + requestId + "/decisions")
                        .header("Authorization", "Bearer " + approverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"justification\":\"looks good\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private void reject(Long requestId, String approverToken) throws Exception {
        mvc.perform(post("/api/v1/requests/" + requestId + "/decisions")
                        .header("Authorization", "Bearer " + approverToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"justification\":\"not needed\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    /**
     * True when the outbox row's JSON payload refers to the given request id.
     * (Parses the JSONB properly — Hibernate re-serializes it on read-back,
     * so raw string matching on the stored format is unreliable.)
     */
    private boolean payloadRequestIs(OutboxMessage m, long reqId) {
        try {
            Map<String, Object> p = new tools.jackson.databind.ObjectMapper()
                    .readValue(m.getPayload(), Map.class);
            Object rid = p.get("requestId");
            return rid instanceof Number n && n.longValue() == reqId;
        } catch (Exception e) {
            return false;
        }
    }

    private String payloadType(OutboxMessage m) {
        try {
            Map<String, Object> p = new tools.jackson.databind.ObjectMapper()
                    .readValue(m.getPayload(), Map.class);
            Object t = p.get("type");
            return t == null ? "" : t.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Outbox entries created on workflow events")
    class OutboxCreation {

        @Test
        @Order(1)
        void submit_createsOutboxEntries_forRequester() throws Exception {
            long reqId = createAndSubmit(aliceToken(), "EXPENSE",
                    "{\"amount\":250,\"description\":\"test\"}");

            List<OutboxMessage> entries = outboxRepo.findAll();
            List<OutboxMessage> forRequest = entries.stream()
                    .filter(m -> payloadRequestIs(m, reqId))
                    .toList();

            assertThat(forRequest).isNotEmpty();
            assertThat(forRequest.stream().anyMatch(m -> m.getChannel().equals("FEED"))).isTrue();
            assertThat(forRequest.stream().anyMatch(m -> m.getChannel().equals("EMAIL"))).isTrue();
            forRequest.forEach(m -> assertThat(m.getStatus()).isEqualTo(OutboxStatus.PENDING));
        }

        @Test
        @Order(2)
        void approve_createsOutboxEntries_forRequester() throws Exception {
            long reqId = createAndSubmit(aliceToken(), "EXPENSE",
                    "{\"amount\":300,\"description\":\"test approve\"}");

            approve(reqId, approverToken);

            List<OutboxMessage> entries = outboxRepo.findAll();
            List<OutboxMessage> approvals = entries.stream()
                    .filter(m -> payloadRequestIs(m, reqId)
                            && payloadType(m).contains("APPROV"))
                    .toList();

            assertThat(approvals).isNotEmpty();
            assertThat(approvals.stream().anyMatch(m -> m.getChannel().equals("FEED"))).isTrue();
            assertThat(approvals.stream().anyMatch(m -> m.getChannel().equals("EMAIL"))).isTrue();
        }

        @Test
        @Order(3)
        void reject_createsOutboxEntries_forRequester() throws Exception {
            long reqId = createAndSubmit(aliceToken(), "EXPENSE",
                    "{\"amount\":150,\"description\":\"test reject\"}");

            reject(reqId, approverToken);

            List<OutboxMessage> entries = outboxRepo.findAll();
            List<OutboxMessage> rejections = entries.stream()
                    .filter(m -> payloadRequestIs(m, reqId)
                            && payloadType(m).contains("REJECT"))
                    .toList();

            assertThat(rejections).isNotEmpty();
            assertThat(rejections.stream().anyMatch(m -> m.getChannel().equals("FEED"))).isTrue();
            assertThat(rejections.stream().anyMatch(m -> m.getChannel().equals("EMAIL"))).isTrue();
        }
    }

    @Nested
    @DisplayName("Dispatcher delivers messages")
    class DispatcherDelivery {

        @Test
        @Order(1)
        void dispatch_feedsNotifications() throws Exception {
            long reqId = createAndSubmit(aliceToken(), "EXPENSE",
                    "{\"amount\":400,\"description\":\"dispatch test\"}");

            dispatcher.dispatch();

            Long aliceId = users.findByUsername("alice").orElseThrow().getId();
            List<Notification> aliceNotifs = notificationRepo.findUnread(aliceId);
            assertThat(aliceNotifs).isNotEmpty();
            assertThat(aliceNotifs.stream()
                    .anyMatch(n -> n.getRequestId().equals(reqId))).isTrue();
        }

        @Test
        @Order(2)
        void dispatch_sendsEmail_storedInLog() throws Exception {
            long reqId = createAndSubmit(bobToken, "EXPENSE",
                    "{\"amount\":500,\"description\":\"email dispatch\"}");

            dispatcher.dispatch();

            var bobUser = users.findByUsername("bob").orElseThrow();
            List<EmailLogEntry> emails = emailLogRepo
                    .findByToAddressOrderByCreatedAtDesc(bobUser.getEmail());
            assertThat(emails).isNotEmpty();
            assertThat(emails.get(0).getSubject()).contains("request");
        }

        @Test
        @Order(3)
        void dispatch_marksMessagesAsSent() throws Exception {
            long reqId = createAndSubmit(aliceToken(), "EXPENSE",
                    "{\"amount\":900,\"description\":\"mark sent\"}");

            dispatcher.dispatch();

            List<OutboxMessage> mine = outboxRepo.findAll().stream()
                    .filter(m -> payloadRequestIs(m, reqId))
                    .toList();
            assertThat(mine).isNotEmpty();
            mine.forEach(m -> assertThat(m.getStatus()).isEqualTo(OutboxStatus.SENT));
        }
    }

    @Nested
    @DisplayName("Retry and backoff")
    class RetryBackoff {

        @Test
        @Order(1)
        void gatewayFailure_triggersRetry() throws Exception {
            emailGateway.setSimulatedFailure(true, 3);

            long reqId = createAndSubmit(aliceToken(), "EXPENSE",
                    "{\"amount\":600,\"description\":\"retry test\"}");

            // Dispatch: email channel fails
            dispatcher.dispatch();

            // Email messages should be FAILED (retry < max)
            List<OutboxMessage> failed = outboxRepo.findAll().stream()
                    .filter(m -> m.getStatus() == OutboxStatus.FAILED
                            && payloadRequestIs(m, reqId)
                            && m.getChannel().equals("EMAIL"))
                    .toList();
            assertThat(failed).isNotEmpty();
            assertThat(failed.get(0).getRetryCount()).isEqualTo(1);
            assertThat(failed.get(0).getNextRetryAt()).isNotNull();

            // Clear failure and force retry eligibility (saveAll = merge, since
            // these instances are detached from any active persistence context)
            emailGateway.clearSimulatedFailure();
            failed.forEach(m -> m.setNextRetryAt(java.time.Instant.now().minusSeconds(1)));
            outboxRepo.saveAll(failed);

            // Dispatch again: should succeed now
            dispatcher.dispatch();

            OutboxMessage delivered = outboxRepo.findById(failed.get(0).getId()).orElseThrow();
            assertThat(delivered.getStatus()).isEqualTo(OutboxStatus.SENT);
        }

        @Test
        @Order(2)
        void repeatedFailures_leadToDeadLetter() throws Exception {
            emailGateway.setSimulatedFailure(true, 0);

            long reqId = createAndSubmit(aliceToken(), "EXPENSE",
                    "{\"amount\":700,\"description\":\"dead letter test\"}");

            // Dispatch multiple times to exhaust retries. Each round makes the
            // FAILED rows eligible again (saveAll, not flush — no active tx here).
            for (int i = 0; i < 6; i++) {
                List<OutboxMessage> failed = outboxRepo.findAll().stream()
                        .filter(m -> m.getStatus() == OutboxStatus.FAILED)
                        .toList();
                failed.forEach(m -> m.setNextRetryAt(java.time.Instant.now().minusSeconds(1)));
                outboxRepo.saveAll(failed);
                dispatcher.dispatch();
            }

            // At least one EMAIL message should be DEAD_LETTER
            List<OutboxMessage> dead = outboxRepo.findAll().stream()
                    .filter(m -> m.getStatus() == OutboxStatus.DEAD_LETTER
                            && payloadRequestIs(m, reqId)
                            && m.getChannel().equals("EMAIL"))
                    .toList();
            assertThat(dead).isNotEmpty();
            assertThat(dead.get(0).getRetryCount()).isGreaterThanOrEqualTo(5);

            emailGateway.clearSimulatedFailure();
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        void duplicateNotify_doesNotCreateDuplicate() throws Exception {
            Long recipientId = users.findByUsername("alice").orElseThrow().getId();
            Long reqId = 99999L;

            outbox.notifyFeed(reqId, recipientId, "NOTIFY_TEST", "Test", "First", null);
            outbox.notifyFeed(reqId, recipientId, "NOTIFY_TEST", "Test", "Second", null);

            List<OutboxMessage> matches = outboxRepo.findAll().stream()
                    .filter(m -> m.getIdempotencyKey().equals("req:99999:NOTIFY_TEST:" + recipientId))
                    .toList();
            assertThat(matches).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Feed endpoint")
    class FeedEndpoint {

        @Test
        @Order(1)
        void list_returnsMyNotifications() throws Exception {
            var alice = users.findByUsername("alice").orElseThrow();
            if (notificationRepo.findByRecipientId(alice.getId()).isEmpty()) {
                outbox.notifyFeed(1L, alice.getId(), "NOTIFY_TEST", "Hello", "World", null);
                dispatcher.dispatch();
            }

            mvc.perform(get("/api/v1/notifications")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").isNumber())
                    .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThan(0)))
                    .andExpect(jsonPath("$.content[0].type").isString())
                    .andExpect(jsonPath("$.content[0].title").isString())
                    .andExpect(jsonPath("$.content[0].read").isBoolean());
        }

        @Test
        @Order(2)
        void unreadCount_returnsNumber() throws Exception {
            mvc.perform(get("/api/v1/notifications/unread-count")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").isNumber());
        }

        @Test
        @Order(3)
        void markRead_updatesNotification() throws Exception {
            var alice = users.findByUsername("alice").orElseThrow();
            List<Notification> unread = notificationRepo.findUnread(alice.getId());
            if (unread.isEmpty()) {
                outbox.notifyFeed(2L, alice.getId(), "NOTIFY_MARKREAD", "MR", "test", null);
                dispatcher.dispatch();
                unread = notificationRepo.findUnread(alice.getId());
            }
            assertThat(unread).isNotEmpty();

            Long notifId = unread.get(0).getId();
            mvc.perform(patch("/api/v1/notifications/" + notifId + "/read")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.read").value(true))
                    .andExpect(jsonPath("$.readAt").isNotEmpty());
        }

        @Test
        @Order(4)
        void markAllRead_clearsUnread() throws Exception {
            var alice = users.findByUsername("alice").orElseThrow();
            if (notificationRepo.countUnread(alice.getId()) == 0) {
                outbox.notifyFeed(3L, alice.getId(), "NOTIFY_ALLREAD", "AR", "test", null);
                dispatcher.dispatch();
            }

            mvc.perform(patch("/api/v1/notifications/read-all")
                            .header("Authorization", "Bearer " + aliceToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(0));
        }
    }

    @Nested
    @DisplayName("Security")
    class Security {

        @Test
        void unauthenticated_gets401() throws Exception {
            mvc.perform(get("/api/v1/notifications"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void cannotSeeOthersNotifications() throws Exception {
            var bob = users.findByUsername("bob").orElseThrow();
            outbox.notifyFeed(100L, bob.getId(), "NOTIFY_BOB_ONLY", "Bob's", "secret", null);
            dispatcher.dispatch();

            var alice = users.findByUsername("alice").orElseThrow();
            List<Notification> aliceNotifs = notificationRepo.findByRecipientId(alice.getId());
            assertThat(aliceNotifs.stream()
                    .noneMatch(n -> n.getType().equals("NOTIFY_BOB_ONLY"))).isTrue();
        }
    }
}
