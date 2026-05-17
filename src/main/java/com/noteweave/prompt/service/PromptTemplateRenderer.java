package com.noteweave.prompt.service;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplateRenderer {

    public String render(String template, Map<String, ?> variables) {
        String rendered = template == null ? "" : template;
        if (variables == null || variables.isEmpty()) {
            return rendered;
        }
        for (Map.Entry<String, ?> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            rendered = rendered.replace(placeholder, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return rendered;
    }
}
