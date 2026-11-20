package com.flowops.application.identity.session;

import com.flowops.application.identity.port.AuthenticationSessionRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpiredSessionCleanup {

    private final AuthenticationSessionRepository sessions;

    private final Clock clock;

    public ExpiredSessionCleanup(AuthenticationSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public int purge() {
        return sessions.deleteExpired(clock.instant());
    }

}
