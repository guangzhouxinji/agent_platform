package cn.xinji.agentplatform.enforcement.deployment.entry;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public final class ProductEnforcementWorkspacePageController {
    @GetMapping({"/", "/app", "/app/", "/app/enforcement", "/app/enforcement/",
            "/app/enforcement/job-pack", "/app/enforcement/document-templates",
            "/app/enforcement/document-rules", "/app/enforcement/legal-references"})
    public String workspace() { return "forward:/app/enforcement.html"; }
}
