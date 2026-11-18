package com.flowops;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseAcceptanceChecksTest extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Test void supplementalChecksExecuteReadOnlyAndExposeExplicitReviewVersusBlockerResults()throws Exception{
        String sql=new ClassPathResource("db/checks/m1_m4_release_acceptance.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var tx=new TransactionTemplate(transactions);tx.setReadOnly(true);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(s->{
            assertEquals("on",jdbc.queryForObject("SHOW transaction_read_only",String.class));
            long before=jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events",Long.class);
            var rows=jdbc.queryForList(sql);assertEquals(15,rows.size());
            assertEquals(15,rows.stream().map(r->r.get("code")).distinct().count());
            assertTrue(rows.stream().allMatch(r->Set.of("BLOCKER","REVIEW").contains(r.get("severity")) && ((Number)r.get("value")).longValue()>=0));
            assertTrue(rows.stream().anyMatch(r->r.get("code").equals("BLOCKED_OWNER_UNAVAILABLE") && r.get("severity").equals("REVIEW")));
            assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM lifecycle_audit_events",Long.class));
        });
    }
}
