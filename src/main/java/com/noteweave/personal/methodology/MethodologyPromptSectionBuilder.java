package com.noteweave.personal.methodology;

import com.noteweave.personal.methodology.model.MethodologyCard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MethodologyPromptSectionBuilder {

    private final MethodologyCardJsonMapper jsonMapper;

    public String build(MethodologyCard card) {
        if (card == null) {
            return "";
        }
        StringBuilder section = new StringBuilder();
        section.append("Selected methodology: ").append(card.getName()).append("\n");
        appendList(section, "Workflow", jsonMapper.readList(card.getWorkflowJson()));
        appendList(section, "Output structure", jsonMapper.readList(card.getOutputStructureJson()));
        appendList(section, "Quality checklist", jsonMapper.readList(card.getQualityChecklistJson()));
        section.append("\n");
        return section.toString();
    }

    private void appendList(StringBuilder section, String label, List<String> items) {
        section.append(label).append(":\n");
        for (int i = 0; i < items.size(); i++) {
            section.append(i + 1).append(". ").append(items.get(i)).append("\n");
        }
    }
}
