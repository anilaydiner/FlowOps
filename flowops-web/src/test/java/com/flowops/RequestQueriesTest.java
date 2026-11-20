package com.flowops;

import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Authority;
import com.flowops.application.mediator.SynchronousMediator;
import com.flowops.application.request.model.RequestListItem;
import com.flowops.application.request.port.ApprovalQueueQueries;
import com.flowops.application.request.port.RequestListQueries;
import com.flowops.application.request.query.GetApprovalQueue;
import com.flowops.application.request.query.GetApprovalQueueHandler;
import com.flowops.application.request.query.ListRequests;
import com.flowops.application.request.query.ListRequestsHandler;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.query.RequestFilter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestQueriesTest {

    private final RequestListQueries lists = mock(RequestListQueries.class);

    private final ApprovalQueueQueries queue = mock(ApprovalQueueQueries.class);

    private final AuthorizationEvaluator authz = mock(AuthorizationEvaluator.class);

    private final ApprovalEligibilityService eligibility = mock(ApprovalEligibilityService.class);

    private final Mediator mediator = new SynchronousMediator(
            List.of(new ListRequestsHandler(lists, authz), new GetApprovalQueueHandler(queue, authz, eligibility)));

    private final PageQuery page = new PageQuery(2, 5,
            List.of(new PageQuery.Order("createdAt", true), new PageQuery.Order("id", false)));

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void listVisibilityComesFromPermissionAndPreservesFiltersAndPage(boolean canSeeAll) {
        CallerIdentity caller = new CallerIdentity(7L, "reader", Set.of("SOME_ROLE"));
        RequestFilter filter = new RequestFilter("DRAFT", "EXPENSE", true);
        RequestListItem item = new RequestListItem(12L, "EXPENSE", "Expense", "FINANCE", RequestStatus.DRAFT, Map.of(),
                0L, 7L, "reader", "Reader", null, null, null, null);
        when(authz.hasPermission(caller, PermissionCodes.REQUEST_LIST)).thenReturn(canSeeAll);
        when(lists.findAll(filter, page, caller.id(), canSeeAll)).thenReturn(new PageResult<>(List.of(item), 2, 5, 17));

        PageResult<RequestListItem> result = mediator.send(new ListRequests(filter, page, caller));

        assertEquals(2, result.page());
        assertEquals(5, result.size());
        assertEquals(17, result.totalElements());
        assertEquals(List.of(item), result.content());
        var ordered = inOrder(authz, lists);
        ordered.verify(authz).hasPermission(caller, PermissionCodes.REQUEST_LIST);
        ordered.verify(lists).findAll(filter, page, caller.id(), canSeeAll);
        verifyNoInteractions(queue);
    }

    @Test
    void emptyListKeepsRequestedPage() {
        CallerIdentity caller = new CallerIdentity(7L, "reader");
        when(lists.findAll(null, page, 7L, false)).thenReturn(PageResult.empty(2, 5));
        assertEquals(PageResult.empty(2, 5), mediator.send(new ListRequests(null, page, caller)));
    }

    @Test
    void queueDenialPreventsAllRepositoryReadsEvenWithoutRoles() {
        CallerIdentity caller = new CallerIdentity(7L, "reader");
        ForbiddenException denied = new ForbiddenException("queue denied");
        doThrow(denied).when(authz).require(caller, PermissionCodes.REQUEST_QUEUE);
        assertSame(denied,
                assertThrows(ForbiddenException.class, () -> mediator.send(new GetApprovalQueue(page, caller))));
        verifyNoInteractions(queue);
    }

    @Test
    void queueUsesCallerEligibilityAndPreservesOrderingAndPagination() {
        CallerIdentity caller = new CallerIdentity(7L, "approver", Set.of("APPROVER", "FINANCE"));
        RequestListItem item = new RequestListItem(12L, "EXPENSE", "Expense", "FINANCE", RequestStatus.IN_REVIEW,
                Map.of(), 0L, 8L, "owner", "Owner", null, null, null, null);
        PageResult<RequestListItem> expected = new PageResult<>(List.of(item), 2, 5, 12);
        Authority authority = new Authority(caller.id(), true, Set.of("CURRENT_ROLE"), null, List.of());
        when(eligibility.authority(caller.id(), DecisionAction.APPROVE)).thenReturn(authority);
        when(queue.findPage(page, authority)).thenReturn(expected);
        assertSame(expected, mediator.send(new GetApprovalQueue(page, caller)));
        var ordered = inOrder(authz, queue);
        ordered.verify(authz).require(caller, PermissionCodes.REQUEST_QUEUE);
        ordered.verify(queue).findPage(page, authority);
    }

}
