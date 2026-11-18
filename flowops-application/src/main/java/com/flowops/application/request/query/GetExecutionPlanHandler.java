package com.flowops.application.request.query;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.domain.execution.port.ExecutionPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetExecutionPlanHandler implements MessageHandler<GetExecutionPlan,GetExecutionPlan.View> {
    private final GetRequestHandler access;
    private final ExecutionPlanRepository plans;
    public GetExecutionPlanHandler(GetRequestHandler access,ExecutionPlanRepository plans){this.access=access;this.plans=plans;}
    @Override public Class<GetExecutionPlan> messageType(){return GetExecutionPlan.class;}
    @Override @Transactional(readOnly=true)
    public GetExecutionPlan.View handle(GetExecutionPlan query){
        access.handle(new GetRequest(query.id(),query.caller()));
        var plan=plans.findByRequestId(query.id()).orElse(null);return new GetExecutionPlan.View(plan!=null,plan);
    }
}
