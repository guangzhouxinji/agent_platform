package cn.xinji.agentplatform.enforcement.procedure;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;

import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/enforcement")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementProcedureController {
    private final ProcedureStore store;
    private final MaterialRepository materials;
    private final MaterialFiles files;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final ObjectMapper json;
    private final String issuer;
    private final String sourceFile;
    private final String approvedBasis;
    public EnforcementProcedureController(ProcedureStore store, MaterialRepository materials, MaterialFiles files,
            JdbcTemplate db, ProductGrantWatermark watermark,
            ObjectMapper json, @Value("${product.identity.issuer:}") String issuer,
            @Value("${enforcement.v2.procedure.source-file:}") String sourceFile,
            @Value("${enforcement.v2.procedure.approved-basis:}") String approvedBasis) {
        this.store = store;
        this.materials = materials;
        this.files = files;
        this.db = db;
        this.watermark = watermark;
        this.json = json;
        this.issuer = issuer;
        this.sourceFile = sourceFile;
        this.approvedBasis = approvedBasis;
    }
    @PostMapping("/procedure-rules")
    public Receipt draft(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody Rule input) { return service(jwt).draft(actor(jwt), key, input); }
    @GetMapping("/procedure-rules/{id}/versions/{version}")
    public Rule rule(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable long version) {
        return service(jwt).readRule(actor(jwt), id, version);
    }
    @PostMapping("/procedure-rules/{id}/versions/{version}/trials")
    public Trial trial(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable long version,
                       @RequestBody ProcedureService.TrialRequest request) {
        return service(jwt).trial(actor(jwt), id, version, request);
    }
    @PostMapping("/procedure-rules/{id}/versions/{version}/publish")
    public Receipt publish(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                           @PathVariable String id, @PathVariable long version,
                           @RequestBody ProcedureService.Publication input) {
        return service(jwt).publish(actor(jwt), key, id, version, input);
    }
    @PostMapping("/procedure-rules/{id}/versions/{version}/disable")
    public Receipt disable(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                           @PathVariable String id, @PathVariable long version) {
        return service(jwt).disable(actor(jwt), key, id, version);
    }
    public record CheckCommand(String id, String caseRef, String caseVersion, String ruleId,
                               long ruleVersion, String calendarRef, String calendarVersion) { }
    @PostMapping("/procedure-checks")
    public Receipt check(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody CheckCommand input) {
        return service(jwt).check(actor(jwt), key, input.id(), new ProcedureService.CheckRequest(
                input.caseRef(), input.caseVersion(), input.ruleId(), input.ruleVersion(),
                input.calendarRef(), input.calendarVersion()));
    }
    @GetMapping("/procedure-checks/{id}/versions/{version}")
    public Report report(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable long version) {
        return service(jwt).readReport(actor(jwt), id, version);
    }
    @PostMapping("/procedure-checks/{id}/rectifications")
    public Receipt rectify(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                           @PathVariable String id, @RequestBody ProcedureService.Rectification input) {
        return service(jwt).rectify(actor(jwt), key, id, input);
    }
    public record RecheckCommand(long expectedVersion, String caseRef, String caseVersion,
                                 String ruleId, long ruleVersion, String calendarRef, String calendarVersion) { }
    @PostMapping("/procedure-checks/{id}/rechecks")
    public Receipt recheck(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                           @PathVariable String id, @RequestBody RecheckCommand input) {
        return service(jwt).recheck(actor(jwt), key, id, input.expectedVersion(), new ProcedureService.CheckRequest(
                input.caseRef(), input.caseVersion(), input.ruleId(), input.ruleVersion(),
                input.calendarRef(), input.calendarVersion()));
    }
    public ProcedureService service(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark,
                jwt, issuer, Clock.systemUTC());
        ProcedureSource source = sourceFile.isBlank() ? new ProcedureSource() {
            @Override public CaseSnapshot caseSnapshot(String ref, String version) {
                throw new IllegalStateException("Procedure case source unavailable");
            }
            @Override public Calendar calendar(String ref, String version) {
                throw new IllegalStateException("Procedure calendar source unavailable");
            }
        } : new FileProcedureSource(Path.of(sourceFile), json,
                new VerifiedProcedureMaterial(materials, files, access, actor(jwt), Clock.systemUTC()));
        return new ProcedureService(store, source, access::require, (basis, rule) -> {
            if (!"VERIFIED".equals(basis.effectStatus()) || approvedBasis.isBlank() ||
                    !approvedBasis.equals(String.join("|", basis.ref(), basis.version(), basis.locator(),
                            basis.scope(), rule.region(), rule.procedure(), rule.stage())))
                throw new SecurityException("Applicable basis not verified");
        });
    }
    private String actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return jwt.getSubject();
    }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    void forbidden() { }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.CONFLICT)
    void conflict() { }
    @ExceptionHandler({IllegalStateException.class, org.springframework.dao.DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
