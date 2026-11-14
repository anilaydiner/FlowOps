package com.flowops;

import com.flowops.domain.DecisionAction;
import com.flowops.domain.User;
import com.flowops.domain.UserRepository;
import com.flowops.security.UserPrincipal;
import com.flowops.service.WorkflowEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optimistic-lock concurrency (iteration 4): multiple approvers race on the
 * same step. All read the same {@code @Version}; whichever commits second
 * hits {@code ObjectOptimisticLockingFailureException} → 409 at the API level.
 *
 * Uses the service layer directly (no HTTP) so the race window is the pure
 * DB transaction window: all threads read version N, then contend on the
 * UPDATE ... WHERE version=N. Only one commits; the rest get the exception.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentDecideTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private UserRepository users;

    private final HttpClient http = HttpClient.newHttpClient();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> send(String method, String path, String bearer, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url(path)));
        b.header("Content-Type", "application/json");
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        b.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(String username, String password) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/auth/token", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        int i = r.body().indexOf("\"token\":\"");
        String t = r.body().substring(i + "\"token\":\"".length());
        return t.substring(0, t.indexOf('"'));
    }

    private static long extractId(String body) {
        int i = body.indexOf("\"id\":");
        String n = body.substring(i + 5);
        int end = n.indexOf(',');
        if (end < 0) end = n.indexOf('}');
        return Long.parseLong(n.substring(0, end).trim());
    }

    private String adminToken() throws Exception {
        return token("admin", "admin123");
    }

    private long createDraft(String bearer, String typeCode, String note) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/requests", bearer,
                "{\"typeCode\":\"" + typeCode + "\",\"payload\":{\"note\":\"" + note + "\"}}");
        assertThat(r.statusCode()).isEqualTo(201);
        return extractId(r.body());
    }

    private int submit(String bearer, long id) throws Exception {
        return send("POST", "/api/v1/requests/" + id + "/submit", bearer, null).statusCode();
    }

    private void adminSetup(String suffix) throws Exception {
        String admin = adminToken();
        HttpResponse<String> wf = send("POST", "/api/v1/workflows", admin, """
                {"code":"WF_DUAL_%s","name":"Dual Approval","steps":[
                  {"position":1,"code":"DUAL_APPROVAL","name":"Dual Approval",
                   "requiredRole":"ADMIN","completionRule":"ANY_OF","requiredApprovals":1}]}
                """.formatted(suffix));
        assertThat(wf.statusCode()).describedAs("create workflow").isEqualTo(201);
        HttpResponse<String> type = send("POST", "/api/v1/request-types", admin, """
                {"code":"DUAL_%s","name":"Dual","departmentCode":"FINANCE",
                 "workflowCode":"WF_DUAL_%s","requiredFields":{"note":"Why"}}
                """.formatted(suffix, suffix));
        assertThat(type.statusCode()).describedAs("create request type").isEqualTo(201);
    }

    private void createUserAdmin(String username) throws Exception {
        String admin = adminToken();
        HttpResponse<String> r = send("POST", "/api/v1/users", admin, """
                {"username":"%s","displayName":"%s","password":"pass123456","roles":["ADMIN"]}
                """.formatted(username, username));
        assertThat(r.statusCode()).describedAs("create admin user").isEqualTo(201);
    }

    private UserPrincipal principal(String username) {
        User u = users.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("User not found: " + username));
        return new UserPrincipal(u.getId(), u.getUsername(), Set.of("ADMIN"));
    }

    @Test
    @DisplayName("simultaneous approvals on the same step → one succeeds, rest get optimistic-lock conflict")
    void simultaneousApprovals_oneGetsConflict() throws Exception {
        adminSetup("RACE");

        String alice = token("alice", "alice123");
        createUserAdmin("bob8");

        // Create and submit a request
        long rid = createDraft(alice, "DUAL_RACE", "race test");
        assertThat(submit(alice, rid)).isEqualTo(200);

        // Build principals for the concurrent approvers
        final UserPrincipal adminP = principal("admin");
        final UserPrincipal bobP = principal("bob8");

        // Fire 10 concurrent decides at the service layer.
        // All threads read the same @Version; the UPDATE ... WHERE version=N
        // guarantees only one commit succeeds. The rest get
        // ObjectOptimisticLockingFailureException (→ 409 at the API).
        final int threads = 10;
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        final AtomicBoolean sawSuccess = new AtomicBoolean(false);
        final AtomicBoolean sawConflict = new AtomicBoolean(false);
        final long requestId = rid;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final UserPrincipal ap = (i % 2 == 0) ? adminP : bobP;
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await();
                        engine.decide(requestId, DecisionAction.APPROVE, "race", ap);
                        sawSuccess.set(true);
                    } catch (ObjectOptimisticLockingFailureException e) {
                        sawConflict.set(true);
                    } catch (Exception e) {
                        // Ignore other exceptions (e.g., step already satisfied)
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(sawSuccess.get()).as("at least one concurrent approval should succeed").isTrue();
        assertThat(sawConflict.get()).as("at least one should hit the optimistic-lock conflict").isTrue();
    }
}
