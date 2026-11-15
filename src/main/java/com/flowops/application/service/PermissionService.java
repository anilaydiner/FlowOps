package com.flowops.application.service;
import com.flowops.domain.exception.NotFoundException;

import com.flowops.domain.query.PermissionFilter;
import com.flowops.domain.Permission;
import com.flowops.domain.port.PermissionRepository;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PermissionService {

    private final PermissionRepository permissions;

    public PermissionService(PermissionRepository permissions) {
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public PageResult<Permission> list(PermissionFilter filter, PageQuery query) {
        return permissions.findAll(filter, query);
    }

    @Transactional(readOnly = true)
    public Permission get(Long id) {
        return permissions.findById(id)
                .orElseThrow(() -> new NotFoundException("Permission not found: " + id));
    }
}
