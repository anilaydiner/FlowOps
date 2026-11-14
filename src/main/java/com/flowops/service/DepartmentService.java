package com.flowops.service;

import com.flowops.domain.Department;
import com.flowops.domain.DepartmentRepository;
import com.flowops.domain.DepartmentSpecifications;
import com.flowops.domain.query.DepartmentFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentService {

    private final DepartmentRepository departments;

    public DepartmentService(DepartmentRepository departments) {
        this.departments = departments;
    }

    @Transactional(readOnly = true)
    public Page<Department> list(DepartmentFilter filter, PageRequest pageRequest) {
        Specification<Department> spec = DepartmentSpecifications.from(filter);
        return (spec == null)
                ? departments.findAll(pageRequest)
                : departments.findAll(spec, pageRequest);
    }

    @Transactional(readOnly = true)
    public Department findByCode(String code) {
        return departments.findByCode(code)
                .orElseThrow(() -> new com.flowops.domain.exception.NotFoundException("Department not found: " + code));
    }
}
