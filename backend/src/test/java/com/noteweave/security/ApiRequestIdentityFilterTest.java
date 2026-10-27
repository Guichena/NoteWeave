package com.noteweave.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiRequestIdentityFilterTest {

    private final ApiRequestIdentityFilter filter = new ApiRequestIdentityFilter(null, false);

    @Test
    void registrationMustBeAPublicAuthenticationEndpoint() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v2/auth/register")))
                .isTrue();
    }

    @Test
    void applicationEndpointsStillRequireAnAuthenticatedIdentity() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v2/workspaces")))
                .isFalse();
    }

    // NR01：等价路径不能把受保护接口变成免认证接口
    @Test
    void semicolonVariantOfProtectedPathStillRequiresIdentity() {
        assertThat(filter.shouldNotFilter(
                new MockHttpServletRequest("DELETE", "/api;x=1/v2/knowledge-items/item-1")))
                .isFalse();
    }

    @Test
    void percentEncodedVariantOfProtectedPathStillRequiresIdentity() {
        assertThat(filter.shouldNotFilter(
                new MockHttpServletRequest("DELETE", "/api%3Bx%3D1/v2/knowledge-items/item-1")))
                .isFalse();
    }

    @Test
    void semicolonVariantOfPublicAuthEndpointStaysPublic() {
        assertThat(filter.shouldNotFilter(
                new MockHttpServletRequest("POST", "/api/v2/auth;trace=1/login")))
                .isTrue();
    }

    @Test
    void malformedEncodingIsRoutedIntoTheFilterForRejection() {
        assertThat(filter.shouldNotFilter(
                new MockHttpServletRequest("GET", "/api/v2/workspaces/%zz")))
                .isFalse();
    }
}
