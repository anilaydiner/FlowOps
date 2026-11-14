package com.flowops.domain;

import java.util.List;
import java.util.Map;

/**
 * Application read model for a request: the aggregate plus its detail
 * collections (steps, per-step decisions, clarifications, execution jobs).
 *
 * <p>Returned by the application service in place of a {@link Request} carrying
 * {@code @Transient} presentation fields, so the domain aggregate stays free of
 * read-model concerns. Mapped to the API's {@code RequestResponse} DTO by the
 * controller layer. Collections are never null — {@link #of(Request)} yields
 * empty collections for lightweight (list/queue) views.</p>
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
    /** Lightweight view (list / queue / draft operations): no detail collections. */
    public static RequestDetail of(Request request) {
        return new RequestDetail(request, List.of(), Map.of(), List.of(), List.of());
    }

    /** Full detail view (get / submit / decide / close). */
    public static RequestDetail full(Request request,
                                     List<StepInstance> steps,
                                     Map<Long, List<Decision>> decisionsByStep,
                                     List<Clarification> clarifications,
                                     List<ExecutionJob> jobs) {
        return new RequestDetail(request, steps, decisionsByStep, clarifications, jobs);
    }
}
