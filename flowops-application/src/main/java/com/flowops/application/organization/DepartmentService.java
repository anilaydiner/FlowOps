package com.flowops.application.organization;

import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.organization.Department;
import com.flowops.domain.organization.port.DepartmentRepository;
import com.flowops.domain.organization.query.DepartmentFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
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
