package com.flowops.application.execution;

import com.flowops.application.execution.ExecutionRecoveryTransactions.*;
import com.flowops.domain.execution.ProviderObservation;
import com.flowops.domain.execution.port.ActionHandler;
import com.flowops.domain.identity.CallerIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RecoverExecution {
    private final ExecutionRecoveryTransactions transactions;
    private final com.flowops.application.execution.port.ProviderCalls calls;
    public RecoverExecution(ExecutionRecoveryTransactions transactions,com.flowops.application.execution.port.ProviderCalls calls) {
        this.transactions=transactions;this.calls=calls;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Result reconcile(Long requestId,Long jobId,Input input,CallerIdentity caller) {
        var prepared=transactions.begin(requestId,jobId,input,caller);
        if(prepared.query()==null) return prepared.result();
        ProviderObservation observation;String failure=null;
        try { observation=java.util.Objects.requireNonNull(calls.reconcile(prepared.query(),prepared.contract(),prepared.deadline()),"Provider returned no observation"); }
        catch(RuntimeException e) { observation=ProviderObservation.unknown();failure=e instanceof com.flowops.domain.execution.ProviderCallFailure p?"ProviderCallFailure:"+p.reason().name():e.getClass().getSimpleName(); }
        // Storage failure leaves the committed pending command and held job; never calls execute.
        var result=transactions.finish(input.commandId(),observation,failure);
        if(result.entry().observation().disposition()==com.flowops.application.execution.model.ExecutionRecovery.Disposition.AUTHORITY_REVOKED)
            throw new com.flowops.domain.exception.ForbiddenException("Execution authority was revoked during provider reconciliation");
        return result;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Result retry(Long requestId,Long jobId,Input input,CallerIdentity caller) {
        return transactions.retry(requestId,jobId,input,caller);
    }
}
