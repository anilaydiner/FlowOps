package com.flowops.domain.execution;

import java.time.Duration;
import java.util.*;

/** Pure per-action adapter metadata. Guarantees must hold for the stable logical-action key. */
public record ProviderContract(String providerId,String revision,Transport transport,Duration connectTimeout,
                               Duration readTimeout,Duration executionTimeout,Duration reconciliationTimeout,
                               boolean idempotentRetries,boolean lookupSupported,boolean simulated) {
    public enum Transport { LOCAL, NETWORK }
    public ProviderContract {
        if(providerId==null || !providerId.matches("[A-Za-z0-9._:-]{1,100}") || revision==null || !revision.matches("[A-Za-z0-9._:-]{1,100}"))
            throw new IllegalArgumentException("Provider id and revision must be stable identifiers of 1–100 characters");
        Objects.requireNonNull(transport,"transport");
        for(var timeout:List.of(connectTimeout,readTimeout,executionTimeout,reconciliationTimeout))
            if(timeout.isNegative() || timeout.compareTo(Duration.ofDays(1))>0) throw new IllegalArgumentException("Provider timeouts must be between zero and one day");
        if(executionTimeout.toMillis()<1 || reconciliationTimeout.toMillis()<1)
            throw new IllegalArgumentException("Execution and reconciliation budgets must be at least one millisecond");
        if(transport==Transport.NETWORK && (connectTimeout.toMillis()<1 || readTimeout.toMillis()<1))
            throw new IllegalArgumentException("Network providers require explicit connect and read timeouts");
        if(transport==Transport.LOCAL && (!connectTimeout.isZero() || !readTimeout.isZero()))
            throw new IllegalArgumentException("Local providers must declare zero network timeouts");
        if(connectTimeout.plus(readTimeout).compareTo(executionTimeout)>0)
            throw new IllegalArgumentException("Connect and read budgets must fit the total execution budget");
    }
    public Map<String,Object> snapshot() {
        var m=new LinkedHashMap<String,Object>();m.put("providerId",providerId);m.put("revision",revision);m.put("transport",transport.name());
        m.put("connectTimeoutMillis",connectTimeout.toMillis());m.put("readTimeoutMillis",readTimeout.toMillis());
        m.put("executionTimeoutMillis",executionTimeout.toMillis());m.put("reconciliationTimeoutMillis",reconciliationTimeout.toMillis());
        m.put("idempotentRetries",idempotentRetries);m.put("lookupSupported",lookupSupported);m.put("simulated",simulated);return Collections.unmodifiableMap(m);
    }
    public static ProviderContract fromSnapshot(Map<String,Object> m) {
        return new ProviderContract((String)m.get("providerId"),(String)m.get("revision"),Transport.valueOf((String)m.get("transport")),
                millis(m,"connectTimeoutMillis"),millis(m,"readTimeoutMillis"),millis(m,"executionTimeoutMillis"),millis(m,"reconciliationTimeoutMillis"),
                (Boolean)m.get("idempotentRetries"),(Boolean)m.get("lookupSupported"),(Boolean)m.get("simulated"));
    }
    private static Duration millis(Map<String,Object> m,String key) { return Duration.ofMillis(((Number)m.get(key)).longValue()); }
}
