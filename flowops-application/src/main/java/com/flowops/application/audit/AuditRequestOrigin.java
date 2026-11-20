package com.flowops.application.audit;

/** Request metadata supplied by the HTTP edge, removed at the end of every request. */
public record AuditRequestOrigin(String correlationId, String remoteAddress, String userAgent) {

    private static final ThreadLocal<AuditRequestOrigin> CURRENT = new ThreadLocal<>();

    public static AuditRequestOrigin current() {
        return CURRENT.get();
    }

    public static Scope open(String correlationId, String remoteAddress, String userAgent) {
        AuditRequestOrigin previous = CURRENT.get();
        CURRENT.set(new AuditRequestOrigin(clean(correlationId, 64), clean(remoteAddress, 64), clean(userAgent, 512)));
        return () -> {
            if (previous == null)
                CURRENT.remove();
            else
                CURRENT.set(previous);
        };
    }

    private static String clean(String value, int length) {
        if (value == null)
            return null;
        String cleaned = value.replaceAll("[\\p{Cntrl}]", " ");
        return cleaned.substring(0, Math.min(length, cleaned.length()));
    }

    public interface Scope extends AutoCloseable {

        @Override
        void close();

    }
}
