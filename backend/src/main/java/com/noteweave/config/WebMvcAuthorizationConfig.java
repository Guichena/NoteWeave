package com.noteweave.config;

import com.noteweave.security.WorkspaceAuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcAuthorizationConfig implements WebMvcConfigurer {

    private final WorkspaceAuthorizationInterceptor authorizationInterceptor;

    public WebMvcAuthorizationConfig(WorkspaceAuthorizationInterceptor authorizationInterceptor) {
        this.authorizationInterceptor = authorizationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authorizationInterceptor).addPathPatterns("/api/v2/**");
    }
}
