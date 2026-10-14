package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.security.product.ProductMaintenanceProof;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/adjudication/source-uses")
@ConditionalOnProperty(name="adjudication.v2.enabled",havingValue="true")
public final class CaseSourceController {
    public record Revocation(long expectedVersion) { }
    private final CaseSourceAuthority sources;
    private final ProductMaintenanceProof maintenance;
    public CaseSourceController(CaseSourceAuthority sources,ProductMaintenanceProof maintenance){this.sources=sources;this.maintenance=maintenance;}
    @PostMapping public CaseSourceAuthority.SourceReceipt register(HttpServletRequest request,@RequestBody CaseSourceAuthority.Use use){
        String actor=maintenance.require(request,ProductMaintenanceProof.Role.SOURCES);
        if(!maintenance.requireOrganization(request,ProductMaintenanceProof.Role.SOURCES).equals(use.scope().organization()))
            throw new SecurityException("SOURCE_MAINTAINER_ORGANIZATION_MISMATCH");
        return sources.register(use,actor);
    }
    @PostMapping("/{key}/revoke") public CaseSourceAuthority.SourceReceipt revoke(HttpServletRequest request,@PathVariable String key,@RequestBody Revocation body){
        String actor=maintenance.require(request,ProductMaintenanceProof.Role.SOURCES);
        if(!maintenance.requireOrganization(request,ProductMaintenanceProof.Role.SOURCES).equals(sources.scope(key).organization()))
            throw new SecurityException("SOURCE_MAINTAINER_ORGANIZATION_MISMATCH");
        return sources.revoke(key,body.expectedVersion(),actor);
    }
}
