package com.flowops.application.request.port;

import java.util.List;

/** Durable identity-change hints, separate from the parent-locked workflow state. */
public interface EligibilityReconciliationQueue {
    record Ticket(Long requestId, long generation) {}
    void userChanged(Long userId);
    void roleChanged(Long roleId);
    List<Ticket> findReady(int limit);
    void acknowledge(Ticket ticket);
    void defer(Ticket ticket);
}
