package com.flowops.worker;

import com.flowops.application.identity.session.ExpiredSessionCleanup;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Remove expired families and their token hashes hourly; no credentials are logged. */
@Component
public class AuthenticationSessionCleanup {
    private final ExpiredSessionCleanup cleanup;

    public AuthenticationSessionCleanup(ExpiredSessionCleanup cleanup) { this.cleanup = cleanup; }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void scheduledTick() { cleanup.purge(); }
}
