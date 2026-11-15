package com.flowops.application.service;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;

import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.query.RequestTypeFilter;
import com.flowops.domain.RequestTypeSpec;
import com.flowops.domain.Department;
import com.flowops.domain.port.DepartmentRepository;
import com.flowops.domain.RequestType;
import com.flowops.domain.port.RequestTypeRepository;
import com.flowops.domain.port.WorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class RequestTypeService {

    private final RequestTypeRepository requestTypes;
    private final DepartmentRepository departments;
    private final WorkflowRepository workflows;
    private final AuthorizationEvaluator authz;

    public RequestTypeService(RequestTypeRepository requestTypes,
                              DepartmentRepository departments,
                              WorkflowRepository workflows,
                              AuthorizationEvaluator authz) {
        this.requestTypes = requestTypes;
        this.departments = departments;
        this.workflows = workflows;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public PageResult<RequestType> list(RequestTypeFilter filter, PageQuery query, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.REQUEST_TYPES_LIST);
        PageResult<RequestType> page = requestTypes.findAll(filter, query);
        page.content().forEach(rt -> {
            if (rt.getOwningDepartment() != null) {
                rt.getOwningDepartment().getCode();
            }
        });
        return page;
    }

    @Transactional(readOnly = true)
    public RequestType get(Long id, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.REQUEST_TYPES_READ);
        RequestType rt = requestTypes.findById(id)
                .orElseThrow(() -> new NotFoundException("Request type not found: " + id));
        if (rt.getOwningDepartment() != null) {
            rt.getOwningDepartment().getCode();
        }
        return rt;
    }

    @Transactional
    public RequestType create(RequestTypeSpec request, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.REQUEST_TYPES_CREATE);
        String code = request.code().strip().toUpperCase();
        if (requestTypes.existsByCode(code)) {
            throw new ConflictException("Request type already exists: " + code);
        }
        RequestType rt = new RequestType(
                code,
                request.name().strip(),
                blankToNull(request.description()),
                resolveDepartment(request.departmentCode()));
        if (request.requiredFields() != null) {
            rt.setRequiredFields(stringMap(request.requiredFields()));
        }
        if (request.validationRules() != null) {
            rt.setValidationRules(request.validationRules());
        }
        rt.setDefaultWorkflow(resolveWorkflow(request.workflowCode()));
        RequestType saved = requestTypes.save(rt);
        touch(saved);
        return saved;
    }

    @Transactional
    public RequestType update(Long id, RequestTypeSpec request, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.REQUEST_TYPES_UPDATE);
        RequestType rt = requestTypes.findById(id)
                .orElseThrow(() -> new NotFoundException("Request type not found: " + id));
        if (request.name() != null && !request.name().isBlank()) {
            rt.updateProfile(request.name().strip(), blankToNull(request.description()),
                    request.departmentCode() != null ? resolveDepartment(request.departmentCode()) : null);
        }
        if (request.requiredFields() != null) {
            rt.setRequiredFields(stringMap(request.requiredFields()));
        }
        if (request.validationRules() != null) {
            rt.setValidationRules(request.validationRules());
        }
        if (request.workflowCode() != null && !request.workflowCode().isBlank()) {
            rt.setDefaultWorkflow(resolveWorkflow(request.workflowCode()));
        }
        RequestType saved = requestTypes.save(rt);
        touch(saved);
        return saved;
    }

    private void touch(RequestType rt) {
        if (rt.getOwningDepartment() != null) {
            rt.getOwningDepartment().getCode();
        }
        if (rt.getDefaultWorkflow() != null) {
            rt.getDefaultWorkflow().getCode();
        }
    }

    private com.flowops.domain.Workflow resolveWorkflow(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return workflows.findByCode(code.strip().toUpperCase())
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + code));
    }

    private Map<String, Object> stringMap(Map<String, String> in) {
        return in != null ? new java.util.LinkedHashMap<>(in) : new java.util.LinkedHashMap<>();
    }

    private Department resolveDepartment(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return departments.findByCode(code.strip().toUpperCase())
                .orElseThrow(() -> new NotFoundException("Department not found: " + code));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
