package com.noteweave.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.noteweave.common.BusinessException;
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

    // NR01：等价路径经规范化后命中同一授权规则，不再从规则缝隙中溜过
    @Test
    void semicolonVariantStillReachesKnowledgeItemAuthorization() {
        WorkspaceAccessGuard guard = mock(WorkspaceAccessGuard.class);
        WorkspaceAuthorizationInterceptor interceptor = new WorkspaceAuthorizationInterceptor(guard);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "DELETE", "/api;x=1/v2/knowledge-items;v=2/item-1");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(guard).requireKnowledgeItemPermission("item-1", WorkspacePermission.KNOWLEDGE_WRITE);
    }

    @Test
    void percentEncodedVariantStillReachesWorkspaceAuthorization() {
        WorkspaceAccessGuard guard = mock(WorkspaceAccessGuard.class);
        WorkspaceAuthorizationInterceptor interceptor = new WorkspaceAuthorizationInterceptor(guard);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api%3Bx%3D1/v2/workspaces/workspace-1/sources");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(guard).requirePermission("workspace-1", WorkspacePermission.WORKSPACE_READ);
    }

    // NR01：受保护前缀下未登记规则的路由默认拒绝，不再静默放行
    @Test
    void unknownProtectedRouteIsDeniedByDefault() {
        WorkspaceAuthorizationInterceptor interceptor =
                new WorkspaceAuthorizationInterceptor(mock(WorkspaceAccessGuard.class));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/v2/mystery/route"),
                new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("默认拒绝");
    }

    @Test
    void explicitlyPublicRoutesStillPassWithoutResourceCheck() {
        WorkspaceAuthorizationInterceptor interceptor =
                new WorkspaceAuthorizationInterceptor(mock(WorkspaceAccessGuard.class));

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/v2/workspaces"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v2/skills"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(
                new MockHttpServletRequest("POST", "/api/v2/auth/login"),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v2/uploads/formats"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void malformedPathIsRejectedAsBadRequest() {
        WorkspaceAuthorizationInterceptor interceptor =
                new WorkspaceAuthorizationInterceptor(mock(WorkspaceAccessGuard.class));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/v2/workspaces/%zz"),
                new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法编码");
    }
}
