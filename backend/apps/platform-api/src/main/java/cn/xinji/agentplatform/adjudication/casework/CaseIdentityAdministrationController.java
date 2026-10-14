package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.security.product.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** ADJ standalone maintenance adapter; uses the same trusted lifecycle, never imports CON controllers. */
@RestController
@RequestMapping("/v2/identity")
@ConditionalOnProperty(name="adjudication.v2.identity-administration.enabled",havingValue="true")
public final class CaseIdentityAdministrationController {
    public record Register(String loginName) { }
    public record Activate(String activation,String password) { }
    private final ProductMaintenanceProof proof; private final ProductSubjects subjects;
    private final ProductSigningKeys keys; private final String organization;
    public CaseIdentityAdministrationController(ProductMaintenanceProof proof,ProductSubjects subjects,
            ProductSigningKeys keys,@Value("${adjudication.v2.identity-administration.organization}")String organization) {
        if(organization.isBlank())throw new IllegalArgumentException("EXACT_ADJ_IDENTITY_ORGANIZATION_REQUIRED");
        this.proof=proof;this.subjects=subjects;this.keys=keys;this.organization=organization;
    }
    private String maintainer(HttpServletRequest request,ProductMaintenanceProof.Role role) {
        String actor=proof.require(request,role);
        if(!organization.equals(proof.requireOrganization(request,role)))throw new SecurityException("EXACT_ADJ_IDENTITY_ORGANIZATION_REQUIRED");
        return actor;
    }
    @PostMapping("/subjects") public ProductSubjects.Registration register(@RequestBody Register body,HttpServletRequest request) {
        if(body==null)throw new IllegalArgumentException("SUBJECT_REGISTRATION_REQUIRED");
        return subjects.register(body.loginName(),maintainer(request,ProductMaintenanceProof.Role.SUBJECTS));
    }
    @PostMapping("/subjects/{id}/activate") public void activate(@PathVariable String id,@RequestBody Activate body,HttpServletRequest request) {
        if(!request.isSecure())throw new SecurityException("TRUSTED_TLS_REQUIRED");
        if(body==null)throw new IllegalArgumentException("ONE_TIME_ACTIVATION_REQUIRED");
        subjects.activate(id,body.activation(),body.password());
    }
    @PostMapping("/keys/rotate") public String rotate(HttpServletRequest request) {
        return keys.rotate(maintainer(request,ProductMaintenanceProof.Role.KEYS));
    }
    @ExceptionHandler({IllegalArgumentException.class,ProductSubjects.InvalidActivationException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST) void invalid(){ }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN) void denied(){ }
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.CONFLICT) void conflict(){ }
}
