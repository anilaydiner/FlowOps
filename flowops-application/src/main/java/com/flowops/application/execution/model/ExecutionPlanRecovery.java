package com.flowops.application.execution.model;

import java.time.Instant;
import java.util.*;

public final class ExecutionPlanRecovery {
    private ExecutionPlanRecovery() {}
    public record Materialization(String action,Long jobId,Instant createdAt,Integer maxRetries,boolean unstartedAtCapture,String provenance) {}
    public record Issue(int position,String action,String code,Long jobId) {}
    public record Result(int created,int restored,int rebound,List<Issue> issues) {
        public Result {issues=List.copyOf(issues);}
        public boolean changed(){return created+restored+rebound>0;}
    }
}
