package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
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
@RequestMapping("/v2/consultation/source-contracts/candidates")
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"},
        havingValue = "true")
public final class ProductSourceVerificationController {
    public record Verify(long expectedVersion, String manifestRef, String manifestSha) { }

    private final ProductSourceVerification verification;
    private final ProductMaintenanceProof maintenance;

    public ProductSourceVerificationController(ProductSourceVerification verification,
            ProductMaintenanceProof maintenance) {
        this.verification = verification;
        this.maintenance = maintenance;
    }

    @PostMapping("/{sourceId}/{contractVersion}/verify")
    public ProductSourceVerification.Receipt verify(@PathVariable String sourceId,
            @PathVariable String contractVersion, @RequestBody Verify body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("Source verification request required");
        return verification.verify(new ProductSourceVerification.Input(sourceId, contractVersion,
                body.expectedVersion(), body.manifestRef(), body.manifestSha(), requestId),
                maintenance.require(request, ProductMaintenanceProof.Role.SOURCES));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalidRequest() { }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailableEvidence() { }
}
