package com.noteweave.security;

import org.springframework.stereotype.Component;

@Component
public class AuditActorProvider {

    private final CurrentUserProvider currentUserProvider;

    public AuditActorProvider(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    public String currentOrSystem(String component) {
        return currentUserProvider.currentUserId().orElseGet(() -> SystemActor.of(component));
    }
}
