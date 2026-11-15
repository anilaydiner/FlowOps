package com.flowops.application.service;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;

import com.flowops.domain.query.RoleFilter;
import com.flowops.domain.RoleSpec;
import com.flowops.domain.Permission;
import com.flowops.domain.port.PermissionRepository;
import com.flowops.domain.Role;
import com.flowops.domain.port.RoleRepository;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AuthorizationEvaluator authz;

    public RoleService(RoleRepository roles, PermissionRepository permissions, AuthorizationEvaluator authz) {
        this.roles = roles;
        this.permissions = permissions;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public PageResult<Role> list(RoleFilter filter, PageQuery query, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.ROLES_LIST);
        PageResult<Role> page = roles.findAll(filter, query);
        // Trigger lazy-load of permissions while the tx is still open
        page.content().forEach(r -> r.getPermissions().size());
        return page;
    }

    @Transactional(readOnly = true)
    public Role get(Long id, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.ROLES_READ);
        Role role = roles.findById(id)
                .orElseThrow(() -> new NotFoundException("Role not found: " + id));
        // Trigger lazy-load of permissions while the tx is still open
        role.getPermissions().size();
        return role;
    }

    @Transactional
    public Role create(RoleSpec request, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.ROLES_CREATE);
        String code = request.code().strip().toUpperCase();
        if (roles.existsByCode(code)) {
            throw new ConflictException("Role already exists: " + code);
        }
        Role role = new Role(code, request.name().strip(),
                (request.description() != null && !request.description().isBlank())
                        ? request.description().strip() : null);
        Set<Permission> permSet = resolvePermissions(request.permissions());
        if (!permSet.isEmpty()) {
            role.setPermissions(permSet);
        }
        Role saved = roles.save(role);
        saved.getPermissions().size();
        return saved;
    }

    @Transactional
    public Role update(Long id, RoleSpec request, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.ROLES_UPDATE);
        Role role = roles.findById(id)
                .orElseThrow(() -> new NotFoundException("Role not found: " + id));
        if (request.name() != null && !request.name().isBlank()) {
            role.setName(request.name().strip());
        }
        role.setDescription((request.description() != null && !request.description().isBlank())
                ? request.description().strip() : null);
        if (request.permissions() != null) {
            role.setPermissions(resolvePermissions(request.permissions()));
        }
        Role saved = roles.save(role);
        saved.getPermissions().size();
        return saved;
    }

    @Transactional
    public void delete(Long id, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.ROLES_DELETE);
        Role role = roles.findById(id)
                .orElseThrow(() -> new NotFoundException("Role not found: " + id));
        // Prevent deletion of built-in roles that users depend on
        if ("ADMIN".equals(role.getCode()) || "MEMBER".equals(role.getCode())) {
            throw new ConflictException("Cannot delete built-in role: " + role.getCode());
        }
        roles.delete(role);
    }

    private Set<Permission> resolvePermissions(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Set.of();
        }
        Set<Permission> result = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) continue;
            Permission p = permissions.findByCode(code.strip())
                    .orElseThrow(() -> new NotFoundException("Permission not found: " + code));
            result.add(p);
        }
        return result;
    }
}
