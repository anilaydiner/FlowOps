package com.flowops.application.request;

import com.flowops.application.model.RequestDetail;
import com.flowops.domain.Clarification;
import com.flowops.domain.Decision;
import com.flowops.domain.ExecutionJob;
import com.flowops.domain.Request;
import com.flowops.domain.StepInstance;
import java.util.ArrayList;import com.flowops.domain.port.ClarificationRepository;
import com.flowops.domain.port.DecisionRepository;
import com.flowops.domain.port.ExecutionJobRepository;
import com.flowops.domain.port.StepInstanceRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregate-based detail assembly for command responses; called within the write transaction. */
@Component
public class RequestDetailReader {
    private final StepInstanceRepository stepInstances;
    private final DecisionRepository decisions;
    private final ClarificationRepository clarifications;
    private final ExecutionJobRepository executionJobs;

    public RequestDetailReader(StepInstanceRepository stepInstances, DecisionRepository decisions,
                               ClarificationRepository clarifications, ExecutionJobRepository executionJobs) {
        this.stepInstances = stepInstances;
        this.decisions = decisions;
        this.clarifications = clarifications;
        this.executionJobs = executionJobs;
    }

    public RequestDetail load(Request r) {
        List<StepInstance> steps = stepInstances.findByRequestIdOrderById(r.getId());
        Map<Long, List<Decision>> byStep = new LinkedHashMap<>();
        steps.forEach(step -> byStep.put(step.getId(), new ArrayList<>()));
        if (!steps.isEmpty()) {
            for (Decision decision : decisions.findByRequestIdOrderById(r.getId())) {
                List<Decision> bucket = byStep.get(decision.getStepInstance().getId());
                if (bucket != null) bucket.add(decision);
            }
        }
        List<Clarification> clari = clarifications.findByRequestIdOrderByCreatedAtDesc(r.getId());
        List<ExecutionJob> jobs = executionJobs.findByRequestIdOrderByCreatedAtAsc(r.getId());
        return RequestDetail.full(r, steps, byStep, clari, jobs);
    }
}
