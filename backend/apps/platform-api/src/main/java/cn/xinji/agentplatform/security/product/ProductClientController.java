package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v2/identity/clients")
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public final class ProductClientController {
    public record Revoke(long expectedVersion) { }

    private final ProductMaintenanceProof maintenance;
    private final ProductClients clients;

    public ProductClientController(ProductMaintenanceProof maintenance, JdbcTemplate db,
            RegisteredClientRepository clients, PlatformTransactionManager manager) {
        this.maintenance = maintenance;
        this.clients = new ProductClients(db, clients, new TransactionTemplate(manager), Clock.systemUTC());
    }

    @PostMapping
    ProductClients.Receipt register(@RequestBody ProductClients.Input input,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        return clients.register(input, requestId,
                maintenance.require(request, ProductMaintenanceProof.Role.SUBJECTS));
    }

    @PostMapping("/{clientId}/revoke")
    ProductClients.Receipt revoke(@PathVariable String clientId, @RequestBody Revoke input,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        if (input == null) throw new IllegalArgumentException("Expected client version required");
        return clients.revoke(clientId, input.expectedVersion(), requestId,
                maintenance.require(request, ProductMaintenanceProof.Role.SUBJECTS));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() { }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void conflict() { }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
