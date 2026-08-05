package com.noteweave.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WorkspaceAuthorizationInterceptorTest {

    @Test
    void editorPermissionShouldBeEnoughToCancelAnswerRun() {
        WorkspaceAccessGuard guard = mock(WorkspaceAccessGuard.class);
        WorkspaceAuthorizationInterceptor interceptor = new WorkspaceAuthorizationInterceptor(guard);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "DELETE", "/api/v2/workspaces/workspace-1/answer-runs/run-1");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(guard).requirePermission("workspace-1", WorkspacePermission.ANSWER_RUN);
    }
}
