package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v2/consultation/source-contracts")
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled",
        "product.source.semantic.enabled"}, havingValue = "true")
public final class ProductSemanticIndexController {
    private final ProductSemanticIndex index;
    private final ProductMaintenanceProof maintenance;

    public ProductSemanticIndexController(ProductSemanticIndex index, ProductMaintenanceProof maintenance) {
        this.index = index;
        this.maintenance = maintenance;
    }

    @PostMapping("/{sourceId}/semantic-index")
    public ProductSemanticIndex.Receipt build(@PathVariable String sourceId,
            @RequestParam(required = false) String field,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        return index.buildField(sourceId, field == null ? "TITLE" : field, requestId,
                maintenance.require(request, ProductMaintenanceProof.Role.SOURCES));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalidRequest() { }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
