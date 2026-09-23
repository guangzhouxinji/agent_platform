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
            "/app", "/app/", "/app/consultation", "/app/consultation/flow", "/app/consultation/intents",
            "/app/consultation/agent-configuration", "/app/search/patents", "/app/search/trademarks",
            "/app/compare/patents", "/app/compare/trademarks", "/app/libraries/patents",
            "/app/libraries/trademarks", "/app/libraries/cases", "/app/enforcement", "/app/enforcement/sources",
            "/app/enforcement/rules", "/app/enforcement/discovery", "/app/enforcement/parsing", "/app/enforcement/evidence", "/app/adjudication",
            "/app/development/synthetic-center"
    })
    String app() {
        return "forward:/app/index.html";
    }
}
