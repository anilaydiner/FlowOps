package com.flowops.application.service;

import com.flowops.domain.Department;
import com.flowops.domain.port.DepartmentRepository;
import com.flowops.domain.query.DepartmentFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentService {

    private final DepartmentRepository departments;
    private final AuthorizationEvaluator authz;

    public DepartmentService(DepartmentRepository departments, AuthorizationEvaluator authz) {
        this.departments = departments;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public PageResult<Department> list(DepartmentFilter filter, PageQuery query, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.DEPARTMENTS_LIST);
        return departments.findAll(filter, query);
    }

    @Transactional(readOnly = true)
    public Department findByCode(String code, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.DEPARTMENTS_READ);
        return departments.findByCode(code)
                .orElseThrow(() -> new com.flowops.domain.exception.NotFoundException("Department not found: " + code));
    }
}
