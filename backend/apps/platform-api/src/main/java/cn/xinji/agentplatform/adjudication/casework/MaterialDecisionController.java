package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions.*;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.StreamReadFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Set;

@RestController
@RequestMapping("/v2/adjudication/case-works/{caseId}/material-organization")
@ConditionalOnProperty(name="adjudication.v2.materials.decisions.enabled",havingValue="true")
public final class MaterialDecisionController {
    private final MaterialDecisionService service;private final JdbcTemplate db;private final ProductGrantWatermark grants;
    private final CaseSourceAuthority sources;private final String issuer;
    private final ObjectMapper parser=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public MaterialDecisionController(MaterialDecisionService service,JdbcTemplate db,ProductGrantWatermark grants,CaseSourceAuthority sources,@org.springframework.beans.factory.annotation.Value("${product.identity.issuer}")String issuer){this.service=service;this.db=db;this.grants=grants;this.sources=sources;this.issuer=issuer;}
    private CaseWorkAccess access(Jwt jwt){return new CaseWorkAccess(db,grants,sources,jwt,issuer,Clock.systemUTC());}
    @PostMapping("/runs/{runId}/candidates/{candidateId}/decisions")
    public MaterialDecisionService.View decide(@PathVariable String caseId,@PathVariable String runId,@PathVariable String candidateId,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal Jwt jwt,@RequestBody String body){return service.decide(caseId,runId,candidateId,request(body),key,access(jwt));}
    @PostMapping("/versions/{versionId}/revisions")
    public MaterialDecisionService.View revise(@PathVariable String caseId,@PathVariable String versionId,@RequestHeader("Idempotency-Key")String key,@AuthenticationPrincipal Jwt jwt,@RequestBody String body){return service.revise(caseId,versionId,request(body),key,access(jwt));}
    @GetMapping("/versions/{versionId}")
    public MaterialDecisionService.View detail(@PathVariable String caseId,@PathVariable String versionId,@AuthenticationPrincipal Jwt jwt){CaseWorkCommands.validId(caseId);return service.detail(caseId,versionId,access(jwt));}
    @GetMapping("/versions")
    public MaterialDecisionService.Page list(@PathVariable String caseId,@RequestParam(defaultValue="0")long afterOrganizationVersion,@RequestParam(defaultValue="20")int limit,@AuthenticationPrincipal Jwt jwt){CaseWorkCommands.validId(caseId);return service.list(caseId,afterOrganizationVersion,limit,access(jwt));}
    Request request(String body){try{JsonNode n=parser.readTree(body);if(n==null||!n.isObject())throw new IllegalArgumentException();n.fieldNames().forEachRemaining(f->{if(!Set.of("expectedWorkVersion","expectedOrganizationVersion","action","humanText","reason").contains(f))throw new IllegalArgumentException();});
        for(var name:ListFields.VERSIONS)if(!n.path(name).isIntegralNumber()||!n.path(name).canConvertToLong())throw new IllegalArgumentException();
        if(!n.path("action").isTextual())throw new IllegalArgumentException();
        for(var name:ListFields.TEXT)if(n.has(name)&&!n.get(name).isTextual())throw new IllegalArgumentException();
        long w=n.path("expectedWorkVersion").asLong(),o=n.path("expectedOrganizationVersion").asLong();if(w<1||o<0)throw new IllegalArgumentException();
        return new Request(w,o,Action.valueOf(n.path("action").asText()),n.has("humanText")?n.path("humanText").asText():null,n.has("reason")?n.path("reason").asText():null);
    }catch(Exception e){throw new IllegalArgumentException("INVALID_COMMAND_BODY");}}
    private static class ListFields {static final String[] VERSIONS={"expectedWorkVersion","expectedOrganizationVersion"},TEXT={"humanText","reason"};}
}
