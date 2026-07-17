package com.noteweave.research;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the atomic cutover guard only on the three legacy result writes. */
@Configuration
class ResearchAgentLegacyResultRouteGuardWebConfig implements WebMvcConfigurer {

    private final ResearchAgentLegacyResultRouteGuardInterceptor interceptor;

    ResearchAgentLegacyResultRouteGuardWebConfig(ResearchAgentLegacyResultRouteGuardInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(
                "/internal/research-agent/workspace-evidence-batches",
                "/internal/research-agent/candidate-batches",
                "/internal/research-agent-tasks/*/submit"
        );
    }
}
