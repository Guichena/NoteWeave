package com.noteweave.security;

import com.noteweave.common.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuthBootstrapInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordHasher passwordHasher;
    private final String username;
    private final String email;
    private final String displayName;
    private final String password;

    public AuthBootstrapInitializer(
            JdbcTemplate jdbcTemplate,
            PasswordHasher passwordHasher,
            @Value("${noteweave.security.bootstrap.username:}") String username,
            @Value("${noteweave.security.bootstrap.email:}") String email,
            @Value("${noteweave.security.bootstrap.display-name:NoteWeave Admin}") String displayName,
            @Value("${noteweave.security.bootstrap.password:}") String password
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordHasher = passwordHasher;
        this.username = username.trim();
        this.email = email.trim();
        this.displayName = displayName.trim();
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || email.isBlank() || password.isBlank()) {
            return;
        }
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from users where username = ? or email = ?", Integer.class, username, email);
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into users(id, username, email, display_name, password_hash, status)
                values (?, ?, ?, ?, ?, 'ACTIVE')
                """, Ids.newId(), username, email, displayName.isBlank() ? username : displayName,
                passwordHasher.hash(password));
    }
}
