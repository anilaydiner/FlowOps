package com.flowops.application.model;

import com.flowops.domain.Clarification;
import com.flowops.domain.Decision;
import com.flowops.domain.ExecutionJob;
import com.flowops.domain.Request;
import com.flowops.domain.StepInstance;

import java.util.List;
import java.util.Map;

/**
 * Command response model for a request: the aggregate plus its detail
 * collections (steps, per-step decisions, clarifications, execution jobs).
 *
 * <p>Returned by lifecycle command handlers in place of a {@link Request} carrying
 * {@code @Transient} presentation fields, so the domain aggregate stays free of
 * read-model concerns. Mapped to the API's {@code RequestResponse} DTO by the
 * controller layer. Collections are never null — {@link #of(Request)} yields
 * empty collections for lightweight draft/cancel responses. GET reads use RequestDetailView.</p>
 *
 * @param request          the request aggregate
 * @param steps            ordered step instances (empty when not loaded)
 * @param decisionsByStep  stepInstanceId → its decisions (empty when not loaded)
 * @param clarifications   pending/answered clarifications (empty when not loaded)
 * @param jobs             execution jobs (empty when not loaded)
 */
public record RequestDetail(
        Request request,
        List<StepInstance> steps,
        Map<Long, List<Decision>> decisionsByStep,
        List<Clarification> clarifications,
        List<ExecutionJob> jobs
) {
    /** Lightweight view (draft operations / cancel): no detail collections. */
    public static RequestDetail of(Request request) {
        return new RequestDetail(request, List.of(), Map.of(), List.of(), List.of());
    }

    /** Full detail view (submit / decide / close). */
    public static RequestDetail full(Request request,
                                     List<StepInstance> steps,
                                     Map<Long, List<Decision>> decisionsByStep,
                                     List<Clarification> clarifications,
                                     List<ExecutionJob> jobs) {
        return new RequestDetail(request, steps, decisionsByStep, clarifications, jobs);
    }
}
