package cn.xinji.agentplatform.web;

import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.SourcePort;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.AuthorizationPort;
import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Source;
import cn.xinji.agentplatform.enforcement.application.EnforcementSourcePort;
import cn.xinji.agentplatform.enforcement.application.EnforcementAuthorizationPort;
import cn.xinji.agentplatform.enforcement.application.PublicEnforcementReferenceCatalogPort;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal-only input catalogs for exercising the existing V0 workflows. */
@RestController
@ConditionalOnProperty(name = "internal-workbench.development-sources-enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v0/development-sources")
public class DevelopmentOperationalSourceController {
    private final EnforcementSourcePort enforcement;
    private final PublicEnforcementReferenceCatalogPort enforcementPublicReferences;
    private final EnforcementAuthorizationPort enforcementAuthorization;
    private final SourcePort adjudication;
    private final PublicAdjudicationReferenceCatalogPort adjudicationPublicReferences;
    private final AuthorizationPort adjudicationAuthorization;

    public DevelopmentOperationalSourceController(
            EnforcementSourcePort enforcement,
            PublicEnforcementReferenceCatalogPort enforcementPublicReferences,
            EnforcementAuthorizationPort enforcementAuthorization,
            SourcePort adjudication,
            PublicAdjudicationReferenceCatalogPort adjudicationPublicReferences,
            AuthorizationPort adjudicationAuthorization) {
        this.enforcement = enforcement;
        this.enforcementPublicReferences = enforcementPublicReferences;
        this.enforcementAuthorization = enforcementAuthorization;
        this.adjudication = adjudication;
        this.adjudicationPublicReferences = adjudicationPublicReferences;
        this.adjudicationAuthorization = adjudicationAuthorization;
    }

    @GetMapping("/enforcement/clues")
    CatalogResponse<EnforcementSourcePort.ClueData> clues(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-V0-Actor", required = false) String actor,
            HttpServletRequest request) {
        requireAuthorizedCatalogAccess(actor, request);
        return new CatalogResponse<>(enforcement.dataMode(), enforcement.catalogVersion(), enforcement.limitation(), enforcement.listClues());
    }

    @GetMapping("/enforcement/evidence")
    CatalogResponse<EnforcementSourcePort.EvidenceData> evidence(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-V0-Actor", required = false) String actor,
            HttpServletRequest request) {
        requireAuthorizedCatalogAccess(actor, request);
        return new CatalogResponse<>(enforcement.dataMode(), enforcement.catalogVersion(), enforcement.limitation(), enforcement.listEvidence());
    }

    @GetMapping("/enforcement/public-references")
    CatalogResponse<PublicEnforcementReferenceCatalogPort.Reference> publicReferences(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-V0-Actor", required = false) String actor) {
        if (actor == null || actor.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!enforcementAuthorization.mayAccess(actor, "READ_PUBLIC_REFERENCE_CATALOG", "ENFORCEMENT_PUBLIC_REFERENCE_CATALOG")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        try {
            return new CatalogResponse<>(enforcementPublicReferences.dataMode(), enforcementPublicReferences.catalogVersion(),
                    enforcementPublicReferences.limitation(), enforcementPublicReferences.list());
        } catch (IllegalStateException failure) {
            HttpStatus status = "SOURCE_NOT_ALLOWED".equals(failure.getMessage())
                    ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
            throw new ResponseStatusException(status);
        }
    }

    @GetMapping("/adjudication/cases")
    CatalogResponse<Source> cases() {
        return catalog(adjudication.cases());
    }

    @GetMapping("/adjudication/materials")
    CatalogResponse<SourcePort.MaterialCatalogItem> materials() {
        return catalog(adjudication.materialItems());
    }

    @GetMapping("/adjudication/public-references")
    CatalogResponse<PublicAdjudicationReferenceCatalogPort.Reference> adjudicationPublicReferences(
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-V0-Actor", required = false) String actor) {
        if (actor == null || actor.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!adjudicationAuthorization.mayAccess(actor, "READ_PUBLIC_REFERENCE_CATALOG", "ADJUDICATION_PUBLIC_REFERENCE_CATALOG")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        try {
            return new CatalogResponse<>(adjudicationPublicReferences.dataMode(), adjudicationPublicReferences.catalogVersion(),
                    adjudicationPublicReferences.limitation(), adjudicationPublicReferences.list());
        } catch (IllegalStateException failure) {
            HttpStatus status = "SOURCE_NOT_ALLOWED".equals(failure.getMessage())
                    ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE;
            throw new ResponseStatusException(status);
        }
    }

    private <T> CatalogResponse<T> catalog(List<T> items) {
        return new CatalogResponse<>(adjudication.dataMode(), adjudication.catalogVersion(), adjudication.limitation(), items);
    }

    private void requireAuthorizedCatalogAccess(String actor, HttpServletRequest request) {
        if (!"AUTHORIZED_LOCAL".equals(enforcement.dataMode())) return;
        if (request == null || request.getAttribute("agentPlatform.trustedClientId") == null
                || actor == null || actor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!enforcementAuthorization.mayAccess(actor, "READ_AUTHORIZED_SOURCE_CATALOG", "ENFORCEMENT_SOURCE_CATALOG")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    record CatalogResponse<T>(String dataMode, String catalogVersion, String limitation, List<T> items) {}
}
