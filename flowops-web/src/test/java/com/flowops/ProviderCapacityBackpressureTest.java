package com.flowops;

import com.flowops.application.execution.*;
import com.flowops.application.execution.port.*;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ProviderCapacityBackpressureTest {
    @Test void knownSaturationDoesNotCommitNewStartAuthorizations(){
        var jobs=mock(ExecutionJobRepository.class);var start=mock(StartExecutionJob.class);var finish=mock(FinishExecutionJob.class);
        var calls=mock(ProviderCalls.class);var contracts=mock(ProviderContracts.class);var observation=mock(ExecutionJobObservability.class);
        var heartbeats=mock(ExecutionHeartbeats.class);
        when(jobs.findCandidates(any(),anyInt())).thenReturn(List.of(new ExecutionJobRepository.Candidate(1L,1L)));
        when(calls.capacity()).thenReturn(new ProviderCalls.Capacity(8,8,4,0));
        new ProcessExecutionJobs(jobs,start,finish,calls,contracts,observation,Clock.systemUTC(),heartbeats).execute();
        verifyNoInteractions(start,finish,contracts,observation,heartbeats);verify(calls,never()).execute(any(),any(),any());
    }
}
