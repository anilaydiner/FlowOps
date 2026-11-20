package com.flowops.config;

import com.flowops.application.config.FlowOpsUrls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the application base URLs (API + web UI) from properties into the framework-free
 * {@link FlowOpsUrls} value used by the email composer.
 */
@Configuration
public class FlowOpsUrlsConfig {

    @Bean
    public FlowOpsUrls flowOpsUrls(@Value("${flowops.base-url:http://localhost:8080}") String baseUrl,
            @Value("${flowops.fe-base-url:http://localhost:5173}") String feBaseUrl) {
        return new FlowOpsUrls(baseUrl, stripTrailingSlash(feBaseUrl));
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

}
