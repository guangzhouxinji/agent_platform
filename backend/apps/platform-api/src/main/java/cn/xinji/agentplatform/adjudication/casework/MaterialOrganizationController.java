package cn.xinji.agentplatform.adjudication.casework;
import java.time.Clock;
import java.util.List;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/v2/adjudication/case-works/{caseId}/material-organization/runs")
@ConditionalOnProperty(name="adjudication.v2.materials.enabled",havingValue="true")
public final class MaterialOrganizationController {
    public record Generate(long expectedVersion,List<String> materialIds) { }
    private final MaterialOrganizationService service;private final JdbcTemplate db;private final ProductGrantWatermark grants;
    private final CaseSourceAuthority sources;private final String issuer;
    public MaterialOrganizationController(MaterialOrganizationService service,JdbcTemplate db,ProductGrantWatermark grants,CaseSourceAuthority sources,@org.springframework.beans.factory.annotation.Value("${product.identity.issuer}")String issuer){this.service=service;this.db=db;this.grants=grants;this.sources=sources;this.issuer=issuer;}
    private CaseWorkAccess access(Jwt jwt){return new CaseWorkAccess(db,grants,sources,jwt,issuer,Clock.systemUTC());}
    @PostMapping public MaterialOrganizationService.RunView generate(@PathVariable String caseId,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal Jwt jwt,@RequestBody com.fasterxml.jackson.databind.JsonNode body){CaseWorkCommands.validId(caseId);if(key==null || !key.matches("[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("MODEL_REQUEST_KEY_INVALID");if(body==null||!body.isObject()||!body.path("expectedVersion").isIntegralNumber()||!body.path("expectedVersion").canConvertToLong()||!body.path("materialIds").isArray())throw new IllegalArgumentException("INVALID_COMMAND_BODY");
        body.fieldNames().forEachRemaining(f->{if(!java.util.Set.of("expectedVersion","materialIds").contains(f))throw new IllegalArgumentException("INVALID_COMMAND_BODY");});
        var ids=new java.util.ArrayList<String>();body.path("materialIds").forEach(v->{if(!v.isTextual())throw new IllegalArgumentException("INVALID_COMMAND_BODY");ids.add(v.asText());});
        return service.generate(caseId,body.path("expectedVersion").asLong(),ids,key,access(jwt));}
    @GetMapping("/{runId}") public MaterialOrganizationService.RunView read(@PathVariable String caseId,@PathVariable String runId,@AuthenticationPrincipal Jwt jwt){CaseWorkCommands.validId(caseId);return service.read(caseId,runId,access(jwt));}
}
