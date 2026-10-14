package cn.xinji.agentplatform.security.product;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/documents/{sourceId}/{documentId}/versions/{version}/content")
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"}, havingValue = "true")
final class ProductPatentDocumentContentController {
    private final ProductPatentDocumentContent content;
    ProductPatentDocumentContentController(ProductPatentSelection selections,
            ProductSourceAvailability availability, ProductCollectedPatentTitles originals,
            JdbcTemplate db, ProductGrantWatermark watermark, @Value("${product.identity.issuer}") String issuer) {
        Clock clock = Clock.systemUTC();
        content = new ProductPatentDocumentContent(selections, availability, originals,
                new ProductPatentDocumentAccess(db, watermark, clock, issuer), clock);
    }
    @GetMapping
    ProductPatentDocumentContent.Content read(@PathVariable String taskId, @PathVariable String sourceId,
            @PathVariable String documentId, @PathVariable String version, @RequestParam String resultSetId,
            @RequestParam String selectionId, @RequestParam String kind,
            @RequestParam(required = false) String locator, @AuthenticationPrincipal Jwt jwt) {
        return content.read(taskId, resultSetId, selectionId, sourceId, documentId, version, kind, locator, jwt);
    }
    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
