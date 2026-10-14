package cn.xinji.agentplatform.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ReactFrontendController {
    @GetMapping("/")
    String root() {
        return "redirect:/app/consultation";
    }

    @GetMapping({
            "/app", "/app/", "/app/consultation/flow", "/app/consultation/intents",
            "/app/consultation/agent-configuration", "/app/search/patents", "/app/search/trademarks",
            "/app/compare/patents", "/app/compare/trademarks", "/app/libraries/patents",
            "/app/libraries/trademarks", "/app/libraries/cases", "/app/enforcement",
            "/app/enforcement/rules", "/app/enforcement/discovery", "/app/enforcement/parsing", "/app/enforcement/evidence",
            "/app/enforcement/material", "/app/enforcement/handoff", "/app/enforcement/procedure",
            "/app/enforcement/conduct", "/app/enforcement/job-pack", "/app/adjudication"
    })
    String app() {
        return "forward:/app/index.html";
    }

}
