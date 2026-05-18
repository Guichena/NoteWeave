package com.noteweave.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {

    @GetMapping({
            "/",
            "/login",
            "/register",
            "/spaces",
            "/spaces/{spaceId}",
            "/spaces/{spaceId}/team/{section}",
            "/spaces/{spaceId}/team/{section}/{id}",
            "/spaces/{spaceId}/personal/{section}",
            "/spaces/{spaceId}/personal/{section}/{id}",
            "/spaces/{spaceId}/personal/{section}/{id}/{tab}",
            "/spaces/{spaceId}/workbench/{section}",
            "/spaces/{spaceId}/artifacts",
            "/spaces/{spaceId}/artifacts/{artifactId}",
            "/spaces/{spaceId}/wiki",
            "/spaces/{spaceId}/memory",
            "/admin",
            "/admin/{section}"
    })
    public String forwardToWorkbench() {
        return "forward:/index.html";
    }
}
