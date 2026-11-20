package com.flowops.domain.identity;

import java.util.List;

/**
 * Input specification for creating or updating a role.
 *
 * <p>
 * Kept separate from the API's {@code RoleRequest} so the application layer depends only
 * on the domain. The controller maps the request DTO onto this value object before
 * calling the service.
 * </p>
 */
public record RoleSpec(String code, String name, String description, List<String> permissions) {
}
