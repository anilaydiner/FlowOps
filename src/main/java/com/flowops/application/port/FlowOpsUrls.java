package com.flowops.application.port;

/**
 * Application base URLs used in notification links (API + web UI).
 *
 * <p>Provided by configuration (see {@code config.FlowOpsUrlsConfig}) so
 * the {@code notification} package stays free of Spring {@code @Value}.
 */
public record FlowOpsUrls(String apiBaseUrl, String uiBaseUrl) {
}
