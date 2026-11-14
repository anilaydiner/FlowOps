package com.flowops.service;

import com.flowops.api.dto.WorkflowFilter;
import com.flowops.api.dto.WorkflowRequest;
import com.flowops.api.dto.WorkflowResponse;
import com.flowops.domain.CompletionRule;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowRepository;
import com.flowops.domain.WorkflowSpecifications;
import com.flowops.domain.WorkflowStep;
import com.flowops.domain.WorkflowStepRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final WorkflowStepRepository steps;

    public WorkflowService(WorkflowRepository workflows, WorkflowStepRepository steps) {
        this.workflows = workflows;
        this.steps = steps;
    }

    @Transactional(readOnly = true)
    public Page<WorkflowResponse> list(WorkflowFilter filter, Pageable pageable) {
        Specification<Workflow> spec = WorkflowSpecifications.from(filter);
        Page<Workflow> page = (spec == null) ? workflows.findAll(pageable) : workflows.findAll(spec, pageable);
        return page.map(w -> WorkflowResponse.from(w, steps.findByWorkflowIdOrderById(w.getId())));
    }

    @Transactional(readOnly = true)
    public WorkflowResponse get(Long id) {
        Workflow workflow = workflows.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + id));
        return WorkflowResponse.from(workflow, steps.findByWorkflowIdOrderById(id));
    }

    @Transactional
    public WorkflowResponse create(WorkflowRequest request) {
        String code = request.code().strip().toUpperCase();
        if (workflows.existsByCode(code)) {
            throw new ConflictException("Workflow already exists: " + code);
        }
        Workflow workflow = new Workflow(code, request.name().strip(), blankToNull(request.description()));
        if (request.active() != null) {
            workflow.setActive(request.active());
        }
        Workflow saved = workflows.save(workflow);
        replaceSteps(saved, request.steps());
        return WorkflowResponse.from(saved, steps.findByWorkflowIdOrderById(saved.getId()));
    }

    @Transactional
    public WorkflowResponse update(Long id, WorkflowRequest request) {
        Workflow workflow = workflows.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + id));
        workflow.updateProfile(request.name().strip(), blankToNull(request.description()));
        if (request.active() != null) {
            workflow.setActive(request.active());
        }
        replaceSteps(workflow, request.steps());
        Workflow saved = workflows.save(workflow);
        return WorkflowResponse.from(saved, steps.findByWorkflowIdOrderById(saved.getId()));
    }

    // ── helpers ────────────────────────────────────────────────────

    private void replaceSteps(Workflow workflow, List<WorkflowRequest.StepDefinition> definitions) {
        steps.deleteAll(steps.findByWorkflowIdOrderById(workflow.getId()));
        steps.flush();
        List<WorkflowRequest.StepDefinition> sorted = definitions.stream()
                .sorted(Comparator.comparingInt(d -> d.position() == null ? 0 : d.position()))
                .toList();
        for (WorkflowRequest.StepDefinition def : sorted) {
            int position = def.position() == null ? 0 : def.position();
            WorkflowStep step = new WorkflowStep(workflow, position,
                    def.code().strip().toUpperCase(), def.name().strip(), def.requiredRole().strip().toUpperCase());
            CompletionRule rule = def.completionRule() == null ? CompletionRule.ANY_OF : def.completionRule();
            int approvals = def.requiredApprovals() == null ? 1 : def.requiredApprovals();
            if (rule == CompletionRule.ANY_OF) {
                approvals = 1; // ANY_OF(1)
            } else if (approvals < 1) {
                throw new IllegalArgumentException("requiredApprovals must be >= 1 for rule " + rule);
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
