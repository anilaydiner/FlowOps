package com.flowops;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

final class LegacyRequestFixtures {
    private LegacyRequestFixtures() {}

    static void approveWithoutManifest(JdbcTemplate jdbc, Long requestId, Instant at, boolean wasPlanned) {
        var timestamp = Timestamp.from(at);
        jdbc.update("""
                UPDATE requests
                SET status = 'APPROVED', approved_at = ?, execution_planned_at = ?,
                    updated_at = ?, version = version + 1
                WHERE id = ?
                """, timestamp, wasPlanned ? timestamp : null, timestamp, requestId);
    }
}
