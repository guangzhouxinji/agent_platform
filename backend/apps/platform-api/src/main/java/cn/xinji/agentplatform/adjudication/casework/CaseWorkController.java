package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/adjudication/case-works")
@ConditionalOnProperty(name="adjudication.v2.enabled",havingValue="true")
public final class CaseWorkController {
    private final CaseWorkRepository repository;
    private final CaseWorkCommands commands;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final CaseSourceAuthority sources;
    private final String issuer;
    public CaseWorkController(CaseWorkRepository repository,CaseWorkCommands commands,JdbcTemplate db,ProductGrantWatermark watermark,
                              CaseSourceAuthority sources,@Value("${product.identity.issuer:}")String issuer) {
        this.repository=repository;this.commands=commands;this.db=db;this.watermark=watermark;this.sources=sources;this.issuer=issuer;
    }
    private CaseWorkAccess access(Jwt jwt){return new CaseWorkAccess(db,watermark,sources,jwt,issuer,Clock.systemUTC());}
    @PostMapping public CaseWorkRepository.Receipt create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.create(body,key,access(jwt));}
    @GetMapping("/{id}") public CaseWorkView read(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){CaseWorkCommands.validId(id);return CaseWorkView.of(repository.read(id,null,access(jwt)),commands.currentPolicyVersion());}
    @GetMapping("/{id}/versions") public List<CaseWorkRepository.Version> versions(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){CaseWorkCommands.validId(id);return repository.versions(id,access(jwt));}
    @GetMapping("/{id}/versions/{version}") public CaseWorkView historical(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable long version){CaseWorkCommands.validId(id);return CaseWorkView.of(repository.read(id,version,access(jwt)),commands.currentPolicyVersion());}
    @GetMapping("/{id}/originals") public List<CaseWorkRepository.Original> originals(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){CaseWorkCommands.validId(id);return repository.originals(id,access(jwt));}
    @GetMapping("/{id}/audits") public List<cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.Audit> audits(@AuthenticationPrincipal Jwt jwt,@PathVariable String id){CaseWorkCommands.validId(id);return repository.read(id,null,access(jwt)).audits();}
    @GetMapping("/{id}/reports/{revision}") public CaseWorkView.ReportView report(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable long revision){
        CaseWorkCommands.validId(id);var state=repository.read(id,null,access(jwt));var report=state.report(revision);return CaseWorkView.report(state,report,commands.currentPolicyVersion());
    }
    @PostMapping("/{id}/context-revisions") public CaseWorkRepository.Receipt context(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.execute(id,"CONTEXT",null,body,key,access(jwt));}
    @PostMapping("/{id}/materials") public CaseWorkRepository.Receipt material(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.execute(id,"MATERIAL",null,body,key,access(jwt));}
    @PostMapping("/{id}/claims") public CaseWorkRepository.Receipt claim(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.execute(id,"CLAIM",null,body,key,access(jwt));}
    @PostMapping("/{id}/citations") public CaseWorkRepository.Receipt citation(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.execute(id,"CITATION",null,body,key,access(jwt));}
    @PostMapping("/{id}/findings") public CaseWorkRepository.Receipt finding(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.execute(id,"FINDING",null,body,key,access(jwt));}
    @PostMapping("/{id}/reports") public CaseWorkRepository.Receipt generate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.execute(id,"GENERATE_REPORT",null,body,key,access(jwt));}
    @PostMapping("/{id}/reports/{revision}/revisions") public CaseWorkRepository.Receipt revise(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable long revision,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.execute(id,"REVISE_REPORT",revision,body,key,access(jwt));}
    @PostMapping("/{id}/reports/{revision}/submit") public CaseWorkRepository.Receipt submit(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable long revision,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.execute(id,"SUBMIT",revision,body,key,access(jwt));}
    @PostMapping("/{id}/reports/{revision}/reviews") public CaseWorkRepository.Receipt review(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable long revision,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body){return commands.execute(id,"REVIEW",revision,body,key,access(jwt));}
}
