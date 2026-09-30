package cn.xinji.agentplatform.enforcement.jobpack;

import static cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.*;

import cn.xinji.agentplatform.enforcement.jobpack.JobPackService.Completion;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackService.Create;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackService.FactInput;
import cn.xinji.agentplatform.enforcement.jobpack.JobPackService.VerificationInput;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/enforcement/job-packs")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementJobPackController {
    private final JobPackStore store;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final MaterialFiles files;
    private final JobPackEvidenceStore evidenceStore;
    private final RealBusinessCollectionTaskStore collectionTasks;
    private final String issuer;

    public EnforcementJobPackController(JobPackStore store, JdbcTemplate db, ProductGrantWatermark watermark,
                                        MaterialFiles files, JobPackEvidenceStore evidenceStore,
                                        RealBusinessCollectionTaskStore collectionTasks,
                                        @Value("${product.identity.issuer:}") String issuer) {
        this.store = store; this.db = db; this.watermark = watermark; this.files = files;
        this.evidenceStore = evidenceStore; this.collectionTasks = collectionTasks; this.issuer = issuer;
    }

    @GetMapping("/design")
    public Design design(@AuthenticationPrincipal Jwt jwt) {
        actor(jwt);
        return JobPackService.design();
    }

    @PostMapping
    public Receipt create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                          @RequestBody Create input) {
        return service(jwt).create(actor(jwt), key, input);
    }

    public record ClaimResult(RealBusinessCollectionTask task, Snapshot jobPack) { }

    @GetMapping("/collection-tasks")
    public List<RealBusinessCollectionTask> collectionTasks(@AuthenticationPrincipal Jwt jwt) {
        String actor = actor(jwt);
        var access = access(jwt);
        return collectionTasks.list().stream()
                .filter(task -> canRead(access, actor, task))
                .toList();
    }

    private static boolean canRead(ProductEnforcementAccess access, String actor,
                                   RealBusinessCollectionTask task) {
        if (task.claimedBy() == null) return true;
        try {
            access.require(actor, task.organization(), "ENFORCEMENT_JOB_PACK", task.packageId(),
                    1, "READ", "JOB_PACK");
            return true;
        } catch (SecurityException unavailable) {
            return false;
        }
    }

    @PostMapping("/collection-tasks/{taskId}/claim")
    public ClaimResult claim(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                             @PathVariable String taskId,
                             @RequestBody RealBusinessCollectionTask.Claim input) {
        if (input == null || input.organization() == null || input.organization().isBlank())
            throw new IllegalArgumentException("Organization required");
        String actor = actor(jwt);
        var task = collectionTasks.find(taskId)
                .orElseThrow(() -> new SecurityException("Collection task unavailable"));
        var access = access(jwt);
        for (String action : List.of("CREATE", "COLLECT", "READ"))
            access.require(actor, input.organization(), "ENFORCEMENT_JOB_PACK", task.packageId(), 1, action, "JOB_PACK");
        byte[] original = files.read(task.publicInput().rawFileKey());
        if (!task.publicInput().rawSha256().equals(sha256(original)))
            throw new IllegalStateException("Public original digest mismatch");
        var claimed = collectionTasks.claim(taskId, actor, input.organization(), key,
                sha256((taskId + "|" + input.organization()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (store.latest(task.packageId()).isEmpty()) {
            var source = claimed.publicInput();
            var publicInput = new PublicInput(source.candidateId(), source.sourceUrl(), source.sourceName(),
                    source.publishedAt(), source.capturedAt(), source.rawSha256(), source.rawFileKey(),
                    source.originalName(), source.collectionBatchId(), source.collectionDecision(),
                    Map.copyOf(source.publicFacts()), actor);
            service(jwt).createPublic(actor, key, new JobPackService.CreatePublic(task.packageId(),
                    input.organization(), task.title(), "REAL_BUSINESS_COLLECTION", publicInput));
        }
        return new ClaimResult(collectionTasks.find(taskId).orElseThrow(), store.latest(task.packageId()).orElseThrow());
    }

    @GetMapping("/{id}")
    public Snapshot read(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).read(actor(jwt), id);
    }

    @PostMapping("/{id}/facts/{type}")
    public Receipt submit(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                          @PathVariable String id, @PathVariable FactType type, @RequestBody FactInput input) {
        return service(jwt).submit(actor(jwt), key, id, type, input);
    }

    @PostMapping(value = "/{id}/evidence", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Evidence upload(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                           @RequestParam String evidenceRef, @RequestParam MultipartFile file) throws java.io.IOException {
        String actor = actor(jwt);
        var snapshot = store.latest(id).orElseThrow(() -> new SecurityException("Package unavailable"));
        var access = access(jwt);
        access.require(actor, snapshot.organization(), "ENFORCEMENT_JOB_PACK", id, 1, "COLLECT", "JOB_PACK");
        byte[] bytes = file.getBytes();
        String digest = sha256(bytes);
        var existing = evidenceStore.find(id, evidenceRef);
        if (existing.isPresent()) {
            if (!existing.get().sha256().equals(digest)) throw new IllegalArgumentException("Evidence Ref conflict");
            return existing.get();
        }
        var staged = files.stage(bytes);
        var evidence = new Evidence(evidenceRef, staged.sha256(), staged.key(),
                file.getOriginalFilename() == null ? "evidence.bin" : file.getOriginalFilename());
        try { evidenceStore.register(id, evidence, actor); return evidence; }
        catch (RuntimeException failed) { files.discard(staged.key()); throw failed; }
    }

    @PostMapping("/{id}/facts/{type}/verification")
    public Receipt verify(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                          @PathVariable String id, @PathVariable FactType type,
                          @RequestBody VerificationInput input) {
        return service(jwt).verify(actor(jwt), key, id, type, input);
    }

    @PostMapping("/{id}/integration-result")
    public Receipt complete(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                            @PathVariable String id, @RequestBody Completion input) {
        return service(jwt).complete(actor(jwt), key, id, input);
    }

    private JobPackService service(Jwt jwt) {
        var access = access(jwt);
        return new JobPackService(store, (actor, organization, id, action) ->
                access.require(actor, organization, "ENFORCEMENT_JOB_PACK", id, 1, action, "JOB_PACK"), (packageId, evidence) -> {
                    if (!evidenceStore.contains(packageId, evidence)) throw new IllegalArgumentException("Registered evidence required");
                    byte[] bytes = files.read(evidence.fileKey());
                    if (!evidence.sha256().equals(sha256(bytes))) throw new IllegalArgumentException("Evidence digest mismatch");
                }, Clock.systemUTC());
    }

    private ProductEnforcementAccess access(Jwt jwt) {
        return new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark,
                jwt, issuer, Clock.systemUTC());
    }

    private static String actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return jwt.getSubject();
    }
    private static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN) void forbidden() { }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.CONFLICT) void conflict() { }
    @ExceptionHandler({IllegalStateException.class, org.springframework.dao.DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void unavailable() { }
}
