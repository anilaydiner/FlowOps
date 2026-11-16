package com.flowops;

import com.flowops.application.model.RequestDetailView;
import com.flowops.application.model.RequestListItem;
import com.flowops.application.request.port.RequestDetailQueries;
import com.flowops.application.request.query.GetRequest;
import com.flowops.application.request.query.GetRequestHandler;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;
import com.flowops.domain.policy.TargetContext;
import com.flowops.domain.RequestStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetRequestHandlerTest {
    private final AuthorizationEvaluator authz = mock(AuthorizationEvaluator.class);
    private final RequestDetailQueries details = mock(RequestDetailQueries.class);
    private final GetRequestHandler handler = new GetRequestHandler(details, authz);

    private RequestListItem storedRequest() {
        RequestListItem request = new RequestListItem(12L, "EXPENSE", "Expense", "FINANCE", RequestStatus.DRAFT,
                Map.of("amount", 10), 0L, 1L, "owner", "Owner", null, null, null, null);
        when(details.findRequest(12L)).thenReturn(Optional.of(request));
        return request;
    }

    private TargetContext target(RequestListItem request) {
        return TargetContext.forRequest(request.requesterId(), request.typeCode(), request.departmentCode(), request.payload());
    }

    @Test
    void ownerUsesOwnReadPermissionAndReceivesFullDetail() {
        RequestListItem request = storedRequest();
        CallerIdentity caller = new CallerIdentity(1L, "owner");
        RequestDetailView expected = new RequestDetailView(request, List.of(), Map.of(), List.of(), List.of());
        when(details.load(request)).thenReturn(expected);
        assertSame(expected, handler.handle(new GetRequest(12L, caller)));
        var ordered = inOrder(authz, details);
        ordered.verify(authz).require(caller, PermissionCodes.REQUEST_READ_OWN, target(request), true);
        ordered.verify(details).load(request);
    }

    @Test
    void otherCallerRequiresScopedReadPermission() {
        RequestListItem request = storedRequest();
        CallerIdentity caller = new CallerIdentity(2L, "reader");
        handler.handle(new GetRequest(12L, caller));
        verify(authz).require(caller, PermissionCodes.REQUEST_READ, target(request), false);
    }

    @Test
    void denialStopsDetailLoadingAndPreservesException() {
        RequestListItem request = storedRequest();
        CallerIdentity caller = new CallerIdentity(2L, "reader");
        ForbiddenException denied = new ForbiddenException("denied");
        doThrow(denied).when(authz).require(caller, PermissionCodes.REQUEST_READ,
                target(request), false);
        assertSame(denied, assertThrows(ForbiddenException.class,
                () -> handler.handle(new GetRequest(12L, caller))));
        verify(details, never()).load(any());
    }

    @Test
    void missingRequestPreservesNotFoundBehavior() {
        when(details.findRequest(99L)).thenReturn(Optional.empty());
        NotFoundException error = assertThrows(NotFoundException.class,
                () -> handler.handle(new GetRequest(99L, new CallerIdentity(1L, "owner"))));
        assertEquals("Request not found: 99", error.getMessage());
        verifyNoInteractions(authz);
        verify(details, never()).load(any());
    }
}
