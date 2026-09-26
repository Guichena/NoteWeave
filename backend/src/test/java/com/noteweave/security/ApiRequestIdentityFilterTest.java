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
}
