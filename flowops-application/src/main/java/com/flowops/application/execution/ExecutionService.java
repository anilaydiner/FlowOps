package com.flowops.application.execution;

import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.execution.port.ExecutionPlanRepository;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.port.RequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Initial approval and event replay share the same evidence-based materialization protocol. */
@Service
public class ExecutionService {
    private final ExecutionPlanRepository plans;
    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final RecoverFrozenPlanJobs recovery;
    public ExecutionService(ExecutionPlanRepository plans, RequestRepository requests,
                            RequestWriteLock requestWriteLock, RecoverFrozenPlanJobs recovery) {
        this.plans = plans;
        this.requests = requests;
        this.requestWriteLock = requestWriteLock;
        this.recovery = recovery;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueueJobs(Request observed){
        materialize(observed,RecoverFrozenPlanJobs.Mode.EXISTING_PLAN);
    }
    /** Only called by FinalizeApproval in the transaction that creates the manifest. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void materializeNewPlan(Request observed){
        materialize(observed,RecoverFrozenPlanJobs.Mode.NEW_PLAN);
    }
    private void materialize(Request observed,RecoverFrozenPlanJobs.Mode mode){
        var request=requestWriteLock.findByIdForUpdate(observed.getId()).orElseThrow();
        var plan=plans.findByRequestId(request.getId()).orElseThrow(()->new ConflictException("execution-plan-unknown","Approval has no frozen execution manifest"));
        var result=recovery.recoverLocked(request,plan,mode,"Approval finalization or replay");
        if(!result.issues().isEmpty())throw new ConflictException("execution-plan-mismatch","Frozen work requires reconciliation: "+result.issues().getFirst().code());
        if(result.changed()){request.recordActivity();requests.save(request);}
    }
}
