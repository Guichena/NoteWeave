package com.noteweave.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditActorProviderTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void backgroundWorkMustUseExplicitSystemActorEvenWhenLocalFallbackIsEnabled() {
        AuditActorProvider provider = new AuditActorProvider(new CurrentUserProvider(true));

        assertThat(provider.currentOrSystem("source_parse")).isEqualTo("SYSTEM:SOURCE_PARSE");
    }

    @Test
    void authenticatedRequestMustUseRealUserAsActor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(CurrentUserProvider.REQUEST_USER_ID, "user-42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AuditActorProvider provider = new AuditActorProvider(new CurrentUserProvider(false));

        assertThat(provider.currentOrSystem("workspace")).isEqualTo("user-42");
    }
}
