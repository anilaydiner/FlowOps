package com.flowops.application.workflow;

import com.flowops.application.workflow.model.WorkflowView;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.request.port.StepInstanceRepository;
import com.flowops.domain.workflow.StepSpec;
import com.flowops.domain.workflow.Workflow;
import com.flowops.domain.workflow.port.WorkflowRepository;
import com.flowops.domain.workflow.port.WorkflowStepRepository;
import com.flowops.domain.workflow.query.WorkflowFilter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {

    private final WorkflowRepository workflows;

    private final WorkflowStepRepository steps;

    private final AuthorizationEvaluator authz;

    private final StepInstanceRepository instances;

    private final com.flowops.application.request.recovery.RecoveryOperations recoveryOperations;

    public WorkflowService(WorkflowRepository workflows, WorkflowStepRepository steps, AuthorizationEvaluator authz,
            StepInstanceRepository instances,
            com.flowops.application.request.recovery.RecoveryOperations recoveryOperations) {
        this.recoveryOperations = recoveryOperations;
        this.workflows = workflows;
        this.steps = steps;
        this.authz = authz;
        this.instances = instances;
    }

    @Transactional(readOnly = true)
    public PageResult<WorkflowView> list(WorkflowFilter filter, PageQuery query, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.WORKFLOWS_LIST);
        PageResult<Workflow> page = workflows.findAll(filter, query);
        return page.map(w -> new WorkflowView(w, steps.findByWorkflowIdOrderById(w.getId())));
    }

    @Transactional(readOnly = true)
    public WorkflowView get(Long id, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.WORKFLOWS_READ);
        Workflow workflow = workflows.findById(id)
            .orElseThrow(() -> new NotFoundException("Workflow not found: " + id));
        return new WorkflowView(workflow, steps.findByWorkflowIdOrderById(id));
    }

    @Transactional
    public WorkflowView create(String code, String name, String description, Boolean active, List<StepSpec> stepSpecs,
            CallerIdentity caller) {
        authz.require(caller, PermissionCodes.WORKFLOWS_CREATE);
        code = code.strip().toUpperCase();
        if (workflows.existsByCode(code)) {
            throw new ConflictException("Workflow already exists: " + code);
        }
        Workflow workflow = new Workflow(code, name.strip(), blankToNull(description));
        if (active != null) {
            workflow.setActive(active);
        }
        if (workflow.isActive())
            recoveryOperations.validateConfiguration();
        Workflow saved = workflows.save(workflow);
        steps.replaceDefinitions(saved.getId(), saved.defineSteps(stepSpecs));
        return new WorkflowView(saved, steps.findByWorkflowIdOrderById(saved.getId()));
    }

    @Transactional
    public WorkflowView update(Long id, String name, String description, Boolean active, List<StepSpec> stepSpecs,
            CallerIdentity caller) {
        authz.require(caller, PermissionCodes.WORKFLOWS_UPDATE);
        Workflow workflow = workflows.findById(id)
            .orElseThrow(() -> new NotFoundException("Workflow not found: " + id));
        if (instances.existsByWorkflowId(id)) {
            throw new ConflictException("Workflow is already in use; create a new definition instead");
        }
        workflow.updateProfile(name.strip(), blankToNull(description));
        if (active != null) {
            workflow.setActive(active);
        }
        steps.replaceDefinitions(workflow.getId(), workflow.defineSteps(stepSpecs));
        if (workflow.isActive())
            recoveryOperations.validateConfiguration();
        Workflow saved = workflows.save(workflow);
        return new WorkflowView(saved, steps.findByWorkflowIdOrderById(saved.getId()));
    }

    // ── helpers ────────────────────────────────────────────────────

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

}
