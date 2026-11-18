package com.flowops.config;

import com.flowops.application.identity.session.SessionSettings;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthenticationConfig {
    @Bean
    public Clock authenticationClock() { return Clock.systemUTC(); }

    @Bean
    public SessionSettings sessionSettings(
            @Value("${flowops.auth.refresh-token-lifetime:7d}") Duration refreshLifetime,
            @Value("${flowops.auth.maximum-session-lifetime:30d}") Duration maximumLifetime) {
        return new SessionSettings(refreshLifetime, maximumLifetime);
    }
}
