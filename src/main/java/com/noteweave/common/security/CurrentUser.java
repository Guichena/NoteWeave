package com.noteweave.common.security;

import com.noteweave.user.model.UserSystemRole;

public record CurrentUser(Long userId, String username, UserSystemRole systemRole) {
}
