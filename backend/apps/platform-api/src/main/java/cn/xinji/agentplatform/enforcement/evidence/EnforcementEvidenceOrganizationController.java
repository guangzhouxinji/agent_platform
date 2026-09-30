package cn.xinji.agentplatform.enforcement.evidence;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/enforcement/evidence-organizations")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementEvidenceOrganizationController {
    private final EvidenceRuleStore rules;
    private final EvidenceOrganizationStore organizations;
    private final MaterialRepository materials;
    private final MaterialFiles files;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final ObjectMapper json;
    private final String issuer;
    private final String caseInput;

    public EnforcementEvidenceOrganizationController(EvidenceRuleStore rules,
            MaterialRepository materials, MaterialFiles files, JdbcTemplate db,
            PlatformTransactionManager manager, ProductGrantWatermark watermark, ObjectMapper json,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${enforcement.v2.evidence.case-input-file:}") String caseInput) {
        this.rules = rules;
        this.organizations = new Dm8EvidenceOrganizationStore(db, new TransactionTemplate(manager),
                json, Clock.systemUTC());
        this.materials = materials;
        this.files = files;
        this.db = db;
        this.watermark = watermark;
        this.json = json;
        this.issuer = issuer;
        this.caseInput = caseInput;
    }

    @PostMapping
    public EvidenceOrganizationStore.Receipt organize(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody EvidenceOrganizationService.Input input) {
        return service(jwt).organize(actor(jwt), key, input);
    }

    @GetMapping("/{id}/versions/{revision}")
    public EvidenceOrganizationStore.Revision read(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String id, @PathVariable long revision) {
        return service(jwt).read(actor(jwt), id, revision);
    }

    public record LinkPage(List<EvidenceOrganizationStore.Link> items, int total, String nextCursor) { }
    public record OriginalPreview(String evidenceId, String version, String sha256,
                                  String locator, String text, String locatedValue) { }

    @GetMapping("/{id}/versions/{revision}/links")
    public LinkPage links(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @PathVariable long revision, @RequestParam String matterId,
            @RequestParam(defaultValue = "5") int pageSize,
            @RequestParam(required = false) String cursor) {
        if (matterId.isBlank() || pageSize < 1 || pageSize > 100)
            throw new IllegalArgumentException("Invalid page request");
        var saved = service(jwt).read(actor(jwt), id, revision);
        if (saved.matters().stream().noneMatch(m -> matterId.equals(m.id())))
            throw new IllegalArgumentException("Matter unavailable");
        var matching = new ArrayList<>(saved.links().stream()
                .filter(link -> matterId.equals(link.matterId())).toList());
        matching.sort(Comparator.comparing(EvidenceOrganizationStore.Link::evidenceId)
                .thenComparingLong(EvidenceOrganizationStore.Link::evidenceVersion)
                .thenComparing(EvidenceOrganizationStore.Link::locator)
                .thenComparing(EvidenceOrganizationStore.Link::originKind)
                .thenComparing(EvidenceOrganizationStore.Link::relation));
        int offset = decodeCursor(cursor, revision, pageSize);
        if (offset > matching.size()) throw new IllegalArgumentException("Cursor outside result");
        int end = Math.min(matching.size(), offset + pageSize);
        // Recheck current authority after assembling the response, as on a version read.
        service(jwt).read(actor(jwt), id, revision);
        return new LinkPage(List.copyOf(matching.subList(offset, end)), matching.size(),
                end < matching.size() ? encodeCursor(revision, pageSize, end) : null);
    }

    @GetMapping("/{id}/versions/{revision}/materials/{materialId}/preview")
    public OriginalPreview preview(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @PathVariable long revision, @PathVariable String materialId,
            @RequestParam String locator) {
        if (!locator.matches("line [1-9][0-9]*"))
            throw new IllegalArgumentException("Invalid locator");
        var saved = service(jwt).read(actor(jwt), id, revision);
        var ref = saved.evidenceSet().stream().filter(item -> item.id().equals(materialId))
                .findFirst().orElseThrow(() -> new SecurityException("Evidence outside locked set"));
        if (saved.links().stream().noneMatch(link -> materialId.equals(link.evidenceId()) &&
                locator.equals(link.locator()) && ref.sha256().equals(link.sha256())))
            throw new SecurityException("Locator outside locked relation");
        var source = new VerifiedEvidenceSource(Path.of(caseInput), json, materials, files,
                new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark, jwt,
                        issuer, Clock.systemUTC()), Clock.systemUTC());
        var original = source.read(saved.caseRef(), saved.caseVersion(), saved.evidenceSet())
                .materials().stream().filter(item -> item.ref().equals(ref)).findFirst().orElseThrow();
        var lines = original.text().split("\\R", -1);
        int line = Integer.parseInt(locator.substring(5));
        if (line > lines.length) throw new SecurityException("Locator unavailable in original");
        service(jwt).read(actor(jwt), id, revision);
        return new OriginalPreview(materialId, ref.version(), ref.sha256(), locator,
                original.text(), lines[line - 1]);
    }

    private static String encodeCursor(long revision, int pageSize, int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (revision + ":" + pageSize + ":" + offset).getBytes(StandardCharsets.US_ASCII));
    }

    private static int decodeCursor(String cursor, long revision, int pageSize) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.US_ASCII).split(":", -1);
            if (parts.length != 3 || Long.parseLong(parts[0]) != revision ||
                    Integer.parseInt(parts[1]) != pageSize || !parts[2].matches("[1-9][0-9]*"))
                throw new IllegalArgumentException("Invalid cursor");
            return Integer.parseInt(parts[2]);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid cursor", invalid);
        }
    }

    @PostMapping("/{id}/revisions")
    public EvidenceOrganizationStore.Receipt correct(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String id,
            @RequestBody EvidenceOrganizationService.Correction input) {
        return service(jwt).correct(actor(jwt), key, id, input);
    }

    @PostMapping("/{id}/review")
    public EvidenceOrganizationStore.Receipt review(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String id,
            @RequestBody EvidenceOrganizationService.Review input) {
        return service(jwt).review(actor(jwt), key, id, input);
    }

    @PostMapping("/{id}/exclusions")
    public EvidenceOrganizationStore.Receipt exclude(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String id,
            @RequestBody EvidenceOrganizationService.Exclude input) {
        return service(jwt).exclude(actor(jwt), key, id, input);
    }

    private EvidenceOrganizationService service(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (caseInput.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(),
                watermark, jwt, issuer, Clock.systemUTC());
        var source = new VerifiedEvidenceSource(Path.of(caseInput), json, materials, files,
                access, Clock.systemUTC());
        return new EvidenceOrganizationService(rules, organizations, source,
                (actor, org, type, id, version, action, purpose) ->
                        access.require(actor, org, type, id, version, action, purpose));
    }

    private static String actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
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
