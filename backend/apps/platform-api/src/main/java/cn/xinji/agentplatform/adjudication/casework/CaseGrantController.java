package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.security.product.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Case grants use the existing trusted maintenance library, without importing consultation task controllers. */
@RestController
@RequestMapping("/v2/adjudication/grants")
@ConditionalOnProperty(name="adjudication.v2.enabled",havingValue="true")
public final class CaseGrantController {
    public record Issue(ProductGrants.Scope scope,Instant expiresAt,long expectedEpoch) { }
    public record Revoke(long expectedVersion) { }
    private final ProductMaintenanceProof proof;
    private final ProductGrants grants;
    private final ProductGrantWatermark watermark;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final CaseWorkCodec codec;
    private final String issuer;
    public CaseGrantController(ProductMaintenanceProof proof,ProductGrants grants,ProductGrantWatermark watermark,org.springframework.jdbc.core.JdbcTemplate db,CaseWorkCodec codec,@org.springframework.beans.factory.annotation.Value("${product.identity.issuer}")String issuer){this.issuer=issuer;this.proof=proof;this.grants=grants;this.watermark=watermark;this.db=db;this.codec=codec;}
    @PostMapping public ProductGrants.Receipt issue(HttpServletRequest request,@RequestHeader("Idempotency-Key")String key,@RequestBody Issue body){
        String actor=proof.require(request,ProductMaintenanceProof.Role.GRANTS);
        var scope=body.scope();
        if(scope==null || !issuer.equals(scope.issuer()) || !"ADJUDICATION".equals(scope.domain()) || !"ADJ_CASE".equals(scope.resourceType())
                || !"CASE_AUXILIARY".equals(scope.purpose()) || scope.resourceVersion()!=1 || !java.util.Objects.equals(scope.taskScope(),scope.resourceId())
                || !java.util.Set.of("CREATE","READ","WRITE","HUMAN_REVIEW","MODEL_EXECUTE").contains(scope.action())
                || !proof.requireOrganization(request,ProductMaintenanceProof.Role.GRANTS).equals(scope.organization()))
            throw new SecurityException("EXACT_ADJ_CASE_GRANT_REQUIRED");
        String fingerprint=AdjudicationCaseWork.sha256(codec.encode(body));
        return watermark.change(()->grants.issue(scope,body.expiresAt(),body.expectedEpoch(),actor,key,fingerprint));
    }
    @PostMapping("/{id}/revoke") public ProductGrants.Receipt revoke(HttpServletRequest request,@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody Revoke body){
        String actor=proof.require(request,ProductMaintenanceProof.Role.GRANTS);
        String organization=proof.requireOrganization(request,ProductMaintenanceProof.Role.GRANTS);
        Integer matches=db.queryForObject("SELECT COUNT(*) FROM id2_grant WHERE grant_id=? AND domain_code='ADJUDICATION' AND organization=? AND resource_type='ADJ_CASE' AND purpose='CASE_AUXILIARY'",Integer.class,id,organization);
        if(matches==null || matches!=1)throw new SecurityException("EXACT_ADJ_CASE_GRANT_REQUIRED");
        String fingerprint=AdjudicationCaseWork.sha256(codec.encode(java.util.List.of("REVOKE",id,body)));
        return watermark.change(()->grants.revoke(id,body.expectedVersion(),actor,key,fingerprint));
    }
}
