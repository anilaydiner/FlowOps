package com.flowops.application.service;

import com.flowops.domain.port.UserRepository;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.service.ConditionEvaluator;
import com.flowops.domain.service.PayloadValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides stateless domain services as Spring beans.
 * The domain classes themselves carry no framework annotations —
 * this configuration is the application-layer wiring point.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public PayloadValidator payloadValidator() {
        return new PayloadValidator();
    }

    @Bean
    public ConditionEvaluator conditionEvaluator() {
        return new ConditionEvaluator();
    }

    @Bean
    public AuthorizationEvaluator authorizationEvaluator(UserRepository users) {
        return new AuthorizationEvaluator(users);
    }
}
