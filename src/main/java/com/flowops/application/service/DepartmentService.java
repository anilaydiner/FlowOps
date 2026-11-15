package com.flowops.application.service;

import com.flowops.domain.Department;
import com.flowops.domain.port.DepartmentRepository;
import com.flowops.domain.query.DepartmentFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentService {

    private final DepartmentRepository departments;

    public DepartmentService(DepartmentRepository departments) {
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public PageResult<Department> list(DepartmentFilter filter, PageQuery query) {
        return departments.findAll(filter, query);
    }

    @Transactional(readOnly = true)
    public Department findByCode(String code) {
        return departments.findByCode(code)
                .orElseThrow(() -> new com.flowops.domain.exception.NotFoundException("Department not found: " + code));
    }
}
