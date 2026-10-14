package cn.xinji.agentplatform.security.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The final consultation workface is shared by the normal and consultation-only deployments. */
@Controller
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled",
        "product.consultation.workspace.enabled"},havingValue="true")
class ProductConsultationWorkspacePageController {
    @GetMapping("/app/consultation")
    String page() {return "forward:/app/consultation.html";}
}
