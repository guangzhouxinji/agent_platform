package cn.xinji.agentplatform.consultation.trademarklibrary.adapter.in.web;

import cn.xinji.agentplatform.consultation.trademarklibrary.application.TrademarkLibraryService;
import cn.xinji.agentplatform.consultation.trademarklibrary.application.TrademarkLibraryService.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v0/trademark-library")
public class TrademarkLibraryController {
    private final TrademarkLibraryService service;
    public TrademarkLibraryController(TrademarkLibraryService service) { this.service = service; }

    @GetMapping
    public Page query(@RequestHeader("X-V0-Actor") String actor,
                      @RequestHeader(value="X-Request-Id", required=false) String requestId,
                      @RequestParam(required=false) String registrationNumber,
                      @RequestParam(required=false) String name,
                      @RequestParam(required=false) String applicant,
                      @RequestParam(required=false) List<String> category,
                      @RequestParam(required=false) List<String> status,
                      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate registeredFrom,
                      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate registeredTo,
                      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate validFrom,
                      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate validTo,
                      @RequestParam(defaultValue="1") int page,
                      @RequestParam(defaultValue="20") int pageSize) {
        return service.query(actor, new Query(registrationNumber, name, applicant, split(category), split(status), registeredFrom, registeredTo, validFrom, validTo, page, pageSize), id(requestId));
    }

    @GetMapping("/{sourceId}")
    public Detail detail(@RequestHeader("X-V0-Actor") String actor, @RequestHeader(value="X-Request-Id", required=false) String requestId, @PathVariable String sourceId) {
        return service.detail(actor, sourceId, id(requestId));
    }

    @GetMapping("/{sourceId}/history/{type}")
    public History history(@RequestHeader("X-V0-Actor") String actor, @RequestHeader(value="X-Request-Id", required=false) String requestId, @PathVariable String sourceId, @PathVariable String type) {
        return service.history(actor, sourceId, type, id(requestId));
    }

    @ExceptionHandler(LibraryException.class)
    ResponseEntity<ErrorResponse> libraryError(LibraryException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage(), exception.requestId()));
    }

    private static List<String> split(List<String> values) { return values == null ? List.of() : values.stream().flatMap(value -> Arrays.stream(value.split(","))).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList(); }
    private static String id(String value) { return value == null || value.isBlank() ? UUID.randomUUID().toString() : value; }
    record ErrorResponse(String code, String message, String requestId) {}
}
