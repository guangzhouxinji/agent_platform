package cn.xinji.agentplatform.enforcement.documenttemplate;

import static cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateContract.*;

import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/enforcement/document-templates")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementDocumentTemplateController {
    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private final DocumentTemplateStore store;
    private final DocumentTemplateFiles files;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final ObjectMapper json;
    private final String issuer;
    private final Set<String> fonts;

    public EnforcementDocumentTemplateController(DocumentTemplateStore store, DocumentTemplateFiles files,
            JdbcTemplate db, ProductGrantWatermark watermark, ObjectMapper json,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${enforcement.document-template.available-fonts:SimSun}") String fonts) {
        this.store = store; this.files = files; this.db = db; this.watermark = watermark; this.json = json;
        this.issuer = issuer; this.fonts = Arrays.stream(fonts.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Receipt create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                          @RequestPart("metadata") String metadata, @RequestPart("file") MultipartFile file) {
        return service(jwt).create(actor(jwt), key, input(metadata), file.getOriginalFilename(), bytes(file));
    }

    @PostMapping(path = "/{id}/revisions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Receipt revise(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                          @PathVariable String id, @RequestPart("metadata") String metadata,
                          @RequestPart("file") MultipartFile file) {
        return service(jwt).revise(actor(jwt), key, id, input(metadata), file.getOriginalFilename(), bytes(file));
    }

    @GetMapping public List<Summary> list(@AuthenticationPrincipal Jwt jwt, @RequestParam String organization) {
        return service(jwt).list(actor(jwt), organization);
    }
    @GetMapping("/{id}") public Revision latest(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).read(actor(jwt), id, 0);
    }
    @GetMapping("/{id}/revisions/{revision}")
    public Revision revision(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable long revision) {
        return service(jwt).read(actor(jwt), id, revision);
    }
    @PostMapping("/{id}/revisions/{revision}/previews")
    public PreviewReceipt preview(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @PathVariable String id, @PathVariable long revision, @RequestBody PreviewInput input) {
        return service(jwt).preview(actor(jwt), key, id, revision, input);
    }
    @PostMapping("/{id}/revisions/{revision}/publish")
    public Receipt publish(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @PathVariable String id, @PathVariable long revision, @RequestBody TransitionInput input) {
        return service(jwt).publish(actor(jwt), key, id, revision, input);
    }
    @PostMapping("/{id}/revisions/{revision}/disable")
    public Receipt disable(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
            @PathVariable String id, @PathVariable long revision, @RequestBody TransitionInput input) {
        return service(jwt).disable(actor(jwt), key, id, revision, input);
    }
    @GetMapping("/{id}/revisions/{revision}/file")
    public ResponseEntity<ByteArrayResource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                       @PathVariable long revision) {
        Revision template = service(jwt).read(actor(jwt), id, revision);
        return file(template.fileName(), service(jwt).download(actor(jwt), id, revision));
    }
    @GetMapping("/{id}/previews/{previewId}/file")
    public ResponseEntity<ByteArrayResource> previewFile(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                          @PathVariable String previewId) {
        return file("preview-" + previewId + ".docx", service(jwt).downloadPreview(actor(jwt), id, previewId));
    }

    private DocumentTemplateService service(Jwt jwt) {
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark, jwt, issuer, Clock.systemUTC());
        return new DocumentTemplateService(store, files,
                (actor, organization, resource, version, action) -> access.require(actor, organization,
                        "DOCUMENT_TEMPLATE", resource, version, action, "DOCUMENT_TEMPLATE"), Clock.systemUTC(), fonts);
    }
    private DraftInput input(String value) {
        try { return json.readValue(value, DraftInput.class); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid template metadata", e); }
    }
    private static byte[] bytes(MultipartFile file) {
        try { return file.getBytes(); } catch (Exception e) { throw new IllegalArgumentException("DOCX upload unreadable", e); }
    }
    private static String actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) throw new SecurityException("Verified identity required");
        return jwt.getSubject();
    }
    private static ResponseEntity<ByteArrayResource> file(String name, byte[] bytes) {
        return ResponseEntity.ok().contentType(DOCX).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new ByteArrayResource(bytes));
    }
    @ExceptionHandler(SecurityException.class) void forbidden(SecurityException failure) { throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, failure.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class) void badRequest(IllegalArgumentException failure) { throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, failure.getMessage()); }
    @ExceptionHandler(IllegalStateException.class) void conflict(IllegalStateException failure) { throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, failure.getMessage()); }
}
