package com.flowops.application.workflow;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.RequestValidationException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;

import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.query.WorkflowFilter;
import com.flowops.domain.CompletionRule;
import com.flowops.domain.StepSpec;
import com.flowops.domain.Workflow;
import com.flowops.domain.port.WorkflowRepository;
import com.flowops.domain.WorkflowStep;
import com.flowops.domain.port.WorkflowStepRepository;
import com.flowops.application.model.WorkflowView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final WorkflowStepRepository steps;
    private final AuthorizationEvaluator authz;

    public WorkflowService(WorkflowRepository workflows, WorkflowStepRepository steps, AuthorizationEvaluator authz) {
        this.workflows = workflows;
        this.steps = steps;
        this.authz = authz;
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
    public WorkflowView create(String code, String name, String description,
                               Boolean active, List<StepSpec> stepSpecs, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.WORKFLOWS_CREATE);
        code = code.strip().toUpperCase();
        if (workflows.existsByCode(code)) {
            throw new ConflictException("Workflow already exists: " + code);
        }
        Workflow workflow = new Workflow(code, name.strip(), blankToNull(description));
        if (active != null) {
            workflow.setActive(active);
        }
        Workflow saved = workflows.save(workflow);
        replaceSteps(saved, stepSpecs);
        return new WorkflowView(saved, steps.findByWorkflowIdOrderById(saved.getId()));
    }

    @Transactional
    public WorkflowView update(Long id, String name, String description,
                               Boolean active, List<StepSpec> stepSpecs, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.WORKFLOWS_UPDATE);
        Workflow workflow = workflows.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + id));
        workflow.updateProfile(name.strip(), blankToNull(description));
        if (active != null) {
            workflow.setActive(active);
        }
        replaceSteps(workflow, stepSpecs);
        Workflow saved = workflows.save(workflow);
        return new WorkflowView(saved, steps.findByWorkflowIdOrderById(saved.getId()));
    }

    // ── helpers ────────────────────────────────────────────────────

    private void replaceSteps(Workflow workflow, List<StepSpec> definitions) {
        steps.deleteAll(steps.findByWorkflowIdOrderById(workflow.getId()));
        steps.flush();
        List<StepSpec> sorted = definitions.stream()
                .sorted(Comparator.comparingInt(d -> d.position() == null ? 0 : d.position()))
                .toList();
        Set<Integer> taken = new HashSet<>();
        for (StepSpec d : definitions) {
            if (d.position() != null) {
                taken.add(d.position());
            }
        }
        int nextAuto = 1;
        for (StepSpec def : sorted) {
            Integer position = def.position();
            if (position == null) {
                while (taken.contains(nextAuto)) {
                    nextAuto++;
                }
                position = nextAuto++;
            }
            WorkflowStep step = new WorkflowStep(workflow, position,
                    def.code().strip().toUpperCase(), def.name().strip(), def.requiredRole().strip().toUpperCase());
            CompletionRule rule = def.completionRule() == null ? CompletionRule.ANY_OF : def.completionRule();
            int approvals = def.requiredApprovals() == null ? 1 : def.requiredApprovals();
            if (rule == CompletionRule.ANY_OF) {
                approvals = 1;
            } else if (approvals < 1) {
                throw new RequestValidationException(Map.of("requiredApprovals",
                        "requiredApprovals must be >= 1 for rule " + rule));
            }
            step.configure(rule, approvals, def.condition(), def.slaMinutes(), def.escalationRole(),
                    Boolean.TRUE.equals(def.terminal()));
            steps.save(step);
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
