package com.flowops.service;
import com.flowops.domain.exception.NotFoundException;

import com.flowops.domain.query.PermissionFilter;
import com.flowops.domain.Permission;
import com.flowops.domain.PermissionRepository;
import com.flowops.domain.PermissionSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PermissionService {

    private final PermissionRepository permissions;

    public PermissionService(PermissionRepository permissions) {
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public Page<Permission> list(PermissionFilter filter, Pageable pageable) {
        Specification<Permission> spec = PermissionSpecifications.from(filter);
        return (spec == null) ? permissions.findAll(pageable) : permissions.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Permission get(Long id) {
        return permissions.findById(id)
                .orElseThrow(() -> new NotFoundException("Permission not found: " + id));
    }
}
