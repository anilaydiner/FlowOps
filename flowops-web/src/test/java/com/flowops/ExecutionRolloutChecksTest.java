package com.flowops;

import com.flowops.application.execution.*;
import com.flowops.application.execution.port.*;
import com.flowops.domain.exception.*;
import com.flowops.domain.execution.ProviderContract;
import com.flowops.domain.identity.*;
import com.flowops.domain.identity.policy.*;
import com.flowops.domain.identity.port.UserRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionRolloutChecksTest {
    private final ExecutionRolloutQueries queries=mock(ExecutionRolloutQueries.class);
    private final ProviderContracts contracts=mock(ProviderContracts.class);
    private final ProviderCalls calls=mock(ProviderCalls.class);
    private final ExecutionWorkerStatus worker=mock(ExecutionWorkerStatus.class);
    private final UserRepository users=mock(UserRepository.class);
    private final CallerIdentity caller=new CallerIdentity(1L,"operator",Set.of("ADMIN"));
    private User operator(ScopeType scope){
        var role=new Role("OPS","Ops",null);role.setPermissions(Set.of(new Permission(PermissionCodes.EXECUTION_ROLLOUT_READ,"Rollout",null,scope,scope==null?null:"EXPENSE")));
        var u=User.newUser("operator","Operator",null,null,Set.of(role),"unused");u.setId(1L);return u;
    }
    private ExecutionRolloutChecks checker(boolean requireLive){
        when(worker.isCompletionReconciliationRunning()).thenAnswer(call->worker.isRunning());
        when(users.findById(1L)).thenReturn(Optional.of(operator(null)));when(calls.capacity()).thenReturn(new ProviderCalls.Capacity(8,0,4,0));
        return new ExecutionRolloutChecks(queries,contracts,calls,worker,new AuthorizationEvaluator(users),users,Clock.systemUTC(),requireLive);
    }
    private ProviderContract contract(boolean simulated){return new ProviderContract("provider","v1",ProviderContract.Transport.LOCAL,Duration.ZERO,Duration.ZERO,Duration.ofSeconds(20),Duration.ofSeconds(10),true,true,simulated);}
    private Map<String,Long> zeroCounts(){var values=new LinkedHashMap<String,Long>();ExecutionRolloutQueries.REQUIRED_CODES.forEach(code->values.put(code,0L));return values;}
    private void facts(Map<String,Long> counts){var values=zeroCounts();values.putAll(counts);when(queries.facts()).thenReturn(new ExecutionRolloutQueries.Facts(values,Set.of("ACTION"),Map.of()));when(contracts.require("ACTION")).thenReturn(contract(false));}
    @Test void cleanMachineChecksDoNotPretendToCertifyManualProviderAcceptance(){
        var c=checker(false);facts(Map.of("SCHEMA_NOT_CURRENT",0L,"UNCERTAIN_JOBS",2L));
        var report=c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller);
        assertTrue(report.machineChecksPassed());assertFalse(report.manualAcceptanceRequired().isEmpty());
        assertTrue(report.checks().stream().anyMatch(check->check.code().equals("UNCERTAIN_JOBS") && check.status()==ExecutionRolloutChecks.Status.ATTENTION));
        verify(calls,never()).execute(any(),any(),any());verify(calls,never()).reconcile(any(),any(),any());
    }
    @Test void phasesRequirePausedDrainBeforeEnableAndRunningWorkerAfterward(){
        var c=checker(false);facts(Map.of("STARTED_LEASES",1L));when(worker.isRunning()).thenReturn(true);
        assertFalse(c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller).machineChecksPassed());
        assertTrue(c.check(ExecutionRolloutChecks.Phase.POST_ENABLE,caller).machineChecksPassed());
        when(worker.isRunning()).thenReturn(false);assertFalse(c.check(ExecutionRolloutChecks.Phase.POST_ENABLE,caller).machineChecksPassed());
        facts(Map.of());when(calls.capacity()).thenReturn(new ProviderCalls.Capacity(8,1,4,0));
        assertFalse(c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller).machineChecksPassed());
    }
    @Test void liveProviderGateMissingContractsAndRecordedProviderMismatchBlockAcceptance(){
        var c=checker(true);facts(Map.of());when(contracts.require("ACTION")).thenReturn(contract(true));
        assertFalse(c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller).machineChecksPassed());
        when(contracts.require("ACTION")).thenThrow(new ConflictException("provider-contract-unavailable","unsupported"));
        assertFalse(c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller).machineChecksPassed());
        doReturn(contract(false)).when(contracts).require("ACTION");
        when(queries.facts()).thenReturn(new ExecutionRolloutQueries.Facts(zeroCounts(),Set.of("ACTION"),Map.of("ACTION",Set.of("old-provider"))));
        assertFalse(c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller).machineChecksPassed());
    }
    @Test void completionReconcilerMustBePausedOrRunningIndependentlyOfJobPolling(){
        var c=checker(false);facts(Map.of());
        when(worker.isRunning()).thenReturn(false);when(worker.isCompletionReconciliationRunning()).thenReturn(true);
        assertFalse(c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller).machineChecksPassed());
        when(worker.isRunning()).thenReturn(true);when(worker.isCompletionReconciliationRunning()).thenReturn(false);
        assertFalse(c.check(ExecutionRolloutChecks.Phase.POST_ENABLE,caller).machineChecksPassed());
        when(worker.isCompletionReconciliationRunning()).thenReturn(true);
        assertTrue(c.check(ExecutionRolloutChecks.Phase.POST_ENABLE,caller).machineChecksPassed());
    }
    @Test void missingDatabaseFactsCannotProduceGreenAcceptance(){
        var c=checker(false);when(queries.facts()).thenReturn(new ExecutionRolloutQueries.Facts(Map.of(),Set.of(),Map.of()));
        assertFalse(c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller).machineChecksPassed());
    }
    @Test void scopedOrRevokedGrantCannotReadGlobalReportDespiteTokenRoles(){
        var c=checker(false);when(users.findById(1L)).thenReturn(Optional.of(operator(ScopeType.REQUEST_TYPE)));
        assertThrows(ForbiddenException.class,()->c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller));
        var inactive=operator(null);inactive.setActive(false);when(users.findById(1L)).thenReturn(Optional.of(inactive));
        assertThrows(ForbiddenException.class,()->c.check(ExecutionRolloutChecks.Phase.PRE_ENABLE,caller));verifyNoInteractions(queries);
    }
}
