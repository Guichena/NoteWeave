package com.noteweave.artifact;

import com.noteweave.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/skills")
public class ArtifactSkillController {

    private final ArtifactSkillCatalogService artifactSkillCatalogService;

    public ArtifactSkillController(ArtifactSkillCatalogService artifactSkillCatalogService) {
        this.artifactSkillCatalogService = artifactSkillCatalogService;
    }

    @GetMapping
    ApiResponse<List<ArtifactSkillSummaryResponse>> listSkills() {
        return ApiResponse.success(artifactSkillCatalogService.listSkills());
    }
}
