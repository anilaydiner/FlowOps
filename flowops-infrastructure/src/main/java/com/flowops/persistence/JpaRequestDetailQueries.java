package com.flowops.persistence;

import com.flowops.application.request.model.RequestDetailView.ClarificationView;
import com.flowops.application.request.model.RequestDetailView.DecisionView;
import com.flowops.application.request.model.RequestDetailView.JobView;
import com.flowops.application.request.model.RequestDetailView.StepView;
import com.flowops.application.request.model.RequestDetailView;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.RequestDetailQueries;
import com.flowops.persistence.entity.ClarificationEntity;
import com.flowops.persistence.entity.DecisionEntity;
import com.flowops.persistence.entity.ExecutionJobEntity;
import com.flowops.persistence.entity.StepInstanceEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaRequestDetailQueries implements RequestDetailQueries {
    private final EntityManager entityManager;

    public JpaRequestDetailQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<RequestListItem> findRequest(Long id) {
        return RequestListProjection.findById(entityManager, id);
    }

    @Override
    public RequestDetailView load(RequestListItem request) {
        Long id = request.id();
        List<StepView> steps = project(StepView.class, StepInstanceEntity.class, id, "request.id", "id", false,
                "id", "position", "step.code", "step.name", "step.requiredRole", "state", "rule",
                "requiredApprovals", "skipReason", "blockedReason", "satisfiedBy", "satisfiedAt");
        Map<Long, List<DecisionView>> byStep = new LinkedHashMap<>();
        steps.forEach(step -> byStep.put(step.id(), new ArrayList<>()));
        if (!steps.isEmpty()) {
            List<DecisionView> decisions = project(DecisionView.class, DecisionEntity.class, id, "request.id", "id", false,
                    "id", "stepInstance.id", "actorId", "actorUsername", "action", "justification", "evidence",
                    "delegatedTo", "createdAt");
            for (DecisionView decision : decisions) {
                List<DecisionView> bucket = byStep.get(decision.stepInstanceId());
                if (bucket != null) bucket.add(decision);
            }
        }
        byStep.replaceAll((step, decisions) -> List.copyOf(decisions));
        List<ClarificationView> clarifications = project(ClarificationView.class, ClarificationEntity.class,
                id, "requestId", "createdAt", true, "id", "askedBy", "question", "answer", "answeredBy", "answeredAt", "createdAt");
        List<JobView> jobs = project(JobView.class, ExecutionJobEntity.class, id, "requestId", "createdAt", false,
                "id", "action", "status", "result", "lastError", "retryCount", "createdAt", "completedAt");
        return new RequestDetailView(request, steps, Map.copyOf(byStep), clarifications, jobs);
    }

    private <T> List<T> project(Class<T> view, Class<?> entity, Long requestId, String requestPath,
                                String orderField, boolean descending, String... columns) {
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(view);
        var root = query.from(entity);
        Selection<?>[] selections = Arrays.stream(columns).map(column -> path(root, column)).toArray(Selection<?>[]::new);
        query.select(cb.construct(view, selections)).where(cb.equal(path(root, requestPath), requestId));
        query.orderBy(descending ? cb.desc(root.get(orderField)) : cb.asc(root.get(orderField)));
        return entityManager.createQuery(query).getResultList();
    }

    private static Path<?> path(Path<?> root, String field) {
        Path<?> result = root;
        for (String segment : field.split("\\.")) result = result.get(segment);
        return result;
    }
}
