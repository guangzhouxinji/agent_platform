package cn.xinji.agentplatform.consultation.patentlibrary.adapter.in.web;

import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryService;
import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryService.*;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v0/patent-library")
public class PatentLibraryController {
    private final PatentLibraryService service;
    public PatentLibraryController(PatentLibraryService service) { this.service = service; }

    @GetMapping
    public Page query(@RequestHeader("X-V0-Actor") String actor,
                      @RequestHeader(value="X-Request-Id", required=false) String requestId,
                      @RequestParam(required=false) String applicationNumber,
                      @RequestParam(required=false) String publicationNumber,
                      @RequestParam(required=false) String title,
                      @RequestParam(required=false) String applicant,
                      @RequestParam(required=false) String inventor,
                      @RequestParam(required=false) List<String> patentType,
                      @RequestParam(required=false) String classification,
                      @RequestParam(defaultValue="false") boolean includeDescendants,
                      @RequestParam(required=false) List<String> status,
                      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate applicationFrom,
                      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate applicationTo,
                      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate grantFrom,
                      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate grantTo,
                      @RequestParam(defaultValue="1") int page,
                      @RequestParam(defaultValue="20") int pageSize) {
        return service.query(actor, new Query(applicationNumber, publicationNumber, title, applicant, inventor,
                split(patentType), classification, includeDescendants, split(status), applicationFrom, applicationTo,
                grantFrom, grantTo, page, pageSize), id(requestId));
    }

    @GetMapping("/{sourceId}")
    public Detail detail(@RequestHeader("X-V0-Actor") String actor, @RequestHeader(value="X-Request-Id", required=false) String requestId, @PathVariable String sourceId) {
        return service.detail(actor, sourceId, id(requestId));
    }

    @GetMapping("/{sourceId}/documents/{versionId}")
    public Document document(@RequestHeader("X-V0-Actor") String actor, @RequestHeader(value="X-Request-Id", required=false) String requestId,
                             @PathVariable String sourceId, @PathVariable String versionId) {
        return service.document(actor, sourceId, versionId, id(requestId));
    }

    @GetMapping("/{sourceId}/events")
    public Events events(@RequestHeader("X-V0-Actor") String actor, @RequestHeader(value="X-Request-Id", required=false) String requestId, @PathVariable String sourceId) {
        return service.events(actor, sourceId, id(requestId));
    }

    @ExceptionHandler(LibraryException.class)
    ResponseEntity<ErrorResponse> libraryError(LibraryException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage(), exception.requestId()));
    }

    private static List<String> split(List<String> values) { return values == null ? List.of() : values.stream().flatMap(value -> Arrays.stream(value.split(","))).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList(); }
    private static String id(String value) { return value == null || value.isBlank() ? UUID.randomUUID().toString() : value; }
    record ErrorResponse(String code, String message, String requestId) {}
}
