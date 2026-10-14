package cn.xinji.agentplatform.adjudication.casework;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Hosts only the adjudication SPA; all domain actions remain behind the existing secured API. */
@Controller
@ConditionalOnProperty(name="adjudication.v2.enabled",havingValue="true")
public final class CaseWorkspacePageController {
    @GetMapping("/app/adjudication")
    public String workspace(){return "forward:/app/adjudication.html";}
}
