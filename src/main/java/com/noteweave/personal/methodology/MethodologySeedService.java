package com.noteweave.personal.methodology;

import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.methodology.model.MethodologyCardSource;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import com.noteweave.personal.methodology.repository.MethodologyCardRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MethodologySeedService {

    public static final long SYSTEM_PRESET_SPACE_ID = 0L;

    private final MethodologyCardRepository methodologyCardRepository;
    private final MethodologyCardJsonMapper jsonMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        seedPresetCards();
    }

    @Transactional
    public void seedPresetCards() {
        for (PresetDefinition preset : presetDefinitions()) {
            methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndNameAndCardSource(
                            SYSTEM_PRESET_SPACE_ID,
                            preset.name(),
                            MethodologyCardSource.PRESET
                    )
                    .orElseGet(() -> methodologyCardRepository.save(toEntity(preset)));
        }
    }

    public List<PresetDefinition> presetDefinitions() {
        return List.of(
                new PresetDefinition(
                        "Research Report Methodology",
                        "research synthesis",
                        "REPORT",
                        List.of(
                                "Clarify the research question and scope",
                                "Extract the strongest evidence and tensions",
                                "Group findings into coherent themes",
                                "Explain trade-offs, implications, and open questions",
                                "Close with grounded conclusions and next steps"
                        ),
                        List.of(
                                "Title",
                                "Executive Summary",
                                "Background",
                                "Core Concepts",
                                "Key Findings",
                                "Trade-offs and Discussion",
                                "Conclusion",
                                "References"
                        ),
                        List.of(
                                "Every major claim is grounded in the provided evidence",
                                "The report distinguishes facts, inference, and open questions",
                                "The structure stays scannable and avoids duplicated points"
                        )
                ),
                new PresetDefinition(
                        "Study Guide Methodology",
                        "concept learning",
                        "STUDY_GUIDE",
                        List.of(
                                "Define the learning objective and audience level",
                                "Sequence concepts from prerequisite to advanced",
                                "Highlight common misunderstandings and recovery hints",
                                "Turn concepts into a staged learning path",
                                "End with self-check prompts and recap"
                        ),
                        List.of(
                                "Learning Goal",
                                "Prerequisites",
                                "Concept Path",
                                "Step-by-Step Study Plan",
                                "Common Pitfalls",
                                "Self-Check Questions",
                                "References"
                        ),
                        List.of(
                                "The guide makes prerequisite dependencies explicit",
                                "Each stage contains concrete actions instead of vague advice",
                                "Pitfalls and self-check items reflect the source material"
                        )
                ),
                new PresetDefinition(
                        "Comparison Analysis Methodology",
                        "trade-off evaluation",
                        "COMPARISON",
                        List.of(
                                "Define the compared options and decision context",
                                "Choose a shared set of evaluation dimensions",
                                "Compare strengths, weaknesses, and assumptions side by side",
                                "Map the best fit for different scenarios",
                                "Summarize the final trade-off recommendation"
                        ),
                        List.of(
                                "Decision Context",
                                "Compared Options",
                                "Evaluation Dimensions",
                                "Comparison Matrix",
                                "Scenario Fit",
                                "Recommendation",
                                "References"
                        ),
                        List.of(
                                "The same dimensions are used for every option",
                                "Trade-offs are explicit instead of implied",
                                "The recommendation names the conditions that would change it"
                        )
                ),
                new PresetDefinition(
                        "Work Prep STAR Methodology",
                        "behavioral interview",
                        "WORK_PREP",
                        List.of(
                                "Identify the target scenario and evaluation signal",
                                "Select the strongest supporting experience or evidence",
                                "Structure the response with Situation, Task, Action, Result",
                                "Prepare follow-up angles, risks, and reflections",
                                "Polish the answer into concise talking points"
                        ),
                        List.of(
                                "Target Scenario",
                                "Core Answer",
                                "STAR Breakdown",
                                "Follow-up Preparation",
                                "Risks and Improvements",
                                "Key Talking Points"
                        ),
                        List.of(
                                "The answer names concrete actions and measurable results",
                                "Follow-up questions are anticipated with grounded details",
                                "The final talking points stay concise and easy to speak"
                        )
                ),
                new PresetDefinition(
                        "General Structured Writing Methodology",
                        "general research synthesis",
                        "GENERAL",
                        List.of(
                                "Restate the goal and the available evidence",
                                "Organize the material into a clear narrative arc",
                                "Separate evidence, interpretation, and recommendation",
                                "Keep the structure concise and readable",
                                "End with a grounded takeaway"
                        ),
                        List.of(
                                "Goal",
                                "Context",
                                "Main Points",
                                "Key Evidence",
                                "Takeaway"
                        ),
                        List.of(
                                "The output stays evidence-based and avoids unsupported claims",
                                "The structure is easy to scan",
                                "The conclusion matches the earlier evidence"
                        )
                )
        );
    }

    private MethodologyCard toEntity(PresetDefinition preset) {
        MethodologyCard card = new MethodologyCard();
        card.setSpaceId(SYSTEM_PRESET_SPACE_ID);
        card.setResearchProjectId(null);
        card.setName(preset.name());
        card.setScene(preset.scene());
        card.setProblemType(preset.problemType());
        card.setWorkflowJson(jsonMapper.writeList(preset.workflow()));
        card.setRequiredConceptsJson(jsonMapper.writeList(List.of()));
        card.setOutputStructureJson(jsonMapper.writeList(preset.outputStructure()));
        card.setQualityChecklistJson(jsonMapper.writeList(preset.qualityChecklist()));
        card.setCardSource(MethodologyCardSource.PRESET);
        card.setStatus(MethodologyCardStatus.ACTIVE);
        card.setVersion(1);
        return card;
    }

    public record PresetDefinition(
            String name,
            String scene,
            String problemType,
            List<String> workflow,
            List<String> outputStructure,
            List<String> qualityChecklist
    ) {
    }
}
