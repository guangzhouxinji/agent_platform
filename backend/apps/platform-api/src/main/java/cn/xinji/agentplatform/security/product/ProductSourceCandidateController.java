package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v2/consultation/source-contracts/candidates")
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public final class ProductSourceCandidateController {
    public record Withdraw(long expectedVersion) { }
    private final ProductMaintenanceProof maintenance;
    private final ProductSourceCandidates candidates;

    public ProductSourceCandidateController(ProductMaintenanceProof maintenance,
            ProductSourceCandidates candidates) {
        this.maintenance = maintenance;
        this.candidates = candidates;
    }

    @PostMapping
    public ProductSourceCandidates.Candidate register(@RequestBody ProductSourceCandidates.Declaration body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        return candidates.register(body, requestId,
                maintenance.require(request, ProductMaintenanceProof.Role.SOURCES));
    }

    @GetMapping("/{sourceId}/{contractVersion}")
    public ProductSourceCandidates.Candidate read(@PathVariable String sourceId,
            @PathVariable String contractVersion, HttpServletRequest request) {
        maintenance.require(request, ProductMaintenanceProof.Role.SOURCES);
        return candidates.read(sourceId, contractVersion);
    }

    @PostMapping("/{sourceId}/{contractVersion}/withdraw")
    public ProductSourceCandidates.Candidate withdraw(@PathVariable String sourceId,
            @PathVariable String contractVersion, @RequestBody Withdraw body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        return candidates.withdraw(sourceId, contractVersion, body.expectedVersion(), requestId,
                maintenance.require(request, ProductMaintenanceProof.Role.SOURCES));
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailableStorage() { }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unreadableStorage() { }
}
