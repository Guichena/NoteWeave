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

    @Test
    void matrixParametersAndEncodedPrefixesCannotHideApplicationEndpoints() {
        for (String path : new String[]{"/api;x=1/v2/workspaces", "/%61pi/v2/workspaces"}) {
            assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", path))).isFalse();
        }
    }

    @Test
    void contextPathAndMatrixParametersPreservePublicAuthRoutes() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/noteweave/api;x=1/v2/auth/login");
        request.setContextPath("/noteweave");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }
}
