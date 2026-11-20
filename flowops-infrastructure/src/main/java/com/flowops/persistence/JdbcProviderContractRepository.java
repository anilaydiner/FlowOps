package com.flowops.persistence;

import com.flowops.application.execution.port.ProviderContractRepository;
import com.flowops.domain.execution.ProviderContract;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcProviderContractRepository implements ProviderContractRepository {

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcProviderContractRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAttempt(UUID token, String action, ProviderContract c) {
        jdbc.update(
                "INSERT INTO execution_attempt_provider_contracts(claim_token,action,provider_id,revision,contract) VALUES (?,?,?,?,?::jsonb)",
                token, action, c.providerId(), c.revision(), mapper.writeValueAsString(c.snapshot()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRecovery(UUID commandId, String action, ProviderContract c) {
        jdbc.update(
                "INSERT INTO execution_recovery_provider_contracts(command_id,action,provider_id,revision,contract) VALUES (?,?,?,?,?::jsonb)",
                commandId, action, c.providerId(), c.revision(), mapper.writeValueAsString(c.snapshot()));
    }

    @Override
    public Optional<ProviderContract> attempt(UUID token) {
        return jdbc
            .query("SELECT contract::text FROM execution_attempt_provider_contracts WHERE claim_token=?",
                    (rs, n) -> read(rs.getString(1)), token)
            .stream()
            .findFirst();
    }

    @Override
    public Optional<ProviderContract> recovery(UUID commandId) {
        return jdbc
            .query("SELECT contract::text FROM execution_recovery_provider_contracts WHERE command_id=?",
                    (rs, n) -> read(rs.getString(1)), commandId)
            .stream()
            .findFirst();
    }

    @Override
    public Optional<String> firstProvider(Long jobId) {
        var attempt = jdbc.queryForList(
                "SELECT c.provider_id FROM execution_attempts a JOIN execution_attempt_provider_contracts c USING(claim_token) WHERE a.job_id=? ORDER BY a.attempt_number LIMIT 1",
                String.class, jobId)
            .stream()
            .findFirst();
        return attempt.isPresent() ? attempt : jdbc
            .queryForList("SELECT provider_id FROM legacy_plan_provider_bindings WHERE job_id=?", String.class, jobId)
            .stream()
            .findFirst();
    }

    private ProviderContract read(String value) {
        return ProviderContract.fromSnapshot(mapper.readValue(value, new TypeReference<Map<String, Object>>() {
        }));
    }

}
