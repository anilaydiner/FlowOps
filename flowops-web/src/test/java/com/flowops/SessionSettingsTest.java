package com.flowops;

import com.flowops.application.identity.session.SessionSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionSettingsTest {

    @Test
    void invalidLifetimesCannotStartTheApplication() {
        assertThrows(IllegalArgumentException.class, () -> new SessionSettings(Duration.ZERO, Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSettings(Duration.ofSeconds(-1), Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionSettings(Duration.ofMillis(500), Duration.ofDays(1)));
        assertThrows(IllegalArgumentException.class, () -> new SessionSettings(Duration.ofDays(8), Duration.ofDays(7)));
    }

}
