package com.noteweave.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 后台线程代表请求用户执行时的身份传递：只在 runAs 范围内生效，结束后恢复。 */
class CurrentUserProviderDelegationTest {

    private final CurrentUserProvider provider = new CurrentUserProvider(false);

    @Test
    void delegatedUserIsVisibleOnlyInsideRunAs() {
        AtomicReference<String> inside = new AtomicReference<>();

        CurrentUserProvider.runAs("user-1", () -> inside.set(provider.requireUserId()));

        assertThat(inside.get()).isEqualTo("user-1");
        assertThat(provider.currentUserId()).isEmpty();
    }

    @Test
    void nestedRunAsRestoresTheOuterUser() {
        AtomicReference<String> afterNested = new AtomicReference<>();

        CurrentUserProvider.runAs("outer", () -> {
            CurrentUserProvider.runAs("inner", () -> { });
            afterNested.set(provider.currentUserId().orElse(null));
        });

        assertThat(afterNested.get()).isEqualTo("outer");
        assertThat(provider.currentUserId()).isEmpty();
    }

    @Test
    void blankUserRunsWithoutDelegatedIdentity() {
        AtomicReference<Boolean> present = new AtomicReference<>();

        CurrentUserProvider.runAs(" ", () -> present.set(provider.currentUserId().isPresent()));

        assertThat(present.get()).isFalse();
    }
}
