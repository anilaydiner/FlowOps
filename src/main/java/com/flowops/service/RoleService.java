package com.flowops.service;

import com.flowops.api.dto.RoleFilter;
import com.flowops.api.dto.RoleRequest;
import com.flowops.domain.Permission;
import com.flowops.domain.PermissionRepository;
import com.flowops.domain.Role;
import com.flowops.domain.RoleRepository;
import com.flowops.domain.RoleSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;

    public RoleService(RoleRepository roles, PermissionRepository permissions) {
        this.roles = roles;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public Page<Role> list(RoleFilter filter, Pageable pageable) {
        Specification<Role> spec = RoleSpecifications.from(filter);
        Page<Role> page = (spec == null) ? roles.findAll(pageable) : roles.findAll(spec, pageable);
        // Trigger lazy-load of permissions while the tx is still open
        page.getContent().forEach(r -> r.getPermissions().size());
        return page;
    }

    @Transactional(readOnly = true)
    public Role get(Long id) {
        Role role = roles.findById(id)
                .orElseThrow(() -> new NotFoundException("Role not found: " + id));
        // Trigger lazy-load of permissions while the tx is still open
        role.getPermissions().size();
        return role;
    }

    @Transactional
    public Role create(RoleRequest request) {
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
    public Role update(Long id, RoleRequest request) {
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
    public void delete(Long id) {
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
