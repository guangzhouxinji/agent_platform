package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v2/consultation/source-contracts")
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"},
        havingValue = "true")
public final class ProductSourceAdmissionController {
    public record Enable(long expectedVersion, String decisionRef, String decisionSha) { }
    public record Withdraw(long expectedEpoch) { }
    private final ProductSourceAdmission admission;
    private final ProductMaintenanceProof maintenance;

    public ProductSourceAdmissionController(ProductSourceAdmission admission,
            ProductMaintenanceProof maintenance) {
        this.admission = admission;
        this.maintenance = maintenance;
    }

    @PostMapping("/{sourceId}/{contractVersion}/product-enable")
    public ProductSourceAdmission.Receipt enable(@PathVariable String sourceId,
            @PathVariable String contractVersion, @RequestBody Enable body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("Decision required");
        String actor = maintenance.require(request, ProductMaintenanceProof.Role.SOURCES);
        return admission.enable(new ProductSourceAdmission.Input(sourceId, contractVersion,
                body.expectedVersion(), body.decisionRef(), body.decisionSha(), requestId),
                actor, maintenance.requireOrganization(request, ProductMaintenanceProof.Role.SOURCES));
    }

    @PostMapping("/{sourceId}/withdraw-product-use")
    public ProductSourceAdmission.Receipt withdraw(@PathVariable String sourceId,
            @RequestBody Withdraw body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("Expected epoch required");
        return admission.withdraw(sourceId, body.expectedEpoch(), requestId,
                maintenance.require(request, ProductMaintenanceProof.Role.SOURCES));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() { }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void unavailable() { }
}
