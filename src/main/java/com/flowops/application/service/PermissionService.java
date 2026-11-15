package com.flowops.application.service;
import com.flowops.domain.exception.NotFoundException;

import com.flowops.domain.query.PermissionFilter;
import com.flowops.domain.Permission;
import com.flowops.domain.port.PermissionRepository;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PermissionService {

    private final PermissionRepository permissions;
    private final AuthorizationEvaluator authz;

    public PermissionService(PermissionRepository permissions, AuthorizationEvaluator authz) {
        this.permissions = permissions;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public PageResult<Permission> list(PermissionFilter filter, PageQuery query, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.PERMISSIONS_LIST);
        return permissions.findAll(filter, query);
    }

    @Transactional(readOnly = true)
    public Permission get(Long id, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.PERMISSIONS_READ);
        return permissions.findById(id)
                .orElseThrow(() -> new NotFoundException("Permission not found: " + id));
    }
}
