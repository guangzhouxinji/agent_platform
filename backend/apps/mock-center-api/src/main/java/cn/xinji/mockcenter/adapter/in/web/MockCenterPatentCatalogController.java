package cn.xinji.mockcenter.adapter.in.web;

import cn.xinji.mockcenter.application.MockCenterPatentCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Explicit development endpoint. Its output must never be presented as center-system data. */
@RestController
@Profile("test-synthetic")
@ConditionalOnProperty(name = "mock-center.service.enabled", havingValue = "true")
@RequestMapping("/api/dev/mock-center/patents")
public final class MockCenterPatentCatalogController {
    private static final String DATA_MODE = "PUBLIC_PILOT";
    private static final String AUTHORITY = "NON_CENTER_PUBLIC_SOURCE";
    private static final String LIMITATION = "本地公开试验目录，非真实中心数据；不构成检索完备性、法律或专业结论。";
    private final MockCenterPatentCatalog catalog;
    public MockCenterPatentCatalogController(MockCenterPatentCatalog catalog) { this.catalog = catalog; }
    @GetMapping("/metadata") public Response metadata() { return new Response(DATA_MODE, AUTHORITY, LIMITATION, catalog.metadata()); }
    @GetMapping public Response search(@RequestParam(required = false) String text, @RequestParam(required = false) String publicationNumber, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) { if (page < 1 || pageSize < 1 || pageSize > 50) throw new InvalidRequestException(); return new Response(DATA_MODE, AUTHORITY, LIMITATION, catalog.search(new MockCenterPatentCatalog.Query(text, publicationNumber, page, pageSize))); }
    @GetMapping("/{recordId}") public Response detail(@PathVariable String recordId) { return new Response(DATA_MODE, AUTHORITY, LIMITATION, catalog.find(recordId).orElseThrow(NotFoundException::new)); }
    public record Response(String dataMode, String authorityStatus, String limitation, Object data) {}
    @ResponseStatus(HttpStatus.BAD_REQUEST) static final class InvalidRequestException extends RuntimeException {}
    @ResponseStatus(HttpStatus.NOT_FOUND) static final class NotFoundException extends RuntimeException {}
}
